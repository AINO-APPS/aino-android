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
}