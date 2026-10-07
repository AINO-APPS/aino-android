package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.AinoDao
import app.aino.mobile.core.db.ConversationEntity
import app.aino.mobile.core.db.MessageEntity
import app.aino.mobile.core.db.OutboxEntity
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.push.VisibleThread
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListActionsTest {
    private val captured = CopyOnWriteArrayList<ApiRequest>()
    @Volatile private var failMutations = false
    /** Holds mutation requests so the optimistic state can be asserted before the server answers. */
    @Volatile private var hold: java.util.concurrent.CountDownLatch? = null
    private val conversationsJson = """[
        {"id":1,"updated_at":"2026-09-14T09:00:00Z","is_group":false,"other_user_id":8,"other_full_name":"Asha K",
         "unread_count":0,"is_pinned":false,"is_muted":false,"is_archived":false},
        {"id":2,"updated_at":"2026-09-14T08:00:00Z","is_group":false,"other_user_id":9,"other_full_name":"Ravi M",
         "unread_count":3,"is_pinned":false,"is_muted":false,"is_archived":false}]"""

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { VisibleThread.clear(); Dispatchers.resetMain() }

    private fun viewModel(): ChatViewModel {
        val api = ApiClient { request ->
            captured += request
            if (request.method != "GET") hold?.await(3, java.util.concurrent.TimeUnit.SECONDS)
            if (failMutations && request.method != "GET") {
                throw ApiError.Http(500, """{"error":"Server said no"}""", request.method, request.path)
            }
            val body = when {
                request.path == "chat/conversations" -> conversationsJson
                request.path.endsWith("/pin") -> """{"pinned":true}"""
                request.path.endsWith("/mute") -> """{"muted":true}"""
                request.path.endsWith("/archive") -> """{"archived":true}"""
                request.path.endsWith("/unread") -> """{"unread":true}"""
                else -> """{"ok":true}"""
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        }
        return ChatViewModel(
            ChatRepository(api),
            { scope -> ChatCache(scope, ScopedCache(scope, EmptyDao)) },
            app.aino.mobile.core.db.ChatOutbox.None,
        ).also { vm ->
            vm.setScope(1, 1)
            await { vm.ui.value.conversations.size == 2 && !vm.ui.value.loading }
        }
    }

    private fun ChatViewModel.row(id: Long) = ui.value.conversations.first { it.id == id }

    @Test fun `pin shows immediately and hits the pin endpoint`() {
        val vm = viewModel()
        val gate = java.util.concurrent.CountDownLatch(1).also { hold = it }
        vm.togglePin(vm.row(1))
        assertTrue(vm.row(1).isPinned)
        gate.countDown()
        await { captured.any { it.path == "chat/conversations/1/pin" } }
    }

    @Test fun `failed mute rolls the row back and reports the server error`() {
        val vm = viewModel()
        failMutations = true
        val gate = java.util.concurrent.CountDownLatch(1).also { hold = it }
        vm.muteFor(vm.row(1), "8h")
        assertTrue(vm.row(1).isMuted)
        gate.countDown()
        await { vm.ui.value.error != null }
        assertFalse(vm.row(1).isMuted)
        assertEquals("Server said no", vm.ui.value.error)
    }

    @Test fun `mark as unread flags the row`() {
        val vm = viewModel()
        val gate = java.util.concurrent.CountDownLatch(1).also { hold = it }
        vm.markUnread(vm.row(1))
        assertEquals(1, vm.row(1).unreadCount)
        gate.countDown()
        await { captured.any { it.path == "chat/conversations/1/unread" } }
    }

    @Test fun `archive offers undo which unarchives`() {
        val vm = viewModel()
        vm.toggleArchive(vm.row(1), undoable = true)
        await { vm.ui.value.archiveUndo != null }
        vm.undoArchive()
        assertNull(vm.ui.value.archiveUndo)
        await { captured.count { it.path == "chat/conversations/1/archive" } == 2 }
    }

    @Test fun `the visible thread stays read across refreshes and is marked read once`() {
        val vm = viewModel()
        vm.openConversation(vm.row(2))
        vm.onThreadVisible(2)
        assertEquals(0, vm.row(2).unreadCount)
        await { captured.any { it.path == "chat/conversations/2/read" } }
        val before = captured.count { it.path == "chat/conversations" }
        vm.refresh()
        await { captured.count { it.path == "chat/conversations" } > before && !vm.ui.value.loading }
        assertEquals(0, vm.row(2).unreadCount)
        assertEquals(1, captured.count { it.path == "chat/conversations/2/read" })
    }

    @Test fun `group call is not started when the calls feature is off`() {
        val vm = viewModel()
        val group = vm.row(1).copy(isGroup = true, groupName = "Team")
        vm.startCall(group, "voice")
        Thread.sleep(100)
        assertFalse(captured.any { it.path == "meetings" })
    }

    @Test fun `group call starts a huddle when the calls feature is on`() {
        val vm = viewModel()
        vm.setCallsEnabled(true)
        val group = vm.row(1).copy(isGroup = true, groupName = "Team")
        vm.startCall(group, "voice")
        await { captured.any { it.method == "POST" && it.path == "meetings" } }
    }

    private fun await(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private object EmptyDao : AinoDao {
        override fun observeConversations(tenantId: Long, userId: Long): Flow<List<ConversationEntity>> = flowOf(emptyList())
        override suspend fun getConversations(tenantId: Long, userId: Long) = emptyList<ConversationEntity>()
        override suspend fun upsertConversations(values: List<ConversationEntity>) = Unit
        override fun observeMessages(tenantId: Long, userId: Long, conversationId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun getMessages(tenantId: Long, userId: Long, conversationId: Long) = emptyList<MessageEntity>()
        override suspend fun upsertMessages(values: List<MessageEntity>) = Unit
        override suspend fun clearConversationMessages(tenantId: Long, userId: Long, conversationId: Long) = Unit
        override suspend fun putOutbox(value: OutboxEntity) = Unit
        override suspend fun pendingOutbox(tenantId: Long, userId: Long, now: Long, limit: Int) = emptyList<OutboxEntity>()
        override suspend fun markOutboxRetry(tenantId: Long, userId: Long, clientMessageId: String, nextAttemptAt: Long) = Unit
        override suspend fun markOutboxFailed(tenantId: Long, userId: Long, clientMessageId: String) = Unit
        override suspend fun deleteOutbox(tenantId: Long, userId: Long, clientMessageId: String) = Unit
        override suspend fun clearConversations(tenantId: Long, userId: Long) = Unit
        override suspend fun deleteConversation(tenantId: Long, userId: Long, conversationId: Long) = Unit
        override suspend fun clearMessages(tenantId: Long, userId: Long) = Unit
        override suspend fun clearOutbox(tenantId: Long, userId: Long) = Unit
    }
}
