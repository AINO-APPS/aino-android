package app.aino.mobile.feature.attendance

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceRepositoryTest {
    @Test
    fun loadsHistoryWithVisibleGridRange() {
        var captured: ApiRequest? = null
        val repository = AttendanceRepository(ApiClient { request ->
            captured = request
            ApiResponse(
                200,
                emptyMap(),
                """[{"date":"2026-09-14","floorMinutes":480,"breakMinutes":30,"totalMinutes":510,"workMode":"office","entries":[]}]""".toByteArray(),
            )
        })

        val result = repository.loadHistory(MonthRange(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 10, 10)))

        assertEquals("tracker/history?from=2026-08-30&to=2026-10-10", captured?.path)
        assertEquals(480, result.single().floorMinutes)
        assertEquals("office", result.single().workMode)
    }

    @Test
    fun nullOrganizationUsesSafeDefaultPolicy() {
        val repository = AttendanceRepository(ApiClient {
            ApiResponse(200, emptyMap(), "null".toByteArray())
        })
        assertEquals(false, repository.loadPolicy().verificationEnabled)
        assertEquals("1,2,3,4,5", repository.loadPolicy().workDays)
    }

    @Test
    fun loadsManualAndOvertimeRequests() {
        val repository = AttendanceRepository(ApiClient { request ->
            val body = when (request.path) {
                "tracker/manual-entries" -> """[{"request_id":1,"approval_status":"pending","metadata":{"date":"2026-09-14","clock_in":"09:00","clock_out":"17:00"}}]"""
                "tracker/overtime-requests" -> """[{"id":2,"status":"approved","reason":"Release","metadata":{"date":"2026-09-14","hours":2.5}}]"""
                else -> error("unexpected ${request.path}")
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })

        assertEquals("09:00", repository.loadManualRequests().single().metadata?.clockIn)
        assertEquals(2.5, repository.loadOvertimeRequests().single().metadata?.hours)
    }

    @Test
    fun submitsTypedManualEntryAndOvertimePayloads() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AttendanceRepository(ApiClient { request ->
            captured += request
            ApiResponse(200, emptyMap(), """{"message":"submitted"}""".toByteArray())
        })

        repository.submitManualEntry(ManualEntryPayload("2026-09-14", "09:00", "17:00", -330, "office"))
        repository.submitOvertime(OvertimePayload("2026-09-14", 2.0, "Release"))

        assertEquals("tracker/manual-entry", captured[0].path)
        assertTrue(captured[0].body!!.toString(Charsets.UTF_8).contains("\"clock_in\":\"09:00\""))
        assertEquals("tracker/overtime-request", captured[1].path)
        assertTrue(captured[1].body!!.toString(Charsets.UTF_8).contains("\"hours\":2.0"))
    }

    @Test
    fun loadsLeaveAndHolidayOverlaysFromTheirPlatformRoutes() {
        val captured = mutableListOf<String>()
        val repository = AttendanceRepository(ApiClient { request ->
            captured += request.path
            val body = if (request.path.startsWith("leaves?")) {
                """[{"id":1,"date":"2026-09-14T00:00:00.000Z","leave_type":"casual","duration":"full","status":"approved"}]"""
            } else {
                """[{"id":2,"date":"2026-09-15","name":"Foundation Day","is_optional":false}]"""
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val range = MonthRange(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 10, 10))

        val leaves = repository.loadLeaves(range)
        val holidays = repository.loadHolidays(2026)

        assertEquals("leaves?start_date=2026-08-30&end_date=2026-10-10", captured[0])
        assertEquals("leave-policy/holidays?year=2026", captured[1])
        assertEquals("casual", leaves.single().leaveType)
        assertEquals("Foundation Day", holidays.single().name)
    }

    @Test
    fun loadsRawEntriesAndUsesPutForExistingManualDay() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AttendanceRepository(ApiClient { request ->
            captured += request
            val body = if (request.method == "GET") {
                """[{"id":1,"entry_type":"clock_in","timestamp":"2026-09-14T09:00:00Z","work_mode":"office"}]"""
            } else """{"message":"updated"}"""
            ApiResponse(200, emptyMap(), body.toByteArray())
        })

        assertEquals("clock_in", repository.loadEntries("2026-09-14").single().entryType)
        repository.updateManualEntry(
            ManualEntryPayload(
                "2026-09-14",
                "09:00",
                "17:00",
                -330,
                "office",
                listOf(ManualBreakPayload("12:00", "12:30")),
            ),
        )

        assertEquals("tracker/entries/2026-09-14", captured[0].path)
        assertEquals("PUT", captured[1].method)
        assertEquals("tracker/manual-entry/2026-09-14", captured[1].path)
        assertTrue(captured[1].body!!.toString(Charsets.UTF_8).contains("\"breaks\":[{\"start\":\"12:00\",\"end\":\"12:30\"}]"))
    }
}