package app.aino.mobile.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.delay

private val HOUR_HEIGHT = 60.dp
private val GUTTER = 40.dp // ≤768px `.timeGridInner { grid-template-columns: 40px … }`

/** Web `CalendarPage` + `Calendar` (day / week / month) inside the app shell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    userId: Long,
    /** Opens the full-screen event form route once an editor draft exists. */
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val zone = remember { ZoneId.systemDefault() }
    LaunchedEffect(userId) { viewModel.userId = userId }
    val editorOpen = ui.editor != null
    LaunchedEffect(editorOpen) { if (editorOpen) onOpenEditor() }
    // The now-line and past-slot shading follow the clock.
    val now by produceState(LocalDateTime.now()) { while (true) { delay(30_000); value = LocalDateTime.now() } }

    PullToRefreshBox(isRefreshing = ui.loading && ui.events.isEmpty(), onRefresh = viewModel::refresh, modifier = modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(22.dp), tint = colors.text)
                Text("Calendar", color = colors.text, fontSize = 1.3.rem, fontWeight = FontWeight.Bold)
            }
            Text("Schedule events and manage your time", color = colors.textMuted, fontSize = 0.82.rem, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            Toolbar(ui.view, ui.baseDate, viewModel)
            when (ui.view) {
                CalendarView.Week -> {
                    val days = weekDays(ui.baseDate)
                    WeekHeader(days, now.toLocalDate(), onOpenDay = viewModel::openDay)
                    TimeGrid(days, ui.events, now, zone, ui.view, ui.baseDate, viewModel)
                }
                CalendarView.Day -> {
                    WeekHeader(listOf(ui.baseDate), now.toLocalDate(), highlightAll = true, onOpenDay = {})
                    TimeGrid(listOf(ui.baseDate), ui.events, now, zone, ui.view, ui.baseDate, viewModel)
                }
                CalendarView.Month -> MonthGrid(ui.baseDate, ui.events, now.toLocalDate(), zone, viewModel)
            }
        }
    }
}

