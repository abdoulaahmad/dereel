package app.dereel.ui.platform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.dereel.R
import app.dereel.core.model.BrowserControl
import app.dereel.core.model.Capabilities
import app.dereel.core.model.Platform
import app.dereel.core.web.PlatformWeb
import app.dereel.ui.AppDialog
import app.dereel.ui.LocalDialogs
import app.dereel.ui.MainState
import app.dereel.ui.MainViewModel
import app.dereel.ui.PlatformOpener
import app.dereel.ui.SingleMethodInfo
import app.dereel.ui.common.BodyText
import app.dereel.ui.common.ClickRow
import app.dereel.ui.common.PlatformAvatar
import app.dereel.ui.common.RowDivider
import app.dereel.ui.common.SectionCard
import app.dereel.ui.common.SwitchRow
import app.dereel.ui.common.allowanceExplainer
import app.dereel.ui.common.formatClock
import app.dereel.ui.common.formatDuration
import app.dereel.ui.common.methodLabel
import app.dereel.ui.common.platformName
import app.dereel.ui.common.ruleBlockDescRes
import app.dereel.ui.common.shortVideoName
import app.dereel.ui.home.AllowanceSummary
import app.dereel.ui.home.HealthRow

/**
 * Platform controls, grouped so the basics come first (UX-05): status and Open, blocking, time
 * limits, how it opens, then browser-only options under More controls and account actions last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformScreen(state: MainState, platform: Platform, vm: MainViewModel, opener: PlatformOpener, onBack: () -> Unit) {
    val ui = state.platform(platform)
    val config = ui.config
    val dialogs = LocalDialogs.current
    val context = LocalContext.current
    val name = platformName(platform)
    val videos = shortVideoName(platform)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(name) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (!config.selected) {
                BodyText(stringResource(R.string.platform_not_added, name))
                Button(onClick = { vm.addPlatform(platform) }, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.home_add_platform, name))
                }
                return@Column
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformAvatar(platform)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            BodyText(methodLabel(config.openingMethod, platform))
                        }
                    }
                    if (ui.health.isEmpty()) BodyText(stringResource(R.string.overall_checking))
                    ui.health.forEach { health ->
                        HealthRow(health, showMethod = ui.health.size > 1, timeLimited = ui.hasTimeLimit, onRecover = { opener.recover(health) })
                    }
                    Button(onClick = { opener.open(platform, config.openingMethod) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_open, name))
                    }
                }
            }

            SectionCard(stringResource(R.string.platform_rules)) {
                SwitchRow(
                    title = stringResource(R.string.rule_block, videos),
                    description = stringResource(ruleBlockDescRes(platform)),
                    checked = config.blockShortVideos,
                    onCheckedChange = { on -> vm.updatePlatform(platform) { it.copy(blockShortVideos = on) } },
                )
                if (Capabilities.sharedItemAvailable(platform)) {
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.rule_shared),
                        description = stringResource(R.string.rule_shared_desc),
                        checked = config.allowSharedVideos,
                        enabled = config.blockShortVideos,
                        onCheckedChange = { on -> vm.updatePlatform(platform) { it.copy(allowSharedVideos = on) } },
                    )
                }
            }

            SectionCard(stringResource(R.string.platform_time)) {
                ClickRow(
                    title = stringResource(R.string.allowance_title),
                    value = config.allowance.minutes?.takeIf { config.allowance.enabled }?.let {
                        stringResource(R.string.allowance_value, formatDuration(it * 60_000L))
                    } ?: stringResource(R.string.allowance_off),
                    description = allowanceExplainer(platform),
                    onClick = { dialogs.show(AppDialog.Allowance(platform)) },
                )
                RowDivider()
                ClickRow(
                    title = stringResource(R.string.interval_title),
                    value = if (config.interval.enabled) {
                        stringResource(
                            R.string.interval_value,
                            formatDuration(config.interval.watchMs),
                            formatDuration(config.interval.breakMs),
                        )
                    } else {
                        stringResource(R.string.allowance_off)
                    },
                    description = stringResource(R.string.interval_explainer, videos),
                    onClick = { dialogs.show(AppDialog.Interval(platform)) },
                )
                RowDivider()
                Column(Modifier.padding(vertical = 12.dp)) { AllowanceSummary(ui) }
            }

            SectionCard(stringResource(R.string.platform_opening)) {
                val onlyMethod = Capabilities.onlyMethod(platform)
                if (onlyMethod != null) {
                    // The status at the top of this screen already says when the app is missing.
                    Column(Modifier.padding(vertical = 10.dp)) { SingleMethodInfo(platform, onlyMethod, appInstalled = true) }
                } else {
                    ClickRow(
                        title = stringResource(R.string.platform_open_with),
                        value = methodLabel(config.openingMethod, platform),
                        onClick = { dialogs.show(AppDialog.ChooseMethod(platform, openAfter = false)) },
                    )
                }
            }

            // UX-10, FR-29: temporary access appears only when the user has turned it on.
            if (state.preferences.temporaryAccessEnabled && config.blockShortVideos) {
                SectionCard(stringResource(R.string.grant_section)) {
                    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.grantRemainingMs > 0) {
                            Text(
                                stringResource(R.string.grant_active_until, videos, formatClock(context, System.currentTimeMillis() + ui.grantRemainingMs)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.endTemporaryAccess(platform) }) { Text(stringResource(R.string.action_end_now)) }
                                TextButton(onClick = { dialogs.show(AppDialog.ReplaceGrant(platform)) }) { Text(stringResource(R.string.grant_replace_confirm)) }
                            }
                        } else {
                            OutlinedButton(onClick = { vm.requestTemporaryAccess(platform) }) {
                                Text(stringResource(R.string.grant_request, videos))
                            }
                        }
                        BodyText(stringResource(R.string.grant_counts))
                    }
                }
            }

            // Browser-only sections appear only where there is something to show (UX-05).
            if (BrowserControl.forPlatform(platform).isNotEmpty()) {
                SectionCard(null, Modifier.padding(top = 20.dp)) {
                    MoreControls(platform, config.browserControls) { control, on ->
                        vm.updatePlatform(platform) { c -> c.copy(browserControls = if (on) c.browserControls + control else c.browserControls - control) }
                    }
                }
            }

            if (PlatformWeb.hasBrowser(platform)) {
                SectionCard(stringResource(R.string.platform_account)) {
                    ClickRow(
                        title = stringResource(R.string.sign_out_title, name),
                        value = null,
                        description = stringResource(R.string.sign_out_desc, name),
                        onClick = { dialogs.show(AppDialog.ClearBrowserData(platform, signOutWording = true)) },
                    )
                }
            }
            TextButton(
                onClick = { dialogs.show(AppDialog.RemovePlatform(platform)) },
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                Text(stringResource(R.string.remove_platform_title, name), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MoreControls(platform: Platform, enabled: Set<BrowserControl>, onToggle: (BrowserControl, Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandedLabel = stringResource(if (expanded) R.string.more_controls_expanded else R.string.more_controls_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = expandedLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.more_controls),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
    }
    if (!expanded) return
    BodyText(stringResource(R.string.more_controls_scope, platformName(platform)), Modifier.padding(bottom = 4.dp))
    BrowserControl.forPlatform(platform).forEach { control ->
        RowDivider()
        SwitchRow(
            title = stringResource(controlTitle(control)),
            description = stringResource(controlDescription(control)),
            checked = control in enabled,
            onCheckedChange = { onToggle(control, it) },
        )
    }
}

private fun controlTitle(control: BrowserControl): Int = when (control) {
    BrowserControl.INSTAGRAM_HIDE_EXPLORE -> R.string.control_ig_explore
    BrowserControl.INSTAGRAM_HIDE_SUGGESTED -> R.string.control_ig_suggested
    BrowserControl.INSTAGRAM_FILTER_SPONSORED -> R.string.control_ig_sponsored
    BrowserControl.YOUTUBE_HIDE_HOME_FEED -> R.string.control_yt_home
    BrowserControl.YOUTUBE_HIDE_WATCH_NEXT -> R.string.control_yt_watch_next
    BrowserControl.YOUTUBE_FILTER_PROMOTED -> R.string.control_yt_promoted
}

private fun controlDescription(control: BrowserControl): Int = when (control) {
    BrowserControl.INSTAGRAM_HIDE_EXPLORE -> R.string.control_ig_explore_desc
    BrowserControl.INSTAGRAM_HIDE_SUGGESTED -> R.string.control_ig_suggested_desc
    BrowserControl.INSTAGRAM_FILTER_SPONSORED -> R.string.control_ig_sponsored_desc
    BrowserControl.YOUTUBE_HIDE_HOME_FEED -> R.string.control_yt_home_desc
    BrowserControl.YOUTUBE_HIDE_WATCH_NEXT -> R.string.control_yt_watch_next_desc
    BrowserControl.YOUTUBE_FILTER_PROMOTED -> R.string.control_yt_promoted_desc
}
