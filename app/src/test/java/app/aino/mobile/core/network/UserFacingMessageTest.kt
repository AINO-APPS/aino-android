package app.aino.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFacingMessageTest {
    @Test
    fun httpErrorsSurfaceTheServerMessageNotTheTransportString() {
        val error = ApiError.Http(
            400,
            """{"error":"Organization context required. Please log in from your organization domain."}""",
            "GET",
            "https://next.aino.org.in/api/tracker/status",
        )
        assertEquals(
            "Organization context required. Please log in from your organization domain.",
            userFacingMessage(error, "fallback"),
        )
    }

    @Test
    fun httpErrorWithoutBodyUsesFallback() {
        val error = ApiError.Http(502, "<html>Bad gateway</html>", "GET", "https://x/api/tracker/status")
        assertEquals("Could not load", userFacingMessage(error, "Could not load"))
    }

    @Test
    fun rawHttpMessagesAreNeverShown() {
        assertEquals("fallback", userFacingMessage(IllegalStateException("HTTP 400 for GET x"), "fallback"))
        assertEquals("Boom", userFacingMessage(IllegalStateException("Boom"), "fallback"))
    }
}
