package app.dereel.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.text.format.DateFormat
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import app.dereel.core.model.IntervalSetting
import app.dereel.core.model.SleepSchedule
import app.dereel.core.status.CheckDetail
import app.dereel.core.status.CheckItem
import app.dereel.core.status.CheckKind
import app.dereel.core.status.CheckResult
import app.dereel.core.status.ProtectionCheck
import app.dereel.ui.common.formatDuration
import app.dereel.R
import app.dereel.browser.BrowserActivity
import app.dereel.core.grant.TemporaryGrant
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.status.MethodHealth
import app.dereel.core.status.RecoveryAction
import app.dereel.core.web.LinkedAccount
import app.dereel.core.web.PlatformWeb
import app.dereel.platform.SystemIntents
import app.dereel.ui.common.BodyText
import app.dereel.ui.common.allowanceExplainer
import app.dereel.ui.common.formatClock
import app.dereel.ui.common.joinNames
import app.dereel.ui.common.methodBodyRes
import app.dereel.ui.common.nativeAppName
import app.dereel.ui.common.platformName
import app.dereel.ui.common.platformNameRes
import app.dereel.ui.common.shortVideoName
import kotlinx.coroutines.launch

sealed interface AppDialog {
    data class ChooseMethod(val platform: Platform, val openAfter: Boolean) : AppDialog
    data class AccessibilityDisclosure(val openAfter: Platform? = null) : AppDialog
    data class AppMissing(val platform: Platform) : AppDialog
    data class Allowance(val platform: Platform) : AppDialog
    data class Interval(val platform: Platform) : AppDialog
    data class SleepTime(val start: Boolean) : AppDialog
    data object ProtectionCheck : AppDialog
    data class ReplaceGrant(val platform: Platform) : AppDialog
    data class ClearBrowserData(val platform: Platform, val signOutWording: Boolean) : AppDialog
    data class RemovePlatform(val platform: Platform) : AppDialog
    data object ClearHistory : AppDialog
    data object Reset : AppDialog
}

@Stable
class DialogState {
    var current by mutableStateOf<AppDialog?>(null)
        private set

    fun show(dialog: AppDialog) {
        current = dialog
    }

    fun dismiss() {
        current = null
    }
}

val LocalDialogs = staticCompositionLocalOf<DialogState> { error("DialogState not provided") }

/** Opens platforms with the saved method and runs recovery actions (UX-03, UX-09, FR-02, FR-03). */
class PlatformOpener(
    private val context: Context,
    private val vm: MainViewModel,
    private val dialogs: DialogState,
) {
    fun open(platform: Platform, method: OpeningMethod?) {
        vm.refreshStatus() // FR-36: re-check on platform launch.
        when (SystemIntents.openPlatform(context, platform, method)) {
            SystemIntents.LaunchResult.NeedsMethodChoice -> dialogs.show(AppDialog.ChooseMethod(platform, openAfter = true))
            SystemIntents.LaunchResult.AppMissing -> dialogs.show(AppDialog.AppMissing(platform))
            SystemIntents.LaunchResult.Launched -> Unit
        }
    }

    fun openInsideDereel(platform: Platform) {
        context.startActivity(BrowserActivity.intent(context, platform))
    }

    fun recover(health: MethodHealth) {
        when (health.recovery) {
            RecoveryAction.NONE -> Unit
            RecoveryAction.CHOOSE_OPENING_METHOD -> dialogs.show(AppDialog.ChooseMethod(health.platform, openAfter = false))
            RecoveryAction.OPEN_ACCESSIBILITY_SETTINGS -> dialogs.show(AppDialog.AccessibilityDisclosure())
            RecoveryAction.OPEN_INSIDE_DEREEL -> openInsideDereel(health.platform)
            RecoveryAction.UPDATE_WEBVIEW -> SystemIntents.openWebViewUpdate(context)
            RecoveryAction.END_TEMPORARY_ACCESS -> vm.endTemporaryAccess(health.platform)
        }
    }
}