@Composable
private fun Toolbar(view: CalendarView, base: LocalDate, viewModel: CalendarViewModel) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(6.dp)
    // ≤768px: `.toolbar { flex-direction: column; align-items: stretch }`.
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).clip(shape).border(1.dp, colors.border, shape).clickable { viewModel.navigate(-1) }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous", tint = colors.text)
            }
            Text(
                "Today", color = colors.text, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(shape).border(1.dp, colors.border, shape).clickable(onClick = viewModel::goToday).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Box(Modifier.size(32.dp).clip(shape).border(1.dp, colors.border, shape).clickable { viewModel.navigate(1) }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next", tint = colors.text)
            }
            Text(calendarTitle(view, base), color = colors.text, fontSize = 0.9.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "New Event", color = Color.White, fontSize = 0.82.rem, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(shape).background(colors.primary).clickable(onClick = viewModel::newEvent).padding(horizontal = 14.dp, vertical = 7.dp),
            )
            Row(Modifier.clip(shape).border(1.dp, colors.border, shape)) {
                CalendarView.entries.forEach { option ->
                    val active = option == view
                    Text(
                        option.label, fontSize = 0.78.rem, fontWeight = FontWeight.Medium,
                        color = if (active) Color.White else colors.textSecondary,
                        modifier = Modifier.background(if (active) colors.primary else Color.Transparent)
                            .clickable { viewModel.setView(option) }.padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(days: List<LocalDate>, today: LocalDate, highlightAll: Boolean = false, onOpenDay: (LocalDate) -> Unit) {
    val colors = LocalWebColors.current
    Column {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(GUTTER))
            days.forEach { day ->
                val isToday = highlightAll || day == today
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { onOpenDay(day) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(DAY_NAMES[monDayIndex(day)].uppercase(), color = colors.textMuted, fontSize = 0.7.rem, letterSpacing = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp))
                    Box(
                        Modifier.padding(top = 2.dp).size(28.dp).then(if (isToday) Modifier.background(colors.primary, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Text("${day.dayOfMonth}", color = if (isToday) Color.White else colors.text, fontSize = 1.1.rem, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TimeGrid(
    days: List<LocalDate>,
    events: List<CalendarEvent>,
    now: LocalDateTime,
    zone: ZoneId,
    view: CalendarView,
    base: LocalDate,
    viewModel: CalendarViewModel,
) {
    val colors = LocalWebColors.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Scroll to an hour before now whenever the view or date changes.
    LaunchedEffect(view, base) {
        val target = with(density) { (HOUR_HEIGHT * maxOf(0, now.hour - 1)).roundToPx() }
        scroll.scrollTo(target)
    }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
        Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
            Column(Modifier.width(GUTTER)) {
                (0 until 24).forEach { hour ->
                    Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth()) {
                        Text(
                            formatHour(hour), color = colors.textMuted, fontSize = 0.6.rem, textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth().padding(end = 4.dp).offset(y = (-6).dp),
                        )
                    }
                }
            }
            days.forEach { day ->
                DayColumn(day, eventsForDay(events, day, zone).filterNot { it.allDay }, now, zone, viewModel, Modifier.weight(1f))
            }
        }
        // `.allDayRow` renders after the hour grid, as on the web.
        if (events.any { it.allDay }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("All day", color = colors.textMuted, fontSize = 0.65.rem, textAlign = TextAlign.End, modifier = Modifier.width(GUTTER).padding(end = 4.dp, top = 4.dp))
                days.forEach { day ->
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        eventsForDay(events, day, zone).filter { it.allDay }.forEach { event ->
                            EventChip(event, 0.7, Modifier.fillMaxWidth()) { viewModel.openEdit(event) }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }
        Spacer(Modifier.height(64.dp))
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    events: List<CalendarEvent>,
    now: LocalDateTime,
    zone: ZoneId,
    viewModel: CalendarViewModel,
    modifier: Modifier,
) {
    val colors = LocalWebColors.current
    val isToday = day == now.toLocalDate()
    BoxWithConstraints(
        modifier.fillMaxSize().then(if (isToday) Modifier.background(colors.bgHover) else Modifier)
            .border(width = 0.5.dp, color = colors.border),
    ) {
        Column(Modifier.fillMaxSize()) {
            (0 until 24).forEach { hour ->
                val past = day.atTime(hour, 0).isBefore(now)
                Box(
                    Modifier.fillMaxWidth().height(HOUR_HEIGHT).alpha(if (past) 0.55f else 1f)
                        .clickable(enabled = !past) { viewModel.openCreate(day, hour) },
                ) { Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = .45f))) }
            }
        }
        val width = maxWidth
        layoutEvents(events, day, zone).forEach { item ->
            val gap = 2.dp
            val height = maxOf(20f, item.endMin - item.startMin).dp
            val colWidth: Dp = if (item.total == 1) width - 4.dp else (width - gap * (item.total + 1)) / item.total
            val left: Dp = if (item.total == 1) 2.dp else colWidth * item.col + gap * (item.col + 1)
            val start = item.event.localStart(zone)
            val end = item.event.localEnd(zone)
            Column(
                Modifier.offset(x = left, y = item.startMin.dp).width(colWidth).height(height)
                    .shadow(1.dp, RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(parseHexColor(eventColor(item.event))))
                    .clickable { viewModel.openEdit(item.event) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.event.meetingCode != null) Icon(Icons.Outlined.Videocam, "Meeting", Modifier.size(11.dp).padding(end = 2.dp), tint = Color.White)
                    Text(item.event.title, color = Color.White, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (height >= 40.dp) Text("${hhmm(start)} – ${hhmm(end)}", color = Color.White.copy(alpha = .8f), fontSize = 0.62.rem, maxLines = 1)
            }
        }
        if (isToday) {
            val minutes = now.hour * 60 + now.minute
            Box(Modifier.offset(y = minutes.dp - 1.dp).fillMaxWidth().height(2.dp).background(colors.danger))
            Box(Modifier.offset(x = (-4).dp, y = minutes.dp - 4.dp).size(8.dp).background(colors.danger, CircleShape))
        }
    }
}

@Composable
private fun EventChip(event: CalendarEvent, fontRem: Double, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(3.dp)).background(Color(parseHexColor(eventColor(event))))
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (event.meetingCode != null) Icon(Icons.Outlined.Videocam, "Meeting", Modifier.size(9.dp).padding(end = 2.dp), tint = Color.White)
        Text(event.title, color = Color.White, fontSize = fontRem.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MonthGrid(
    base: LocalDate,
    events: List<CalendarEvent>,
    today: LocalDate,
    zone: ZoneId,
    viewModel: CalendarViewModel,
) {
    val colors = LocalWebColors.current
    val days = monthDays(base)
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) {
            DAY_NAMES.forEach {
                Text(
                    it.uppercase(), color = colors.textMuted, fontSize = 0.7.rem, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    val dayEvents = eventsForDay(events, day, zone)
                    val isToday = day == today
                    Column(
                        Modifier.weight(1f).heightIn(min = 60.dp)
                            .alpha(if (day.month != base.month) 0.35f else 1f)
                            .then(if (isToday) Modifier.background(colors.bgHover) else Modifier)
                            .border(0.5.dp, colors.border.copy(alpha = .45f))
                            .clickable { viewModel.openDay(day) }.padding(3.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Box(
                            Modifier.size(24.dp).then(if (isToday) Modifier.background(colors.primary, CircleShape) else Modifier),
                            contentAlignment = if (isToday) Alignment.Center else Alignment.TopStart,
                        ) { Text("${day.dayOfMonth}", color = if (isToday) Color.White else colors.text, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold) }
                        dayEvents.take(3).forEach { event -> EventChip(event, 0.62, Modifier.fillMaxWidth()) { viewModel.openEdit(event) } }
                        if (dayEvents.size > 3) Text("+${dayEvents.size - 3} more", color = colors.textMuted, fontSize = 0.62.rem)
                    }
                }
            }
        }
        Spacer(Modifier.height(64.dp))
    }
}
