package app.aino.mobile.feature.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class CalendarView(val label: String) { Day("Day"), Week("Week"), Month("Month") }

/** `DAY_NAMES` — the web's weeks start on Monday. */
val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Web default accent (`branding.accent_color || "#2383e2"`). */
const val DEFAULT_ACCENT = "#2383e2"

/** Mon=0 … Sun=6 (`toMonDayIndex`). */
fun monDayIndex(date: LocalDate): Int = date.dayOfWeek.value - 1

fun weekStart(date: LocalDate): LocalDate = date.minusDays(monDayIndex(date).toLong())

fun weekDays(base: LocalDate): List<LocalDate> = weekStart(base).let { mon -> (0..6).map { mon.plusDays(it.toLong()) } }

/** Six Monday-first weeks covering the month (`getMonthDays`). */
fun monthDays(base: LocalDate): List<LocalDate> {
    val first = base.withDayOfMonth(1)
    val start = first.minusDays(monDayIndex(first).toLong())
    return (0 until 42).map { start.plusDays(it.toLong()) }
}

/** `[start, endExclusive)` of the fetched range for a view. */
fun visibleRange(view: CalendarView, base: LocalDate): Pair<LocalDate, LocalDate> = when (view) {
    CalendarView.Day -> base to base.plusDays(1)
    CalendarView.Week -> weekDays(base).let { it.first() to it.last().plusDays(1) }
    CalendarView.Month -> monthDays(base).let { it.first() to it.last().plusDays(1) }
}

fun navigate(view: CalendarView, base: LocalDate, direction: Int): LocalDate = when (view) {
    CalendarView.Week -> base.plusWeeks(direction.toLong())
    CalendarView.Day -> base.plusDays(direction.toLong())
    CalendarView.Month -> base.plusMonths(direction.toLong())
}

private fun monthName(date: LocalDate) = date.month.getDisplayName(TextStyle.FULL, Locale.US)

/** `getTitle()` — en-US strings exactly as the web renders them. */
fun calendarTitle(view: CalendarView, base: LocalDate): String = when (view) {
    CalendarView.Day -> DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US).format(base)
    CalendarView.Month -> "${monthName(base)} ${base.year}"
    CalendarView.Week -> {
        val days = weekDays(base)
        val st = days.first()
        val e = days.last()
        if (st.month == e.month) "${monthName(st)} ${st.dayOfMonth} – ${e.dayOfMonth}, ${st.year}"
        else "${monthName(st)} ${st.dayOfMonth} – ${monthName(e)} ${e.dayOfMonth}, ${e.year}"
    }
}