@Composable
fun AppDialogHost(state: MainState, vm: MainViewModel, dialogs: DialogState, opener: PlatformOpener) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (val dialog = dialogs.current) {
        null -> Unit
        is AppDialog.ChooseMethod -> {
            val ui = state.platforms.firstOrNull { it.platform == dialog.platform } ?: return
            OpeningMethodDialog(
                platform = dialog.platform,
                current = ui.config.openingMethod,
                appInstalled = ui.appInstalled,
                onDismiss = dialogs::dismiss,
                onChoose = { method ->
                    vm.updatePlatform(dialog.platform) { it.copy(openingMethod = method) }
                    val needsAccess = method == OpeningMethod.NATIVE_APP && state.environment?.accessibilityEnabled != true
                    when {
                        // UX-07: explain access at the point the dependent feature is chosen.
                        needsAccess -> dialogs.show(AppDialog.AccessibilityDisclosure(openAfter = dialog.platform.takeIf { dialog.openAfter }))
                        dialog.openAfter -> {
                            dialogs.dismiss()
                            opener.open(dialog.platform, method)
                        }
                        else -> dialogs.dismiss()
                    }
                },
            )
        }
        is AppDialog.AccessibilityDisclosure -> AccessibilityDisclosureDialog(
            onOpenSettings = {
                dialogs.dismiss()
                SystemIntents.openAccessibilitySettings(context)
            },
            onNotNow = {
                dialogs.dismiss()
                dialog.openAfter?.let { platform -> opener.open(platform, OpeningMethod.NATIVE_APP) }
            },
        )
        is AppDialog.AppMissing -> {
            val name = platformName(dialog.platform)
            val app = nativeAppName(dialog.platform)
            // FR-03: offer the browser only where the platform can open inside Dereel.
            val hasBrowser = PlatformWeb.hasBrowser(dialog.platform)
            AlertDialog(
                onDismissRequest = dialogs::dismiss,
                title = { Text(stringResource(R.string.app_missing_title, app)) },
                text = {
                    Text(stringResource(if (hasBrowser) R.string.app_missing_body else R.string.app_missing_body_no_browser, app, name))
                },
                confirmButton = {
                    if (hasBrowser) {
                        TextButton(onClick = {
                            dialogs.dismiss()
                            opener.openInsideDereel(dialog.platform)
                        }) { Text(stringResource(R.string.recovery_open_inside)) }
                    } else {
                        TextButton(onClick = dialogs::dismiss) { Text(stringResource(R.string.action_ok)) }
                    }
                },
                dismissButton = if (hasBrowser) {
                    { TextButton(onClick = dialogs::dismiss) { Text(stringResource(R.string.action_cancel)) } }
                } else {
                    null
                },
            )
        }
        is AppDialog.Allowance -> {
            val ui = state.platforms.firstOrNull { it.platform == dialog.platform } ?: return
            AllowanceDialog(
                platform = dialog.platform,
                current = ui.config.allowance,
                onDismiss = dialogs::dismiss,
                onSave = { allowance ->
                    vm.updatePlatform(dialog.platform) { it.copy(allowance = allowance) }
                    dialogs.dismiss()
                },
            )
        }
        is AppDialog.ReplaceGrant -> {
            val ui = state.platforms.firstOrNull { it.platform == dialog.platform } ?: return
            val now = System.currentTimeMillis()
            AlertDialog(
                onDismissRequest = dialogs::dismiss,
                title = { Text(stringResource(R.string.grant_replace_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.grant_replace_body,
                            shortVideoName(dialog.platform),
                            formatClock(context, now + ui.grantRemainingMs),
                            formatClock(context, now + TemporaryGrant.DURATION_MS),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.requestTemporaryAccess(dialog.platform)
                        dialogs.dismiss()
                    }) { Text(stringResource(R.string.grant_replace_confirm)) }
                },
                dismissButton = { TextButton(onClick = dialogs::dismiss) { Text(stringResource(R.string.grant_replace_keep)) } },
            )
        }
        is AppDialog.ClearBrowserData -> {
            val name = platformName(dialog.platform)
            val scopeInfo = PlatformWeb.dataScope(dialog.platform)
            // FR-19: name the other sign-ins this does and does not end.
            val alsoSignedOut = PlatformWeb.alsoSignedOut(dialog.platform)
            val unaffected = Platform.entries.filter {
                it != dialog.platform && PlatformWeb.hasBrowser(it) && it !in alsoSignedOut
            }
            val unaffectedNames = joinNames(unaffected.map { platformName(it) })
            val body = buildList {
                add(stringResource(R.string.clear_browser_scope, name, scopeInfo.cookieDomains.joinToString(", ")))
                scopeInfo.linkedAccounts.forEach {
                    add(
                        stringResource(
                            when (it) {
                                LinkedAccount.FACEBOOK -> R.string.clear_browser_linked_facebook
                                LinkedAccount.GOOGLE -> R.string.clear_browser_linked_google
                            },
                        ),
                    )
                }
                alsoSignedOut.forEach { add(stringResource(R.string.clear_browser_also_signs_out, platformName(it))) }
                add(stringResource(R.string.clear_browser_unaffected, unaffectedNames, name))
            }.joinToString("\n\n")
            ConfirmDialog(
                title = stringResource(if (dialog.signOutWording) R.string.sign_out_title else R.string.clear_browser_title, name),
                body = body,
                confirm = stringResource(R.string.clear_browser_confirm),
                onDismiss = dialogs::dismiss,
                onConfirm = {
                    dialogs.dismiss()
                    scope.launch {
                        val result = vm.clearBrowserData(dialog.platform)
                        val message = if (result.remainingCookieDomains.isEmpty()) {
                            context.getString(R.string.clear_browser_done, context.getString(platformNameRes(dialog.platform)))
                        } else {
                            context.getString(R.string.clear_browser_partial, context.getString(platformNameRes(dialog.platform)))
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
        is AppDialog.RemovePlatform -> {
            val name = platformName(dialog.platform)
            ConfirmDialog(
                title = stringResource(R.string.remove_platform_title, name),
                body = stringResource(R.string.remove_platform_body, shortVideoName(dialog.platform), name),
                confirm = stringResource(R.string.remove_platform_confirm),
                onDismiss = dialogs::dismiss,
                onConfirm = {
                    vm.removePlatform(dialog.platform)
                    dialogs.dismiss()
                },
            )
        }
        is AppDialog.Interval -> {
            val ui = state.platforms.firstOrNull { it.platform == dialog.platform } ?: return
            IntervalDialog(
                platform = dialog.platform,
                current = ui.config.interval,
                onDismiss = dialogs::dismiss,
                onSave = { interval ->
                    vm.updatePlatform(dialog.platform) { it.copy(interval = interval) }
                    dialogs.dismiss()
                },
            )
        }
        is AppDialog.SleepTime -> SleepTimeDialog(
            start = dialog.start,
            schedule = state.preferences.sleep,
            onDismiss = dialogs::dismiss,
            onSave = { minute ->
                vm.updateSleep { if (dialog.start) it.copy(startMinute = minute) else it.copy(endMinute = minute) }
                dialogs.dismiss()
            },
        )
        AppDialog.ProtectionCheck -> ProtectionCheckDialog(
            vm = vm,
            onDismiss = dialogs::dismiss,
            onFixAccessibility = { dialogs.show(AppDialog.AccessibilityDisclosure()) },
        )
        AppDialog.ClearHistory -> ConfirmDialog(
            title = stringResource(R.string.clear_history_title),
            body = stringResource(R.string.clear_history_body),
            confirm = stringResource(R.string.clear_history_confirm),
            onDismiss = dialogs::dismiss,
            onConfirm = {
                vm.clearHistory()
                dialogs.dismiss()
            },
        )
        AppDialog.Reset -> ConfirmDialog(
            title = stringResource(R.string.reset_title),
            body = stringResource(R.string.reset_body),
            confirm = stringResource(R.string.reset_confirm),
            onDismiss = dialogs::dismiss,
            onConfirm = {
                dialogs.dismiss()
                vm.resetEverything { Toast.makeText(context, R.string.reset_done, Toast.LENGTH_LONG).show() }
            },
        )
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** SEC-02: explains what Accessibility access can expose, what Dereel uses and keeps, before enabling. */
@Composable
fun AccessibilityDisclosureDialog(onOpenSettings: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.disclosure_intro))
                DisclosureItem(R.string.disclosure_can_see_title, R.string.disclosure_can_see_body)
                DisclosureItem(R.string.disclosure_uses_title, R.string.disclosure_uses_body)
                DisclosureItem(R.string.disclosure_keeps_title, R.string.disclosure_keeps_body)
                DisclosureItem(R.string.disclosure_does_title, R.string.disclosure_does_body)
                BodyText(stringResource(R.string.disclosure_turn_off))
                BodyText(stringResource(R.string.disclosure_restricted_hint))
            }
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.disclosure_open_settings)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.action_not_now)) } },
    )
}

