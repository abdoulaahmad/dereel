package app.dereel.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dereel.R
import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.pacing.IntervalStatus
import app.dereel.core.usage.DayUsage
import app.dereel.ui.MainState
import app.dereel.ui.PlatformUi
import app.dereel.ui.common.BodyText
import app.dereel.ui.common.PlatformAvatar
import app.dereel.ui.common.RowDivider
import app.dereel.ui.common.ScreenTitle
import app.dereel.ui.common.SectionCard
import app.dereel.ui.common.formatDay
import app.dereel.ui.common.formatDuration
import app.dereel.ui.common.formatWeekday
import app.dereel.ui.common.nativeAppName
import app.dereel.ui.common.platformName
import app.dereel.ui.common.shortVideoName
import app.dereel.ui.home.AllowanceSummary
import app.dereel.ui.theme.platformAccent

/**
 * FR-31: today and the previous six days for each selected platform. The combined total is
 * split into the native app and inside Dereel. The week is drawn as bars and also listed as
 * numbers. No time-saved figures or streaks (UX-11).
 */
@Composable
fun ActivityScreen(state: MainState) {
    val selected = state.selected
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.nav_activity))
        BodyText(stringResource(R.string.activity_intro))
        if (selected.isEmpty()) {
            BodyText(stringResource(R.string.activity_empty), Modifier.padding(top = 16.dp))
            return@Column
        }
        val today = state.week.firstOrNull { it.day == state.today }
        // Oldest day first, so the chart reads left to right.
        WeekCard(state.week.sortedBy { it.day }, selected, state.today)

        Text(
            stringResource(R.string.activity_today),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
        )
        selected.forEach { TodayCard(it, today) }

        val previous = state.week.filter { it.day != state.today }
        if (previous.any { day -> selected.any { day.total(it.platform) > 0 } }) {
            SectionCard(stringResource(R.string.activity_previous)) {
                previous.forEachIndexed { index, day ->
                    DayRow(day, selected)
                    if (index < previous.lastIndex) RowDivider()
                }
            }
        }
    }
}

@Composable
private fun WeekCard(days: List<DayUsage>, selected: List<PlatformUi>, todayDay: Long) {
    val context = LocalContext.current
    val totals = days.map { day -> selected.sumOf { day.total(it.platform) } }
    val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val weekTotal = totals.sum()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(stringResource(R.string.activity_week_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(
                    if (weekTotal == 0L) stringResource(R.string.activity_week_none) else formatDuration(weekTotal),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (weekTotal == 0L) {
                BodyText(stringResource(R.string.activity_week_none_body))
                return@Column
            }
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEachIndexed { index, day ->
                    val total = totals[index]
                    val isToday = day.day == todayDay
                    val spoken = stringResource(R.string.activity_bar_label, formatDay(day.day), formatDuration(context, total))
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = spoken },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    // A day with any time always shows a visible sliver.
                                    .fillMaxHeight(if (total == 0L) 0.02f else (total.toFloat() / max).coerceIn(0.06f, 1f))
                                    .background(
                                        if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                        RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp),
                                    ),
                            )
                        }
                        Text(
                            formatWeekday(day.day),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCard(ui: PlatformUi, today: DayUsage?) {
    val platform = ui.platform
    val native = today?.of(platform, OpeningMethod.NATIVE_APP) ?: 0
    val browser = today?.of(platform, OpeningMethod.IN_DEREEL) ?: 0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformAvatar(platform, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        stringResource(R.string.activity_platform_heading, platformName(platform), shortVideoName(platform)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    // The split is shown only where a platform has two opening methods, and only
                    // once there is time to split.
                    when (Capabilities.onlyMethod(platform)) {
                        null -> if (native + browser > 0) {
                            BodyText(
                                stringResource(
                                    R.string.activity_split,
                                    nativeAppName(platform),
                                    formatDuration(native),
                                    formatDuration(browser),
                                ),
                            )
                        }
                        OpeningMethod.NATIVE_APP -> BodyText(stringResource(R.string.activity_native_only, nativeAppName(platform)))
                        OpeningMethod.IN_DEREEL -> BodyText(stringResource(R.string.activity_browser_only))
                    }
                }
                Text(formatDuration(native + browser), style = MaterialTheme.typography.titleLarge, color = platformAccent(platform))
            }
            // The total is already the large figure on this card, so only limits are added.
            val onTimer = ui.config.blockShortVideos && ui.interval != IntervalStatus.Off
            if (ui.config.allowance.limitMs != null || onTimer) AllowanceSummary(ui, showPlainTotal = false)
        }
    }
}

@Composable
private fun DayRow(day: DayUsage, selected: List<PlatformUi>) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatDay(day.day), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            val used = selected.filter { day.total(it.platform) > 0 }
            if (used.isEmpty()) BodyText(stringResource(R.string.activity_day_none))
            used.forEach { ui ->
                Text(
                    stringResource(R.string.activity_day_value, platformName(ui.platform), formatDuration(day.total(ui.platform))),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
