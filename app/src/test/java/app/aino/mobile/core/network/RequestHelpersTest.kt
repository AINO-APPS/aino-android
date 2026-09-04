package app.aino.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.SimpleTimeZone

class RequestHelpersTest {
    @Test
    fun standardHeaders_addsRequiredHeadersAndBearerToken() {
        val headers = standardHeaders(" token-value ", 330)

        assertEquals("Bearer token-value", headers[RequestHeaders.AUTHORIZATION])
        assertEquals("AINO", headers[RequestHeaders.REQUESTED_WITH])
        assertEquals("330", headers[RequestHeaders.TIMEZONE_OFFSET])
    }

    @Test
    fun standardHeaders_omitsAuthorizationForMissingToken() {
        assertFalse(standardHeaders("  ", 0).containsKey(RequestHeaders.AUTHORIZATION))
    }

    @Test
    fun timezoneOffset_usesJavascriptUtcMinusLocalConvention() {
        assertEquals(-330, timezoneOffsetMinutes(SimpleTimeZone(330 * 60_000, "IST"), 0L))
        assertEquals(480, timezoneOffsetMinutes(SimpleTimeZone(-480 * 60_000, "PST"), 0L))
    }

    @Test
    fun resolveApiUrl_joinsRelativePathsWithoutDroppingApiPrefix() {
        assertEquals(
            "https://example.com/api/users",
            resolveApiUrl("https://example.com/api/", "/users"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolveApiUrl_rejectsAbsoluteUrlToProtectAuthorizationHeader() {
        resolveApiUrl("https://example.com/api", "https://untrusted.example/secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolveApiUrl_rejectsProtocolRelativeUrlToProtectAuthorizationHeader() {
        resolveApiUrl("https://example.com/api", "//untrusted.example/secret")
    }
}
