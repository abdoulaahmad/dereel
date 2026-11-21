package app.dereel.core.status

import app.dereel.core.model.OpeningMethod.IN_DEREEL
import app.dereel.core.model.OpeningMethod.NATIVE_APP
import app.dereel.core.model.Platform.FACEBOOK
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.SNAPCHAT
import app.dereel.core.model.Platform.YOUTUBE
import app.dereel.core.model.PlatformConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusEvaluatorTest {
    private val healthy = EnvironmentSnapshot(
        accessibilityEnabled = true,
        accessibilityConnected = true,
        accessibilityEverConnected = true,
        installedApps = setOf(INSTAGRAM, YOUTUBE),
        webViewAvailable = true,
        webViewSupportsRules = true,
        checkedAtWallMs = 0,
    )
    private val nativeIg = PlatformConfig(INSTAGRAM, selected = true, openingMethod = NATIVE_APP)
    private val browserIg = PlatformConfig(INSTAGRAM, selected = true, openingMethod = IN_DEREEL)

    private fun state(config: PlatformConfig, env: EnvironmentSnapshot = healthy, grant: Long = 0) =
        StatusEvaluator.platformHealth(config, env, grant).map { it.method to it.state }

    @Test
    fun `operational native protection is active`() {
        assertEquals(listOf(NATIVE_APP to ProtectionState.ACTIVE), state(nativeIg))
    }

    @Test
    fun `declined accessibility needs setup and the browser stays usable (AT-02)`() {
        val env = healthy.copy(accessibilityEnabled = false, accessibilityConnected = false, accessibilityEverConnected = false)
        val native = StatusEvaluator.platformHealth(nativeIg, env, 0).single()
        assertEquals(ProtectionState.NEEDS_SETUP, native.state)
        assertEquals(RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS, native.recovery)
        assertEquals(listOf(IN_DEREEL to ProtectionState.ACTIVE), state(browserIg, env))
    }

    @Test
    fun `revoked access needs attention with a fix action (AT-13)`() {
        val env = healthy.copy(accessibilityEnabled = false, accessibilityConnected = false)
        val health = StatusEvaluator.platformHealth(nativeIg, env, 0).single()
        assertEquals(ProtectionState.NEEDS_ATTENTION, health.state)
        assertEquals(StatusReason.ACCESSIBILITY_TURNED_OFF, health.reason)
        assertEquals(RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS, health.recovery)
    }

    @Test
    fun `enabled but disconnected service is never shown as active (FR-36)`() {
        val health = StatusEvaluator.platformHealth(nativeIg, healthy.copy(accessibilityConnected = false), 0).single()
        assertEquals(ProtectionState.NEEDS_ATTENTION, health.state)
        assertEquals(StatusReason.SERVICE_NOT_RUNNING, health.reason)
    }

    @Test
    fun `missing app is unavailable and offers the browser (FR-03)`() {
        val health = StatusEvaluator.platformHealth(nativeIg, healthy.copy(installedApps = setOf(YOUTUBE)), 0).single()
        assertEquals(ProtectionState.UNAVAILABLE, health.state)
        assertEquals(RecoveryAction.OPEN_INSIDE_DEREEL, health.recovery)
    }

    @Test
    fun `unrecognized layout needs attention (FR-37, AT-18)`() {
        val env = healthy.copy(layout = mapOf(INSTAGRAM to LayoutRecognition.NOT_RECOGNIZED))
        assertEquals(StatusReason.LAYOUT_NOT_RECOGNIZED, StatusEvaluator.platformHealth(nativeIg, env, 0).single().reason)
    }

    @Test
    fun `missing webview is unavailable (AT-18)`() {
        val health = StatusEvaluator.platformHealth(browserIg, healthy.copy(webViewAvailable = false), 0)
        assertEquals(ProtectionState.UNAVAILABLE, health.first().state)
        assertEquals(RecoveryAction.UPDATE_WEBVIEW, health.first().recovery)
    }

    @Test
    fun `blocking off shows Off and temporary access shows Paused`() {
        assertEquals(listOf(NATIVE_APP to ProtectionState.OFF), state(nativeIg.copy(blockShortVideos = false)))
        val paused = StatusEvaluator.platformHealth(nativeIg, healthy, 120_000).single()
        assertEquals(ProtectionState.PAUSED, paused.state)
        assertEquals(RecoveryAction.END_TEMPORARY_ACCESS, paused.recovery)
    }

    @Test
    fun `missing opening method asks for it once (FR-02)`() {
        val health = StatusEvaluator.platformHealth(nativeIg.copy(openingMethod = null), healthy, 0).single()
        assertEquals(ProtectionState.NEEDS_SETUP, health.state)
        assertEquals(RecoveryAction.CHOOSE_OPENING_METHOD, health.recovery)
    }

    @Test
    fun `browser users with accessibility enabled also see native status`() {
        assertEquals(listOf(IN_DEREEL to ProtectionState.ACTIVE, NATIVE_APP to ProtectionState.ACTIVE), state(browserIg))
        // Never set up: there is nothing to report for the app.
        val neverSetUp = healthy.copy(accessibilityEnabled = false, accessibilityConnected = false, accessibilityEverConnected = false)
        assertEquals(listOf(IN_DEREEL to ProtectionState.ACTIVE), state(browserIg, neverSetUp))
    }

    @Test
    fun `app protection that worked and was turned off is shown to browser users too`() {
        val revoked = healthy.copy(accessibilityEnabled = false, accessibilityConnected = false)
        val health = StatusEvaluator.platformHealth(browserIg, revoked, 0)
        assertEquals(listOf(IN_DEREEL to ProtectionState.ACTIVE, NATIVE_APP to ProtectionState.NEEDS_ATTENTION), health.map { it.method to it.state })
        assertEquals(StatusReason.ACCESSIBILITY_TURNED_OFF, health.last().reason)
        assertEquals(RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS, health.last().recovery)
        // Home must not say everything is working (UX-08).
        assertEquals(OverallStatus.NEEDS_ACTION, StatusEvaluator.overall(health))
    }

    @Test
    fun `overall active message only when every shown method is active (UX-08)`() {
        val active = StatusEvaluator.platformHealth(nativeIg, healthy, 0)
        assertEquals(OverallStatus.ALL_ACTIVE, StatusEvaluator.overall(active))

        val broken = StatusEvaluator.platformHealth(PlatformConfig(YOUTUBE, selected = true, openingMethod = NATIVE_APP), healthy.copy(accessibilityConnected = false), 0)
        assertEquals(OverallStatus.NEEDS_ACTION, StatusEvaluator.overall(active + broken))

        val off = StatusEvaluator.platformHealth(PlatformConfig(YOUTUBE, selected = true, openingMethod = IN_DEREEL, blockShortVideos = false), healthy, 0)
        assertEquals(OverallStatus.PARTLY_OFF_OR_PAUSED, StatusEvaluator.overall(active + off))
        assertEquals(OverallStatus.NOTHING_SELECTED, StatusEvaluator.overall(emptyList()))
    }

    @Test
    fun `unselected platforms show nothing`() {
        assertTrue(StatusEvaluator.platformHealth(nativeIg.copy(selected = false), healthy, 0).isEmpty())
    }

    @Test
    fun `snapchat is protected in its app only and never offers the browser`() {
        val snapchat = PlatformConfig(SNAPCHAT, selected = true)
        val installed = healthy.copy(installedApps = setOf(SNAPCHAT))
        assertEquals(listOf(NATIVE_APP to ProtectionState.ACTIVE), state(snapchat, installed))

        val missing = StatusEvaluator.platformHealth(snapchat, healthy, 0).single()
        assertEquals(ProtectionState.UNAVAILABLE, missing.state)
        assertEquals(StatusReason.APP_NOT_INSTALLED, missing.reason)
        assertEquals(RecoveryAction.NONE, missing.recovery)

        val unrecognized = installed.copy(layout = mapOf(SNAPCHAT to LayoutRecognition.NOT_RECOGNIZED))
        val health = StatusEvaluator.platformHealth(snapchat, unrecognized, 0).single()
        assertEquals(ProtectionState.NEEDS_ATTENTION, health.state)
        assertEquals(RecoveryAction.NONE, health.recovery)

        val noAccess = installed.copy(accessibilityEnabled = false, accessibilityConnected = false, accessibilityEverConnected = false)
        assertEquals(listOf(NATIVE_APP to ProtectionState.NEEDS_SETUP), state(snapchat, noAccess))
    }

    @Test
    fun `facebook shows app protection only when a supported facebook app is installed (UX-08)`() {
        val inDereel = PlatformConfig(FACEBOOK, selected = true, openingMethod = IN_DEREEL)
        // "Installed" means Facebook Lite, the only Facebook app Dereel can recognise.
        val withLite = healthy.copy(installedApps = setOf(FACEBOOK))
        assertEquals(listOf(IN_DEREEL to ProtectionState.ACTIVE, NATIVE_APP to ProtectionState.ACTIVE), state(inDereel, withLite))
        // Without it, no app row is shown, even with Accessibility on.
        assertEquals(listOf(IN_DEREEL to ProtectionState.ACTIVE), state(inDereel, healthy))

        val inApp = PlatformConfig(FACEBOOK, selected = true, openingMethod = NATIVE_APP)
        val missing = StatusEvaluator.platformHealth(inApp, healthy, 0).single()
        assertEquals(ProtectionState.UNAVAILABLE, missing.state)
        assertEquals(RecoveryAction.OPEN_INSIDE_DEREEL, missing.recovery)
    }
}
