package app.aino.mobile.feature.attendance

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Leaves/analytics endpoint coverage added in P3 (was feature/leaves). */
class AttendanceLeavesRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        AttendanceRepository(
            ApiClient { request ->
                captured += request
                ApiResponse(200, emptyMap(), body(request).toByteArray())
            },
        )

    @Test
    fun loadsLeavesForAnExplicitRange() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """[{"id":1,"date":"2026-09-14","leave_type":"casual","duration":"half","status":"approved","reason":"Family"}]"""
        }

        val leaves = repository.loadLeaves("2026-09-01", "2026-09-30")

        assertEquals("leaves?start_date=2026-09-01&end_date=2026-09-30", captured.single().path)
        assertEquals("casual", leaves.single().leaveType)
        assertEquals("half", leaves.single().duration)
    }

    @Test
    fun balancesUseTheLeavePolicyRouteWithTolerantNumerics() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            // leave-policy/balances does NOT coerce NUMERIC — it arrives quoted.
            """[{"leave_type":"casual","year":"2026","quota":"12","used":"3.5","carried_forward":"2","policy_name":"Casual"}]"""
        }

        val balance = repository.loadLeaveBalances(2026).single()

        assertEquals("leave-policy/balances?year=2026", captured.single().path)
        assertEquals(10.5, balance.available, 0.0)
        assertEquals("Casual", balance.label())
    }

    @Test
    fun applySendsTheDateArrayAndDuration() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"message":"2 leave(s) submitted","ids":[4,5]}""" }

        val result = repository.applyLeave(
            ApplyLeavePayload("casual", listOf("2026-09-14", "2026-09-15"), "half", "Family"),
        )

        val body = captured.single().body!!.toString(Charsets.UTF_8)
        assertEquals("POST", captured.single().method)
        assertEquals("leaves", captured.single().path)
        assertTrue(body.contains("\"leave_type\":\"casual\""))
        assertTrue(body.contains("""["2026-09-14","2026-09-15"]"""))
        assertTrue(body.contains("\"duration\":\"half\""))
        assertEquals(listOf(4L, 5L), result.ids)
    }

    @Test
    fun cancelUsesDeleteAndWithdrawUsesPost() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"message":"Leave cancelled"}""" }

        repository.cancelLeave(7)
        repository.withdrawLeave(8)

        assertEquals("DELETE", captured[0].method)
        assertEquals("leaves/7", captured[0].path)
        assertEquals("POST", captured[1].method)
        assertEquals("leaves/8/withdraw", captured[1].path)
    }

    @Test
    fun surfacesTheServerPolicyMessageAndCodeVerbatim() {
        val repository = AttendanceRepository(
            ApiClient {
                throw ApiError.Http(
                    400,
                    """{"error":"Public holidays are organisation-wide and cannot be withdrawn individually. Ask HR to remove the holiday from the Holidays panel.","code":"PUBLIC_HOLIDAY_LOCKED"}""",
                    "POST",
                    "https://next.aino.org.in/api/leaves/9/withdraw",
                )
            },
        )

        try {
            repository.withdrawLeave(9)
            fail("expected an AttendanceFailure")
        } catch (failure: AttendanceFailure) {
            assertEquals(400, failure.statusCode)
            assertEquals("PUBLIC_HOLIDAY_LOCKED", failure.code)
            assertTrue(failure.message!!.startsWith("Public holidays are organisation-wide"))
        }
    }

    @Test
    fun analyticsAcceptsDaysOrAnExplicitRange() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            if (request.path == "tracker/widgets") {
                """{"avgFloorMinutes":480,"punctualityPercent":90,"attendancePercent":95,"targetMetDays":8,"workDays":10,"leaveCount":1}"""
            } else {
                """[{"date":"2026-09-14","floorMinutes":480,"breakMinutes":30,"workMode":"office"}]"""
            }
        }

        repository.loadAnalytics(14, null, null)
        repository.loadAnalytics(null, "2026-09-01", "2026-09-14")
        repository.loadWidgets()

        assertEquals("tracker/analytics?days=14", captured[0].path)
        assertEquals("tracker/analytics?from=2026-09-01&to=2026-09-14", captured[1].path)
        assertEquals("tracker/widgets", captured[2].path)
    }

    @Test
    fun clockInCarriesWifiAndGeoSignals() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"message":"Clocked in"}""" }

        repository.clockIn(
            WorkMode.Office,
            LocationProof(10.0, 20.0, 25f),
            fingerprintVerified = true,
            wifiBssid = "AA:BB:CC:DD:EE:FF",
        )

        val body = captured.single().body!!.toString(Charsets.UTF_8)
        assertEquals("tracker/clock-in", captured.single().path)
        assertTrue(body.contains("\"wifi_bssid\":\"AA:BB:CC:DD:EE:FF\""))
        assertTrue(body.contains("\"work_mode\":\"office\""))
        assertTrue(body.contains("\"fingerprint_verified\":true"))
    }
}
