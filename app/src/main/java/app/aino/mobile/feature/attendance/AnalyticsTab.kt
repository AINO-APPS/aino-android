package app.aino.mobile.feature.attendance

import app.aino.mobile.core.common.formatMinutes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Analytics tab (P3.5) — port of `pages/analytics/index.tsx`: date-range
 * filter (7/14/30/custom), widgets + summary tiles, work/break charts and the
 * daily history log rendered as cards on mobile.
 */
@Composable
fun AnalyticsTab(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Attendance Analytics", color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.25.rem)

        // `.date-filter` toolbar.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30).forEach { days ->
                WebFilterChip("Last $days days", ui.analyticsDays == days) { viewModel.setAnalyticsDays(days) }
            }
            WebFilterChip("Custom", ui.analyticsDays == null) { viewModel.setAnalyticsDays(null) }
        }
        if (ui.analyticsDays == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WebDateField(ui.analyticsFrom, { viewModel.setAnalyticsCustomRange(from = it) }, Modifier.weight(1f))
                Text("to", color = colors.textMuted, fontSize = 0.8.rem)
                WebDateField(ui.analyticsTo, { viewModel.setAnalyticsCustomRange(to = it) }, Modifier.weight(1f))
            }
        }

        when {
            ui.analyticsLoading -> AnalyticsSkeleton()
            ui.analyticsError != null -> WebErrorBanner(ui.analyticsError)
            else -> {
                ui.analyticsWidgets?.let { WidgetsTiles(it) }
                NotificationRoutingTile(ui.notificationMetrics)
                SummaryTiles(ui.analyticsData)
                WorkBreakChartCard(ui.analyticsData)
                TrendChartCard(ui.analyticsData)
                DistributionRow(ui.analyticsData)
                HistoryLogCard(ui.analyticsHistory)
            }
        }
    }
}

@Composable
private fun AnalyticsSkeleton() {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { Box(Modifier.weight(1f).height(64.dp).background(colors.surface, RoundedCornerShape(12.dp))) }
        }
        Box(Modifier.fillMaxWidth().height(180.dp).background(colors.surface, RoundedCornerShape(12.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { Box(Modifier.weight(1f).height(140.dp).background(colors.surface, RoundedCornerShape(12.dp))) }
        }
        Box(Modifier.fillMaxWidth().height(200.dp).background(colors.surface, RoundedCornerShape(12.dp)))
    }
}

/** Global `.stat-card` tile: value + label inside a bordered surface. */
@Composable
private fun StatTile(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .background(colors.bgSecondary, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(16.dp).padding(end = 2.dp), tint = valueColor) }
            Text(value, color = valueColor, fontWeight = FontWeight.ExtraBold, fontSize = 1.15.rem)
        }
        Text(label, color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(top = 3.dp))
    }
}

/** `WidgetsGrid` port: the six tracker-widgets tiles (the routing card follows). */
@Composable
private fun WidgetsTiles(widgets: TrackerWidgets) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(formatMinutes(widgets.avgFloorMinutes.toInt()), "Avg Work Time", colors.text, Modifier.weight(1f), Icons.Outlined.BarChart)
            StatTile(
                "${widgets.punctualityPercent.toInt()}%",
                "Punctuality",
                if (widgets.punctualityPercent >= 80) colors.success else colors.warning,
                Modifier.weight(1f),
                Icons.Outlined.Timer,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "${widgets.attendancePercent.toInt()}%",
                "Attendance",
                when {
                    widgets.attendancePercent >= 90 -> colors.success
                    widgets.attendancePercent >= 75 -> colors.warning
                    else -> colors.danger
                },
                Modifier.weight(1f),
                Icons.Outlined.CalendarMonth,
            )
            StatTile("${widgets.targetMetDays}/${widgets.workDays}", "8hr Target Met", colors.primary, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(widgets.leaveCount.toString(), "Leaves (Month)", colors.warning, Modifier.weight(1f), Icons.Outlined.BeachAccess)
            StatTile("${widgets.officeDays} / ${widgets.remoteDays}", "Office / Remote", colors.text, Modifier.weight(1f), Icons.Outlined.Apartment)
        }
    }
}

