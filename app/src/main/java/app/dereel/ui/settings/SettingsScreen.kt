package app.dereel.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import app.dereel.ui.common.BodyText
import app.dereel.ui.common.ClickRow
import app.dereel.ui.common.RowDivider
import app.dereel.ui.common.ScreenTitle
import app.dereel.ui.common.SectionCard
import app.dereel.ui.common.SwitchRow
import app.dereel.ui.common.formatMinuteOfDay
import app.dereel.ui.common.platformName

/**
 * Settings in four groups: protection, time, data and help. FR-35 keeps history, one platform's
 * browser data and a full reset as separate actions.
 */
@Composable
fun SettingsScreen(state: MainState, vm: MainViewModel, onHelp: () -> Unit) {
    val dialogs = LocalDialogs.current
    val context = LocalContext.current
    val env = state.environment
    val preferences = state.preferences

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
                title = stringResource(R.string.settings_accessibility),
                value = stringResource(
                    when {
                        env == null -> R.string.overall_checking
                        env.accessibilityEnabled && env.accessibilityConnected -> R.string.settings_accessibility_on
                        env.accessibilityEnabled -> R.string.settings_accessibility_not_responding
                        else -> R.string.settings_accessibility_off
                    },
                ),
                description = stringResource(R.string.settings_accessibility_desc),
                onClick = { dialogs.show(AppDialog.AccessibilityDisclosure()) },
            )
            RowDivider()
            ClickRow(
                title = stringResource(R.string.check_title),
                value = null,
                description = stringResource(R.string.settings_check_desc),
                onClick = { dialogs.show(AppDialog.ProtectionCheck) },
            )
            RowDivider()
            SwitchRow(
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
                Text(
                    stringResource(R.string.settings_alerts_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(
                    onClick = { SystemIntents.openNotificationSettings(context) },
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                ) { Text(stringResource(R.string.settings_alerts_open)) }
            }
        }

        SectionCard(stringResource(R.string.settings_time)) {
            SwitchRow(
                title = stringResource(R.string.settings_sleep),
                description = stringResource(R.string.settings_sleep_desc),
                checked = preferences.sleep.enabled,
                onCheckedChange = { on -> vm.updateSleep { it.copy(enabled = on) } },
            )
            if (preferences.sleep.enabled) {
                ClickRow(
                    title = stringResource(R.string.settings_sleep_start),
                    value = formatMinuteOfDay(context, preferences.sleep.startMinute),
                    onClick = { dialogs.show(AppDialog.SleepTime(start = true)) },
                )
                ClickRow(
                    title = stringResource(R.string.settings_sleep_end),
                    value = formatMinuteOfDay(context, preferences.sleep.endMinute),
                    onClick = { dialogs.show(AppDialog.SleepTime(start = false)) },
                )
                if (preferences.sleep.startMinute == preferences.sleep.endMinute) {
                    Text(
                        stringResource(R.string.settings_sleep_same),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.settings_live_timer),
                description = stringResource(R.string.settings_live_timer_desc),
                checked = preferences.liveTimer,
                onCheckedChange = vm::setLiveTimer,
            )
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.settings_temporary_access),
                description = stringResource(R.string.settings_temporary_access_desc),
                checked = preferences.temporaryAccessEnabled,
                onCheckedChange = vm::setTemporaryAccessEnabled,
            )
        }

        SectionCard(stringResource(R.string.settings_data)) {
            ClickRow(
                title = stringResource(R.string.clear_history_title),
                value = null,
                description = stringResource(R.string.settings_clear_history_desc),
                onClick = { dialogs.show(AppDialog.ClearHistory) },
            )
            Platform.entries.filter(PlatformWeb::hasBrowser).forEach { platform ->
                val name = platformName(platform)
                RowDivider()
                ClickRow(
                    title = stringResource(R.string.clear_browser_title, name),
                    value = null,
                    description = stringResource(R.string.sign_out_desc, name),
                    onClick = { dialogs.show(AppDialog.ClearBrowserData(platform, signOutWording = false)) },
                )
            }
            RowDivider()
            ClickRow(
                title = stringResource(R.string.reset_title),
                value = null,
                description = stringResource(R.string.settings_reset_desc),
                onClick = { dialogs.show(AppDialog.Reset) },
            )
        }

        SectionCard(stringResource(R.string.settings_help)) {
            ClickRow(
                title = stringResource(R.string.help_title),
                value = null,
                description = stringResource(R.string.settings_help_desc),
                onClick = onHelp,
            )
        }

        BodyText(
            stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME),
            Modifier.padding(start = 4.dp, top = 20.dp, bottom = 16.dp),
        )
    }
}
