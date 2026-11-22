package app.dereel.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Home, the dashboard (UX-01, UX-03). From the top: the one status for everything, today's and
 * the week's short-video time, any time rule that is in force right now, then one compact card
 * per app with a single primary Open action. Apps that are not added yet are offered as chips.
 */
@Composable
fun HomeScreen(
    state: MainState,
    vm: MainViewModel,
    opener: PlatformOpener,
    onPlatformSettings: (Platform) -> Unit,
    onActivity: () -> Unit,
) {
    val dialogs = LocalDialogs.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            ScreenTitle(stringResource(R.string.app_name))
            BodyText(todayLabel())
        }
        StatusHero(state, onCheck = { dialogs.show(AppDialog.ProtectionCheck) })
        if (state.selected.isNotEmpty()) StatsRow(state, onActivity)
        state.sleepUntilWallMs?.let { SleepBanner(it) }
        state.selected.filter { it.grantRemainingMs > 0 }.forEach { TemporaryAccessBanner(it, vm) }
        if (state.selected.isNotEmpty()) {
            Text(
                stringResource(R.string.settings_apps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
            )
        }
        state.selected.forEach { PlatformCard(it, opener, onPlatformSettings) }
        AddPlatforms(state, vm, onPlatformSettings)
    }
}

/** Today's date in the phone's own format, such as "Sunday, 22 November". */
@Composable
private fun todayLabel(): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
    return LocalDate.now().format(DateTimeFormatter.ofPattern(pattern, locale))
}

private class Hero(val title: String, val body: String?, val background: Brush, val content: Color, val icon: ImageVector)

/**
 * The one status for everything. UX-08: the "active" look and wording appear only when every
 * shown method is operational; any other state has its own colour, icon and words (NFR-03).
 */
@Composable
private fun StatusHero(state: MainState, onCheck: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val neutral = SolidColor(scheme.surfaceContainerHigh)
    val hero = if (state.environment == null) {
        Hero(stringResource(R.string.overall_checking), null, neutral, scheme.onSurface, Icons.Default.Info)
    } else {
        when (state.overall) {
            OverallStatus.ALL_ACTIVE -> {
                // A deep teal. Its text stays above 6:1 in both themes (NFR-03 asks for 4.5:1).
                val dark = isSystemInDarkTheme()
                val colors = if (dark) listOf(scheme.primaryContainer, Color(0xFF00382F)) else listOf(scheme.primary, Color(0xFF12453F))
                Hero(
                    stringResource(R.string.overall_active_title),
                    stringResource(R.string.overall_active_body),
                    Brush.linearGradient(colors),
                    if (dark) scheme.onPrimaryContainer else scheme.onPrimary,
                    Icons.Default.CheckCircle,
                )
            }
            OverallStatus.NEEDS_ACTION -> Hero(
                stringResource(R.string.overall_needs_action_title),
                stringResource(R.string.overall_needs_action_body),
                SolidColor(scheme.errorContainer), scheme.onErrorContainer, Icons.Default.Warning,
            )
            OverallStatus.PARTLY_OFF_OR_PAUSED -> Hero(
                stringResource(R.string.overall_partly_title),
                stringResource(R.string.overall_partly_body),
                neutral, scheme.onSurface, Icons.Default.Info,
            )
            OverallStatus.NOTHING_SELECTED -> Hero(
                stringResource(R.string.overall_nothing_title),
                stringResource(R.string.overall_nothing_body),
                neutral, scheme.onSurface, Icons.Default.Info,
            )
        }
    }
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(hero.background)) {
        CompositionLocalProvider(LocalContentColor provides hero.content) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).background(hero.content.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(hero.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(hero.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        if (hero.body != null) Text(hero.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.selected.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onCheck,
                        // 12% keeps the button's label above 4.5:1 on the lightest part of the card.
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = hero.content.copy(alpha = 0.12f),
                            contentColor = hero.content,
                        ),
                    ) { Text(stringResource(R.string.check_title)) }
                }
            }
        }
    }
}

/**
 * Two figures side by side: short-video time today, and over the last seven days with one small
 * bar per day. Both open Activity, where the same numbers are listed in full.
 */
@Composable
private fun StatsRow(state: MainState, onActivity: () -> Unit) {
    val selected = state.selected
    // Today's figure includes the session that is open right now.
    val todayTotal = selected.sumOf { it.usedTodayMs }
    val days = state.week.sortedBy { it.day }
    val totals = days.map { day ->
        val stored = selected.sumOf { day.total(it.platform) }
        if (day.day == state.today) maxOf(stored, todayTotal) else stored
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.activity_today), formatDuration(todayTotal), onActivity, Modifier.weight(1f).fillMaxHeight()) {
            BodyText(stringResource(R.string.home_stat_today_caption))
        }
        StatTile(stringResource(R.string.activity_week_title), formatDuration(totals.sum()), onActivity, Modifier.weight(1f).fillMaxHeight()) {
            Sparkline(totals, todayIndex = days.indexOfFirst { it.day == state.today })
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, footer: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_see_activity), onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            footer()
        }
    }
}

/** One small bar per day, today's the strongest. Decoration: the figure above it is the content. */
@Composable
private fun Sparkline(totals: List<Long>, todayIndex: Int) {
    val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        totals.forEachIndexed { index, total ->
            val fraction = if (total == 0L) 0.08f else (total.toFloat() / max).coerceIn(0.16f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (index == todayIndex) 1f else 0.35f),
                        RoundedCornerShape(3.dp),
                    ),
            )
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

/**
 * One app: logo, name, a one-line status, the settings gear and the single primary action
 * (UX-03). When everything works the card is three short lines. Anything that needs explaining
 * or fixing gets its full status rows underneath (UX-09).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformCard(ui: PlatformUi, opener: PlatformOpener, onPlatformSettings: (Platform) -> Unit) {
    val name = platformName(ui.platform)
    val openLabel = stringResource(R.string.home_open, name)
    val settingsLabel = stringResource(R.string.home_settings_for, name)
    val allActive = ui.health.isNotEmpty() && ui.health.all { it.state == ProtectionState.ACTIVE }
    // With no allowance or interval timer there is only one number to show.
    val plainUsage = ui.config.allowance.limitMs == null && ui.interval == IntervalStatus.Off
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformAvatar(ui.platform, size = 48.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    when {
                        ui.health.isEmpty() -> BodyText(stringResource(R.string.overall_checking))
                        // Nothing to explain or fix: the state as an icon and a word (NFR-03), then how it opens.
                        allActive -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.status_active),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            BodyText(
                                if (ui.health.size > 1) stringResource(R.string.home_active_both)
                                else methodLabel(ui.config.openingMethod, ui.platform),
                            )
                        }
                        else -> BodyText(methodLabel(ui.config.openingMethod, ui.platform))
                    }
                    if (allActive && plainUsage) BodyText(stringResource(R.string.home_today_short, formatDuration(ui.usedTodayMs)))
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
            if (!allActive) {
                ui.health.forEach { health ->
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
