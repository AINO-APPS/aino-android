package app.aino.mobile.feature.meeting

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        MeetingRepository(
            ApiClient { request ->
                captured += request
                ApiResponse(200, emptyMap(), body(request).toByteArray())
            },
        )

    @Test
    fun meetingByCodeDecodesOrganizerParticipantsAndSettings() {
        val captured = mutableListOf<ApiRequest>()
        val meeting = repository(captured) {
            """{"id":7,"org_id":2,"title":"Standup","description":null,"meeting_code":"abc-defg-hij","created_by":3,
               "conversation_id":41,"calendar_event_id":null,"settings":{"callType":"video","preset":"standard"},
               "is_huddle":true,"status":"active","started_at":"2026-09-25T06:00:00.000Z","ended_at":null,
               "organizer_name":"Priya","organizer_avatar":"/uploads/a.png","calendar_title":null,
               "participants":[{"id":1,"meeting_id":7,"user_id":3,"role":"host","status":"joined","full_name":"Priya","avatar":null,"username":"priya"},
                               {"id":2,"meeting_id":7,"user_id":9,"role":"participant","status":"invited","full_name":null,"avatar":null,"username":"raj"}]}"""
        }.meeting("abc-defg-hij")

        assertEquals("meetings/abc-defg-hij", captured.single().path)
        assertEquals(7L, meeting.id)
        assertEquals("abc-defg-hij", meeting.meetingCode)
        assertTrue(meeting.isHost(3))
        assertFalse(meeting.isHost(9))
        assertTrue(meeting.isVideoCall())
        assertEquals(41L, meeting.conversationId)
        assertEquals("raj", meeting.participants[1].display())
        assertFalse(meeting.isEnded())
    }

    @Test
    fun listToleratesPostgresCountStrings() {
        val captured = mutableListOf<ApiRequest>()
        val list = repository(captured) {
            """[{"id":1,"title":"Retro","meeting_code":"x","created_by":3,"status":"ended","participant_count":"4","my_status":"left","settings":null}]"""
        }.list(status = "ended")

        assertEquals("meetings?limit=20&offset=0&status=ended", captured.single().path)
        assertEquals(4, list.single().participantTotal())
        assertTrue(list.single().isEnded())
    }

    @Test
    fun messagesMapRestSnakeCaseAndSystemRows() {
        val captured = mutableListOf<ApiRequest>()
        val messages = repository(captured) {
            """[{"id":10,"client_msg_id":"c-1","sender_id":3,"sender_name":"Priya","text":"hi","file_url":null,"file_name":null,"file_size":null,"system":null,"created_at":"2026-09-25T06:00:00.000Z"},
                {"id":11,"client_msg_id":null,"sender_id":3,"sender_name":"Priya","text":null,"file_url":null,"file_name":null,"file_size":null,"system":{"type":"meeting_joined"},"created_at":"2026-09-25T06:01:00.000Z"}]"""
        }.messages("abc", sinceId = 9)

        assertEquals("meetings/abc/messages?limit=200&since=9", captured.single().path)
        val chat = messages.map(MeetingMessageDto::toChat)
        assertEquals("c-1", chat[0].clientMsgId)
        assertFalse(chat[0].system)
        assertTrue(chat[1].system)
    }

    @Test
    fun writesUseTheWebRoutesAndBodies() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { request ->
            when {
                request.path.endsWith("hls/status") -> """{"live":true,"hlsUrl":"https://cdn/x.m3u8","hostId":3,"startedAt":"t"}"""
                request.path.endsWith("hls/start") -> """{"broadcastId":"b1","ingestUrl":"https://in","hlsUrl":"https://cdn/x.m3u8"}"""
                request.path.endsWith("hls/stop") -> """{"ok":true}"""
                request.method == "PUT" -> """{"id":7,"title":"Renamed"}"""
                request.path.endsWith("/participants") && request.method == "GET" -> """[{"user_id":3,"full_name":"Priya","role":"host"}]"""
                else -> """{"message":"ok"}"""
            }
        }

        assertEquals("Renamed", repo.update(7, MeetingUpdate(title = "Renamed")).title)
        repo.cancel(7)
        assertEquals(3L, repo.participants(7).single().userId)
        repo.addParticipant(7, 9)
        repo.removeParticipant(7, 9)
        assertEquals("b1", repo.startHls("abc").broadcastId)
        assertTrue(repo.hlsStatus("abc").live)
        assertTrue(repo.stopHls("abc", "b1").ok)

        assertEquals(
            listOf(
                "PUT meetings/7", "DELETE meetings/7", "GET meetings/7/participants", "POST meetings/7/participants",
                "DELETE meetings/7/participants/9", "POST meetings/abc/hls/start", "GET meetings/abc/hls/status", "POST meetings/abc/hls/stop",
            ),
            captured.map { "${it.method} ${it.path}" },
        )
        assertEquals("""{"title":"Renamed"}""", captured[0].body!!.decodeToString())
        assertNull(captured[1].body)
        assertEquals("""{"user_id":9}""", captured[3].body!!.decodeToString())
        assertEquals("""{"broadcastId":"b1"}""", captured[7].body!!.decodeToString())
    }

    @Test
    fun chatUploadReadsEitherCasing() {
        val captured = mutableListOf<ApiRequest>()
        val uploaded = repository(captured) { """{"id":5,"file_url":"/uploads/chat/a.pdf","file_name":"a.pdf","file_size":2048}""" }
            .uploadChatFile(41, "a.pdf", "application/pdf", byteArrayOf(1, 2))

        assertEquals("chat/conversations/41/files", captured.single().path)
        assertTrue(captured.single().headers.getValue("Content-Type").startsWith("multipart/form-data; boundary="))
        assertEquals(UploadedFile("/uploads/chat/a.pdf", "a.pdf", 2048), uploaded)
    }
}
