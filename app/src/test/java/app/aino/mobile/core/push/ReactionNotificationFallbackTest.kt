package app.aino.mobile.core.push

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.feature.chat.ChatConversation
import app.aino.mobile.feature.chat.ChatReactionEvent
import app.aino.mobile.feature.chat.ChatRepository
import org.junit.Assert.*
import org.junit.Test

class ReactionNotificationFallbackTest {
    private val event = ChatReactionEvent(50, 7, 2, "Asha", "👍", "added", reactionId = 9)

    private fun repository(requests: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        ChatRepository(ApiClient { request ->
            requests += request
            ApiResponse(200, emptyMap(), body(request).toByteArray())
        })

    private fun message(id: Long, owner: Long, conversation: Long = 7) =
        """{"id":$id,"conversation_id":$conversation,"sender_id":$owner,"content":"test","created_at":"2026-10-11T00:00:00Z"}"""

    @Test fun noncachedOwnMessageLoadsExactIdAndConversationBeforePosting() {
        val requests = mutableListOf<ApiRequest>()
        val repo = repository(requests) { request ->
            if (request.path == "chat/conversations") """[{"id":8,"is_muted":true},{"id":7,"is_muted":false}]"""
            else "[${message(49, 3)},${message(50, 1)},${message(51, 4)}]"
        }
        var posted = false
        val outcome = resolveReactionNotification(repo, event, null, null, 1, false) { owner, conversation ->
            assertEquals(1L, owner)
            assertEquals(7L, conversation.id)
            posted = true
            PostingOutcome.Posted
        }
        assertEquals(PostingOutcome.Posted, outcome)
        assertTrue(posted)
        assertEquals(listOf("chat/conversations/7/messages?limit=50&before=51", "chat/conversations"), requests.map { it.path })
    }

    @Test fun otherPersonsMessageDoesNotPostOrFetchConversation() {
        val requests = mutableListOf<ApiRequest>()
        val repo = repository(requests) { "[${message(50, 3)}]" }
        val outcome = resolveReactionNotification(repo, event, null, null, 1, false) { _, _ -> fail("Must not post"); PostingOutcome.Posted }
        assertTrue(outcome is PostingOutcome.Suppressed)
        assertEquals(1, requests.size)
    }

    @Test fun unavailableOrWrongMessageOwnershipBlocksWithoutPosting() {
        for (body in listOf("[]", "[${message(49, 1)}]", "[${message(50, 1, 8)}]")) {
            val requests = mutableListOf<ApiRequest>()
            val repo = repository(requests) { body }
            val outcome = resolveReactionNotification(repo, event, null, null, 1, false) { _, _ -> fail("Must not post"); PostingOutcome.Posted }
            assertEquals(PostingOutcome.Blocked("Message ownership not loaded"), outcome)
            assertEquals(1, requests.size)
        }
        val repo = repository(mutableListOf()) { throw IllegalStateException("Offline") }
        assertTrue(resolveReactionNotification(repo, event, null, null, 1, false) { _, _ -> PostingOutcome.Posted } is PostingOutcome.Blocked)
    }

    @Test fun unknownConversationIsFetchedAndMutedResultSuppresses() {
        val requests = mutableListOf<ApiRequest>()
        val repo = repository(requests) { """[{"id":7,"is_muted":true}]""" }
        assertEquals(PostingOutcome.Suppressed("Muted conversation"),
            resolveReactionNotification(repo, event, 1, null, 1, false) { _, _ -> fail("Must not post"); PostingOutcome.Posted })
        assertEquals("chat/conversations", requests.single().path)
    }

    @Test fun unavailableConversationEligibilityBlocksRatherThanAssumingUnmuted() {
        for (body in listOf("[]", """[{"id":8,"is_muted":false}]""")) {
            val repo = repository(mutableListOf()) { body }
            assertEquals(PostingOutcome.Blocked("Conversation eligibility not loaded"),
                resolveReactionNotification(repo, event, 1, null, 1, false) { _, _ -> fail("Must not post"); PostingOutcome.Posted })
        }
        val repo = repository(mutableListOf()) { throw IllegalStateException("Offline") }
        assertTrue(resolveReactionNotification(repo, event, 1, null, 1, false) { _, _ -> PostingOutcome.Posted } is PostingOutcome.Blocked)
    }

    @Test fun knownEligibilityNeedsNoNetworkAndSelfOrVisibleReactionsSuppress() {
        val requests = mutableListOf<ApiRequest>()
        val repo = repository(requests) { throw AssertionError("Unexpected network") }
        val conversation = ChatConversation(id = 7)
        assertEquals(PostingOutcome.Posted,
            resolveReactionNotification(repo, event, 1, conversation, 1, false) { _, _ -> PostingOutcome.Posted })
        for (input in listOf(event.copy(userId = 1), event.copy(action = "removed"))) {
            assertTrue(resolveReactionNotification(repo, input, 1, conversation, 1, false) { _, _ -> fail("Must not post"); PostingOutcome.Posted } is PostingOutcome.Suppressed)
        }
        assertTrue(resolveReactionNotification(repo, event, 1, conversation, 1, true) { _, _ -> fail("Must not post"); PostingOutcome.Posted } is PostingOutcome.Suppressed)
        assertTrue(requests.isEmpty())
    }
}
