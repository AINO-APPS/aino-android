package app.aino.mobile.feature.home

import app.aino.mobile.core.common.TimeEntryDto
import app.aino.mobile.core.common.TrackerStatus
import app.aino.mobile.core.common.formatDuration

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DashboardRepositoryTest {
    @Test
    fun loadsStatusAndTaskSummary() {
        val api = ApiClient { request ->
            when (request.path) {
                "tracker/status" -> response("""{"state":"on_floor","floorMinutes":60,"breakMinutes":10,"workMode":"remote","targetMinutes":480,"entries":[]}""")
                "tracker/task-summary" -> response("""{"total":4,"done":1,"inProgress":2,"activeTasks":[{"title":"Ship Android","priority":"high"}]}""")
                "notifications/announcements" -> response("""{"data":[{"id":"tenant:1","message":"Welcome","type":"announcement"}]}""")
                else -> if (request.path.startsWith("calendar?")) response("[]") else error("unexpected ${request.path}")
            }
        }

        val result = DashboardRepository(api).load(123)

        assertEquals("on_floor", result.status.state)
        assertEquals("remote", result.status.workMode)
        assertEquals(4, result.tasks?.total)
        assertEquals("Ship Android", result.tasks?.activeTasks?.single()?.title)
        assertEquals("Welcome", result.announcements.single().message)
        assertEquals(123, result.loadedAtEpochMs)
    }

    @Test
    fun taskSummaryFailureDoesNotHideAttendanceStatus() {
        val api = ApiClient { request ->
            if (request.path == "tracker/status") response("""{"state":"logged_out"}""")
            else throw ApiError.Network("GET", request.path, java.io.IOException("offline"))
        }

        val result = DashboardRepository(api).load()

        assertEquals("logged_out", result.status.state)
        assertNull(result.tasks)
    }

    private fun response(json: String) = ApiResponse(200, emptyMap(), json.toByteArray())
}

class DashboardModelsTest {
    @Test
    fun dashboardWeekEndsWithTodayAcrossSevenDays() {
        val today = LocalDate.of(2026, 9, 17)
        val dates = dashboardWeek(today)
        assertEquals(7, dates.size)
        assertEquals(LocalDate.of(2026, 9, 11), dates.first())
        assertEquals(today, dates.last())
    }

    @Test
    fun dashboardGreetingTracksTimeOfDay() {
        assertEquals("Good Morning", dashboardGreeting(8))
        assertEquals("Good Afternoon", dashboardGreeting(14))
        assertEquals("Good Evening", dashboardGreeting(20))
    }

    @Test
    fun calculatesLiveFloorAndBreakDurations() {
        val status = TrackerStatus(
            state = "on_floor",
            floorMinutes = 105,
            breakMinutes = 15,
            entries = listOf(
                TimeEntryDto("clock_in", "2026-09-14T08:00:00Z"),
                TimeEntryDto("break_start", "2026-09-14T09:00:00Z"),
                TimeEntryDto("break_end", "2026-09-14T09:15:00Z"),
            ),
        )
        val now = java.time.Instant.parse("2026-09-14T10:15:00Z").toEpochMilli()

        val loadedAt = java.time.Instant.parse("2026-09-14T10:00:00Z").toEpochMilli()
        val (floor, breaks) = liveDurations(status, loadedAt, now)

        assertEquals(7_200, floor)
        assertEquals(900, breaks)
        assertEquals("02:00:00", formatDuration(floor))
    }

    @Test
    fun malformedEntryTimestampIsIgnored() {
        val status = TrackerStatus(state = "logged_out", entries = listOf(TimeEntryDto("clock_in", "invalid")))
        assertEquals(0L to 0L, liveDurations(status, 0, 0))
    }
}