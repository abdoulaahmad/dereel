package app.dereel.core.status

import app.dereel.core.model.AppPreferences
import app.dereel.core.model.OpeningMethod.IN_DEREEL
import app.dereel.core.model.OpeningMethod.NATIVE_APP
import app.dereel.core.model.Platform
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.SNAPCHAT
import app.dereel.core.model.Platform.YOUTUBE
import app.dereel.core.model.PlatformConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scope change S-02: "Check protection" and the "protection has stopped" alert rule. */
class ProtectionCheckTest {
    private val packages = setOf("com.instagram.android", "com.google.android.youtube", "com.snapchat.android")
    private val healthy = EnvironmentSnapshot(
        accessibilityEnabled = true,
        accessibilityConnected = true,
        accessibilityEverConnected = true,
        installedApps = setOf(INSTAGRAM, YOUTUBE),
        webViewAvailable = true,
        webViewSupportsRules = true,
        layout = mapOf(INSTAGRAM to LayoutRecognition.RECOGNIZED),
        checkedAtWallMs = 0,
    )
    private val alertsOn = AppPreferences(nativeProtectionEverConnected = true, protectionAlerts = true)

    private fun configs(vararg list: PlatformConfig): Map<Platform, PlatformConfig> = list.associateBy { it.platform }
    private val instagramApp = PlatformConfig(INSTAGRAM, selected = true, openingMethod = NATIVE_APP)

    private fun run(
        configs: Map<Platform, PlatformConfig>,
        env: EnvironmentSnapshot = healthy,
        preferences: AppPreferences = alertsOn,
        probe: ServiceProbe? = ServiceProbe(packages),
        notifications: Boolean = true,
    ) = ProtectionCheck.run(configs, preferences, env, probe, packages, notifications)

    private fun List<CheckItem>.of(kind: CheckKind) = single { it.kind == kind }

    @Test
    fun `a working setup passes every check`() {
        val items = run(configs(instagramApp))
        assertTrue(ProtectionCheck.allPassed(items))
        assertEquals(
            listOf(CheckKind.ACCESSIBILITY_ON, CheckKind.SERVICE_RESPONDING, CheckKind.SERVICE_SCOPE, CheckKind.APP, CheckKind.BROWSER, CheckKind.ALERTS),
            items.map { it.kind },
        )
        assertEquals(CheckDetail.APP_RECOGNIZED, items.of(CheckKind.APP).detail)
    }

    @Test
    fun `a switch that is on is not enough when the service does not answer (UX-08)`() {
        val items = run(configs(instagramApp), probe = null)
        assertEquals(CheckResult.PASS, items.of(CheckKind.ACCESSIBILITY_ON).result)
        assertEquals(CheckResult.FAIL, items.of(CheckKind.SERVICE_RESPONDING).result)
        assertFalse(ProtectionCheck.allPassed(items))
    }

    @Test
    fun `access turned off fails and skips the checks that depend on it`() {
        val items = run(configs(instagramApp), env = healthy.copy(accessibilityEnabled = false, accessibilityConnected = false), probe = null)
        assertEquals(CheckResult.FAIL, items.of(CheckKind.ACCESSIBILITY_ON).result)
        assertTrue(items.none { it.kind == CheckKind.SERVICE_RESPONDING || it.kind == CheckKind.SERVICE_SCOPE })
    }

    @Test
    fun `a service that is not set up for every supported app fails the scope check`() {
        val stale = ServiceProbe(setOf("com.instagram.android", "com.google.android.youtube"))
        assertEquals(CheckResult.FAIL, run(configs(instagramApp), probe = stale).of(CheckKind.SERVICE_SCOPE).result)
    }

    @Test
    fun `app layout results are reported per platform without overclaiming`() {
        val both = configs(instagramApp, PlatformConfig(YOUTUBE, selected = true, openingMethod = NATIVE_APP))
        val items = run(both).filter { it.kind == CheckKind.APP }
        assertEquals(listOf(INSTAGRAM, YOUTUBE), items.map { it.platform })
        // YouTube has not been opened since Dereel started: that is unknown, not a pass.
        assertEquals(CheckResult.INFO to CheckDetail.APP_NOT_SEEN_YET, items[1].result to items[1].detail)

        val broken = run(both, env = healthy.copy(layout = mapOf(YOUTUBE to LayoutRecognition.NOT_RECOGNIZED))).filter { it.kind == CheckKind.APP }
        assertEquals(CheckResult.FAIL to CheckDetail.APP_NOT_RECOGNIZED, broken[1].result to broken[1].detail)
    }