@Composable
private fun DisclosureItem(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}

/** UX-04: the two opening methods, each with a short explanation of its protection. */
@Composable
fun OpeningMethodDialog(
    platform: Platform,
    current: OpeningMethod?,
    appInstalled: Boolean,
    onDismiss: () -> Unit,
    onChoose: (OpeningMethod) -> Unit,
) {
    val offered = Capabilities.methods(platform)
    var choice by rememberSaveable {
        mutableStateOf(current ?: offered.firstOrNull { it != OpeningMethod.NATIVE_APP || appInstalled } ?: offered.first())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.method_dialog_title, platformName(platform))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OpeningMethodOptions(platform, choice, appInstalled) { choice = it }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(choice) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun OpeningMethodOptions(platform: Platform, selected: OpeningMethod?, appInstalled: Boolean, onSelect: (OpeningMethod) -> Unit) {
    val name = nativeAppName(platform)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Only the methods Dereel has an adapter for are offered.
        Capabilities.methods(platform).forEach { method ->
            val body = methodBodyRes(platform, method)?.let { stringResource(it) }.orEmpty()
            when (method) {
                OpeningMethod.NATIVE_APP -> MethodOption(
                    title = stringResource(R.string.method_native_title, name),
                    body = if (appInstalled) body else stringResource(R.string.method_native_missing, name),
                    selected = selected == method,
                    enabled = appInstalled,
                    onClick = { onSelect(method) },
                )
                OpeningMethod.IN_DEREEL -> MethodOption(
                    title = stringResource(R.string.method_browser_title),
                    body = body,
                    selected = selected == method,
                    enabled = true,
                    onClick = { onSelect(method) },
                )
            }
        }
    }
}

