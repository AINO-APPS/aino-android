package app.aino.mobile.feature.meeting

import app.aino.mobile.core.call.webrtc.IceCandidateSignal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingProtocolTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun descriptionSignalsCarryTheSessionDescriptionObject() {
        val offer = meetingDescriptionSignal("offer", "v=0")
        assertEquals("""{"type":"offer","sdp":{"type":"offer","sdp":"v=0"}}""", offer.toString())
        assertEquals(MeetingSignal.Offer("v=0"), parseMeetingSignal(offer))
        assertEquals(MeetingSignal.Answer("v=1"), parseMeetingSignal(obj("""{"type":"answer","sdp":"v=1"}""")))
        assertNull(parseMeetingSignal(obj("""{"type":"offer","sdp":{"type":"offer","sdp":""}}""")))
        assertNull(parseMeetingSignal(obj("""{"type":"rollback"}""")))
    }

    @Test
    fun candidatesRoundTrip() {
        val signal = meetingCandidateSignal(IceCandidateSignal("candidate:1 1 udp 1 1.2.3.4 5 typ host", "0", 0))
        assertEquals("candidate", obj(signal.toString())["type"].toString().trim('"'))
        assertEquals(
            MeetingSignal.Candidate(IceCandidateSignal("candidate:1 1 udp 1 1.2.3.4 5 typ host", "0", 0)),
            parseMeetingSignal(signal),
        )
    }

    @Test
    fun framesWrapMeetingId() {
        val frame = meetingFrame("meeting_raise_hand", 7) { put("raised", kotlinx.serialization.json.JsonPrimitive(true)) }
        assertEquals("meeting_raise_hand", frame.type)
        assertEquals("""{"meetingId":7,"raised":true}""", frame.data.toString())
    }

    @Test
    fun politenessIsLexicographicLikeTheWeb() {
        assertTrue(meetingPolite(9, 10)) // "9" > "10"
        assertFalse(meetingPolite(10, 9))
        assertFalse(meetingPolite(3, 3))
    }

    @Test
    fun gridAndBitrateFollowMeetingRoomCss() {
        assertEquals(listOf(1, 1, 2, 2, 2, 2, 3, 3, 3, 4), (1..10).map(::meetingGridColumns))
        assertEquals(listOf(1, 2, 2, 2, 3, 3, 3, 3, 3, 3), (1..10).map(::meetingGridRows))
        assertEquals(500_000, meetingVideoBitrate(3))
        assertEquals(300_000, meetingVideoBitrate(6))
        assertEquals(150_000, meetingVideoBitrate(7))
    }

    @Test
    fun timerMatchesFormatDuration() {
        assertEquals("0:05", meetingTimer(5))
        assertEquals("12:00", meetingTimer(720))
        assertEquals("1:02:03", meetingTimer(3723))
    }

    @Test
    fun chatMergeReplacesOptimisticRowAndDedupesReplays() {
        val optimistic = MeetingChatMessage(clientMsgId = "c1", senderId = 3, text = "hi", delivery = ChatDelivery.Sending)
        val server = obj("""{"id":10,"clientMsgId":"c1","sender_id":3,"sender_name":"Priya","text":"hi","created_at":"t"}""").toMeetingChat()
        val merged = listOf(optimistic).mergeMessage(server)
        assertEquals(1, merged.size)
        assertEquals(10L, merged.single().id)
        assertEquals(ChatDelivery.Sent, merged.single().delivery)

        val replay = obj("""{"id":10,"sender_id":3,"text":"hi"}""").toMeetingChat()
        assertEquals(1, merged.mergeMessage(replay).size)
        assertEquals("c1", merged.mergeMessage(replay).single().clientMsgId)
        assertEquals(2, merged.mergeMessage(replay.copy(id = 11)).size)
    }

    @Test
    fun chatTextAndSizesFollowTheWeb() {
        assertNull(normalizeMeetingChat("   "))
        assertEquals(5000, normalizeMeetingChat("x".repeat(6000))!!.length)
        assertEquals("512 B", meetingFileSize(512))
        assertEquals("2.0 KB", meetingFileSize(2048))
        assertEquals("1.5 MB", meetingFileSize(1572864))
    }

    @Test
    fun meetingStartedAnnouncementParses() {
        val a = meetingAnnouncement(obj("""{"meetingId":7,"meetingCode":"abc","title":"Standup","organizerName":"Priya","restarted":true}"""))!!
        assertEquals(MeetingAnnouncement(7, "abc", "Standup", "Priya", true), a)
        assertNull(meetingAnnouncement(obj("""{"meetingId":7}""")))
    }
}
