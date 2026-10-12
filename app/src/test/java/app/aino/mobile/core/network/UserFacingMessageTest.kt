package app.aino.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun transientFailuresAreOfflineAndServerBlipsOnly() {
        fun http(code: Int) = ApiError.Http(code, "", "GET", "u")
        assertTrue(isTransientFailure(ApiError.Network("GET", "u", java.io.IOException())))
        assertTrue(isTransientFailure(http(500)))
        assertTrue(isTransientFailure(http(503)))
        assertTrue(isTransientFailure(http(408)))
        assertTrue(isTransientFailure(http(429)))
        assertFalse(isTransientFailure(http(400)))
        assertFalse(isTransientFailure(http(404)))
        assertFalse(isTransientFailure(IllegalStateException("boom")))
        // Feature repositories wrap the transport error.
        assertTrue(isTransientFailure(Exception("wrapped", http(502))))
        assertFalse(isTransientFailure(Exception("wrapped", http(403))))
    }

    @Test
    fun rawHttpMessagesAreNeverShown() {
        assertEquals("fallback", userFacingMessage(IllegalStateException("HTTP 400 for GET x"), "fallback"))
        assertEquals("Boom", userFacingMessage(IllegalStateException("Boom"), "fallback"))
    }
}
