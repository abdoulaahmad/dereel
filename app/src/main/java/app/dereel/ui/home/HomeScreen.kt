package app.dereel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dereel.R
import app.dereel.core.model.Platform
import app.dereel.core.pacing.IntervalStatus
import app.dereel.core.status.MethodHealth
import app.dereel.core.status.OverallStatus
import app.dereel.core.status.ProtectionState
import app.dereel.ui.AppDialog
import app.dereel.ui.LocalDialogs
import app.dereel.ui.MainState
import app.dereel.ui.MainViewModel
import app.dereel.ui.PlatformOpener
import app.dereel.ui.PlatformUi
import app.dereel.ui.common.BodyText
import app.dereel.ui.common.PlatformAvatar
import app.dereel.ui.common.ScreenTitle
import app.dereel.ui.common.StatusChip
import app.dereel.ui.common.formatClock
import app.dereel.ui.common.formatDuration
import app.dereel.ui.common.methodLabel
import app.dereel.ui.common.platformName
import app.dereel.ui.common.reasonText
import app.dereel.ui.common.recoveryLabel
import app.dereel.ui.common.shortVideoName

/**
 * Home (UX-01, UX-03): one status card for everything, then one compact card per platform with
 * a single primary Open action. Platform settings sit behind the gear, and apps that are not
 * added yet are offered as chips instead of a column of buttons.
 */
@Composable
fun HomeScreen(
    state: MainState,
    vm: MainViewModel,
    opener: PlatformOpener,
    onPlatformSettings: (Platform) -> Unit,
) {
    val dialogs = LocalDialogs.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.app_name))
        StatusHero(state, onCheck = { dialogs.show(AppDialog.ProtectionCheck) })
        state.sleepUntilWallMs?.let { SleepBanner(it) }
        state.selected.filter { it.grantRemainingMs > 0 }.forEach { TemporaryAccessBanner(it, vm) }
        state.selected.forEach { PlatformCard(it, opener, onPlatformSettings) }
        AddPlatforms(state, vm, onPlatformSettings)
    }
}

private class Hero(val title: String, val body: String?, val container: Color, val content: Color, val icon: ImageVector)

/** UX-08: the overall active message appears only when every shown method is operational. */
@Composable
private fun StatusHero(state: MainState, onCheck: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hero = if (state.environment == null) {
        Hero(stringResource(R.string.overall_checking), null, scheme.surfaceContainerHigh, scheme.onSurface, Icons.Default.Info)
    } else {
        when (state.overall) {
            OverallStatus.ALL_ACTIVE -> Hero(
                stringResource(R.string.overall_active_title),
                stringResource(R.string.overall_active_body),
                scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Default.CheckCircle,
            )
            OverallStatus.NEEDS_ACTION -> Hero(
                stringResource(R.string.overall_needs_action_title),
                stringResource(R.string.overall_needs_action_body),
                scheme.errorContainer, scheme.onErrorContainer, Icons.Default.Warning,
            )
            OverallStatus.PARTLY_OFF_OR_PAUSED -> Hero(
                stringResource(R.string.overall_partly_title),
                stringResource(R.string.overall_partly_body),
                scheme.surfaceContainerHigh, scheme.onSurface, Icons.Default.Info,
            )
            OverallStatus.NOTHING_SELECTED -> Hero(
                stringResource(R.string.overall_nothing_title),
                stringResource(R.string.overall_nothing_body),
                scheme.surfaceContainerHigh, scheme.onSurface, Icons.Default.Info,
            )
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = hero.container, contentColor = hero.content)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(hero.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hero.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    if (hero.body != null) Text(hero.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.selected.isNotEmpty()) {
                TextButton(
                    onClick = onCheck,
                    colors = ButtonDefaults.textButtonColors(contentColor = hero.content),
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.check_title)) }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SleepBanner(untilWallMs: Long) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.home_sleep_active, formatClock(context, untilWallMs)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TemporaryAccessBanner(ui: PlatformUi, vm: MainViewModel) {
    val context = LocalContext.current
    val until = formatClock(context, System.currentTimeMillis() + ui.grantRemainingMs)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_grant_active, shortVideoName(ui.platform), platformName(ui.platform), until),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.endTemporaryAccess(ui.platform) }) { Text(stringResource(R.string.action_end_now)) }
        }
    }
}

