package app.dereel.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.dereel.R
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import app.dereel.core.model.RuleSummary
import app.dereel.core.status.MethodHealth
import app.dereel.core.status.ProtectionState
import app.dereel.core.status.RecoveryAction
import app.dereel.core.status.StatusReason
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@StringRes
fun platformNameRes(platform: Platform): Int = when (platform) {
    Platform.INSTAGRAM -> R.string.platform_instagram
    Platform.YOUTUBE -> R.string.platform_youtube
    Platform.FACEBOOK -> R.string.platform_facebook
    Platform.SNAPCHAT -> R.string.platform_snapchat
}

@StringRes
fun shortVideoNameRes(platform: Platform): Int = when (platform) {
    Platform.INSTAGRAM, Platform.FACEBOOK -> R.string.short_video_reels
    Platform.YOUTUBE -> R.string.short_video_shorts
    Platform.SNAPCHAT -> R.string.short_video_spotlight
}

/**
 * The installed app Dereel protects for a platform. For Facebook that is Facebook Lite, so text
 * about app protection must not say "the Facebook app".
 */
@StringRes
fun nativeAppNameRes(platform: Platform): Int = when (platform) {
    Platform.FACEBOOK -> R.string.app_facebook_lite
    else -> platformNameRes(platform)
}

@Composable
fun nativeAppName(platform: Platform): String = stringResource(nativeAppNameRes(platform))

/** What keeps working while the platform's short videos are blocked. */
@StringRes
fun ruleBlockDescRes(platform: Platform): Int = when (platform) {
    Platform.INSTAGRAM -> R.string.rule_block_desc_instagram
    Platform.YOUTUBE -> R.string.rule_block_desc_youtube
    Platform.FACEBOOK -> R.string.rule_block_desc_facebook
    Platform.SNAPCHAT -> R.string.rule_block_desc_snapchat
}

/** Explains an opening method for a platform, or null where Dereel does not offer that method. */
fun methodBodyRes(platform: Platform, method: OpeningMethod): Int? = when (method) {
    OpeningMethod.NATIVE_APP -> when (platform) {
        Platform.INSTAGRAM -> R.string.method_native_body_instagram
        Platform.YOUTUBE -> R.string.method_native_body_youtube
        Platform.SNAPCHAT -> R.string.method_native_body_snapchat
        Platform.FACEBOOK -> R.string.method_native_body_facebook
    }
    OpeningMethod.IN_DEREEL -> when (platform) {
        Platform.INSTAGRAM -> R.string.method_browser_body_instagram
        Platform.YOUTUBE -> R.string.method_browser_body_youtube
        Platform.FACEBOOK -> R.string.method_browser_body_facebook
        Platform.SNAPCHAT -> null
    }
}

/** The allowance is shared between methods only where a platform has two (FR-24). */
@Composable
fun allowanceExplainer(platform: Platform): String =
    if (Capabilities.methods(platform).size > 1) {
        stringResource(R.string.allowance_explainer, shortVideoName(platform), nativeAppName(platform))
    } else {
        stringResource(R.string.allowance_explainer_single, shortVideoName(platform))
    }

/** "A", "A and B" or "A, B and C". */
@Composable
fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> stringResource(R.string.list_and, names.dropLast(1).joinToString(", "), names.last())
}

@Composable
fun platformName(platform: Platform): String = stringResource(platformNameRes(platform))

@Composable
fun shortVideoName(platform: Platform): String = stringResource(shortVideoNameRes(platform))

/** One line saying what is switched on for a platform, for the list of apps in Settings. */
@Composable
fun ruleSummaryText(config: PlatformConfig): String {
    val videos = shortVideoName(config.platform)
    return when (val summary = RuleSummary.of(config)) {
        RuleSummary.NotAdded -> stringResource(R.string.rules_not_added)
        RuleSummary.BlockingOff -> stringResource(R.string.rules_blocking_off, videos)
        is RuleSummary.Blocking -> {
            val blocking = stringResource(R.string.rules_blocking, videos)
            val allowance = summary.allowanceMinutes?.let {
                stringResource(R.string.allowance_value, formatDuration(it * AllowanceSetting.MINUTE_MS))
            }
            val interval = if (summary.interval != null) stringResource(R.string.rules_interval) else null
            listOfNotNull(blocking, allowance, interval).joinToString(stringResource(R.string.rules_separator))
        }
    }
}

@Composable
fun stateLabel(state: ProtectionState): String = stringResource(
    when (state) {
        ProtectionState.ACTIVE -> R.string.status_active
        ProtectionState.OFF -> R.string.status_off
        ProtectionState.PAUSED -> R.string.status_paused
        ProtectionState.NEEDS_SETUP -> R.string.status_needs_setup
        ProtectionState.NEEDS_ATTENTION -> R.string.status_needs_attention
        ProtectionState.UNAVAILABLE -> R.string.status_unavailable
    },
)

