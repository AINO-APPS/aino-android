package app.aino.mobile.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

// Status colours, verbatim from AttendanceCalendar.module.css (dark theme).
private val PresentFill = Color(0x2610B981)
private val PresentBorder = Color(0x7310B981)
private val PresentText = Color(0xFF34D399)
private val AbsentFill = Color(0x21EF4444)
private val AbsentBorder = Color(0x66EF4444)
private val AbsentText = Color(0xFFFCA5A5)
private val LeaveFill = Color(0x2EF59E0B)
private val LeaveBorder = Color(0x73F59E0B)
private val LeaveText = Color(0xFFFCD34D)
private val LeavePendingFill = Color(0x26FBBF24)
private val HolidayFill = Color(0x1F94A3B8)
private val HolidayBorder = Color(0x4D94A3B8)
private val SwatchPresent = Color(0xFF10B981)
private val SwatchAbsent = Color(0xFFEF4444)
private val SwatchLeave = Color(0xFFF59E0B)
private val SwatchLeavePending = Color(0xB3FBBF24)
private val SwatchHoliday = Color(0xFF94A3B8)

/** `workDaysToJsDowSet` port: "1,2,3,4,5" → JS day-of-week set (0=Sun). */
private fun workDaysOf(policy: AttendancePolicy?): Set<Int> {
    val parsed = policy?.workDays.orEmpty().split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 0..6 }
    return (if (parsed.isEmpty()) listOf(1, 2, 3, 4, 5) else parsed).toSet()
}

/**
 * `AttendanceCalendar` port (P3.2): month grid with status colours, legend,
 * month navigation and the four-stat summary row.
 */
@Composable
fun OverviewCalendarTab(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val policy = ui.policy
    val workDays = workDaysOf(policy)
    val minMinutes = ((policy?.minHoursPresent ?: policy?.workHoursPerDay ?: 8.0) * 60).toInt()
    val today = LocalDate.now()
    val cells = monthGrid(ui.month)

    val stats = cells.filter { it.year == ui.month.year && it.monthValue == ui.month.monthValue }
        .groupingBy {
            attendanceKind(it, today, ui.history[it], workDays, minMinutes, ui.leaves[it], ui.holidays[it])
        }.eachCount()
    val presentDays = stats[AttendanceDayKind.Present] ?: 0
    val absentDays = stats[AttendanceDayKind.Absent] ?: 0
    val leaveDays = (stats[AttendanceDayKind.Leave] ?: 0) + (stats[AttendanceDayKind.LeavePending] ?: 0)
    val holidayDays = (stats[AttendanceDayKind.Holiday] ?: 0) + (stats[AttendanceDayKind.Weekend] ?: 0)

    AttendanceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp), tint = colors.text)
            Text(
                "Attendance Calendar",
                color = colors.text,
                fontWeight = FontWeight.Bold,
                fontSize = 1.rem,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            MonthNavButton(Icons.Outlined.ChevronLeft, "Previous month") { viewModel.changeMonth(-1) }
            Text(
                ui.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 0.86.rem,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .clickable { viewModel.currentMonth() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            MonthNavButton(Icons.Outlined.ChevronRight, "Next month") { viewModel.changeMonth(1) }
        }
        Spacer(Modifier.height(12.dp))
        Legend(minMinutes / 60)
        Spacer(Modifier.height(12.dp))
        WeekdayHeader()
        Spacer(Modifier.height(4.dp))
        if (ui.loading && ui.history.isEmpty()) CalendarSkeleton()
        else CalendarGrid(cells, ui, today, workDays, minMinutes)
        Spacer(Modifier.height(20.dp))
        StatsRow(presentDays, absentDays, leaveDays, holidayDays)
    }
}

/** 32px bordered nav buttons (`.navBtn`). */
@Composable
private fun MonthNavButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint = colors.text)
    }
}

/** Legend row (`.legend`): five swatches with labels. */
@Composable
private fun Legend(minHoursPresent: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LegendItem(SwatchPresent, "Present (≥ ${minHoursPresent}h)")
        LegendItem(SwatchAbsent, "Absent")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(SwatchLeave, "Leave")
        LegendItem(SwatchLeavePending, "Leave (pending)")
        LegendItem(SwatchHoliday, "Holiday / Weekend")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(" $label", color = colors.textMuted, fontSize = 0.72.rem)
    }
}

