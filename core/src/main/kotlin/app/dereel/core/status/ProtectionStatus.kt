package app.dereel.core.status

import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig

/** The six user-visible states (UX-08). */
enum class ProtectionState { ACTIVE, OFF, PAUSED, NEEDS_SETUP, NEEDS_ATTENTION, UNAVAILABLE }

enum class StatusReason {
    PROTECTING,
    BLOCKING_OFF,
    TEMPORARY_ACCESS,
    OPENING_METHOD_NOT_CHOSEN,
    ACCESSIBILITY_NOT_ENABLED,
    ACCESSIBILITY_TURNED_OFF,
    SERVICE_NOT_RUNNING,
    LAYOUT_NOT_RECOGNIZED,
    APP_NOT_INSTALLED,
    WEBVIEW_UNAVAILABLE,
    WEBVIEW_OUTDATED,
}

/** One primary recovery action per status that needs user action (UX-09). */
enum class RecoveryAction {
    NONE,
    CHOOSE_OPENING_METHOD,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_INSIDE_DEREEL,
    UPDATE_WEBVIEW,
    END_TEMPORARY_ACCESS,
}

enum class LayoutRecognition { UNKNOWN, RECOGNIZED, NOT_RECOGNIZED }

/** The "Method health" logical record (Section 7.1). Computed from live checks, not stored. */
data class MethodHealth(
    val platform: Platform,
    /** Null when no opening method has been chosen yet. */
    val method: OpeningMethod?,
    val state: ProtectionState,
    val reason: StatusReason,
    val recovery: RecoveryAction,
    val checkedAtWallMs: Long,
    val grantRemainingMs: Long = 0,
) {
    val needsAction: Boolean
        get() = state == ProtectionState.NEEDS_SETUP || state == ProtectionState.NEEDS_ATTENTION ||
            state == ProtectionState.UNAVAILABLE
}

/** Results of the live checks behind status (FR-36). */
data class EnvironmentSnapshot(
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val accessibilityEverConnected: Boolean,
    val installedApps: Set<Platform>,
    val webViewAvailable: Boolean,
    val webViewSupportsRules: Boolean,
    val layout: Map<Platform, LayoutRecognition> = emptyMap(),
    val checkedAtWallMs: Long,
)

enum class OverallStatus {
    /** Every shown method is operational. The only case that shows an overall active message. */
    ALL_ACTIVE,
    NEEDS_ACTION,
    PARTLY_OFF_OR_PAUSED,
    NOTHING_SELECTED,
}

object StatusEvaluator {
    /**
     * Methods to show for a platform: the saved opening method, plus native protection whenever
     * the accessibility service is enabled, because it then enforces in the installed app too.
     * Native protection that worked before is still shown after access is turned off, so the
     * loss is visible (as "Needs attention") instead of the row silently disappearing.
     */
    fun methodsToShow(config: PlatformConfig, env: EnvironmentSnapshot): List<OpeningMethod> {
        val saved = config.openingMethod ?: return emptyList()
        val methods = mutableListOf(saved)
        // Only where Dereel has a native detector for the platform: an unsupported method is
        // never shown as protection.
        val nativeToo = saved == OpeningMethod.IN_DEREEL &&
            Capabilities.supports(config.platform, OpeningMethod.NATIVE_APP) &&
            (env.accessibilityEnabled || env.accessibilityEverConnected) && config.platform in env.installedApps
        if (nativeToo) methods += OpeningMethod.NATIVE_APP
        return methods
    }

    fun platformHealth(config: PlatformConfig, env: EnvironmentSnapshot, grantRemainingMs: Long): List<MethodHealth> {
        if (!config.selected) return emptyList()
        if (config.openingMethod == null) {
            return listOf(
                MethodHealth(
                    config.platform, null, ProtectionState.NEEDS_SETUP, StatusReason.OPENING_METHOD_NOT_CHOSEN,
                    RecoveryAction.CHOOSE_OPENING_METHOD, env.checkedAtWallMs,
                ),
            )
        }
        return methodsToShow(config, env).map { evaluate(config, it, env, grantRemainingMs) }
    }

    fun evaluate(
        config: PlatformConfig,
        method: OpeningMethod,
        env: EnvironmentSnapshot,
        grantRemainingMs: Long,
    ): MethodHealth {
        fun health(state: ProtectionState, reason: StatusReason, recovery: RecoveryAction = RecoveryAction.NONE) =
            MethodHealth(config.platform, method, state, reason, recovery, env.checkedAtWallMs, grantRemainingMs)

        // "Open inside Dereel" is a way out only for platforms that have a browser method.
        val browserFallback = if (Capabilities.supports(config.platform, OpeningMethod.IN_DEREEL)) {
            RecoveryAction.OPEN_INSIDE_DEREEL
        } else {
            RecoveryAction.NONE
        }

        return when (method) {
            OpeningMethod.NATIVE_APP -> when {
                config.platform !in env.installedApps ->
                    health(ProtectionState.UNAVAILABLE, StatusReason.APP_NOT_INSTALLED, browserFallback)
                !config.blockShortVideos -> health(ProtectionState.OFF, StatusReason.BLOCKING_OFF)
                !env.accessibilityEnabled && env.accessibilityEverConnected ->
                    health(ProtectionState.NEEDS_ATTENTION, StatusReason.ACCESSIBILITY_TURNED_OFF, RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS)
                !env.accessibilityEnabled ->
                    health(ProtectionState.NEEDS_SETUP, StatusReason.ACCESSIBILITY_NOT_ENABLED, RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS)
                !env.accessibilityConnected ->
                    health(ProtectionState.NEEDS_ATTENTION, StatusReason.SERVICE_NOT_RUNNING, RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS)
                env.layout[config.platform] == LayoutRecognition.NOT_RECOGNIZED ->
                    health(ProtectionState.NEEDS_ATTENTION, StatusReason.LAYOUT_NOT_RECOGNIZED, browserFallback)
                grantRemainingMs > 0 ->
                    health(ProtectionState.PAUSED, StatusReason.TEMPORARY_ACCESS, RecoveryAction.END_TEMPORARY_ACCESS)
                else -> health(ProtectionState.ACTIVE, StatusReason.PROTECTING)
            }
            OpeningMethod.IN_DEREEL -> when {
                !env.webViewAvailable ->
                    health(ProtectionState.UNAVAILABLE, StatusReason.WEBVIEW_UNAVAILABLE, RecoveryAction.UPDATE_WEBVIEW)
                !env.webViewSupportsRules ->
                    health(ProtectionState.NEEDS_ATTENTION, StatusReason.WEBVIEW_OUTDATED, RecoveryAction.UPDATE_WEBVIEW)
                !config.blockShortVideos -> health(ProtectionState.OFF, StatusReason.BLOCKING_OFF)
                grantRemainingMs > 0 ->
                    health(ProtectionState.PAUSED, StatusReason.TEMPORARY_ACCESS, RecoveryAction.END_TEMPORARY_ACCESS)
                else -> health(ProtectionState.ACTIVE, StatusReason.PROTECTING)
            }
        }
    }

    fun overall(all: List<MethodHealth>): OverallStatus = when {
        all.isEmpty() -> OverallStatus.NOTHING_SELECTED
        all.any { it.needsAction } -> OverallStatus.NEEDS_ACTION
        all.all { it.state == ProtectionState.ACTIVE } -> OverallStatus.ALL_ACTIVE
        else -> OverallStatus.PARTLY_OFF_OR_PAUSED
    }
}