@Composable
fun methodLabel(method: OpeningMethod?, platform: Platform): String = when (method) {
    OpeningMethod.NATIVE_APP -> stringResource(R.string.method_label_native, nativeAppName(platform))
    OpeningMethod.IN_DEREEL -> stringResource(R.string.method_label_browser)
    null -> stringResource(R.string.method_label_none)
}

/**
 * Specific reason text for a status (UX-09). [timeLimited] is true when an allowance or interval
 * timer is set: short videos are then limited, not simply blocked, and the text must not say
 * otherwise.
 */
@Composable
fun reasonText(health: MethodHealth, timeLimited: Boolean = false): String {
    val platform = health.platform
    val name = platformName(platform)
    val app = nativeAppName(platform)
    val videos = shortVideoName(platform)
    return when (health.reason) {
        StatusReason.PROTECTING -> when {
            timeLimited && health.method == OpeningMethod.NATIVE_APP -> stringResource(R.string.reason_limiting_native, videos, app)
            timeLimited -> stringResource(R.string.reason_limiting_browser, videos, name)
            health.method == OpeningMethod.NATIVE_APP -> stringResource(R.string.reason_protecting_native, videos, app)
            else -> stringResource(R.string.reason_protecting_browser, videos, name)
        }
        StatusReason.BLOCKING_OFF -> stringResource(R.string.reason_blocking_off, videos)
        StatusReason.TEMPORARY_ACCESS ->
            stringResource(R.string.reason_temporary_access, videos, formatClock(LocalContext.current, System.currentTimeMillis() + health.grantRemainingMs))
        StatusReason.OPENING_METHOD_NOT_CHOSEN -> stringResource(R.string.reason_method_not_chosen, name)
        StatusReason.ACCESSIBILITY_NOT_ENABLED -> stringResource(R.string.reason_accessibility_not_enabled, videos, app)
        StatusReason.ACCESSIBILITY_TURNED_OFF -> stringResource(R.string.reason_accessibility_turned_off, videos, app)
        StatusReason.SERVICE_NOT_RUNNING -> stringResource(R.string.reason_service_not_running)
        StatusReason.LAYOUT_NOT_RECOGNIZED -> stringResource(R.string.reason_layout_not_recognized, app, videos)
        StatusReason.APP_NOT_INSTALLED -> stringResource(R.string.reason_app_not_installed, app)
        StatusReason.WEBVIEW_UNAVAILABLE -> stringResource(R.string.reason_webview_unavailable, name)
        StatusReason.WEBVIEW_OUTDATED -> stringResource(R.string.reason_webview_outdated)
    }
}

@Composable
fun recoveryLabel(health: MethodHealth): String? = when (health.recovery) {
    RecoveryAction.NONE -> null
    RecoveryAction.CHOOSE_OPENING_METHOD -> stringResource(R.string.recovery_choose_method)
    RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS ->
        if (health.state == ProtectionState.NEEDS_SETUP) stringResource(R.string.recovery_set_up) else stringResource(R.string.recovery_fix)
    RecoveryAction.OPEN_INSIDE_DEREEL -> stringResource(R.string.recovery_open_inside)
    RecoveryAction.UPDATE_WEBVIEW -> stringResource(R.string.action_update_webview)
    RecoveryAction.END_TEMPORARY_ACCESS -> stringResource(R.string.recovery_end_temporary)
}

/** Short duration, such as "45 sec", "12 min" or "1 h 5 min". */
fun formatDuration(context: Context, ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    if (totalSeconds < 60) return context.getString(R.string.duration_seconds, totalSeconds)
    val minutes = totalSeconds / 60
    if (minutes < 60) return context.getString(R.string.duration_minutes, minutes)
    // Whole hours read as "2 h", not "2 h 0 min".
    if (minutes % 60 == 0L) return context.getString(R.string.duration_hours, minutes / 60)
    return context.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
fun formatDuration(ms: Long): String = formatDuration(LocalContext.current, ms)

fun formatClock(context: Context, wallMs: Long): String = DateFormat.getTimeFormat(context).format(Date(wallMs))

fun formatDay(epochDay: Long, locale: Locale = Locale.getDefault()): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
    return LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern(pattern, locale))
}

fun today(): Long = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** Short weekday, such as "Mon". */
fun formatWeekday(epochDay: Long, locale: Locale = Locale.getDefault()): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE", locale))

/** A minute of the day in the phone's 12- or 24-hour format, such as "22:00" or "10:00 pm". */
fun formatMinuteOfDay(context: Context, minuteOfDay: Int): String {
    val time = LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), (minuteOfDay % 60).coerceIn(0, 59))
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
