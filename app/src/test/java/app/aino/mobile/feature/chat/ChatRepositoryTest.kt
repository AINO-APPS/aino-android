package app.aino.mobile.feature.chat

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        ChatRepository(ApiClient { request ->
            captured += request
            ApiResponse(200, emptyMap(), body(request).toByteArray())
        })

    @Test
    fun decodesTheExactEnrichedConversationProjection() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """[{"id":4,"updated_at":"2026-09-14T09:00:00Z","is_group":false,
               "other_user_id":8,"other_username":"asha","other_full_name":"Asha K",
               "last_message":"Morning","last_sender_name":"Asha K","last_message_at":"2026-09-14T09:01:00Z",
               "unread_count":2,"is_pinned":true,"is_muted":false,"is_archived":false}]"""
        }

        val conversation = repository.loadConversations().single()

        assertEquals("chat/conversations", captured.single().path)
        assertEquals("Asha K", conversation.title())
        assertEquals("Morning", conversation.preview())
        assertEquals(8L, conversation.otherUserId)
        assertTrue(conversation.isPinned)
        assertEquals(2, conversation.unreadCount)
    }

    @Test
    fun decodesBulkPresenceMapByNumericUserId() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """{"8":{"presence":"online","userStatus":"busy","workMode":"office"},
                 "9":{"presence":"offline","userStatus":"offline","workMode":null}}"""
        }

        val presence = repository.loadPresence(listOf(8, 9, 8))

        assertEquals("chat/presence?userIds=8,9", captured.single().path)
        assertEquals("busy", presence[8]?.userStatus)
        assertEquals("office", presence[8]?.workMode)
        assertEquals("offline", presence[9]?.presence)
    }

    @Test
    fun emptyPresenceDoesNotSpendARequest() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { "{}" }
        assertTrue(repository.loadPresence(emptyList()).isEmpty())
        assertTrue(captured.isEmpty())
    }

    @Test
    fun searchHonoursMinimumAndEncodesTheTerm() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """[{"id":8,"username":"asha","full_name":"Asha K"}]""" }

        assertTrue(repository.searchUsers("a").isEmpty())
        assertTrue(captured.isEmpty())
        assertEquals("Asha K", repository.searchUsers("asha k").single().display())
        assertEquals("chat/search?q=asha+k", captured.single().path)
    }

    @Test
    fun directConversationAndReadSyncUsePost() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            if (request.path.endsWith("/read")) """{"ok":true}""" else """{"conversationId":14}"""
        }

        val created = repository.createDirect(8)
        repository.markRead(14)

        assertEquals(14, created.conversationId)
        assertEquals("POST", captured[0].method)
        assertEquals("chat/conversations", captured[0].path)
        assertEquals("""{"userId":8}""", captured[0].body!!.toString(Charsets.UTF_8))
        assertEquals("POST", captured[1].method)
        assertEquals("chat/conversations/14/read", captured[1].path)
    }

    @Test
    fun surfacesServerChatErrors() {
        val repository = ChatRepository(ApiClient {
            throw ApiError.Http(403, """{"error":"Not a participant"}""", "POST", "chat/conversations/9/read")
        })
        try {
            repository.markRead(9)
            fail("expected ChatFailure")
        } catch (failure: ChatFailure) {
            assertEquals(403, failure.statusCode)
            assertEquals("Not a participant", failure.message)
        }
    }

    @Test
    fun loadsThreadWithRepliesReactionsAndPagination() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """[{"id":77,"sender_id":8,"content":"Reply","created_at":"2026-09-15T04:00:00Z",
               "sender_name":"Asha K","reply_to_id":70,"reply_content":"Original","reply_sender_name":"Vishnu",
               "reactions":[{"emoji":"👍","userId":9,"fullName":"Member"}]}]"""
        }

        val message = repository.loadMessages(12, before = 99).single()

        assertEquals("chat/conversations/12/messages?limit=50&before=99", captured.single().path)
        assertEquals("Original", message.replyContent)
        assertEquals("Vishnu", message.replySenderName)
        assertEquals("👍", message.reactions.single().emoji)
        assertEquals(9, message.reactions.single().userId)
    }

    @Test
    fun loadsReceiptsAndPostsReaction() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            if (request.method == "GET") {
                """[{"user_id":8,"last_read_at":"2026-09-15T04:00:00Z","full_name":"Asha K"}]"""
            } else """{"ok":true}"""
        }

        val receipt = repository.loadReadReceipts(12).single()
        repository.toggleReaction(77, "👍")

        assertEquals("Asha K", receipt.fullName)
        assertEquals("chat/conversations/12/read-status", captured[0].path)
        assertEquals("POST", captured[1].method)
        assertEquals("chat/messages/77/reactions", captured[1].path)
        assertEquals("""{"emoji":"👍"}""", captured[1].body!!.toString(Charsets.UTF_8))
    }

    @Test
    fun editsDeletesAndStarsThroughExactServerContracts() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            if (request.path.endsWith("/star")) """{"ok":true,"starred":true}""" else """{"ok":true}"""
        }

        repository.editMessage(77, "  revised  ")
        repository.deleteMessage(77)
        val star = repository.toggleStar(77)

        assertEquals("PUT", captured[0].method)
        assertEquals("chat/messages/77", captured[0].path)
        assertEquals("""{"content":"revised"}""", captured[0].body!!.toString(Charsets.UTF_8))
        assertEquals("DELETE", captured[1].method)
        assertEquals("chat/messages/77", captured[1].path)
        assertEquals("POST", captured[2].method)
        assertEquals("chat/messages/77/star", captured[2].path)
        assertTrue(star.starred)
    }

    @Test
    fun uploadsMultipartFileAndDecodesMediaJob() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """{"id":88,"conversation_id":12,"sender_id":4,"created_at":"2026-09-15T04:00:00Z",
               "file_name":"report.pdf","file_type":"application/pdf","file_size":3,
               "media_job_id":9,"media_state":"queued","media_stage":"queued","media_progress":0}"""
        }

        val message = repository.uploadFile(12, ChatUpload("report.pdf", "application/pdf", byteArrayOf(1, 2, 3)))

        val request = captured.single()
        assertEquals("POST", request.method)
        assertEquals("chat/conversations/12/files", request.path)
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
        assertTrue(request.body!!.toString(Charsets.ISO_8859_1).contains("name=\"file\"; filename=\"report.pdf\""))
        assertEquals(9L, message.mediaJobId)
        assertEquals("queued", message.mediaState)
    }

    @Test
    fun controlsMediaJobsThroughTheServerRoutes() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"ok":true,"mediaJobId":9}""" }
        repository.cancelMediaJob(9)
        repository.retryMediaJob(9)
        assertEquals("chat/media-jobs/9/cancel", captured[0].path)
        assertEquals("chat/media-jobs/9/retry", captured[1].path)
        assertTrue(captured.all { it.method == "POST" })
    }

    @Test
    fun loadsCallHistoryAndMembers() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            when (request.path) {
                "chat/calls" -> """[{"id":1,"conversation_id":12,"caller_id":4,"call_type":"voice","status":"answered","created_at":"2026-09-15T04:00:00Z","other_name":"Asha"}]"""
                "chat/conversations/12/calls" -> """[{"id":2,"conversation_id":12,"caller_id":8,"call_type":"video","status":"missed","created_at":"2026-09-15T05:00:00Z","caller_name":"Asha"}]"""
                else -> """[{"id":8,"username":"asha","full_name":"Asha K","role":"owner"}]"""
            }
        }
        assertEquals("Asha", repository.loadCalls().single().title(4))
        assertEquals("missed", repository.loadConversationCalls(12).single().status)
        assertEquals("owner", repository.loadMembers(12).single().role)
        assertEquals(listOf("chat/calls", "chat/conversations/12/calls", "chat/conversations/12/members"), captured.map { it.path })
    }

    @Test
    fun togglesConversationInfoActions() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request -> when {
            request.path.endsWith("/pin") -> """{"pinned":true}"""
            request.path.endsWith("/favourite") -> """{"favourite":true}"""
            request.path.endsWith("/mute") -> """{"muted":true,"mutedUntil":null}"""
            else -> """{"archived":true}"""
        } }
        assertEquals(true, repository.togglePinConversation(12).pinned)
        assertEquals(true, repository.toggleFavouriteConversation(12).favourite)
        assertEquals(true, repository.setMute(12, "always").muted)
        assertEquals(true, repository.toggleArchive(12).archived)
        assertTrue(captured.all { it.method == "POST" })
        assertTrue(captured[2].body!!.toString(Charsets.UTF_8).contains("\"duration\":\"always\""))
    }
}