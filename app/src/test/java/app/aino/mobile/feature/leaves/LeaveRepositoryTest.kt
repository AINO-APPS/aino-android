package app.aino.mobile.feature.leaves

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LeaveRepositoryTest {
    /** One repository wired to one stubbed response, capturing every request. */
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        LeaveRepository(
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
    fun balancesDecodeTheServerCoercedNumerics() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """[{"leave_type":"casual","year":2026,"quota":12,"used":3.5,"carried_forward":2,"policy_name":"Casual"}]"""
        }

        val balance = repository.loadBalances(2026).single()

        assertEquals("leaves/balance?year=2026", captured.single().path)
        assertEquals(10.5, balance.available, 0.0)
        assertEquals("Casual", balance.label())
    }

    @Test
    fun calendarSendsBothBoundsUrlEncoded() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { "[]" }

        repository.loadEvents("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")

        assertEquals(
            "calendar?from=2026-09-01T00%3A00%3A00Z&to=2026-10-01T00%3A00%3A00Z",
            captured.single().path,
        )
    }

    @Test
    fun applySendsTheDateArrayAndDuration() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"message":"2 leave(s) submitted","ids":[4,5]}""" }

        val result = repository.apply(
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

        repository.cancel(7)
        repository.withdraw(8)

        assertEquals("DELETE", captured[0].method)
        assertEquals("leaves/7", captured[0].path)
        assertEquals("POST", captured[1].method)
        assertEquals("leaves/8/withdraw", captured[1].path)
    }

    @Test
    fun surfacesTheServerPolicyMessageVerbatim() {
        val repository = LeaveRepository(
            ApiClient {
                throw ApiError.Http(
                    400,
                    """{"error":"Public holidays are organisation-wide and cannot be withdrawn individually. Ask HR to remove the holiday from the Holidays panel."}""",
                    "POST",
                    "https://next.aino.org.in/api/leaves/9/withdraw",
                )
            },
        )

        try {
            repository.withdraw(9)
            fail("expected a LeaveFailure")
        } catch (failure: LeaveFailure) {
            assertEquals(400, failure.statusCode)
            assertTrue(failure.message!!.startsWith("Public holidays are organisation-wide"))
        }
    }
}
