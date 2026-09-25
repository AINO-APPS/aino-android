package app.aino.mobile.feature.calendar

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarLogicTest {
    private val utc = ZoneOffset.UTC
    private fun event(id: Long, start: String, end: String, allDay: Boolean = false) =
        CalendarEvent(id = id, title = "E$id", startTime = start, endTime = end, allDay = allDay)

    @Test
    fun weeksStartOnMondayAndMonthsSpanSixWeeks() {
        val thursday = LocalDate.of(2026, 9, 24)
        assertEquals(LocalDate.of(2026, 9, 21), weekDays(thursday).first())
        assertEquals(LocalDate.of(2026, 9, 27), weekDays(thursday).last())
        val month = monthDays(thursday)
        assertEquals(42, month.size)
        assertEquals(LocalDate.of(2026, 8, 31), month.first())
        assertEquals(LocalDate.of(2026, 9, 21) to LocalDate.of(2026, 9, 28), visibleRange(CalendarView.Week, thursday))
    }

    @Test
    fun titlesMatchTheWebFormats() {
        assertEquals("Thursday, September 24, 2026", calendarTitle(CalendarView.Day, LocalDate.of(2026, 9, 24)))
        assertEquals("September 2026", calendarTitle(CalendarView.Month, LocalDate.of(2026, 9, 24)))
        assertEquals("September 21 – 27, 2026", calendarTitle(CalendarView.Week, LocalDate.of(2026, 9, 24)))
        assertEquals("September 28 – October 4, 2026", calendarTitle(CalendarView.Week, LocalDate.of(2026, 10, 1)))
        assertEquals("12 AM", formatHour(0))
        assertEquals("12 PM", formatHour(12))
        assertEquals("3 PM", formatHour(15))
        assertEquals("9:45 AM", timeLabel(LocalTime.of(9, 45)))
        assertEquals("12:00 AM", timeLabel(LocalTime.MIDNIGHT))
    }

    @Test
    fun overlappingEventsShareColumns() {
        val day = LocalDate.of(2026, 9, 24)
        val events = listOf(
            event(1, "2026-09-24T09:00:00Z", "2026-09-24T11:00:00Z"),
            event(2, "2026-09-24T10:00:00Z", "2026-09-24T10:30:00Z"),
            event(3, "2026-09-24T12:00:00Z", "2026-09-24T13:00:00Z"),
        )
        val layout = layoutEvents(events, day, utc).associateBy { it.event.id }
        assertEquals(0, layout.getValue(1).col)
        assertEquals(1, layout.getValue(2).col)
        assertEquals(2, layout.getValue(1).total)
        assertEquals(1, layout.getValue(3).total)
        assertEquals(540f, layout.getValue(1).startMin)
        assertEquals(3, eventsForDay(events, day, utc).size)
        assertTrue(eventsForDay(events, day.plusDays(1), utc).isEmpty())
    }

    @Test
    fun createRoundsToTheNextQuarterAndRefusesThePast() {
        val now = LocalDateTime.of(2026, 9, 24, 10, 7, 30)
        val fromButton = createForm(LocalDate.of(2026, 9, 24), null, now)!!
        assertEquals(LocalDateTime.of(2026, 9, 24, 10, 15), fromButton.start)
        assertEquals(LocalDateTime.of(2026, 9, 24, 11, 15), fromButton.end)
        assertEquals(listOf(3), fromButton.weekdays)
        assertNull(createForm(LocalDate.of(2026, 9, 23), null, now))
        assertNull(createForm(LocalDate.of(2026, 9, 24), 9, now))
        assertEquals(LocalDateTime.of(2026, 9, 24, 14, 0), createForm(LocalDate.of(2026, 9, 24), 14, now)!!.start)
    }

    @Test
    fun startChangePushesTheEnd() {
        val form = EventForm(start = LocalDateTime.of(2026, 9, 24, 10, 0), end = LocalDateTime.of(2026, 9, 24, 11, 0))
        assertEquals(LocalDateTime.of(2026, 9, 24, 12, 30), form.withStart(LocalDateTime.of(2026, 9, 24, 11, 30)).end)
        assertEquals(LocalDateTime.of(2026, 9, 24, 11, 0), form.withStart(LocalDateTime.of(2026, 9, 24, 10, 30)).end)
    }

    @Test
    fun timeOptionsIncludeOffGridValuesAndDisablePastSlots() {
        assertEquals(96, timeOptions(LocalTime.of(10, 0)).size)
        assertEquals(97, timeOptions(LocalTime.of(10, 7)).size)
        val now = LocalDateTime.of(2026, 9, 24, 10, 7)
        val form = EventForm(start = LocalDateTime.of(2026, 9, 24, 10, 15), end = LocalDateTime.of(2026, 9, 24, 11, 15))
        assertTrue(startTimeDisabled(LocalTime.of(10, 0), form, now, creating = true))
        assertFalse(startTimeDisabled(LocalTime.of(10, 0), form, now, creating = false))
        assertTrue(endTimeDisabled(LocalTime.of(10, 15), form, now, creating = false))
        assertFalse(endTimeDisabled(LocalTime.of(10, 30), form, now, creating = false))
    }

    @Test
    fun customDaysCreateOneOccurrencePerFutureWeekday() {
        val now = LocalDateTime.of(2026, 9, 23, 9, 0) // Wednesday
        val form = EventForm(
            start = LocalDateTime.of(2026, 9, 24, 10, 0), end = LocalDateTime.of(2026, 9, 24, 11, 0),
            scheduleMode = "multi", weekdays = listOf(4, 0, 3, 3),
        )
        // Monday (0) is already past this week; Thursday (3) and Friday (4) remain.
        assertEquals(
            listOf(LocalDateTime.of(2026, 9, 24, 10, 0), LocalDateTime.of(2026, 9, 25, 10, 0)),
            occurrenceStarts(form, now),
        )
        assertEquals(listOf(form.start), occurrenceStarts(form.copy(scheduleMode = "single"), now))
    }

    @Test
    fun enablingAMeetingOnAnAllDayDraftPicksTimes() {
        val form = EventForm(start = LocalDateTime.of(2026, 9, 24, 0, 0), end = LocalDateTime.of(2026, 9, 25, 0, 0), allDay = true)
        val meeting = form.forMeeting(LocalDateTime.of(2026, 9, 24, 10, 50))
        assertFalse(meeting.allDay)
        assertEquals(LocalDateTime.of(2026, 9, 24, 11, 0), meeting.start)
        assertEquals(LocalDateTime.of(2026, 9, 24, 12, 0), meeting.end)
    }

    @Test
    fun colorsFallBackToTheAccent() {
        assertEquals(0xFF2383E2, parseHexColor(null))
        assertEquals(0xFFFF0000, parseHexColor("#f00"))
        assertEquals(0xFF6366F1, parseHexColor("#6366f1"))
        assertEquals(DEFAULT_ACCENT, eventColor(event(1, "2026-09-24T09:00:00Z", "2026-09-24T10:00:00Z").copy(color = "#ff0000", meetingCode = "abc")))
    }
}

class CalendarRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        CalendarRepository(ApiClient { request -> captured += request; ApiResponse(200, emptyMap(), body(request).toByteArray()) })

    @Test
    fun routesAndPayloadsMatchTheServer() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            when {
                request.path.startsWith("calendar?") -> """[{"id":7,"title":"Standup","start_time":"2026-09-24T09:00:00.000Z","end_time":"2026-09-24T09:15:00.000Z","all_day":false,"color":"#2383e2","meeting_code":"abc-def","meeting_created_by":3}]"""
                request.path == "meetings" -> """{"id":41,"meeting_code":"abc-def"}"""
                request.path == "meetings/check-conflicts" -> """{"conflicts":[{"userId":5,"name":"Asha","events":[{"id":1,"title":"Review"}]}]}"""
                request.path.startsWith("meetings/") -> """{"id":41,"participants":[{"user_id":3,"full_name":"Vishnu","role":"organizer","participant_type":"required"}]}"""
                request.path.startsWith("tasks?") -> """{"tasks":[{"id":9,"title":"Ship"}],"stats":{}}"""
                request.path.startsWith("chat/search") -> """[{"id":5,"full_name":"Asha K","email":"a@x.io"}]"""
                else -> """{"id":7,"title":"Standup","start_time":"2026-09-24T09:00:00Z","end_time":"2026-09-24T09:15:00Z"}"""
            }
        }

        val events = repository.events("2026-09-21T00:00:00Z", "2026-09-28T00:00:00Z")
        val payload = eventPayload(
            EventForm(title = "Standup", start = LocalDateTime.of(2026, 9, 24, 9, 0), end = LocalDateTime.of(2026, 9, 24, 9, 15)),
            LocalDateTime.of(2026, 9, 24, 9, 0), LocalDateTime.of(2026, 9, 24, 9, 15), ZoneOffset.UTC, meetingId = 41,
        )
        repository.create(payload)
        repository.update(7, payload)
        repository.delete(7)
        val meeting = repository.createMeeting(
            CreateMeetingRequest("Standup", null, listOf(5), emptyList(), MeetingSettings(), "2026-09-24T09:00:00Z", "2026-09-24T09:15:00Z"),
        )
        val conflicts = repository.conflicts(ConflictRequest(listOf(5), "a", "b"))
        val detail = repository.meeting("abc-def")
        val tasks = repository.linkableTasks("2026-09-24")
        val people = repository.searchPeople("as")

        assertEquals("calendar?from=2026-09-21T00%3A00%3A00Z&to=2026-09-28T00%3A00%3A00Z", captured[0].path)
        assertEquals("abc-def", events.single().meetingCode)
        assertEquals(3L, events.single().meetingCreatedBy)
        assertEquals("POST", captured[1].method)
        assertEquals(
            """{"title":"Standup","description":"","all_day":false,"color":"#2383e2","task_id":null,"meeting_id":41,"start_time":"2026-09-24T09:00:00Z","end_time":"2026-09-24T09:15:00Z"}""",
            captured[1].body!!.toString(Charsets.UTF_8),
        )
        assertEquals("PUT" to "calendar/7", captured[2].method to captured[2].path)
        assertEquals("DELETE" to "calendar/7", captured[3].method to captured[3].path)
        assertEquals(41L, meeting.id)
        assertTrue(captured[4].body!!.toString(Charsets.UTF_8).contains(""""required_participant_ids":[5]"""))
        assertEquals("Asha", conflicts.conflicts.single().name)
        assertEquals("organizer", detail.participants.single().role)
        assertEquals("tasks?date=2026-09-24&scope=personal&include_due=1", captured[7].path)
        assertEquals("Ship", tasks.single().title)
        assertEquals("Asha K", people.single().display())
    }
}