@Composable
private fun WeekdayHeader() {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth()) {
        WEEKDAYS.forEach { day ->
            Text(
                day,
                modifier = Modifier.weight(1f),
                color = colors.textMuted,
                fontSize = 0.65.rem,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 35 shimmering placeholder cells while the first month load is in flight. */
@Composable
private fun CalendarSkeleton() {
    val colors = LocalWebColors.current
    repeat(5) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) {
                Box(Modifier.weight(1f).height(38.dp).background(colors.surface, RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    cells: List<LocalDate>,
    ui: AttendanceUiState,
    today: LocalDate,
    workDays: Set<Int>,
    minMinutes: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    val inMonth = date.year == ui.month.year && date.monthValue == ui.month.monthValue
                    val kind = if (inMonth) {
                        attendanceKind(date, today, ui.history[date], workDays, minMinutes, ui.leaves[date], ui.holidays[date])
                    } else {
                        null
                    }
                    DayCell(
                        date = date,
                        inMonth = inMonth,
                        today = date == today,
                        kind = kind,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One day cell: kind background/border, today ring, day number + status dot. */
@Composable
private fun DayCell(date: LocalDate, inMonth: Boolean, today: Boolean, kind: AttendanceDayKind?, modifier: Modifier) {
    val colors = LocalWebColors.current
    val (fill, borderColor, numColor) = when (kind) {
        AttendanceDayKind.Present -> Triple(PresentFill, PresentBorder, PresentText)
        AttendanceDayKind.Absent -> Triple(AbsentFill, AbsentBorder, AbsentText)
        AttendanceDayKind.Leave -> Triple(LeaveFill, LeaveBorder, LeaveText)
        AttendanceDayKind.LeavePending -> Triple(LeavePendingFill, LeaveBorder, LeaveText)
        AttendanceDayKind.Holiday, AttendanceDayKind.Weekend -> Triple(HolidayFill, HolidayBorder, colors.textMuted)
        AttendanceDayKind.InProgress -> Triple(colors.surface, colors.border, colors.text)
        AttendanceDayKind.Future -> Triple(colors.surface, Color.Transparent, colors.textMuted)
        null -> Triple(Color.Transparent, Color.Transparent, colors.textMuted)
    }
    val dot = when (kind) {
        AttendanceDayKind.Present -> SwatchPresent
        AttendanceDayKind.Leave -> SwatchLeave
        AttendanceDayKind.LeavePending -> SwatchLeavePending
        AttendanceDayKind.Absent -> SwatchAbsent
        AttendanceDayKind.Holiday -> SwatchHoliday
        else -> Color.Transparent
    }
    Column(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .then(if (today) Modifier.border(2.dp, colors.primary, RoundedCornerShape(6.dp)) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            color = if (inMonth) numColor else colors.textMuted.copy(alpha = 0.4f),
            fontSize = 0.72.rem,
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
        )
        Box(Modifier.padding(top = 2.dp).size(5.dp).background(dot, CircleShape))
    }
}

/** `.statsRow`: 2×2 stat cards on mobile (Present / Absent / Leave / Holiday-Weekend). */
@Composable
private fun StatsRow(present: Int, absent: Int, leave: Int, holiday: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(SwatchPresent, present, "Present days", Modifier.weight(1f))
            StatCard(SwatchAbsent, absent, "Absent days", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(SwatchLeave, leave, "Leave days", Modifier.weight(1f))
            StatCard(SwatchHoliday, holiday, "Holiday/Weekend", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(dot: Color, value: Int, label: String, modifier: Modifier) {
    val colors = LocalWebColors.current
    Row(
        modifier
            .background(colors.surface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(14.dp).background(dot, RoundedCornerShape(4.dp)))
        Column {
            Text(value.toString(), color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.1.rem, lineHeight = 1.1.rem)
            Text(label.uppercase(), color = colors.textMuted, fontSize = 0.68.rem)
        }
    }
}