@Composable
private fun PlatformCard(ui: PlatformUi, opener: PlatformOpener, onPlatformSettings: (Platform) -> Unit) {
    val name = platformName(ui.platform)
    val openLabel = stringResource(R.string.home_open, name)
    val settingsLabel = stringResource(R.string.home_settings_for, name)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformAvatar(ui.platform)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    BodyText(methodLabel(ui.config.openingMethod, ui.platform))
                }
                IconButton(onClick = { onPlatformSettings(ui.platform) }) {
                    Icon(Icons.Default.Settings, contentDescription = settingsLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // UX-03: the one primary action for a platform.
                Button(
                    onClick = { opener.open(ui.platform, ui.config.openingMethod) },
                    modifier = Modifier.semantics { contentDescription = openLabel },
                ) { Text(stringResource(R.string.action_open)) }
            }
            val allActive = ui.health.isNotEmpty() && ui.health.all { it.state == ProtectionState.ACTIVE }
            // With no allowance or interval timer there is only one number to show.
            val plainUsage = ui.config.allowance.limitMs == null && ui.interval == IntervalStatus.Off
            when {
                ui.health.isEmpty() -> BodyText(stringResource(R.string.overall_checking))
                // Nothing to explain or fix: one line says it all. Details stay on the platform screen.
                allActive -> Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(ProtectionState.ACTIVE)
                    if (ui.health.size > 1) BodyText(stringResource(R.string.home_active_both), Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    if (plainUsage) BodyText(stringResource(R.string.home_today_short, formatDuration(ui.usedTodayMs)))
                }
                else -> ui.health.forEach { health ->
                    HealthRow(health, showMethod = ui.health.size > 1, timeLimited = ui.hasTimeLimit, onRecover = { opener.recover(health) })
                }
            }
            if (!(allActive && plainUsage)) AllowanceSummary(ui)
        }
    }
}

/** One method's status: a chip with icon and words, the specific reason, and one way to fix it (UX-09). */
@Composable
fun HealthRow(health: MethodHealth, showMethod: Boolean, timeLimited: Boolean = false, onRecover: () -> Unit) {
    val calm = health.state == ProtectionState.ACTIVE
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(health.state)
            if (showMethod) BodyText(methodLabel(health.method, health.platform))
        }
        Text(
            reasonText(health, timeLimited),
            style = MaterialTheme.typography.bodyMedium,
            color = if (calm) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        recoveryLabel(health)?.let { label -> FilledTonalButton(onClick = onRecover) { Text(label) } }
    }
}

/** Today's time for a platform: the allowance bar when one is set, and where the interval timer stands. */
@Composable
fun AllowanceSummary(ui: PlatformUi, showPlainTotal: Boolean = true) {
    val context = LocalContext.current
    val videos = shortVideoName(ui.platform)
    val limit = ui.config.allowance.limitMs
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (limit == null) {
            if (showPlainTotal) BodyText(stringResource(R.string.usage_today, formatDuration(ui.usedTodayMs), videos))
        } else {
            val used = ui.usedTodayMs.coerceAtMost(limit)
            val usedUp = ui.usedTodayMs >= limit
            Text(
                if (usedUp) stringResource(R.string.allowance_used_up, videos)
                else stringResource(R.string.allowance_progress, formatDuration(used), formatDuration(limit)),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = if (usedUp) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
            )
        }
        if (ui.config.blockShortVideos) {
            when (val interval = ui.interval) {
                is IntervalStatus.OnBreak -> Text(
                    stringResource(R.string.interval_on_break, formatClock(context, System.currentTimeMillis() + interval.remainingMs)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is IntervalStatus.Watching -> BodyText(stringResource(R.string.interval_watching, formatDuration(interval.remainingMs)))
                IntervalStatus.Off -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPlatforms(state: MainState, vm: MainViewModel, onPlatformSettings: (Platform) -> Unit) {
    val missing = state.platforms.filterNot { it.config.selected }
    if (missing.isEmpty()) return
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (state.selected.isEmpty()) R.string.home_add_first else R.string.home_add_more),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                missing.forEach { ui ->
                    val name = platformName(ui.platform)
                    val label = stringResource(R.string.home_add_platform, name)
                    AssistChip(
                        onClick = {
                            vm.addPlatform(ui.platform)
                            onPlatformSettings(ui.platform)
                        },
                        label = { Text(name) },
                        leadingIcon = { PlatformAvatar(ui.platform, size = 24.dp) },
                        trailingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}
