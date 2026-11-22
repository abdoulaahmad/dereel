package app.dereel.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dereel.R
import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.ui.AllowancePicker
import app.dereel.ui.AppDialog
import app.dereel.ui.LocalDialogs
import app.dereel.ui.MainState
import app.dereel.ui.MainViewModel
import app.dereel.ui.OpeningMethodOptions
import app.dereel.ui.PlatformDraft
import app.dereel.ui.SingleMethodInfo
import app.dereel.ui.common.BodyText
import app.dereel.ui.common.StatusChip
import app.dereel.ui.common.SwitchRow
import app.dereel.ui.common.allowanceExplainer
import app.dereel.ui.common.PlatformAvatar
import app.dereel.ui.common.platformName
import app.dereel.ui.common.ruleBlockDescRes
import app.dereel.ui.common.shortVideoName
import app.dereel.core.status.ProtectionState

/**
 * UX-06: three stages. Select platforms, choose initial rules, activate protection. No account
 * and no social sign-in (FR-01). Setup can finish with one platform and with access declined.
 */
@Composable
fun OnboardingScreen(state: MainState, vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val drafts = remember { mutableStateMapOf<Platform, PlatformDraft>() }
    val selected = Platform.entries.filter { it in drafts }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(R.string.onboarding_step, step + 1, 3),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> ChoosePlatforms(drafts)
                1 -> ChooseRules(selected, drafts)
                else -> ActivateProtection(state, selected, drafts)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_back)) }
            }
            Button(
                onClick = { if (step < 2) step++ else vm.completeOnboarding(drafts.toMap()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(if (step < 2) R.string.action_continue else R.string.onboarding_finish))
            }
        }
    }
}

@Composable
private fun ChoosePlatforms(drafts: MutableMap<Platform, PlatformDraft>) {
    Title(stringResource(R.string.onboarding_platforms_title))
    BodyText(stringResource(R.string.onboarding_platforms_body))
    Platform.entries.forEach { platform ->
        val checked = platform in drafts
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .toggleable(checked, role = Role.Checkbox) { on -> if (on) drafts[platform] = PlatformDraft() else drafts.remove(platform) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformAvatar(platform)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(platformName(platform), style = MaterialTheme.typography.titleMedium)
                    BodyText(stringResource(R.string.onboarding_platform_blocks, shortVideoName(platform)))
                    // Say up front when protection works one way only (UX-04).
                    Capabilities.onlyMethod(platform)?.let { only ->
                        BodyText(
                            stringResource(
                                if (only == OpeningMethod.NATIVE_APP) R.string.onboarding_platform_only_native
                                else R.string.onboarding_platform_only_browser,
                                platformName(platform),
                            ),
                        )
                    }
                }
                Checkbox(checked = checked, onCheckedChange = null)
            }
        }
    }
    BodyText(stringResource(R.string.onboarding_no_account))
}

@Composable
private fun ChooseRules(selected: List<Platform>, drafts: MutableMap<Platform, PlatformDraft>) {
    Title(stringResource(R.string.onboarding_rules_title))
    BodyText(stringResource(R.string.onboarding_rules_body))
    selected.forEach { platform ->
        val draft = drafts.getValue(platform)
        val videos = shortVideoName(platform)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlatformAvatar(platform, size = 32.dp)
                    Text(platformName(platform), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                }
                SwitchRow(
                    title = stringResource(R.string.rule_block, videos),
                    description = stringResource(ruleBlockDescRes(platform)),
                    checked = draft.blockShortVideos,
                    onCheckedChange = { drafts[platform] = draft.copy(blockShortVideos = it) },
                )
                if (Capabilities.sharedItemAvailable(platform)) {
                    SwitchRow(
                        title = stringResource(R.string.rule_shared),
                        description = stringResource(R.string.rule_shared_desc),
                        checked = draft.allowSharedVideos,
                        enabled = draft.blockShortVideos,
                        onCheckedChange = { drafts[platform] = draft.copy(allowSharedVideos = it) },
                    )
                }
                Text(stringResource(R.string.allowance_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                BodyText(allowanceExplainer(platform))
                AllowancePicker(draft.allowance) { setting, valid ->
                    if (valid) drafts[platform] = drafts.getValue(platform).copy(allowance = setting)
                }
            }
        }
    }
}

@Composable
private fun ActivateProtection(state: MainState, selected: List<Platform>, drafts: MutableMap<Platform, PlatformDraft>) {
    val dialogs = LocalDialogs.current
    val env = state.environment
    Title(stringResource(R.string.onboarding_activate_title))
    BodyText(stringResource(R.string.onboarding_activate_body))
    selected.forEach { platform ->
        val draft = drafts.getValue(platform)
        val installed = env?.installedApps?.contains(platform) == true
        val onlyMethod = Capabilities.onlyMethod(platform)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onlyMethod != null) {
                    Text(
                        platformName(platform),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    SingleMethodInfo(platform, onlyMethod, installed)
                } else {
                    Text(
                        stringResource(R.string.method_dialog_title, platformName(platform)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    OpeningMethodOptions(platform, draft.openingMethod, installed) { drafts[platform] = draft.copy(openingMethod = it) }
                }
            }
        }
    }

    // A platform with a single method uses it without being asked, so it counts here too.
    if (selected.any { (drafts[it]?.openingMethod ?: Capabilities.onlyMethod(it)) == OpeningMethod.NATIVE_APP }) {
        val enabled = env?.accessibilityEnabled == true
        val connected = env?.accessibilityConnected == true
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.disclosure_title), style = MaterialTheme.typography.titleMedium)
                StatusChip(
                    when {
                        enabled && connected -> ProtectionState.ACTIVE
                        enabled -> ProtectionState.NEEDS_ATTENTION
                        else -> ProtectionState.NEEDS_SETUP
                    },
                )
                BodyText(stringResource(R.string.onboarding_accessibility_body))
                if (!(enabled && connected)) {
                    FilledTonalButton(onClick = { dialogs.show(AppDialog.AccessibilityDisclosure()) }) {
                        Text(stringResource(R.string.recovery_set_up))
                    }
                }
                BodyText(stringResource(R.string.onboarding_accessibility_later))
            }
        }
    }
    Spacer(Modifier.heightIn(min = 8.dp))
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}
