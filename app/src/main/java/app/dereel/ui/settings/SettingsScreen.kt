package app.dereel.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dereel.BuildConfig
import app.dereel.R
import app.dereel.core.model.Platform
import app.dereel.core.web.PlatformWeb
import app.dereel.platform.SystemIntents
import app.dereel.status.ProtectionAlerts
import app.dereel.ui.AppDialog
import app.dereel.ui.LocalDialogs
import app.dereel.ui.MainState
import app.dereel.ui.MainViewModel
import app.dereel.ui.PlatformUi
import app.dereel.ui.common.AppIcons
import app.dereel.ui.common.ClickRow
import app.dereel.ui.common.InfoRow
import app.dereel.ui.common.PlatformAvatar
import app.dereel.ui.common.RowDivider
import app.dereel.ui.common.RowIcon
import app.dereel.ui.common.RowLeadingGap
import app.dereel.ui.common.RowLeadingSize
import app.dereel.ui.common.ScreenTitle
import app.dereel.ui.common.SectionCard
import app.dereel.ui.common.StatusPill
import app.dereel.ui.common.SwitchRow
import app.dereel.ui.common.formatMinuteOfDay
import app.dereel.ui.common.platformName
import app.dereel.ui.common.ruleSummaryText

/**
 * Settings in five groups, each one card: protection, your apps, time, your data and about.
 *
 * Every row has the same shape (an icon or logo, a title, one line of help, then its control), so
 * the text lines up down the whole screen. What applies to one app is reached through that app's
 * row. Choices that are rarely needed or cannot be undone sit one tap further in (UX-05), and
 * FR-35 keeps history, one platform's browser data and a full reset as separate actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: MainState, vm: MainViewModel, onPlatform: (Platform) -> Unit, onHelp: () -> Unit) {
    val dialogs = LocalDialogs.current
    val context = LocalContext.current
    val env = state.environment
    val preferences = state.preferences
    val scheme = MaterialTheme.colorScheme
    // Where a row's text starts, so dividers and anything shown under a row line up with it.
    val textStart = RowLeadingSize + RowLeadingGap

    // UX-07: notification permission is asked for when the alert is turned on, not before.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.setProtectionAlerts(true)
        } else {
            Toast.makeText(context, R.string.settings_alerts_denied, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        ScreenTitle(stringResource(R.string.nav_settings))

        SectionCard(stringResource(R.string.settings_protection)) {
            ClickRow(
                leading = { RowIcon(AppIcons.Shield) },
                title = stringResource(R.string.settings_accessibility),
                value = null,
                description = stringResource(R.string.settings_accessibility_desc),
                // The state as words and an icon, never colour alone (NFR-03). "Off" is neutral:
                // app protection is optional, so its absence is not an error (UX-11).
                status = {
                    when {
                        env == null -> Unit
                        env.accessibilityEnabled && env.accessibilityConnected ->
                            StatusPill(stringResource(R.string.settings_accessibility_on), scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Default.CheckCircle)
                        env.accessibilityEnabled ->
                            StatusPill(stringResource(R.string.settings_accessibility_issue), scheme.errorContainer, scheme.onErrorContainer, Icons.Default.Warning)
                        else -> StatusPill(stringResource(R.string.settings_accessibility_off), scheme.surfaceVariant, scheme.onSurfaceVariant, null)
                    }
                },
                onClick = { dialogs.show(AppDialog.AccessibilityDisclosure()) },
            )
            RowDivider(textStart)
            ClickRow(
                leading = { RowIcon(Icons.Default.CheckCircle) },
                title = stringResource(R.string.check_title),
                value = null,
                description = stringResource(R.string.settings_check_desc),
                onClick = { dialogs.show(AppDialog.ProtectionCheck) },
            )
            RowDivider(textStart)
            SwitchRow(
                leading = { RowIcon(Icons.Default.Notifications) },
                title = stringResource(R.string.settings_alerts),
                description = stringResource(R.string.settings_alerts_desc),
                checked = preferences.protectionAlerts,
                onCheckedChange = { on ->
                    val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ProtectionAlerts.canPost(context)
                    if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setProtectionAlerts(on)
                },
            )
            // Alerts that Android will not show are worse than no alerts: say so and offer the fix.
            if (preferences.protectionAlerts && !ProtectionAlerts.canPost(context)) {
                Column(Modifier.padding(start = textStart, bottom = 12.dp)) {
                    Text(
                        stringResource(R.string.settings_alerts_blocked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error,
                    )
                    FilledTonalButton(
                        onClick = { SystemIntents.openNotificationSettings(context) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(stringResource(R.string.settings_alerts_open)) }
                }
            }
        }

        SectionCard(stringResource(R.string.settings_apps)) {
            // Added apps first, then the ones that can still be added. The sort is stable, so each
            // group keeps the usual order.
            state.platforms.sortedByDescending { it.config.selected }.forEachIndexed { index, ui ->
                if (index > 0) RowDivider(textStart)
                AppRow(
                    ui,
                    onOpen = { onPlatform(ui.platform) },
                    onAdd = {
                        vm.addPlatform(ui.platform)
                        onPlatform(ui.platform)
                    },
                )
            }
        }

        SectionCard(stringResource(R.string.settings_time)) {
            SwitchRow(
                leading = { RowIcon(AppIcons.Moon) },
                title = stringResource(R.string.settings_sleep),
                description = stringResource(R.string.settings_sleep_desc),
                checked = preferences.sleep.enabled,
                onCheckedChange = { on -> vm.updateSleep { it.copy(enabled = on) } },
            )
            // The two times appear under the switch that needs them, and nowhere else.
            AnimatedVisibility(visible = preferences.sleep.enabled) {
                Column(Modifier.padding(start = textStart, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { dialogs.show(AppDialog.SleepTime(start = true)) }) {
                            Text(stringResource(R.string.settings_sleep_starts, formatMinuteOfDay(context, preferences.sleep.startMinute)))
                        }
                        FilledTonalButton(onClick = { dialogs.show(AppDialog.SleepTime(start = false)) }) {
                            Text(stringResource(R.string.settings_sleep_ends, formatMinuteOfDay(context, preferences.sleep.endMinute)))
                        }
                    }
                    if (preferences.sleep.startMinute == preferences.sleep.endMinute) {
                        Text(
                            stringResource(R.string.settings_sleep_same),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.error,
                        )
                    }
                }
            }
            RowDivider(textStart)
            SwitchRow(
                leading = { RowIcon(AppIcons.Timer) },
                title = stringResource(R.string.settings_live_timer),
                description = stringResource(R.string.settings_live_timer_desc),
                checked = preferences.liveTimer,
                onCheckedChange = vm::setLiveTimer,
            )
            RowDivider(textStart)
            SwitchRow(
                leading = { RowIcon(AppIcons.Hourglass) },
                title = stringResource(R.string.settings_temporary_access),
                description = stringResource(R.string.settings_temporary_access_desc),
                checked = preferences.temporaryAccessEnabled,
                onCheckedChange = vm::setTemporaryAccessEnabled,
            )
        }

        SectionCard(stringResource(R.string.settings_data)) {
            ClickRow(
                leading = { RowIcon(Icons.Default.Delete) },
                title = stringResource(R.string.clear_history_title),
                value = null,
                description = stringResource(R.string.settings_clear_history_desc),
                onClick = { dialogs.show(AppDialog.ClearHistory) },
            )
            RowDivider(textStart)
            BrowserData(textStart) { platform -> dialogs.show(AppDialog.ClearBrowserData(platform, signOutWording = false)) }
            RowDivider(textStart)
            // The one action here that removes everything: marked as such, and last.
            ClickRow(
                leading = { RowIcon(Icons.Default.Refresh, tint = scheme.error) },
                title = stringResource(R.string.reset_title),
                titleColor = scheme.error,
                value = null,
                description = stringResource(R.string.settings_reset_desc),
                onClick = { dialogs.show(AppDialog.Reset) },
            )
        }

        SectionCard(stringResource(R.string.settings_about), Modifier.padding(bottom = 16.dp)) {
            ClickRow(
                leading = { RowIcon(Icons.Default.Info) },
                title = stringResource(R.string.settings_help),
                value = null,
                description = stringResource(R.string.settings_help_desc),
                onClick = onHelp,
            )
            RowDivider(textStart)
            InfoRow(
                leading = { RowIcon(ImageVector.vectorResource(R.drawable.ic_stat_dereel)) },
                title = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                description = stringResource(R.string.settings_about_desc),
            )
        }
    }
}

/** The word at the end of a row whose tap does something other than open a screen. */
@Composable
private fun RowAction(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** One app: its logo, its name and what is switched on for it. Opens the app's own screen. */
@Composable
private fun AppRow(ui: PlatformUi, onOpen: () -> Unit, onAdd: () -> Unit) {
    val name = platformName(ui.platform)
    if (ui.config.selected) {
        ClickRow(
            leading = { PlatformAvatar(ui.platform, size = RowLeadingSize) },
            title = name,
            value = null,
            description = ruleSummaryText(ui.config),
            onClick = onOpen,
        )
    } else {
        ClickRow(
            leading = { PlatformAvatar(ui.platform, size = RowLeadingSize) },
            title = name,
            value = null,
            description = stringResource(R.string.rules_not_added),
            trailing = { RowAction(stringResource(R.string.action_add)) },
            onClickLabel = stringResource(R.string.home_add_platform, name),
            onClick = onAdd,
        )
    }
}

/**
 * Clearing one site's sign-in and data in Dereel's browser. The sites are listed only when asked
 * for, so a choice that is rarely needed does not take up three rows (UX-05). Each stays its own
 * action, with its own confirmation (FR-35).
 */
@Composable
private fun ColumnScope.BrowserData(textStart: Dp, onClear: (Platform) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandedLabel = stringResource(if (expanded) R.string.more_controls_expanded else R.string.more_controls_collapsed)
    ClickRow(
        leading = { RowIcon(Icons.AutoMirrored.Filled.ExitToApp) },
        title = stringResource(R.string.settings_browser_data),
        value = null,
        description = stringResource(R.string.settings_browser_data_desc),
        trailing = {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.semantics { stateDescription = expandedLabel },
        onClick = { expanded = !expanded },
    )
    AnimatedVisibility(visible = expanded) {
        Column(Modifier.padding(start = textStart)) {
            Platform.entries.filter(PlatformWeb::hasBrowser).forEach { platform ->
                RowDivider()
                val name = platformName(platform)
                ClickRow(
                    leading = { PlatformAvatar(platform, size = 32.dp) },
                    title = name,
                    value = null,
                    trailing = { RowAction(stringResource(R.string.action_clear)) },
                    onClickLabel = stringResource(R.string.clear_browser_title, name),
                    onClick = { onClear(platform) },
                )
            }
        }
    }
}
