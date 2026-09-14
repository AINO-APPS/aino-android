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
}