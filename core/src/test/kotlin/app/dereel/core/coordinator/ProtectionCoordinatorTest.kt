package app.dereel.core.coordinator

import app.dereel.core.InMemoryGrantStore
import app.dereel.core.InMemoryUsageStore
import app.dereel.core.SequentialIds
import app.dereel.core.TestClock
import app.dereel.core.grant.GrantManager
import app.dereel.core.grant.TemporaryGrant
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.OpeningMethod.IN_DEREEL
import app.dereel.core.model.OpeningMethod.NATIVE_APP
import app.dereel.core.model.Platform
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.PlatformConfig
import app.dereel.core.policy.AllowReason
import app.dereel.core.policy.DenyReason
import app.dereel.core.policy.PolicyDecision
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.policy.UnavailableReason
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionCoordinatorTest {
    private val shortVideo = SurfaceObservation(SurfaceKind.SHORT_VIDEO)

    private class Harness(
        val configs: MutableStateFlow<Map<Platform, PlatformConfig>>,
        val usage: UsageTracker,
        val grants: GrantManager,
        val coordinator: ProtectionCoordinator,
        val clock: TestClock,
    ) {
        fun update(platform: Platform, transform: (PlatformConfig) -> PlatformConfig) {
            configs.value = configs.value + (platform to transform(configs.value.getValue(platform)))
        }
        fun decision(platform: Platform, method: app.dereel.core.model.OpeningMethod) =
            coordinator.decisions.value[MethodKey(platform, method)]
    }

    private fun TestScope.harness(instagram: PlatformConfig = PlatformConfig(INSTAGRAM, selected = true)): Harness {
        val clock = TestClock({ testScheduler.currentTime })
        val writer = SerialWriter(backgroundScope)
        val configs = MutableStateFlow(
            mapOf(INSTAGRAM to instagram, Platform.YOUTUBE to PlatformConfig(Platform.YOUTUBE, selected = true)),
        )
        val usage = UsageTracker(InMemoryUsageStore(), clock, backgroundScope, writer, SequentialIds("u"))
        val grants = GrantManager(InMemoryGrantStore(), clock, writer, SequentialIds("g"))
        val coordinator = ProtectionCoordinator(configs, grants, usage, clock, backgroundScope)
        coordinator.start()
        coordinator.markReady()
        runCurrent()
        return Harness(configs, usage, grants, coordinator, clock)
    }

    private fun allowance(minutes: Int) = PlatformConfig(INSTAGRAM, selected = true, allowance = AllowanceSetting().enable(minutes))

    @Test
    fun `allowance running out during viewing switches to exhausted without a new event (FR-27)`() = runTest {
        val h = harness(allowance(1))
        val first = h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo)
        assertEquals(PolicyDecision.Allow(AllowReason.ALLOWANCE, remainingAllowanceMs = 60_000), first)

        advanceTimeBy(60_050)
        runCurrent()

        assertEquals(PolicyDecision.Deny(DenyReason.ALLOWANCE_EXHAUSTED), h.decision(INSTAGRAM, NATIVE_APP))
        val used = h.usage.usedTodayMs(INSTAGRAM)
        assertTrue("used $used", used in 60_000..60_100)
        advanceTimeBy(30_000)
        assertEquals("time behind the block is not counted (FR-25)", used, h.usage.usedTodayMs(INSTAGRAM))
    }

    @Test
    fun `native and browser viewing share one balance (AT-08, DATA-02)`() = runTest {
        val h = harness(allowance(15))
        h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo)
        advanceTimeBy(6 * 60_000)
        h.coordinator.clear(INSTAGRAM, NATIVE_APP)
        val browser = h.coordinator.report(INSTAGRAM, IN_DEREEL, shortVideo)
        assertEquals(9 * 60_000L, (browser as PolicyDecision.Allow).remainingAllowanceMs)
        advanceTimeBy(4 * 60_000)
        h.coordinator.clear(INSTAGRAM, IN_DEREEL)

        assertEquals(10 * 60_000, h.usage.usedTodayMs(INSTAGRAM))
        val again = h.coordinator.evaluate(INSTAGRAM, NATIVE_APP, shortVideo) as PolicyDecision.Allow
        assertEquals(5 * 60_000L, again.remainingAllowanceMs)
    }

    @Test
    fun `settings change applies to an active journey without restart (POL-06)`() = runTest {
        val h = harness(PlatformConfig(INSTAGRAM, selected = true, blockShortVideos = false))
        assertEquals(PolicyDecision.Allow(AllowReason.BLOCKING_OFF), h.coordinator.report(INSTAGRAM, IN_DEREEL, shortVideo))
        advanceTimeBy(1_000)

        h.update(INSTAGRAM) { it.copy(blockShortVideos = true) }
        runCurrent()

        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), h.decision(INSTAGRAM, IN_DEREEL))
        val used = h.usage.usedTodayMs(INSTAGRAM)
        advanceTimeBy(10_000)
        assertEquals(used, h.usage.usedTodayMs(INSTAGRAM))
    }

    @Test
    fun `temporary access bypasses an exhausted allowance, keeps counting, then expires (POL-04, AT-10)`() = runTest {
        val h = harness(allowance(1))
        h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(PolicyDecision.Deny(DenyReason.ALLOWANCE_EXHAUSTED), h.decision(INSTAGRAM, NATIVE_APP))
        val beforeGrant = h.usage.usedTodayMs(INSTAGRAM)

        val issued = h.grants.request(INSTAGRAM)
        runCurrent()
        assertEquals(h.clock.read().wallMs + TemporaryGrant.DURATION_MS, issued.grant.expiresWallMs)
        assertTrue(h.decision(INSTAGRAM, NATIVE_APP) is PolicyDecision.Allow)

        advanceTimeBy(2 * 60_000)
        assertTrue("usage continues during the grant", h.usage.usedTodayMs(INSTAGRAM) >= beforeGrant + 2 * 60_000)

        advanceTimeBy(3 * 60_000 + 100)
        runCurrent()
        assertEquals(PolicyDecision.Deny(DenyReason.ALLOWANCE_EXHAUSTED), h.decision(INSTAGRAM, NATIVE_APP))
        assertTrue("accrued usage is not reset", h.usage.usedTodayMs(INSTAGRAM) >= beforeGrant + 5 * 60_000 - 100)
    }

    @Test
    fun `ending temporary access early resumes normal policy (FR-29)`() = runTest {
        val h = harness()
        h.grants.request(INSTAGRAM)
        runCurrent()
        assertTrue(h.coordinator.report(INSTAGRAM, IN_DEREEL, shortVideo) is PolicyDecision.Allow)
        advanceTimeBy(30_000)
        h.grants.end(INSTAGRAM)
        runCurrent()
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), h.decision(INSTAGRAM, IN_DEREEL))
    }

    @Test
    fun `second grant replaces the first with a fresh five minutes (FR-29)`() = runTest {
        val h = harness()
        val first = h.grants.request(INSTAGRAM)
        advanceTimeBy(2 * 60_000)
        val second = h.grants.request(INSTAGRAM)
        assertEquals(first.grant, second.replaced)
        assertEquals(TemporaryGrant.DURATION_MS, h.grants.remainingMs(INSTAGRAM))
    }

    @Test
    fun `disabling the allowance keeps usage and re-enabling applies the same-day total (FR-30, AT-19)`() = runTest {
        val h = harness(allowance(5))
        h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo)
        advanceTimeBy(4 * 60_000)
        h.update(INSTAGRAM) { it.copy(allowance = it.allowance.disable()) }
        runCurrent()
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), h.decision(INSTAGRAM, NATIVE_APP))
        val used = h.usage.usedTodayMs(INSTAGRAM)
        assertEquals(4 * 60_000L, used)

        h.update(INSTAGRAM) { it.copy(allowance = it.allowance.enable(5)) }
        runCurrent()
        val decision = h.decision(INSTAGRAM, NATIVE_APP) as PolicyDecision.Allow
        assertEquals(60_000L, decision.remainingAllowanceMs)
    }

    @Test
    fun `removing a platform stops enforcement and measurement (FR-05)`() = runTest {
        val h = harness(PlatformConfig(INSTAGRAM, selected = true, blockShortVideos = false))
        h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo)
        advanceTimeBy(5_000)
        h.update(INSTAGRAM) { it.copy(selected = false) }
        runCurrent()
        assertEquals(PolicyDecision.NotManaged, h.decision(INSTAGRAM, NATIVE_APP))
        val used = h.usage.usedTodayMs(INSTAGRAM)
        advanceTimeBy(20_000)
        assertEquals(used, h.usage.usedTodayMs(INSTAGRAM))
    }

    @Test
    fun `unavailable method is reported as unavailable (POL-01)`() = runTest {
        val h = harness()
        h.coordinator.setMethodAvailable(INSTAGRAM, NATIVE_APP, false)
        assertEquals(
            PolicyDecision.Unavailable(UnavailableReason.METHOD_UNAVAILABLE),
            h.coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo),
        )
    }

    @Test
    fun `decisions before settings load are STARTING, not blocking`() = runTest {
        val clock = TestClock({ testScheduler.currentTime })
        val writer = SerialWriter(backgroundScope)
        val configs = MutableStateFlow(mapOf(INSTAGRAM to PlatformConfig(INSTAGRAM, selected = true)))
        val usage = UsageTracker(InMemoryUsageStore(), clock, backgroundScope, writer)
        val coordinator = ProtectionCoordinator(configs, GrantManager(InMemoryGrantStore(), clock, writer), usage, clock, backgroundScope)
        assertEquals(PolicyDecision.Unavailable(UnavailableReason.STARTING), coordinator.report(INSTAGRAM, NATIVE_APP, shortVideo))
        coordinator.markReady()
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), coordinator.decisions.value[MethodKey(INSTAGRAM, NATIVE_APP)])
    }

    @Test
    fun `regular content never starts a session (POL-05)`() = runTest {
        val h = harness(PlatformConfig(INSTAGRAM, selected = true, blockShortVideos = false))
        h.coordinator.report(INSTAGRAM, NATIVE_APP, SurfaceObservation(SurfaceKind.OTHER))
        advanceTimeBy(60_000)
        assertEquals(0, h.usage.usedTodayMs(INSTAGRAM))
    }
}
