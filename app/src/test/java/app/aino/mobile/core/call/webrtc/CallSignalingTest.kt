package app.aino.mobile.core.call.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalingTest {
    @Test
    fun buildsPlatformCompatibleOfferEnvelope() {
        val envelope = callSignalEnvelope(
            CallSignalCommand(10, 20, 30, CallSignal("offer", sdp = "v=0", signalId = "sig-1")),
        )
        assertEquals("call_signal", envelope.type)
        val json = envelope.data.toString()
        assertTrue(json.contains("\"callId\":10"))
        assertTrue(json.contains("\"targetUserId\":30"))
        assertTrue(json.contains("\"type\":\"offer\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOfferWithoutSdp() {
        callSignalEnvelope(CallSignalCommand(1, 2, 3, CallSignal("offer")))
    }

    @Test
    fun buildsReliableDeliveryHandshakeFrames() {
        assertEquals("call_subscribe", callSubscribeEnvelope(1, 2).type)
        assertEquals("call_ready", callReadyEnvelope(1, 2).type)
    }

    @Test
    fun buildsIdempotentCallInitiateFrame() {
        val envelope = callInitiateEnvelope(20, "video", "client-1")
        assertEquals("call_initiate", envelope.type)
        val json = envelope.data.toString()
        assertTrue(json.contains("\"conversationId\":20"))
        assertTrue(json.contains("\"callType\":\"video\""))
        assertTrue(json.contains("\"clientMsgId\":\"client-1\""))
    }

    @Test
    fun mediaStateSignalsOmitUnusedFields() {
        val audio = callSignalEnvelope(CallSignalCommand(1, 2, 3, CallSignal("audio-state", muted = true)))
        assertEquals("""{"callId":1,"conversationId":2,"targetUserId":3,"signal":{"type":"audio-state","muted":true}}""", audio.data.toString())
        val video = callSignalEnvelope(CallSignalCommand(1, 2, 3, CallSignal("video-state", videoOff = false)))
        assertTrue(video.data.toString().contains("\"videoOff\":false"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAudioStateWithoutMuted() {
        callSignalEnvelope(CallSignalCommand(1, 2, 3, CallSignal("audio-state")))
    }

    @Test
    fun buildsTerminationAndHuddleDeclineFrames() {
        assertEquals("""{"callId":5,"conversationId":6,"clientMsgId":"m"}""", callEndEnvelope(5, 6, "m").data.toString())
        assertEquals("call_cancel", callCancelEnvelope(6, "m").type)
        val decline = huddleDeclineEnvelope(9, nowMs = 42)
        assertEquals("huddle_decline", decline.type)
        assertEquals("""{"meetingId":9,"clientMsgId":"hd-9-42"}""", decline.data.toString())
    }
}