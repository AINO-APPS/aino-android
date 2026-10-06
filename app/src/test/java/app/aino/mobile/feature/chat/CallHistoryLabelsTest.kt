package app.aino.mobile.feature.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryLabelsTest {
    private val me = 1L
    private val peer = 2L

    @Test
    fun outgoingLabels() {
        assertEquals(CallHistoryLabel("Outgoing voice call", "3:05", outgoing = true, video = false, danger = false), callHistoryLabel("voice", "ended", 185, me, me))
        assertEquals(CallHistoryLabel("Outgoing voice call", "No answer", outgoing = true, video = false, danger = false), callHistoryLabel("voice", "missed", null, me, me))
        assertEquals(CallHistoryLabel("Outgoing video call", "Declined", outgoing = true, video = true, danger = false), callHistoryLabel("video", "declined", null, me, me))
    }

    @Test
    fun incomingLabels() {
        assertEquals(CallHistoryLabel("Incoming voice call", "0:42", outgoing = false, video = false, danger = false), callHistoryLabel("voice", "ended", 42, peer, me))
        assertEquals(CallHistoryLabel("Missed video call", null, outgoing = false, video = true, danger = true), callHistoryLabel("video", "missed", null, peer, me))
        assertEquals(CallHistoryLabel("Declined voice call", null, outgoing = false, video = false, danger = false), callHistoryLabel("voice", "declined", null, peer, me))
    }

    @Test
    fun liveAndLegacyStatuses() {
        assertEquals("Ongoing", callHistoryLabel("voice", "ringing", null, me, me).detail)
        assertEquals("Ongoing", callHistoryLabel("video", "answered", null, peer, me).detail)
        assertEquals("Declined voice call", callHistoryLabel("voice", "rejected", null, peer, me).title)
        // Unknown signed-in user: everything reads as incoming.
        assertFalse(callHistoryLabel("voice", "ended", 5, me, null).outgoing)
        // A zero-length ended call shows no duration.
        assertNull(callHistoryLabel("voice", "ended", 0, me, me).detail)
        assertEquals("1:01:01", formatCallHistoryDuration(3661))
    }

    @Test
    fun callLogUsesCallerDirection() {
        val log = CallLog(id = 1, conversationId = 7, callerId = peer, callType = "video", status = "missed")
        assertEquals("Missed video call", log.historyLabel(me).title)
        assertTrue(log.historyLabel(me).danger)
        assertEquals("Outgoing video call", log.historyLabel(peer).title)
    }

    @Test
    fun callSystemMessageBecomesAChip() {
        val meta = Json.parseToJsonElement("""{"type":"call","callType":"voice","status":"ended","duration":65,"callerId":1}""")
        val message = ChatMessage(id = 5, senderId = 1, content = "Voice call", createdAt = "2026-10-06T10:00:00Z", formatType = "system", metadata = meta)
        assertTrue(isCallHistoryMessage("system", meta))
        assertEquals(CallHistoryLabel("Outgoing voice call", "1:05", outgoing = true, video = false, danger = false), message.callHistoryLabel(me))
        assertEquals("Incoming voice call", message.callHistoryLabel(peer)?.title)

        // callerId missing: the sender is the caller.
        val noCaller = message.copy(senderId = peer, metadata = Json.parseToJsonElement("""{"type":"call","callType":"video","status":"missed"}"""))
        assertEquals("Missed video call", noCaller.callHistoryLabel(me)?.title)

        // Other system messages keep their plain rendering.
        val other = message.copy(metadata = Json.parseToJsonElement("""{"text":"Priya joined"}"""))
        assertNull(other.callHistoryLabel(me))
        assertFalse(isCallHistoryMessage("text", meta))
    }
}