fun formatHour(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

/** `HH:mm` like the web's `pad(h):pad(m)` event time label. */
fun hhmm(time: LocalDateTime): String = "%02d:%02d".format(time.hour, time.minute)

// ── Events on the grid ───────────────────────────────────────────────────────

fun CalendarEvent.localStart(zone: ZoneId): LocalDateTime = parseInstant(startTime).atZone(zone).toLocalDateTime()
fun CalendarEvent.localEnd(zone: ZoneId): LocalDateTime = parseInstant(endTime).atZone(zone).toLocalDateTime()

fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse {
    runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrElse {
        LocalDateTime.parse(value.take(19)).atZone(ZoneId.systemDefault()).toInstant()
    }
}

/** `getEventsForDay`: any overlap with the local day (inclusive bounds, as on the web). */
fun eventsForDay(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId): List<CalendarEvent> {
    val dayStart = day.atStartOfDay()
    val dayEnd = day.atTime(LocalTime.MAX)
    return events.filter { !it.localStart(zone).isAfter(dayEnd) && !it.localEnd(zone).isBefore(dayStart) }
}

data class LayoutItem(val event: CalendarEvent, val startMin: Float, val endMin: Float, val col: Int, val total: Int)

/** `layoutEvents`: greedy column packing of overlapping timed events. */
fun layoutEvents(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId): List<LayoutItem> {
    val dayStart = day.atStartOfDay()
    val items = events.map {
        val start = ChronoUnit.SECONDS.between(dayStart, it.localStart(zone)) / 60f
        val end = ChronoUnit.SECONDS.between(dayStart, it.localEnd(zone)) / 60f
        Triple(it, maxOf(0f, start), minOf(1440f, end))
    }.sortedWith(compareBy<Triple<CalendarEvent, Float, Float>> { it.second }.thenByDescending { it.third - it.second })
    val columns = mutableListOf<Float>()
    val placed = items.map { (event, start, end) ->
        var col = 0
        while (col < columns.size && columns[col] > start) col++
        if (col == columns.size) columns += end else columns[col] = end
        LayoutItem(event, start, end, col, 1)
    }
    return placed.map { p ->
        val maxCol = placed.filter { it.startMin < p.endMin && it.endMin > p.startMin }.maxOfOrNull { it.col } ?: p.col
        p.copy(total = maxOf(p.col, maxCol) + 1)
    }
}

// ── Event form ───────────────────────────────────────────────────────────────

data class EventForm(
    val title: String = "",
    val description: String = "",
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean = false,
    val color: String = DEFAULT_ACCENT,
    val taskId: Long? = null,
    /** "single" or "multi" ("Custom days this week"). */
    val scheduleMode: String = "single",
    val weekdays: List<Int> = emptyList(),
)

/**
 * `openCreate`: null when creating is not allowed (a past day from the button,
 * or a past hour slot). The start rounds up to the next quarter hour.
 */
fun createForm(day: LocalDate, hour: Int?, now: LocalDateTime, accent: String = DEFAULT_ACCENT): EventForm? {
    if (hour == null && day.isBefore(now.toLocalDate())) return null
    var start = if (hour == null) day.atTime(now.hour, now.minute) else day.atTime(hour, 0)
    if (hour != null && start.isBefore(now)) return null
    val rem = start.minute % 15
    if (rem > 0) start = start.plusMinutes((15 - rem).toLong())
    return EventForm(start = start, end = start.plusHours(1), color = accent, weekdays = listOf(monDayIndex(start.toLocalDate())))
}

fun editForm(event: CalendarEvent, zone: ZoneId, accent: String = DEFAULT_ACCENT): EventForm = EventForm(
    title = event.title,
    description = event.description.orEmpty(),
    start = event.localStart(zone).truncatedTo(ChronoUnit.MINUTES),
    end = event.localEnd(zone).truncatedTo(ChronoUnit.MINUTES),
    allDay = event.allDay,
    color = event.color?.takeIf(String::isNotBlank) ?: accent,
    taskId = event.taskId,
)

/** `handleStartChange`: a start at/after the end pushes the end to start + 1h. */
fun EventForm.withStart(next: LocalDateTime): EventForm =
    if (!end.isAfter(next)) copy(start = next, end = next.plusHours(1)) else copy(start = next)

data class TimeOption(val time: LocalTime, val label: String)

fun timeLabel(time: LocalTime): String {
    val h12 = when { time.hour == 0 -> 12; time.hour > 12 -> time.hour - 12; else -> time.hour }
    return "$h12:%02d ${if (time.hour < 12) "AM" else "PM"}".format(time.minute)
}

private val QUARTER_OPTIONS: List<TimeOption> =
    (0 until 24 * 4).map { LocalTime.of(it / 4, (it % 4) * 15) }.map { TimeOption(it, timeLabel(it)) }

/** `getTimeOptions`: 15-minute slots plus the current value when it is off-grid. */
fun timeOptions(current: LocalTime?): List<TimeOption> {
    if (current == null || current.minute % 15 == 0) return QUARTER_OPTIONS
    return QUARTER_OPTIONS.filter { it.time < current } + TimeOption(current, timeLabel(current)) +
        QUARTER_OPTIONS.filter { it.time > current }
}

private fun LocalDateTime.minuteTime(): LocalTime = toLocalTime().truncatedTo(ChronoUnit.MINUTES)

fun startTimeDisabled(option: LocalTime, form: EventForm, now: LocalDateTime, creating: Boolean): Boolean =
    creating && form.start.toLocalDate() == now.toLocalDate() && option < now.minuteTime()

fun endTimeDisabled(option: LocalTime, form: EventForm, now: LocalDateTime, creating: Boolean): Boolean {
    val endDate = form.end.toLocalDate()
    return (creating && endDate == now.toLocalDate() && option < now.minuteTime()) ||
        (endDate == form.start.toLocalDate() && option <= form.start.toLocalTime())
}

/** `getFirstValidEndTime`: the earliest selectable slot for [date], else [preferred]. */
fun firstValidTime(
    date: LocalDate,
    startDate: LocalDate,
    startTime: LocalTime,
    now: LocalDateTime,
    creating: Boolean,
    preferred: LocalTime?,
): LocalTime = timeOptions(preferred ?: LocalTime.MIDNIGHT).firstOrNull {
    !(creating && date == now.toLocalDate() && it.time < now.minuteTime()) && !(date == startDate && it.time <= startTime)
}?.time ?: preferred ?: LocalTime.of(23, 59)

/** Start date input `onChange`. */
fun EventForm.withStartDate(date: LocalDate, now: LocalDateTime, creating: Boolean): EventForm {
    val current = start.toLocalTime()
    val time = if (creating && date.atTime(current).isBefore(now.truncatedTo(ChronoUnit.MINUTES))) {
        firstValidTime(date, date, now.minuteTime(), now, creating, current)
    } else current
    return withStart(date.atTime(time))
}

/** End date input `onChange`. */
fun EventForm.withEndDate(date: LocalDate, now: LocalDateTime, creating: Boolean): EventForm =
    copy(end = date.atTime(firstValidTime(date, start.toLocalDate(), start.toLocalTime(), now, creating, end.toLocalTime())))

/** "Add online meeting" on an all-day draft: meetings need times (the web rounds now up to the quarter). */
fun EventForm.forMeeting(now: LocalDateTime): EventForm {
    if (!allDay) return this
    val rounded = ((now.minute + 14) / 15) * 15
    val hour = if (rounded >= 60) now.hour + 1 else now.hour
    val startAt = start.toLocalDate().atStartOfDay().plusHours(hour.toLong()).plusMinutes((rounded % 60).toLong())
    return copy(allDay = false, start = startAt, end = startAt.plusHours(1))
}

/** The Single / Custom-days schedule starts, skipping occurrences in the past (`handleSave`). */
fun occurrenceStarts(form: EventForm, now: LocalDateTime): List<LocalDateTime> {
    val base = if (form.scheduleMode != "multi" || form.weekdays.isEmpty()) listOf(form.start) else {
        val monday = weekStart(form.start.toLocalDate())
        form.weekdays.filter { it in 0..6 }.distinct().sorted().map { monday.plusDays(it.toLong()).atTime(form.start.toLocalTime()) }
    }
    return base.filter { start ->
        if (form.allDay) !start.toLocalDate().isBefore(now.toLocalDate()) else !start.isBefore(now)
    }
}

/** Create-time guard (`handleSave` returns early for these). */
fun createBlocked(form: EventForm, now: LocalDateTime): Boolean =
    if (form.allDay) form.start.toLocalDate().isBefore(now.toLocalDate()) else form.start.isBefore(now)

fun toIso(time: LocalDateTime, zone: ZoneId): String = time.atZone(zone).toInstant().toString()

fun eventPayload(form: EventForm, start: LocalDateTime, end: LocalDateTime, zone: ZoneId, meetingId: Long? = null) = CalendarEventPayload(
    title = form.title,
    description = form.description,
    allDay = form.allDay,
    color = form.color,
    taskId = form.taskId,
    meetingId = meetingId,
    startTime = toIso(start, zone),
    endTime = toIso(end, zone),
)

fun eventColor(event: CalendarEvent, accent: String = DEFAULT_ACCENT): String =
    if (event.meetingCode != null) accent else event.color?.takeIf(String::isNotBlank) ?: accent

/** `#rrggbb` / `#rgb` → ARGB, falling back to the accent. */
fun parseHexColor(value: String?, fallback: Long = 0xFF2383E2): Long {
    val hex = value?.trim()?.removePrefix("#") ?: return fallback
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return fallback
    }
    return full.toLongOrNull(16)?.let { 0xFF000000 or it } ?: fallback
}