/** A platform with one opening method has nothing to choose. Say how it opens and why (UX-04). */
@Composable
fun SingleMethodInfo(platform: Platform, method: OpeningMethod, appInstalled: Boolean) {
    val name = platformName(platform)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                when (method) {
                    OpeningMethod.NATIVE_APP -> R.string.method_only_native_title
                    OpeningMethod.IN_DEREEL -> R.string.method_only_browser_title
                },
                name,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        methodBodyRes(platform, method)?.let { BodyText(stringResource(it)) }
        if (method == OpeningMethod.NATIVE_APP && !appInstalled) {
            Text(
                stringResource(R.string.method_native_missing, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MethodOption(title: String, body: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            BodyText(body)
        }
    }
}

@Composable
fun AllowanceDialog(platform: Platform, current: AllowanceSetting, onDismiss: () -> Unit, onSave: (AllowanceSetting) -> Unit) {
    var draft by remember { mutableStateOf(current) }
    var valid by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.allowance_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BodyText(allowanceExplainer(platform))
                AllowancePicker(draft, onChange = { setting, isValid ->
                    draft = setting
                    valid = isValid
                })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** FR-24: Off, 5, 15, 30 minutes or a custom 1 to 180 minutes. Turning it off keeps the last limit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllowancePicker(current: AllowanceSetting, onChange: (AllowanceSetting, Boolean) -> Unit) {
    val suggested = AllowanceSetting.SUGGESTED_MINUTES
    var custom by rememberSaveable {
        mutableStateOf(current.minutes?.takeIf { current.enabled && it !in suggested }?.toString() ?: "")
    }
    var customMode by rememberSaveable { mutableStateOf(current.enabled && current.minutes !in suggested) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !current.enabled,
                onClick = {
                    customMode = false
                    onChange(current.disable(), true)
                },
                label = { Text(stringResource(R.string.allowance_off)) },
            )
            suggested.forEach { minutes ->
                FilterChip(
                    selected = current.enabled && !customMode && current.minutes == minutes,
                    onClick = {
                        customMode = false
                        onChange(current.enable(minutes), true)
                    },
                    label = { Text(stringResource(R.string.duration_minutes, minutes.toLong())) },
                )
            }
            FilterChip(
                selected = customMode,
                onClick = {
                    customMode = true
                    val parsed = AllowanceSetting.parseCustomMinutes(custom)
                    onChange(if (parsed != null) current.enable(parsed) else current, parsed != null)
                },
                label = { Text(stringResource(R.string.allowance_custom)) },
            )
        }
        if (customMode) {
            val parsed = AllowanceSetting.parseCustomMinutes(custom)
            val errorText = stringResource(R.string.allowance_custom_error)
            OutlinedTextField(
                value = custom,
                onValueChange = { text ->
                    custom = text.filter(Char::isDigit).take(3)
                    val value = AllowanceSetting.parseCustomMinutes(custom)
                    onChange(if (value != null) current.enable(value) else current, value != null)
                },
                label = { Text(stringResource(R.string.allowance_custom_label)) },
                supportingText = { Text(if (parsed == null && custom.isNotEmpty()) errorText else stringResource(R.string.allowance_custom_hint)) },
                isError = parsed == null && custom.isNotEmpty(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().semantics { if (parsed == null && custom.isNotEmpty()) error(errorText) },
            )
        }
    }
}

/** S-02: watch for a while, then a break. The chosen lengths are kept when the timer is turned off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntervalDialog(platform: Platform, current: IntervalSetting, onDismiss: () -> Unit, onSave: (IntervalSetting) -> Unit) {
    var draft by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.interval_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BodyText(stringResource(R.string.interval_explainer, shortVideoName(platform)))
                Text(stringResource(R.string.interval_watch_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !draft.enabled,
                        onClick = { draft = draft.copy(enabled = false) },
                        label = { Text(stringResource(R.string.allowance_off)) },
                    )
                    IntervalSetting.WATCH_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = draft.enabled && draft.watchMinutes == minutes,
                            onClick = { draft = draft.copy(enabled = true, watchMinutes = minutes) },
                            label = { Text(formatDuration(minutes * IntervalSetting.MINUTE_MS)) },
                        )
                    }
                }
                if (draft.enabled) {
                    Text(stringResource(R.string.interval_break_label), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntervalSetting.BREAK_CHOICES.forEach { minutes ->
                            FilterChip(
                                selected = draft.breakMinutes == minutes,
                                onClick = { draft = draft.copy(breakMinutes = minutes) },
                                label = { Text(formatDuration(minutes * IntervalSetting.MINUTE_MS)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** S-02: one end of the sleep schedule, typed in the phone's own 12- or 24-hour format. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimeDialog(start: Boolean, schedule: SleepSchedule, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val context = LocalContext.current
    val initial = if (start) schedule.startMinute else schedule.endMinute
    val picker = rememberTimePickerState(
        initialHour = initial / 60,
        initialMinute = initial % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (start) R.string.settings_sleep_start else R.string.settings_sleep_end)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                TimeInput(state = picker)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(picker.hour * 60 + picker.minute) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** S-02: runs the live checks when it opens and lists each result in words, with one way to fix access. */
@Composable
fun ProtectionCheckDialog(vm: MainViewModel, onDismiss: () -> Unit, onFixAccessibility: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<CheckItem>?>(null) }
    LaunchedEffect(Unit) { items = vm.runProtectionCheck(context.applicationContext) }
    val result = items
    val accessKinds = setOf(CheckKind.ACCESSIBILITY_ON, CheckKind.SERVICE_RESPONDING, CheckKind.SERVICE_SCOPE)
    val needsAccess = result?.any { it.result == CheckResult.FAIL && it.kind in accessKinds } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    result == null -> BodyText(stringResource(R.string.check_running))
                    result.isEmpty() -> BodyText(stringResource(R.string.check_nothing))
                    else -> {
                        Text(
                            stringResource(if (ProtectionCheck.allPassed(result)) R.string.check_all_ok else R.string.check_some_fail),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        result.forEach { CheckRow(it) }
                        BodyText(stringResource(R.string.check_note))
                    }
                }
            }
        },
        confirmButton = {
            if (needsAccess) {
                TextButton(onClick = onFixAccessibility) { Text(stringResource(R.string.recovery_fix)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = if (needsAccess) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
        } else {
            null
        },
    )
}

@Composable
private fun CheckRow(item: CheckItem) {
    val scheme = MaterialTheme.colorScheme
    // The result is carried by the icon's spoken label as well as its colour (NFR-03).
    val icon = when (item.result) {
        CheckResult.PASS -> Icons.Default.CheckCircle
        CheckResult.FAIL -> Icons.Default.Warning
        CheckResult.INFO -> Icons.Default.Info
    }
    val tint = when (item.result) {
        CheckResult.PASS -> scheme.primary
        CheckResult.FAIL -> scheme.error
        CheckResult.INFO -> scheme.onSurfaceVariant
    }
    val spoken = stringResource(
        when (item.result) {
            CheckResult.PASS -> R.string.check_result_pass
            CheckResult.FAIL -> R.string.check_result_fail
            CheckResult.INFO -> R.string.check_result_info
        },
    )
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = spoken, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(checkText(item), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun checkText(item: CheckItem): String {
    val app = item.platform?.let { nativeAppName(it) }.orEmpty()
    val ok = item.result == CheckResult.PASS
    return when (item.kind) {
        CheckKind.ACCESSIBILITY_ON -> stringResource(if (ok) R.string.check_accessibility_on else R.string.check_accessibility_off)
        CheckKind.SERVICE_RESPONDING -> stringResource(if (ok) R.string.check_service_ok else R.string.check_service_fail)
        CheckKind.SERVICE_SCOPE -> stringResource(if (ok) R.string.check_scope_ok else R.string.check_scope_fail)
        CheckKind.APP -> stringResource(
            when (item.detail) {
                CheckDetail.APP_RECOGNIZED -> R.string.check_app_recognized
                CheckDetail.APP_NOT_RECOGNIZED -> R.string.check_app_not_recognized
                CheckDetail.APP_NOT_INSTALLED -> R.string.check_app_missing
                else -> R.string.check_app_not_seen
            },
            app,
        )
        CheckKind.BROWSER -> stringResource(
            when (item.detail) {
                CheckDetail.WEBVIEW_MISSING -> R.string.check_browser_missing
                CheckDetail.WEBVIEW_OUTDATED -> R.string.check_browser_outdated
                else -> R.string.check_browser_ok
            },
        )
        CheckKind.ALERTS -> stringResource(
            when (item.detail) {
                CheckDetail.ALERTS_OFF -> R.string.check_alerts_off
                CheckDetail.ALERTS_BLOCKED -> R.string.check_alerts_blocked
                else -> R.string.check_alerts_ok
            },
        )
    }
}
