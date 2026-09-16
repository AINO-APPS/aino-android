package app.aino.mobile.core.call

import app.aino.mobile.core.realtime.RealtimeEnvelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRealtimeRouterTest {
    @Test
    fun decodesDeployedIncomingShape() {
        val event = decode(
            "call_incoming",
            """{"callId":41,"conversationId":7,"callerId":9,"callerName":"Priya","callerAvatar":null,"callType":"video","isGroup":false}""",
        ) as CallRealtimeEvent.Incoming

        assertEquals(41L, event.callId)
        assertEquals(7L, event.conversationId)
        assertEquals(9L, event.callerId)
        assertEquals("Priya", event.callerName)
        assertEquals("video", event.callType)
    }

    @Test
    fun decodesEventsMissingCallIdWhereServerOmitsIt() {
        val busy = decode("call_busy", """{"conversationId":7,"targetUserId":9,"reason":"busy"}""")
        assertTrue(busy is CallRealtimeEvent.Busy)
        val signal = decode(
            "call_signal",
            """{"conversationId":7,"fromUserId":9,"signal":{"type":"offer","sdp":"v=0","signalId":"s-1"}}""",
        ) as CallRealtimeEvent.Signal
        assertEquals("s-1", signal.signalId)
    }

    @Test
    fun rejectsMalformedOrUnknownFrames() {
        assertNull(decode("call_incoming", """{"conversationId":7}"""))
        assertNull(decode("call_handled_elsewhere", """{"callId":4,"conversationId":7,"action":"other"}"""))
        assertNull(decode("chat_message", """{"id":1}"""))
    }

    private fun decode(type: String, data: String): CallRealtimeEvent? = CallRealtimeRouter.decode(
        RealtimeEnvelope(type, Json.parseToJsonElement(data)),
    )
}