/** Web "Notification Routing" stat card (BellRing icon, success-rate colour bands 99 / 95). */
@Composable
private fun NotificationRoutingTile(metrics: NotificationMetrics?) {
    val colors = LocalWebColors.current
    val rate = metrics?.successRate
    val valueColor = when {
        rate == null -> colors.text
        rate >= 99 -> colors.success
        rate >= 95 -> colors.warning
        else -> colors.danger
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp)
            .background(colors.bgSecondary, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(18.dp).padding(end = 4.dp), tint = colors.primary)
            Text(routingSuccessLabel(metrics), color = valueColor, fontWeight = FontWeight.ExtraBold, fontSize = 1.15.rem)
        }
        Text("Notification Routing", color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(top = 3.dp))
        metrics?.let { Text(routingMeta(it), color = colors.textSecondary, fontSize = 0.7.rem, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** `SummaryStats` port: six tiles computed from the analytics series. */
@Composable
private fun SummaryTiles(data: List<AttendanceDay>) {
    val colors = LocalWebColors.current
    val totalFloor = data.sumOf { it.floorMinutes }
    val totalBreak = data.sumOf { it.breakMinutes }
    val workingDays = data.count { it.floorMinutes > 0 }
    val avgFloor = if (workingDays > 0) totalFloor / workingDays else 0
    val daysMet = data.count { it.floorMinutes >= 480 }
    val officeDays = data.count { it.floorMinutes > 0 && it.workMode != "remote" }
    val remoteDays = data.count { it.floorMinutes > 0 && it.workMode == "remote" }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(formatMinutes(totalFloor), "Total Work Time", colors.primary, Modifier.weight(1f))
            StatTile(formatMinutes(totalBreak), "Total Break Time", colors.warning, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(formatMinutes(avgFloor), "Avg Work / Day", colors.primary, Modifier.weight(1f))
            StatTile("$daysMet / $workingDays", "Days Met 8hr Target", colors.success, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(officeDays.toString(), "Office Days", colors.primary, Modifier.weight(1f), Icons.Outlined.Apartment)
            StatTile(remoteDays.toString(), "Remote Days", colors.success, Modifier.weight(1f), Icons.Outlined.Home)
        }
    }
}

private fun dayLabel(date: String): String = runCatching {
    LocalDate.parse(date.take(10)).format(DateTimeFormatter.ofPattern("EEE d", Locale.US))
}.getOrDefault(date)

/** `WorkBreakChart` port: grouped floor/break bars per day. */
@Composable
private fun WorkBreakChartCard(data: List<AttendanceDay>) {
    val colors = LocalWebColors.current
    AttendanceCard {
        CardTitle(Icons.Outlined.BarChart, "Work vs Break (hours)")
        if (data.isEmpty()) {
            Text("No data for this period", color = colors.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(top = 12.dp))
            return@AttendanceCard
        }
        val maxMinutes = (data.maxOf { maxOf(it.floorMinutes, it.breakMinutes) }).coerceAtLeast(60)
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).height(150.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            data.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        val floorH = (110.dp * (day.floorMinutes / maxMinutes.toFloat())).coerceAtLeast(2.dp)
                        val breakH = (110.dp * (day.breakMinutes / maxMinutes.toFloat())).coerceAtLeast(2.dp)
                        Box(Modifier.width(9.dp).height(floorH).background(colors.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                        Box(Modifier.width(9.dp).height(breakH).background(colors.warning, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                    }
                    Text(dayLabel(day.date), color = colors.textMuted, fontSize = 0.6.rem, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChartKey(colors.primary, "Work")
            ChartKey(colors.warning, "Break")
        }
    }
}

/** `TrendChart` port: floor-hours line across the range. */
@Composable
private fun TrendChartCard(data: List<AttendanceDay>) {
    val colors = LocalWebColors.current
    AttendanceCard {
        CardTitle(Icons.Outlined.Timer, "Work Trend (hours)")
        if (data.isEmpty()) {
            Text("No data for this period", color = colors.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(top = 12.dp))
            return@AttendanceCard
        }
        val points = data.map { it.floorMinutes / 60f }
        val maxV = (points.maxOrNull() ?: 8f).coerceAtLeast(8f)
        val lineColor = colors.primary
        Canvas(Modifier.fillMaxWidth().padding(top = 14.dp).height(120.dp)) {
            if (points.size < 2) return@Canvas
            val stepX = size.width / (points.size - 1)
            val path = Path()
            points.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - (value / maxV) * (size.height - 12f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 5f))
            points.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - (value / maxV) * (size.height - 12f)
                drawCircle(lineColor, radius = 6f, center = Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dayLabel(data.first().date), color = colors.textMuted, fontSize = 0.62.rem)
            Text(dayLabel(data.last().date), color = colors.textMuted, fontSize = 0.62.rem)
        }
    }
}

@Composable
private fun ChartKey(color: Color, label: String) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(" $label", color = colors.textSecondary, fontSize = 0.72.rem)
    }
}

/** `TimeDistributionChart` + `WorkModeChart` port: two donut charts. */
@Composable
private fun DistributionRow(data: List<AttendanceDay>) {
    val colors = LocalWebColors.current
    val totalFloor = data.sumOf { it.floorMinutes }.toFloat()
    val totalBreak = data.sumOf { it.breakMinutes }.toFloat()
    val officeDays = data.count { it.floorMinutes > 0 && it.workMode != "remote" }.toFloat()
    val remoteDays = data.count { it.floorMinutes > 0 && it.workMode == "remote" }.toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AttendanceCard {
            CardTitle(Icons.Outlined.Timer, "Time Distribution")
            Donut(
                segments = listOf(totalFloor to colors.primary, totalBreak to colors.warning),
                centerLabel = formatMinutes((totalFloor + totalBreak).toInt()),
                modifier = Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChartKey(colors.primary, "Work ${formatMinutes(totalFloor.toInt())}")
                ChartKey(colors.warning, "Break ${formatMinutes(totalBreak.toInt())}")
            }
        }
        AttendanceCard {
            CardTitle(Icons.Outlined.Apartment, "Work Mode Split")
            Donut(
                segments = listOf(officeDays to colors.primary, remoteDays to colors.success),
                centerLabel = "${(officeDays + remoteDays).toInt()} days",
                modifier = Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChartKey(colors.primary, "Office ${officeDays.toInt()}")
                ChartKey(colors.success, "Remote ${remoteDays.toInt()}")
            }
        }
    }
}

@Composable
private fun Donut(segments: List<Pair<Float, Color>>, centerLabel: String, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    val total = segments.sumOf { it.first.toDouble() }.toFloat()
    Box(modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 26.dp.toPx()
            val inset = stroke / 2
            if (total <= 0f) {
                drawArc(colors.surfaceHover, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            segments.forEach { (value, color) ->
                val sweep = (value / total) * 360f
                if (sweep > 0f) {
                    drawArc(color, start, sweep, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                }
                start += sweep
            }
        }
        Text(centerLabel, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 0.78.rem)
    }
}

/** `HistoryTable` port (P3.5): the 6-column table collapses to cards on mobile. */
@Composable
private fun HistoryLogCard(history: List<AttendanceDay>) {
    val colors = LocalWebColors.current
    AttendanceCard {
        CardTitle(Icons.Outlined.Assignment, "Daily Log")
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (history.isEmpty()) {
                Text("No data for this period", color = colors.textMuted, fontSize = 0.85.rem)
            }
            history.forEach { day ->
                val met = day.floorMinutes >= 480
                val remote = day.workMode == "remote"
                Column(
                    Modifier.fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(10.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            runCatching {
                                LocalDate.parse(day.date.take(10)).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
                            }.getOrDefault(day.date),
                            color = colors.text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 0.85.rem,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(if (remote) Color(0x1F0EA5E9) else colors.primaryGlow, RoundedCornerShape(20.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                if (remote) Icons.Outlined.Home else Icons.Outlined.Apartment,
                                null,
                                Modifier.size(12.dp),
                                tint = if (remote) Color(0xFF0EA5E9) else colors.primary,
                            )
                            Text(
                                if (remote) " Remote" else " Office",
                                color = if (remote) Color(0xFF0EA5E9) else colors.primary,
                                fontSize = 0.72.rem,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        LogCell("Work", formatMinutes(day.floorMinutes), colors.primary)
                        LogCell("Break", formatMinutes(day.breakMinutes), colors.warning)
                        LogCell("Total", formatMinutes(day.floorMinutes + day.breakMinutes), colors.text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (met) Icons.Outlined.Check else Icons.Outlined.Close,
                                null,
                                Modifier.size(12.dp),
                                tint = if (met) colors.success else colors.danger,
                            )
                            Text(
                                if (met) " Met" else " Not Met",
                                color = if (met) colors.success else colors.danger,
                                fontSize = 0.75.rem,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCell(label: String, value: String, color: Color) {
    val colors = LocalWebColors.current
    Column {
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 0.82.rem)
        Text(label, color = colors.textMuted, fontSize = 0.62.rem)
    }
}
