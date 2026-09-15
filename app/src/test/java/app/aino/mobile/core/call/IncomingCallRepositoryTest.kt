package app.aino.mobile.core.call

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallRepositoryTest {
    @Test
    fun answerAndDeclineUseIdempotentPlatformEndpoints() {
        val captured = mutableListOf<ApiRequest>()
        val api = ApiClient { request ->
            captured += request
            ApiResponse(200, emptyMap(), """{"ok":true,"status":"answered"}""".toByteArray())
        }
        val route = IncomingCallRoute(301, 31, 6, "Priya", null, "voice")
        val answer = buildCallActionRequest(route, "accept")
        val decline = buildCallActionRequest(route, "reject")

        api.execute(answer)
        api.execute(decline)

        assertEquals("chat/calls/301/accept", captured[0].path)
        assertEquals("chat/calls/301/reject", captured[1].path)
        assertTrue(captured.all { it.method == "POST" })
        assertTrue(captured[0].body!!.toString(Charsets.UTF_8).contains("\"conversationId\":31"))
    }
}