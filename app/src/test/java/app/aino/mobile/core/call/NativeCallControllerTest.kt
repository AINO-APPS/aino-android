package app.aino.mobile.core.call

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import org.junit.Assert.assertNull

class NativeCallControllerTest {
    @Test
    fun privateIncomingCallDoesNotInventCallerIdentity() {
        val extras = incomingCallServiceExtras(
            mapOf(
                "title" to "Incoming Voice Call",
                "body" to "Tap to answer",
                "callId" to "301",
                "conversationId" to "31",
                "callerId" to "6",
                "callType" to "voice",
            ),
            "jwt",
        )
        assertEquals("", extras[CallRingService.EXTRA_CALLER_NAME])
        assertEquals("", extras[CallRingService.EXTRA_CALLER_AVATAR])
        assertEquals("jwt", extras[CallRingService.EXTRA_TOKEN])
        assertEquals("aino", extras[CallRingService.EXTRA_SCHEME])
    }

    @Test
    fun parsesAnswerAndDeclineDeepLinks() {
        val answer = parseIncomingCallRoute("aino://call/31?callId=301&callType=video&peerId=6&peerName=Priya&expiresAt=2026-09-15T00%3A00%3A30Z&autoAnswer=1")!!
        assertEquals(301, answer.callId)
        assertEquals(31, answer.conversationId)
        assertEquals("video", answer.callType)
        assertEquals("answer", answer.action)
        assertEquals("2026-09-15T00:00:30Z", answer.expiresAt)

        val decline = parseIncomingCallRoute("aino://call/31?callId=301&action=decline")!!
        assertEquals("decline", decline.action)
        assertNull(parseIncomingCallRoute("https://example.test/call/31?callId=301"))
    }

    @Test
    fun ringWindowIsCappedAtThirtySecondsAndExpires() {
        val now = Instant.parse("2026-09-15T00:00:00Z")
        assertEquals(30_000, remainingRingMillis("2026-09-15T00:01:00Z", now))
        assertEquals(15_000, remainingRingMillis("2026-09-15T00:00:15Z", now))
        assertEquals(0, remainingRingMillis("2026-09-14T23:59:59Z", now))
    }
}