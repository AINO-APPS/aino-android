package app.aino.mobile.core.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeModelsTest {
    @Test
    fun buildsAuthenticatedWebSocketUrl() {
        assertEquals(
            "wss://www.aino.org.in/ws?token=header.payload.signature",
            realtimeUrl("wss://www.aino.org.in", "header.payload.signature"),
        )
    }

    @Test
    fun preservesAnExplicitWsPathAndEncodesToken() {
        assertEquals(
            "wss://example.test/custom?token=a%20b",
            realtimeUrl("wss://example.test/custom", "a b"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpEndpoint() {
        realtimeUrl("https://www.aino.org.in", "token")
    }

    @Test
    fun reconnectBackoffCapsAtFifteenSeconds() {
        assertEquals(1_000L, reconnectCeiling(0))
        assertEquals(8_000L, reconnectCeiling(3))
        assertEquals(15_000L, reconnectCeiling(30))
        assertEquals(7_500L, reconnectDelay(30, 0.5))
    }

    @Test
    fun authTenantAndConnectionLimitClosesAreTerminal() {
        assertTrue(isTerminalRealtimeClose(4001))
        assertTrue(isTerminalRealtimeClose(4003))
        assertTrue(isTerminalRealtimeClose(4029))
        assertFalse(isTerminalRealtimeClose(1006))
    }

    @Test
    fun decodesTypedEnvelopeWithFlexibleData() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString<RealtimeEnvelope>(
            """{"type":"chat_message","data":{"id":4,"text":"hello"},"extra":true}""",
        )
        assertEquals("chat_message", envelope.type)
        assertEquals("hello", envelope.data?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }
}