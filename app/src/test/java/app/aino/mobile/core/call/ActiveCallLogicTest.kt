package app.aino.mobile.core.call

import app.aino.mobile.core.call.webrtc.fallbackIceConfig
import app.aino.mobile.core.call.webrtc.pcm16Level
import app.aino.mobile.core.realtime.RealtimeEnvelope
import app.aino.mobile.core.navigation.huddleRoute
import app.aino.mobile.core.navigation.meetingRoomRoute
import app.aino.mobile.core.navigation.webLinkToRoute
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCallLogicTest {
    @Test
    fun durationMatchesCallDurationTsx() {
        assertEquals("00:07", formatCallDuration(7))
        assertEquals("10:00", formatCallDuration(600))
        assertEquals("1:00:05", formatCallDuration(3605))
        assertEquals("00:00", formatCallDuration(-3))
    }

    @Test
    fun statusLineFollowsCallOverlayCopy() {
        assertEquals("Ringing...", ActiveCallUi(visible = true).statusText)
        assertEquals("Connecting...", ActiveCallUi(visible = true, accepted = true).statusText)
        assertEquals("Connecting...", ActiveCallUi(visible = true, incoming = true).statusText)
        assertEquals("Reconnecting...", ActiveCallUi(visible = true, accepted = true, phase = CallPhase.Reconnecting).statusText)
        assertEquals("No answer", ActiveCallUi(visible = true, endMessage = "No answer").statusText)
    }

    @Test
    fun groupCallRingCarriesTheMeeting() {
        val envelope = RealtimeEnvelope(
            "call_incoming",
            Json.parseToJsonElement(
                """{"callId":7,"conversationId":41,"callerId":3,"callerName":"Priya","callType":"video","isGroup":true,"groupName":"Team","meetingCode":"abc","meetingId":7,"isHuddle":true}""",
            ),
        )
        val incoming = CallRealtimeRouter.decode(envelope) as CallRealtimeEvent.Incoming
        assertEquals("abc", incoming.meetingCode)
        assertEquals(7L, incoming.meetingId)
        val route = parseIncomingCallRoute("aino://call/41?callId=7&callType=voice&meetingCode=abc&meetingId=7")!!
        assertEquals("abc", route.meetingCode)
        assertEquals(7L, route.meetingId)
        assertNull(parseIncomingCallRoute("aino://call/41?callId=7")!!.meetingCode)
    }

    @Test
    fun callStartedKeepsTheCallType() {
        val started = CallRealtimeRouter.decode(
            RealtimeEnvelope("call_started", Json.parseToJsonElement("""{"callId":5,"conversationId":6,"callType":"video"}""")),
        ) as CallRealtimeEvent.Started
        assertEquals("video", started.callType)
    }

    @Test
    fun meetingAndHuddleLinksMapToRoutes() {
        assertEquals(meetingRoomRoute("abc"), webLinkToRoute("/meeting/abc/room"))
        assertEquals("meeting/abc", webLinkToRoute("/meeting/abc"))
        assertEquals(huddleRoute("abc"), webLinkToRoute("/huddle/abc"))
    }

    @Test
    fun micLevelIsRmsOfPcm16() {
        assertEquals(0f, pcm16Level(ByteArray(0)))
        assertEquals(0f, pcm16Level(ByteArray(64)))
        // Full-scale square wave clamps to 1.
        val loud = ByteArray(64) { if (it % 2 == 0) 0xFF.toByte() else 0x7F }
        assertEquals(1f, pcm16Level(loud))
    }

    @Test
    fun fallbackIceHasStunAndRelay() {
        val urls = fallbackIceConfig().iceServers.flatMap { it.urls.values }
        assertTrue(urls.first().startsWith("stun:"))
        assertTrue(urls.any { it.startsWith("turn:") })
    }
}
