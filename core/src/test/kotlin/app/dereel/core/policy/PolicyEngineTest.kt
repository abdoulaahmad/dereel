package app.dereel.core.policy

import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import app.dereel.core.shared.ShareOrigin
import app.dereel.core.shared.SharedItemMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private val instagram = PlatformConfig(Platform.INSTAGRAM, selected = true, openingMethod = OpeningMethod.IN_DEREEL)
    private val shared = SharedItemMatch(ShareOrigin.Conversation("thread-1"), "ABCDE12345")

    private fun input(
        config: PlatformConfig = instagram,
        method: OpeningMethod = OpeningMethod.IN_DEREEL,
        surface: SurfaceKind = SurfaceKind.SHORT_VIDEO,
        available: Boolean = true,
        grantMs: Long = 0,
        usedMs: Long = 0,
        sharedItem: SharedItemMatch? = null,
    ) = PolicyInput(config, method, surface, available, grantMs, usedMs, sharedItem)

    private fun withAllowance(minutes: Int) = instagram.copy(allowance = AllowanceSetting().enable(minutes))

    @Test
    fun `unselected platform is not managed (FR-05)`() {
        val decision = PolicyEngine.evaluate(input(config = instagram.copy(selected = false)))
        assertEquals(PolicyDecision.NotManaged, decision)
        assertFalse(decision.countsUsage)
    }

    @Test
    fun `regular content is never restricted or counted (POL-05)`() {
        val decision = PolicyEngine.evaluate(input(config = withAllowance(5), surface = SurfaceKind.OTHER, usedMs = 10 * 60_000))
        assertEquals(PolicyDecision.NotShortVideo, decision)
        assertFalse(decision.countsUsage)
    }

    @Test
    fun `default policy blocks short videos (POL-02)`() {
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), PolicyEngine.evaluate(input()))
    }

    @Test
    fun `blocking off allows viewing and keeps measuring (POL-07)`() {
        val decision = PolicyEngine.evaluate(input(config = instagram.copy(blockShortVideos = false), available = false))
        assertEquals(PolicyDecision.Allow(AllowReason.BLOCKING_OFF), decision)
        assertTrue(decision.countsUsage)
    }

    @Test
    fun `unavailable method is reported as unavailable, not as blocking (POL-01)`() {
        val decision = PolicyEngine.evaluate(input(available = false, grantMs = 60_000))
        assertEquals(PolicyDecision.Unavailable(UnavailableReason.METHOD_UNAVAILABLE), decision)
    }

    @Test
    fun `temporary access outranks an exhausted allowance and counts usage (POL-04)`() {
        val decision = PolicyEngine.evaluate(input(config = withAllowance(5), grantMs = 120_000, usedMs = 6 * 60_000))
        assertEquals(PolicyDecision.Allow(AllowReason.TEMPORARY_ACCESS, grantRemainingMs = 120_000), decision)
        assertTrue(decision.countsUsage)
    }

    @Test
    fun `exhausted allowance denies even a recognized shared item (POL-03, AT-09)`() {
        val decision = PolicyEngine.evaluate(input(config = withAllowance(5), usedMs = 5 * 60_000, sharedItem = shared))
        assertEquals(PolicyDecision.Deny(DenyReason.ALLOWANCE_EXHAUSTED), decision)
    }

    @Test
    fun `recognized shared item is allowed when blocking by default (POL-02)`() {
        val decision = PolicyEngine.evaluate(input(sharedItem = shared))
        assertEquals(PolicyDecision.Allow(AllowReason.SHARED_ITEM, remainingAllowanceMs = null), decision)
    }

    @Test
    fun `shared item consumes an enabled allowance (POL-03)`() {
        val decision = PolicyEngine.evaluate(input(config = withAllowance(15), usedMs = 60_000, sharedItem = shared))
        assertEquals(PolicyDecision.Allow(AllowReason.SHARED_ITEM, remainingAllowanceMs = 14 * 60_000), decision)
        assertTrue(decision.countsUsage)
    }

    @Test
    fun `shared item setting off applies ordinary policy`() {
        val decision = PolicyEngine.evaluate(input(config = instagram.copy(allowSharedVideos = false), sharedItem = shared))
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), decision)
    }

    @Test
    fun `native method never grants a shared exception (FR-11, AT-07)`() {
        val decision = PolicyEngine.evaluate(input(method = OpeningMethod.NATIVE_APP, sharedItem = shared))
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), decision)
    }

    @Test
    fun `YouTube browser has no shared exception (D-05)`() {
        val youtube = PlatformConfig(Platform.YOUTUBE, selected = true)
        val decision = PolicyEngine.evaluate(input(config = youtube, sharedItem = shared))
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), decision)
    }

    @Test
    fun `remaining allowance allows and reports remaining time`() {
        val decision = PolicyEngine.evaluate(input(config = withAllowance(15), usedMs = 5 * 60_000))
        assertEquals(PolicyDecision.Allow(AllowReason.ALLOWANCE, remainingAllowanceMs = 10 * 60_000), decision)
    }

    @Test
    fun `disabled allowance keeps its limit but does not apply`() {
        val config = withAllowance(15).let { it.copy(allowance = it.allowance.disable()) }
        assertEquals(15, config.allowance.minutes)
        assertEquals(PolicyDecision.Deny(DenyReason.BLOCKED), PolicyEngine.evaluate(input(config = config, usedMs = 0)))
    }

    @Test
    fun `allowance choices are validated (FR-24)`() {
        assertEquals(listOf(5, 15, 30), AllowanceSetting.SUGGESTED_MINUTES)
        assertEquals(1, AllowanceSetting.parseCustomMinutes("1"))
        assertEquals(180, AllowanceSetting.parseCustomMinutes(" 180 "))
        assertEquals(null, AllowanceSetting.parseCustomMinutes("0"))
        assertEquals(null, AllowanceSetting.parseCustomMinutes("181"))
        assertEquals(null, AllowanceSetting.parseCustomMinutes("12.5"))
    }
}