    @Test
    fun `a missing app fails only when the user opens that platform in its app`() {
        val env = healthy.copy(installedApps = emptySet())
        assertEquals(CheckResult.FAIL, run(configs(instagramApp), env = env).of(CheckKind.APP).result)
        val inDereel = configs(PlatformConfig(INSTAGRAM, selected = true, openingMethod = IN_DEREEL))
        assertEquals(CheckResult.INFO, run(inDereel, env = env).of(CheckKind.APP).result)
        // Snapchat has no browser, so nothing about the browser is checked for it.
        val snapchat = configs(PlatformConfig(SNAPCHAT, selected = true))
        assertTrue(run(snapchat, env = healthy.copy(installedApps = setOf(SNAPCHAT))).none { it.kind == CheckKind.BROWSER })
    }

    @Test
    fun `the browser check fails only when a platform relies on it`() {
        val noWebView = healthy.copy(webViewAvailable = false, webViewSupportsRules = false)
        assertEquals(CheckResult.INFO, run(configs(instagramApp), env = noWebView).of(CheckKind.BROWSER).result)
        val inDereel = configs(PlatformConfig(INSTAGRAM, selected = true, openingMethod = IN_DEREEL))
        val item = run(inDereel, env = noWebView).of(CheckKind.BROWSER)
        assertEquals(CheckResult.FAIL to CheckDetail.WEBVIEW_MISSING, item.result to item.detail)
        assertEquals(CheckDetail.WEBVIEW_OUTDATED, run(inDereel, env = healthy.copy(webViewSupportsRules = false)).of(CheckKind.BROWSER).detail)
    }

    @Test
    fun `alerts are reported as off, blocked or working`() {
        assertEquals(CheckDetail.ALERTS_OFF, run(configs(instagramApp), preferences = AppPreferences()).of(CheckKind.ALERTS).detail)
        val blocked = run(configs(instagramApp), notifications = false).of(CheckKind.ALERTS)
        assertEquals(CheckResult.FAIL to CheckDetail.ALERTS_BLOCKED, blocked.result to blocked.detail)
        assertEquals(CheckResult.PASS, run(configs(instagramApp)).of(CheckKind.ALERTS).result)
    }

    @Test
    fun `nothing selected gives nothing to check and is not a pass`() {
        val items = run(configs(PlatformConfig(INSTAGRAM)))
        assertTrue(items.isEmpty())
        assertFalse(ProtectionCheck.allPassed(items))
    }

    // --- alert rule -------------------------------------------------------------------------

    private val stopped = healthy.copy(accessibilityEnabled = false, accessibilityConnected = false)

    @Test
    fun `the alert fires when app protection that worked before stops`() {
        assertTrue(ProtectionCheck.protectionStopped(configs(instagramApp), alertsOn, stopped))
        assertTrue(ProtectionCheck.protectionStopped(configs(instagramApp), alertsOn, healthy.copy(accessibilityConnected = false)))
        assertFalse(ProtectionCheck.protectionStopped(configs(instagramApp), alertsOn, healthy))
    }

    @Test
    fun `no alert unless the user asked for it and protection was set up once`() {
        assertFalse(ProtectionCheck.protectionStopped(configs(instagramApp), AppPreferences(nativeProtectionEverConnected = true), stopped))
        // Never set up: Home already says "Needs setup". An alert would nag about an optional feature.
        assertFalse(ProtectionCheck.protectionStopped(configs(instagramApp), AppPreferences(protectionAlerts = true), stopped))
    }

    @Test
    fun `no alert when nothing relies on app protection`() {
        assertFalse(ProtectionCheck.protectionStopped(configs(instagramApp.copy(selected = false)), alertsOn, stopped))
        assertFalse(ProtectionCheck.protectionStopped(configs(instagramApp.copy(blockShortVideos = false)), alertsOn, stopped))
        assertFalse(ProtectionCheck.protectionStopped(configs(instagramApp), alertsOn, stopped.copy(installedApps = emptySet())))
        // A platform opened inside Dereel still counts if its app is installed, because the
        // service blocks there too.
        val inDereel = configs(PlatformConfig(INSTAGRAM, selected = true, openingMethod = IN_DEREEL))
        assertTrue(ProtectionCheck.protectionStopped(inDereel, alertsOn, stopped))
    }
}
