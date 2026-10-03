package app.aino.mobile.feature.attendance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.common.formatMinutes
import app.aino.mobile.core.designsystem.component.FirstLoadSpinner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Insights page (analytics): period presets, KPI tiles, the work/break,
 * trend and distribution charts in cards, and a compact daily log.
 */
@Composable
fun InsightsPage(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    var pickRange by remember { mutableStateOf(false) }
    val rangeMissing = ui.analyticsDays == null && (ui.analyticsFrom.isBlank() || ui.analyticsTo.isBlank())

    AttendancePageList(loading = ui.analyticsLoading, onRefresh = viewModel::loadAnalytics) {
        item(key = "period") {
            PeriodSelector(ui.analyticsDays) { days ->
                viewModel.setAnalyticsDays(days)
                if (days == null && (ui.analyticsFrom.isBlank() || ui.analyticsTo.isBlank())) pickRange = true
            }
        }
        if (ui.analyticsDays == null) {
            item(key = "range") {
                PickerField(
                    "Custom period",
                    if (rangeMissing) "" else "${fmtDate(ui.analyticsFrom)} – ${fmtDate(ui.analyticsTo)}",
                    HeroIcons.CalendarDateRange,
                    { pickRange = true },
                    Modifier.fillMaxWidth(),
                    "Choose dates",
                )
            }
        }
        when {
            rangeMissing -> item(key = "pick") { EmptyState(HeroIcons.CalendarDateRange, "Pick a date range", "Choose the start and end dates to see your insights.") }
            ui.analyticsError != null -> item(key = "error") { ErrorNotice(ui.analyticsError, onRetry = viewModel::loadAnalytics) }
            ui.analyticsLoading && ui.analyticsData.isEmpty() && ui.analyticsHistory.isEmpty() ->
                item(key = "loading") { FirstLoadSpinner(Modifier.height(200.dp)) }
            else -> insightsItems(ui)
        }
    }
    if (pickRange) {
        DateRangeDialog(
            ui.analyticsFrom,
            ui.analyticsTo,
            onDismiss = { pickRange = false },
            onConfirm = { from, to ->
                pickRange = false
                viewModel.setAnalyticsCustomRange(from, to)
            },
        )
    }
}

@Composable
private fun PeriodSelector(selected: Int?, onSelect: (Int?) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        INSIGHTS_PRESETS.forEachIndexed { index, days ->
            SegmentedButton(
                selected = selected == days,
                onClick = { onSelect(days) },
                shape = SegmentedButtonDefaults.itemShape(index, INSIGHTS_PRESETS.size),
                colors = segmentedColors(),
                icon = {},
            ) { Text(days?.let { "${it}d" } ?: "Custom", maxLines = 1) }
        }
    }
}

private fun LazyListScope.insightsItems(ui: AttendanceUiState) {
    val target = ui.status?.targetMinutes ?: 480
    val kpis = insightsKpis(ui.analyticsData, target)
    item(key = "kpis") {
        val colors = LocalWebColors.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile(hoursLabel(kpis.workedMinutes), "Total worked", colors.primary, HeroIcons.Clock, Modifier.weight(1f))
                KpiTile(hoursLabel(kpis.avgPerDayMinutes), "Avg per day", colors.primary, HeroIcons.ChartBar, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile(hoursLabel(kpis.overtimeMinutes), "Overtime", colors.warning, HeroIcons.Bolt, Modifier.weight(1f))
                KpiTile("${kpis.presentDays}", "Days worked", colors.success, HeroIcons.CalendarDays, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("${kpis.targetMetDays}/${kpis.presentDays}", "Target met", colors.success, HeroIcons.CheckCircle, Modifier.weight(1f), "≥ ${hoursLabel(target)} a day")
                KpiTile(hoursLabel(kpis.breakMinutes), "Breaks", colors.warning, HeroIcons.Cup, Modifier.weight(1f))
            }
        }
    }
    ui.analyticsWidgets?.let { widgets ->
        item(key = "month-header") { SectionHeader("This month", Modifier.padding(top = 6.dp)) }
        item(key = "widgets") {
            val colors = LocalWebColors.current
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTile(
                        "${widgets.attendancePercent.toInt()}%",
                        "Attendance",
                        when {
                            widgets.attendancePercent >= 90 -> colors.success
                            widgets.attendancePercent >= 75 -> colors.warning
                            else -> colors.danger
                        },
                        HeroIcons.CalendarDays,
                        Modifier.weight(1f),
                    )
                    KpiTile(
                        "${widgets.punctualityPercent.toInt()}%",
                        "Punctuality",
                        if (widgets.punctualityPercent >= 80) colors.success else colors.warning,
                        HeroIcons.Clock,
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTile("${widgets.officeDays} / ${widgets.remoteDays}", "Office / Remote", colors.primary, HeroIcons.BuildingOffice2, Modifier.weight(1f))
                    KpiTile(widgets.leaveCount.toString(), "Leaves", colors.warning, HeroIcons.Sun, Modifier.weight(1f))
                }
            }
        }
    }
    item(key = "routing") { NotificationRoutingTile(ui.notificationMetrics) }
    item(key = "work-break") { WorkBreakChartCard(ui.analyticsData) }
    item(key = "trend") { TrendChartCard(ui.analyticsData, target) }
    item(key = "split") { DistributionCard(ui.analyticsData) }
    item(key = "log-header") { SectionHeader("Daily log", Modifier.padding(top = 6.dp)) }
    if (ui.analyticsHistory.isEmpty()) {
        item(key = "log-empty") { EmptyState(HeroIcons.ClipboardDocumentList, "No days recorded in this period") }
    }
    items(ui.analyticsHistory.sortedByDescending { it.date }) { day -> DailyLogRow(day, target) }
}

