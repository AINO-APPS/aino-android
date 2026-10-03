package app.aino.mobile.feature.attendance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WEEKDAYS = listOf("S", "M", "T", "W", "T", "F", "S")
private val WEEKDAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

// Calendar status colours (AttendanceCalendar.module.css swatches), readable on both themes.
internal val SwatchPresent = Color(0xFF10B981)
internal val SwatchAbsent = Color(0xFFEF4444)
internal val SwatchLeave = Color(0xFFF59E0B)
internal val SwatchLeavePending = Color(0xFFFBBF24)
internal val SwatchHoliday = Color(0xFF94A3B8)

internal fun kindColor(kind: AttendanceDayKind?): Color? = when (kind) {
    AttendanceDayKind.Present -> SwatchPresent
    AttendanceDayKind.Absent -> SwatchAbsent
    AttendanceDayKind.Leave -> SwatchLeave
    AttendanceDayKind.LeavePending -> SwatchLeavePending
    AttendanceDayKind.Holiday, AttendanceDayKind.Weekend -> SwatchHoliday
    else -> null
}

internal fun kindLabel(kind: AttendanceDayKind): String = when (kind) {
    AttendanceDayKind.Present -> "Present"
    AttendanceDayKind.Absent -> "Absent"
    AttendanceDayKind.Leave -> "On leave"
    AttendanceDayKind.LeavePending -> "Leave pending"
    AttendanceDayKind.Holiday -> "Holiday"
    AttendanceDayKind.Weekend -> "Weekend"
    AttendanceDayKind.InProgress -> "Today"
    AttendanceDayKind.Future -> "Upcoming"
}

/** Calendar kind for [date], or null while that month's history has not loaded (renders neutral). */
internal fun calendarKind(ui: AttendanceUiState, date: LocalDate, today: LocalDate = LocalDate.now()): AttendanceDayKind? {
    if (ui.historyMonth != YearMonth.from(date)) return null
    return attendanceKind(
        date, today, ui.history[date], workDaySet(ui.policy?.workDays.orEmpty()),
        presentThresholdMinutes(ui.policy), ui.leaves[date], ui.holidays[date],
    )
}

/** Overview page items: month calendar card + month summary chips. */
fun LazyListScope.overviewItems(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    item(key = "calendar") { CalendarCard(ui, viewModel) }
    item(key = "summary") { MonthSummaryCard(ui) }
}

@Composable
private fun CalendarCard(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val today = LocalDate.now()
    val onSwipe by rememberUpdatedState<(Long) -> Unit> { delta -> viewModel.changeMonth(delta) }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }

    AttendanceCard(contentPadding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(HeroIcons.CalendarDays, null, Modifier.padding(start = 4.dp).size(18.dp), tint = colors.primary)
            Box(Modifier.weight(1f))
            Stepper(
                label = ui.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                onPrev = { viewModel.changeMonth(-1) },
                onNext = { viewModel.changeMonth(1) },
                prevDescription = "Previous month",
                nextDescription = "Next month",
                onLabelClick = viewModel::currentMonth,
            )
            Box(Modifier.weight(1f))
            Box(Modifier.size(22.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
            WEEKDAYS.forEachIndexed { index, day ->
                Text(
                    day,
                    modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = WEEKDAY_NAMES[index] },
                    color = colors.textMuted,
                    fontSize = 0.7.rem,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AnimatedContent(
            targetState = ui.month,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            label = "calendar-month",
            // Swipe left/right on the grid changes month; consuming the drag keeps the page pager still.
            modifier = Modifier.pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total <= -threshold) onSwipe(1) else if (total >= threshold) onSwipe(-1)
                    },
                ) { change, amount ->
                    change.consume()
                    total += amount
                }
            },
        ) { month ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                monthGrid(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { date ->
                            val inMonth = YearMonth.from(date) == month
                            DayCell(
                                date = date,
                                inMonth = inMonth,
                                isToday = date == today,
                                kind = if (inMonth) calendarKind(ui, date, today) else null,
                                onClick = { viewModel.openDayDetail(date) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    kind: AttendanceDayKind?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalWebColors.current
    val swatch = kindColor(kind)
    val shape = RoundedCornerShape(10.dp)
    var cell = modifier.height(42.dp).clip(shape)
    if (inMonth) {
        cell = cell
            .background(swatch?.copy(alpha = 0.18f) ?: colors.surface)
            .border(if (isToday) 2.dp else 1.dp, if (isToday) colors.primary else swatch?.copy(alpha = 0.45f) ?: Color.Transparent, shape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)) +
                    (kind?.let { ", ${kindLabel(it)}" } ?: "") + if (isToday) ", today" else ""
                role = Role.Button
                onClick(label = "Show details") { onClick(); true }
            }
    } else {
        cell = cell.clearAndSetSemantics { }
    }
    Column(cell, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            date.dayOfMonth.toString(),
            color = when {
                !inMonth -> colors.textMuted.copy(alpha = 0.35f)
                isToday -> colors.primary
                kind == AttendanceDayKind.Future -> colors.textMuted
                else -> colors.text
            },
            fontSize = 0.8.rem,
            fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Medium,
        )
        if (inMonth && swatch != null && kind != AttendanceDayKind.Weekend) {
            Box(Modifier.padding(top = 3.dp).size(5.dp).background(swatch, CircleShape))
        }
    }
}

/** Month summary chips — each chip is also the legend for its calendar colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthSummaryCard(ui: AttendanceUiState) {
    val colors = LocalWebColors.current
    val loaded = ui.historyMonth == ui.month
    val summary = if (loaded) {
        monthSummary(
            ui.month, LocalDate.now(), ui.history, ui.leaves, ui.holidays,
            workDaySet(ui.policy?.workDays.orEmpty()), presentThresholdMinutes(ui.policy),
        )
    } else {
        MonthSummary()
    }
    AttendanceCard {
        SectionHeader(ui.month.format(DateTimeFormatter.ofPattern("MMMM", Locale.US)) + " at a glance") {
            Icon(HeroIcons.Clock, null, Modifier.size(14.dp), tint = colors.textSecondary)
            Text(
                " " + hoursLabel(summary.workedMinutes),
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 0.85.rem,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Total worked ${summary.workedMinutes / 60} hours ${summary.workedMinutes % 60} minutes"
                },
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill("Present ${summary.present}", SwatchPresent, dot = true)
            StatusPill("Absent ${summary.absent}", SwatchAbsent, dot = true)
            StatusPill("Leave ${summary.leave}", SwatchLeave, dot = true)
            if (summary.leavePending > 0) StatusPill("Pending ${summary.leavePending}", SwatchLeavePending, dot = true)
            StatusPill("Holiday ${summary.holiday}", SwatchHoliday, dot = true)
            StatusPill("Weekend ${summary.weekend}", SwatchHoliday)
        }
        Text(
            "Present means at least ${hoursLabel(presentThresholdMinutes(ui.policy))} worked. Tap a day for details; swipe to change month.",
            color = colors.textMuted,
            fontSize = 0.74.rem,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
