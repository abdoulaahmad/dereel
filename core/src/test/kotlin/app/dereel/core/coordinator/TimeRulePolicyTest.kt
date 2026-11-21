package app.dereel.core.coordinator

import app.dereel.core.InMemoryGrantStore
import app.dereel.core.InMemoryUsageStore
import app.dereel.core.SequentialIds
import app.dereel.core.TestClock
import app.dereel.core.grant.GrantManager
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.AppPreferences
import app.dereel.core.model.IntervalSetting
import app.dereel.core.model.OpeningMethod.IN_DEREEL
import app.dereel.core.model.OpeningMethod.NATIVE_APP
import app.dereel.core.model.Platform
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.PlatformConfig
import app.dereel.core.model.SleepSchedule
import app.dereel.core.pacing.IntervalStatus
import app.dereel.core.pacing.PacingTracker
import app.dereel.core.policy.AllowReason
import app.dereel.core.policy.DenyReason
import app.dereel.core.policy.PolicyDecision
import app.dereel.core.policy.PolicyEngine
import app.dereel.core.policy.PolicyInput
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.shared.ShareOrigin
import app.dereel.core.shared.SharedItemMatch
import app.dereel.core.usage.UsageTracker
import app.dereel.core.util.SerialWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scope change S-02: where the sleep schedule and the interval timer sit in the policy order. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeRulePolicyTest {
    private val minute = 60_000L
    private val base = PlatformConfig(INSTAGRAM, selected = true, openingMethod = IN_DEREEL)
    private val shared = SharedItemMatch(ShareOrigin.Conversation("thread-1"), "ABCDE12345")
    private val shortVideo = SurfaceObservation(SurfaceKind.SHORT_VIDEO)

    private fun decide(
        config: PlatformConfig = base,
        grantMs: Long = 0,
        usedMs: Long = 0,
        sharedItem: SharedItemMatch? = null,
        sleepEndsInMs: Long? = null,
        interval: IntervalStatus = IntervalStatus.Off,
    ) = PolicyEngine.evaluate(PolicyInput(config, IN_DEREEL, SurfaceKind.SHORT_VIDEO, true, grantMs, usedMs, sharedItem, sleepEndsInMs, interval))

    private fun withAllowance(minutes: Int) = base.copy(allowance = AllowanceSetting().enable(minutes))

    // --- order ------------------------------------------------------------------------------

    @Test
    fun `the sleep schedule blocks even with allowance left and names when it ends`() {
        val decision = decide(config = withAllowance(30), sleepEndsInMs = 90 * minute)
        assertEquals(PolicyDecision.Deny(DenyReason.SLEEP_SCHEDULE, retryInMs = 90 * minute), decision)
        // A shared reel is not an exception to it.
        assertEquals(DenyReason.SLEEP_SCHEDULE, (decide(sharedItem = shared, sleepEndsInMs = minute) as PolicyDecision.Deny).reason)
    }

    @Test
    fun `temporary access is the only thing above the sleep schedule and a break (POL-04)`() {
        val asleep = decide(grantMs = 2 * minute, sleepEndsInMs = 90 * minute)
        assertEquals(AllowReason.TEMPORARY_ACCESS, (asleep as PolicyDecision.Allow).reason)
        val onBreak = decide(grantMs = 2 * minute, interval = IntervalStatus.OnBreak(10 * minute))
        assertEquals(AllowReason.TEMPORARY_ACCESS, (onBreak as PolicyDecision.Allow).reason)
    }

    @Test
    fun `neither rule applies when blocking is off or the page is not a short video`() {
        val off = decide(config = base.copy(blockShortVideos = false), sleepEndsInMs = minute, interval = IntervalStatus.OnBreak(minute))
        assertEquals(PolicyDecision.Allow(AllowReason.BLOCKING_OFF), off)
        val other = PolicyEngine.evaluate(PolicyInput(base, IN_DEREEL, SurfaceKind.OTHER, true, 0, 0, null, minute, IntervalStatus.OnBreak(minute)))
        assertEquals(PolicyDecision.NotShortVideo, other)
    }

    @Test
    fun `an interval break denies shared items and reports how long is left`() {
        val decision = decide(config = withAllowance(30), sharedItem = shared, interval = IntervalStatus.OnBreak(12 * minute))
        assertEquals(PolicyDecision.Deny(DenyReason.INTERVAL_BREAK, retryInMs = 12 * minute), decision)
    }

    @Test
    fun `an exhausted allowance wins over the interval timer`() {
        val watching = decide(config = withAllowance(5), usedMs = 5 * minute, interval = IntervalStatus.Watching(3 * minute))
        assertEquals(PolicyDecision.Deny(DenyReason.ALLOWANCE_EXHAUSTED), watching)
        val onBreak = decide(config = withAllowance(5), usedMs = 5 * minute, interval = IntervalStatus.OnBreak(3 * minute))
        assertEquals(DenyReason.ALLOWANCE_EXHAUSTED, (onBreak as PolicyDecision.Deny).reason)
    }

    @Test
    fun `the interval timer alone allows viewing and with an allowance the sooner limit applies`() {
        val alone = decide(interval = IntervalStatus.Watching(4 * minute)) as PolicyDecision.Allow
        assertEquals(AllowReason.INTERVAL, alone.reason)
        assertEquals(4 * minute, alone.runsOutInMs)

        val both = decide(config = withAllowance(30), usedMs = 10 * minute, interval = IntervalStatus.Watching(4 * minute)) as PolicyDecision.Allow
        assertEquals(AllowReason.ALLOWANCE, both.reason)
        assertEquals(20 * minute, both.remainingAllowanceMs)
        assertEquals(4 * minute, both.runsOutInMs)

        val nearlyOut = decide(config = withAllowance(30), usedMs = 29 * minute, interval = IntervalStatus.Watching(4 * minute)) as PolicyDecision.Allow
        assertEquals(1 * minute, nearlyOut.runsOutInMs)
        // Without either, the default still blocks (POL-02).
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), decide())
    }

    // --- live behaviour through the coordinator --------------------------------------------

    private class Harness(
        val coordinator: ProtectionCoordinator,
        val usage: UsageTracker,
        val grants: GrantManager,
        val preferences: MutableStateFlow<AppPreferences?>,
    ) {
        fun decision(method: app.dereel.core.model.OpeningMethod) = coordinator.decisions.value[MethodKey(INSTAGRAM, method)]
    }

    private fun TestScope.harness(instagram: PlatformConfig, preferences: AppPreferences = AppPreferences()): Harness {
        val clock = TestClock({ testScheduler.currentTime }) // starts at 10:00 UTC
        val writer = SerialWriter(backgroundScope)
        val configs = MutableStateFlow(mapOf<Platform, PlatformConfig>(INSTAGRAM to instagram))
        val usage = UsageTracker(InMemoryUsageStore(), clock, backgroundScope, writer, SequentialIds("u"))
        val grants = GrantManager(InMemoryGrantStore(), clock, writer, SequentialIds("g"))
        val prefs = MutableStateFlow<AppPreferences?>(preferences)
        val coordinator = ProtectionCoordinator(configs, grants, usage, clock, backgroundScope, prefs, PacingTracker(clock))
        coordinator.start()
        coordinator.markReady()
        runCurrent()
        return Harness(coordinator, usage, grants, prefs)
    }

    private val paced = PlatformConfig(INSTAGRAM, selected = true, interval = IntervalSetting(enabled = true, watchMinutes = 1, breakMinutes = 5))

    @Test
    fun `a watch window running out starts the break without a new event, and the break ends by itself`() = runTest {
        val h = harness(paced)
        val first = h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo)
        assertEquals(PolicyDecision.Allow(AllowReason.INTERVAL, remainingIntervalMs = minute), first)

        advanceTimeBy(minute + 50)
        runCurrent()
        val denied = h.decision(NATIVE_APP) as PolicyDecision.Deny
        assertEquals(DenyReason.INTERVAL_BREAK, denied.reason)
        val used = h.usage.usedTodayMs(INSTAGRAM)
        assertTrue("used $used", used in minute..minute + 100)

        // Time behind the block is not counted, and nothing is allowed until the break is over.
        advanceTimeBy(4 * minute)
        runCurrent()
        assertEquals(DenyReason.INTERVAL_BREAK, (h.decision(NATIVE_APP) as PolicyDecision.Deny).reason)
        assertEquals(used, h.usage.usedTodayMs(INSTAGRAM))

        advanceTimeBy(minute + 50)
        runCurrent()
        assertEquals(AllowReason.INTERVAL, (h.decision(NATIVE_APP) as PolicyDecision.Allow).reason)
    }

    @Test
    fun `the app and the browser share one watch window (DATA-02)`() = runTest {
        val h = harness(paced)
        h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo)
        advanceTimeBy(40_000)
        h.coordinator.clear(INSTAGRAM, NATIVE_APP)
        val browser = h.coordinator.report(INSTAGRAM, IN_DEREEL, shortVideo) as PolicyDecision.Allow
        assertEquals(20_000L, browser.remainingIntervalMs)
    }

    @Test
    fun `the sleep schedule starts and ends during viewing without a new event`() = runTest {
        // The test clock starts at 10:00. Sleep runs from 10:30 to 11:00.
        val sleep = SleepSchedule(enabled = true, startMinute = 10 * 60 + 30, endMinute = 11 * 60)
        val h = harness(PlatformConfig(INSTAGRAM, selected = true, allowance = AllowanceSetting().enable(120)), AppPreferences(sleep = sleep))
        assertTrue(h.coordinator.report(INSTAGRAM, IN_DEREEL, shortVideo) is PolicyDecision.Allow)

        advanceTimeBy(30 * minute + 50)
        runCurrent()
        val asleep = h.decision(IN_DEREEL) as PolicyDecision.Deny
        assertEquals(DenyReason.SLEEP_SCHEDULE, asleep.reason)
        assertTrue("retry ${asleep.retryInMs}", asleep.retryInMs!! in 29 * minute..30 * minute)
        val used = h.usage.usedTodayMs(INSTAGRAM)

        // Temporary access still works when the user asks for it (POL-04).
        h.grants.request(INSTAGRAM)
        runCurrent()
        assertEquals(AllowReason.TEMPORARY_ACCESS, (h.decision(IN_DEREEL) as PolicyDecision.Allow).reason)
        h.grants.end(INSTAGRAM)
        runCurrent()
        assertEquals(DenyReason.SLEEP_SCHEDULE, (h.decision(IN_DEREEL) as PolicyDecision.Deny).reason)

        advanceTimeBy(30 * minute)
        runCurrent()
        assertEquals(AllowReason.ALLOWANCE, (h.decision(IN_DEREEL) as PolicyDecision.Allow).reason)
        assertTrue(h.usage.usedTodayMs(INSTAGRAM) >= used)
    }

    @Test
    fun `turning the sleep schedule on applies to what is on screen now (POL-06)`() = runTest {
        val h = harness(PlatformConfig(INSTAGRAM, selected = true, allowance = AllowanceSetting().enable(120)))
        h.coordinator.report(INSTAGRAM, IN_DEREEL, shortVideo)
        h.preferences.value = AppPreferences(sleep = SleepSchedule(enabled = true, startMinute = 9 * 60, endMinute = 12 * 60))
        runCurrent()
        assertEquals(DenyReason.SLEEP_SCHEDULE, (h.decision(IN_DEREEL) as PolicyDecision.Deny).reason)
    }
}