/** "Notification Routing" metric (success-rate colour bands 99 / 95). */
@Composable
private fun NotificationRoutingTile(metrics: NotificationMetrics?) {
    val colors = LocalWebColors.current
    val rate = metrics?.successRate
    val accent = when {
        rate == null -> colors.textSecondary
        rate >= 99 -> colors.success
        rate >= 95 -> colors.warning
        else -> colors.danger
    }
    KpiTile(
        routingSuccessLabel(metrics),
        "Notification routing (24h)",
        accent,
        HeroIcons.BellAlert,
        Modifier.fillMaxWidth(),
        metrics?.let(::routingMeta),
    )
}

private fun dayLabel(date: String): String = runCatching {
    LocalDate.parse(date.take(10)).format(DateTimeFormatter.ofPattern("EEE d", Locale.US))
}.getOrDefault(date)

@Composable
private fun ChartKey(color: Color, label: String) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(" $label", color = colors.textSecondary, fontSize = 0.74.rem)
    }
}

@Composable
private fun NoChartData() {
    Text("No data for this period", color = LocalWebColors.current.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(top = 12.dp))
}

/** Grouped work/break bars per day (horizontally scrollable for long periods). */
@Composable
private fun WorkBreakChartCard(data: List<AttendanceDay>) {
    val colors = LocalWebColors.current
    AttendanceCard {
        SectionHeader("Work vs break") {
            ChartKey(colors.primary, "Work")
            Box(Modifier.width(10.dp))
            ChartKey(colors.warning, "Break")
        }
        if (data.isEmpty()) { NoChartData(); return@AttendanceCard }
        val maxMinutes = data.maxOf { maxOf(it.floorMinutes, it.breakMinutes) }.coerceAtLeast(60)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(150.dp)
                .horizontalScroll(rememberScrollState())
                .clearAndSetSemantics { contentDescription = "Work and break hours per day, ${data.size} days" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            data.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        val floorH = (112.dp * (day.floorMinutes / maxMinutes.toFloat())).coerceAtLeast(2.dp)
                        val breakH = (112.dp * (day.breakMinutes / maxMinutes.toFloat())).coerceAtLeast(2.dp)
                        Box(Modifier.width(9.dp).height(floorH).background(colors.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        Box(Modifier.width(9.dp).height(breakH).background(colors.warning, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                    }
                    Text(dayLabel(day.date), color = colors.textMuted, fontSize = 0.6.rem, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

/** Worked-hours line with the daily target as a dashed guide. */
@Composable
private fun TrendChartCard(data: List<AttendanceDay>, targetMinutes: Int) {
    val colors = LocalWebColors.current
    AttendanceCard {
        SectionHeader("Work trend")
        if (data.isEmpty()) { NoChartData(); return@AttendanceCard }
        val points = data.map { it.floorMinutes / 60f }
        val targetHours = targetMinutes / 60f
        val maxV = maxOf(points.maxOrNull() ?: 0f, targetHours, 1f)
        val lineColor = colors.primary
        val guideColor = colors.textMuted
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(120.dp)
                .clearAndSetSemantics { contentDescription = "Worked hours trend over ${data.size} days" },
        ) {
            val y = { v: Float -> size.height - (v / maxV) * (size.height - 12f) }
            drawLine(
                guideColor,
                Offset(0f, y(targetHours)),
                Offset(size.width, y(targetHours)),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
            if (points.size < 2) {
                drawCircle(lineColor, radius = 6f, center = Offset(size.width / 2, y(points.first())))
                return@Canvas
            }
            val stepX = size.width / (points.size - 1)
            val path = Path()
            points.forEachIndexed { index, value ->
                if (index == 0) path.moveTo(0f, y(value)) else path.lineTo(index * stepX, y(value))
            }
            drawPath(path, lineColor, style = Stroke(width = 5f))
            if (points.size <= 31) points.forEachIndexed { index, value -> drawCircle(lineColor, radius = 5f, center = Offset(index * stepX, y(value))) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dayLabel(data.first().date), color = colors.textMuted, fontSize = 0.62.rem)
            Text("– – target ${hoursLabel(targetMinutes)}", color = colors.textMuted, fontSize = 0.62.rem)
            Text(dayLabel(data.last().date), color = colors.textMuted, fontSize = 0.62.rem)
        }
    }
}

/** Time split (work/break) and work-mode split doughnuts side by side. */
@Composable
private fun DistributionCard(data: List<AttendanceDay>) {
    val colors = LocalWebColors.current
    val totalFloor = data.sumOf { it.floorMinutes }.toFloat()
    val totalBreak = data.sumOf { it.breakMinutes }.toFloat()
    val officeDays = data.count { it.floorMinutes > 0 && it.workMode != "remote" }.toFloat()
    val remoteDays = data.count { it.floorMinutes > 0 && it.workMode == "remote" }.toFloat()
    AttendanceCard {
        SectionHeader("Breakdown")
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Donut(listOf(totalFloor to colors.primary, totalBreak to colors.warning), formatMinutes((totalFloor + totalBreak).toInt()))
                ChartKey(colors.primary, "Work")
                ChartKey(colors.warning, "Break")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Donut(listOf(officeDays to colors.primary, remoteDays to colors.success), "${(officeDays + remoteDays).toInt()} days")
                ChartKey(colors.primary, "Office ${officeDays.toInt()}")
                ChartKey(colors.success, "Remote ${remoteDays.toInt()}")
            }
        }
    }
}

@Composable
private fun Donut(segments: List<Pair<Float, Color>>, centerLabel: String) {
    val colors = LocalWebColors.current
    val total = segments.sumOf { it.first.toDouble() }.toFloat()
    Box(Modifier.size(112.dp).padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            if (total <= 0f) {
                drawArc(colors.surfaceHover, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            segments.forEach { (value, color) ->
                val sweep = (value / total) * 360f
                if (sweep > 0f) drawArc(color, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                start += sweep
            }
        }
        Text(centerLabel, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 0.72.rem)
    }
}

/** One compact daily-log row: date, mode, worked / break, target met. */
@Composable
private fun DailyLogRow(day: AttendanceDay, targetMinutes: Int) {
    val colors = LocalWebColors.current
    val met = day.floorMinutes >= targetMinutes
    val remote = day.workMode == "remote"
    AttendanceCard(contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (remote) HeroIcons.Home else HeroIcons.BuildingOffice2,
                if (remote) "Remote" else "Office",
                Modifier.size(16.dp),
                tint = colors.textSecondary,
            )
            Text(fmtDate(day.date), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(hoursLabel(day.floorMinutes), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem)
                Text("break ${hoursLabel(day.breakMinutes)}", color = colors.textMuted, fontSize = 0.7.rem)
            }
            Icon(
                if (met) HeroIcons.CheckCircle else HeroIcons.XCircle,
                if (met) "Target met" else "Target not met",
                Modifier.padding(start = 10.dp).size(18.dp),
                tint = if (met) colors.success else colors.textMuted,
            )
        }
    }
}
