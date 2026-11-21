package app.dereel.core.status

import app.dereel.core.model.AppPreferences
import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig

enum class CheckKind { ACCESSIBILITY_ON, SERVICE_RESPONDING, SERVICE_SCOPE, APP, BROWSER, ALERTS }

enum class CheckResult {
    PASS,
    FAIL,

    /** Not a failure, but worth knowing: for example an app Dereel has not seen open yet. */
    INFO,
}

enum class CheckDetail {
    NONE,
    APP_NOT_INSTALLED,
    APP_RECOGNIZED,
    APP_NOT_SEEN_YET,
    APP_NOT_RECOGNIZED,
    WEBVIEW_MISSING,
    WEBVIEW_OUTDATED,
    ALERTS_OFF,
    ALERTS_BLOCKED,
}

data class CheckItem(
    val kind: CheckKind,
    val result: CheckResult,
    val platform: Platform? = null,
    val detail: CheckDetail = CheckDetail.NONE,
)

/** What the live accessibility service answered when asked. Null means nothing answered. */
data class ServiceProbe(val observedPackages: Set<String>)

/**
 * "Check protection" (scope change S-02): a list of live checks the user can run on demand, and
 * the rule for the "protection has stopped" alert. Both read real state. Neither assumes that a
 * switch being on means protection is working (UX-08, FR-36).
 */
object ProtectionCheck {
    fun run(
        configs: Map<Platform, PlatformConfig>,
        preferences: AppPreferences,
        env: EnvironmentSnapshot,
        probe: ServiceProbe?,
        expectedPackages: Set<String>,
        notificationsAllowed: Boolean,
    ): List<CheckItem> {
        val selected = configs.values.filter { it.selected }.sortedBy { it.platform.ordinal }
        val inApps = selected.filter { Capabilities.supports(it.platform, OpeningMethod.NATIVE_APP) }
        val items = ArrayList<CheckItem>()

        if (inApps.isNotEmpty()) {
            items += CheckItem(CheckKind.ACCESSIBILITY_ON, passIf(env.accessibilityEnabled))
            if (env.accessibilityEnabled) {
                items += CheckItem(CheckKind.SERVICE_RESPONDING, passIf(probe != null))
                // An old service configuration would silently miss apps added in an update.
                if (probe != null) items += CheckItem(CheckKind.SERVICE_SCOPE, passIf(probe.observedPackages.containsAll(expectedPackages)))
            }
            for (config in inApps) {
                val platform = config.platform
                items += when {
                    platform !in env.installedApps -> CheckItem(
                        CheckKind.APP,
                        // Only a problem if the user chose to open this platform in its app.
                        if (config.openingMethod == OpeningMethod.NATIVE_APP) CheckResult.FAIL else CheckResult.INFO,
                        platform,
                        CheckDetail.APP_NOT_INSTALLED,
                    )
                    env.layout[platform] == LayoutRecognition.NOT_RECOGNIZED ->
                        CheckItem(CheckKind.APP, CheckResult.FAIL, platform, CheckDetail.APP_NOT_RECOGNIZED)
                    env.layout[platform] == LayoutRecognition.RECOGNIZED ->
                        CheckItem(CheckKind.APP, CheckResult.PASS, platform, CheckDetail.APP_RECOGNIZED)
                    else -> CheckItem(CheckKind.APP, CheckResult.INFO, platform, CheckDetail.APP_NOT_SEEN_YET)
                }
            }
        }

        val inBrowser = selected.filter { Capabilities.supports(it.platform, OpeningMethod.IN_DEREEL) }
        if (inBrowser.isNotEmpty()) {
            val relied = inBrowser.any { it.openingMethod == OpeningMethod.IN_DEREEL }
            val problem = if (relied) CheckResult.FAIL else CheckResult.INFO
            items += when {
                !env.webViewAvailable -> CheckItem(CheckKind.BROWSER, problem, detail = CheckDetail.WEBVIEW_MISSING)
                !env.webViewSupportsRules -> CheckItem(CheckKind.BROWSER, problem, detail = CheckDetail.WEBVIEW_OUTDATED)
                else -> CheckItem(CheckKind.BROWSER, CheckResult.PASS)
            }
        }

        if (inApps.isNotEmpty()) {
            items += when {
                !preferences.protectionAlerts -> CheckItem(CheckKind.ALERTS, CheckResult.INFO, detail = CheckDetail.ALERTS_OFF)
                !notificationsAllowed -> CheckItem(CheckKind.ALERTS, CheckResult.FAIL, detail = CheckDetail.ALERTS_BLOCKED)
                else -> CheckItem(CheckKind.ALERTS, CheckResult.PASS)
            }
        }
        return items
    }

    fun allPassed(items: List<CheckItem>): Boolean = items.isNotEmpty() && items.none { it.result == CheckResult.FAIL }

    /**
     * Whether to alert that app protection stopped: the user asked for alerts, protection worked
     * at least once, a selected platform with blocking on has its app installed, and the service
     * is off or not connected. Platforms used only inside Dereel never cause an alert.
     */
    fun protectionStopped(
        configs: Map<Platform, PlatformConfig>,
        preferences: AppPreferences,
        env: EnvironmentSnapshot,
    ): Boolean {
        if (!preferences.protectionAlerts || !preferences.nativeProtectionEverConnected) return false
        val relied = configs.values.any {
            it.selected && it.blockShortVideos && Capabilities.supports(it.platform, OpeningMethod.NATIVE_APP) &&
                it.platform in env.installedApps
        }
        return relied && !(env.accessibilityEnabled && env.accessibilityConnected)
    }

    private fun passIf(ok: Boolean) = if (ok) CheckResult.PASS else CheckResult.FAIL
}
