package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.InMemoryAinoDao
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.push.VisibleThread
import app.aino.mobile.core.push.syncChatThread
import app.aino.mobile.core.realtime.RealtimeEvent
import app.aino.mobile.core.realtime.RoutedRealtimeEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Notification-tap open by id, push-stored rows, and per-user clear / delete (local cleanup). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLocalSyncTest {
    private val requests = CopyOnWriteArrayList<ApiRequest>()
    private val dao = InMemoryAinoDao()
    private val scope = CacheScope(1, 7)
    private val forgotten = CopyOnWriteArrayList<Long>()
    private val storedUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    @Volatile private var listGate: CountDownLatch? = null
    @Volatile private var messageGate: CountDownLatch? = null
    @Volatile private var cleared = false
    @Volatile private var offline = false
    /** Hidden for this user by the server (per-user delete). */
    private val hiddenConversations = java.util.concurrent.CopyOnWriteArraySet<Long>()
    private val conversationRows = listOf(
        """{"id":5,"updated_at":"2026-10-06T09:00:00Z","is_group":false,"other_user_id":8,"other_full_name":"Asha Kumar","unread_count":2}""",
        """{"id":6,"updated_at":"2026-10-06T08:00:00Z","is_group":false,"other_user_id":9,"other_full_name":"Ravi M","unread_count":0}""",
    )
    private val conversationsJson get() = conversationRows.filterIndexed { index, _ -> (index + 5L) !in hiddenConversations }
        .joinToString(",", "[", "]")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { VisibleThread.clear(); Dispatchers.resetMain() }

    private fun row(id: Long, conversationId: Long = 5) =
        """{"id":$id,"conversation_id":$conversationId,"sender_id":8,"content":"m$id","created_at":"2026-10-06T10:00:0${id % 10}Z"}"""

    private val api = ApiClient { request ->
        requests += request
        val path = request.path
        if (request.method == "GET" && path == "chat/conversations") listGate?.await(3, TimeUnit.SECONDS)
        if (request.method == "GET" && "/messages" in path) {
            messageGate?.await(3, TimeUnit.SECONDS)
            if (offline) throw ApiError.Network(request.method, path, java.io.IOException("offline"))
        }
        val body = when {
            request.method != "GET" -> """{"ok":true}"""
            path == "chat/conversations" -> conversationsJson
            path.startsWith("chat/conversations/5/messages") -> if (cleared) "[]" else "[${row(1)},${row(2)},${row(3)}]"
            path.startsWith("chat/conversations/6/messages") -> "[${row(10, 6)}]"
            else -> "[]"
        }
        ApiResponse(200, mapOf("Content-Type" to listOf("application/json")), body.toByteArray())
    }

    private fun viewModel(scoped: Boolean = true) = ChatViewModel(
        ChatRepository(api),
        { scope -> ChatCache(scope, ScopedCache(scope, dao)) },
        app.aino.mobile.core.db.ChatOutbox.None,
        forgetWarmThread = { forgotten += it },
        storedThreadUpdates = storedUpdates,
    ).also { if (scoped) it.setScope(1, 7) }

    private fun ChatViewModel.loaded() = also { await { ui.value.conversations.size == 2 && !ui.value.loading } }

    private fun ChatViewModel.openLoaded(id: Long) = also {
        prepareConversation(id)
        await { ui.value.selectedConversation?.id == id && ui.value.messages.isNotEmpty() && !ui.value.threadLoading }
    }

    private fun event(event: RealtimeEvent, json: String) = RoutedRealtimeEvent(event, Json.parseToJsonElement(json))

    private fun storedIds(conversationId: Long) = runBlocking { dao.getMessages(1, 7, conversationId).map { it.messageId } }

    @Test fun `a notification tap paints a placeholder and stored rows before the list, then adopts the real row`() {
        // The push wake already wrote rows 1-2 to Room.
        runBlocking { syncChatThread(ApiClient { ApiResponse(200, emptyMap(), "[${row(1)},${row(2)}]".toByteArray()) }, ScopedCache(scope, dao), scope, 5) }
        val list = CountDownLatch(1).also { listGate = it }
        val messages = CountDownLatch(1).also { messageGate = it }
        val vm = viewModel()

        vm.openConversationById(5, ConversationHint(title = "Asha", isGroup = false, unreadCount = 1))

        assertEquals(5L, vm.ui.value.selectedConversation?.id)
        assertEquals("Asha", vm.ui.value.selectedConversation?.title())
        assertEquals(1, vm.ui.value.unreadAtOpen)
        await { vm.ui.value.messages.map(ChatMessage::id) == listOf(1L, 2L) }
        assertTrue(vm.ui.value.conversations.isEmpty())

        messages.countDown()
        await { vm.ui.value.messages.map(ChatMessage::id) == listOf(1L, 2L, 3L) }
        list.countDown()
        await { vm.ui.value.selectedConversation?.otherUserId == 8L }
        assertEquals("Asha Kumar", vm.ui.value.selectedConversation?.title())
        assertEquals(listOf(1L, 2L, 3L), vm.ui.value.messages.map(ChatMessage::id))
        await { requests.any { it.method == "POST" && it.path == "chat/conversations/5/read" } }
    }

    @Test fun `a listed conversation opens as itself`() {
        val vm = viewModel().loaded()
        vm.openConversationById(6)
        assertEquals("Ravi M", vm.ui.value.selectedConversation?.title())
        await { vm.ui.value.messages.map(ChatMessage::id) == listOf(10L) }
    }

    @Test fun `a tap before the account scope is known opens once it is`() {
        val vm = viewModel(scoped = false)
        vm.openConversationById(5, ConversationHint(title = "Asha"))
        assertNull(vm.ui.value.selectedConversation)
        vm.setScope(1, 7)
        assertEquals(5L, vm.ui.value.selectedConversation?.id)
        await { vm.ui.value.messages.size == 3 }
    }

    @Test fun `rows stored by a push wake join the open thread`() {
        val vm = viewModel().loaded().openLoaded(5)
        // The open thread writes its rows to Room asynchronously; a push wake only ever
        // sees a stored thread (the delta path) once that write has landed.
        await { storedIds(5) == listOf(1L, 2L, 3L) }
        runBlocking { syncChatThread(ApiClient { ApiResponse(200, emptyMap(), "[${row(4)}]".toByteArray()) }, ScopedCache(scope, dao), scope, 5) }
        storedUpdates.tryEmit(5)
        await { vm.ui.value.messages.map(ChatMessage::id) == listOf(1L, 2L, 3L, 4L) }
    }

    @Test fun `a live message is stored for the next open`() {
        viewModel().loaded().onRealtimeEvent(event(RealtimeEvent.ChatMessage,
            """{"id":11,"conversationId":6,"senderId":9,"content":"hi","createdAt":"2026-10-06T10:00:09Z"}"""))
        await { storedIds(6) == listOf(11L) }
        val stored = runBlocking { ChatCache(scope, ScopedCache(scope, dao)).fullMessages(6) }
        assertEquals("hi", stored.single().content)
    }

    @Test fun `clear chat drops every local copy and never repaints the old history`() {
        val vm = viewModel().loaded().openLoaded(5)
        await { storedIds(5) == listOf(1L, 2L, 3L) }
        cleared = true
        vm.clearChat()
        await { requests.any { it.method == "DELETE" && it.path == "chat/conversations/5/messages" } }
        await { forgotten.contains(5L) && storedIds(5).isEmpty() && vm.ui.value.messages.isEmpty() && !vm.ui.value.threadLoading }
        assertTrue(vm.ui.value.pinnedMessages.isEmpty())
        assertTrue(vm.ui.value.hiddenMessageIds.isEmpty())
        assertEquals(0, vm.ui.value.unreadAtOpen)
        Thread.sleep(100)
        assertTrue(vm.ui.value.messages.isEmpty())
    }

    @Test fun `chat_cleared from another of my devices clears a thread kept in memory`() {
        val vm = viewModel().loaded().openLoaded(5)
        vm.closeConversation(5)
        cleared = true
        vm.onRealtimeEvent(event(RealtimeEvent.ChatCleared, """{"conversationId":5}"""))
        await { storedIds(5).isEmpty() && forgotten.contains(5L) }
        offline = true
        vm.prepareConversation(5)
        await { !vm.ui.value.threadLoading }
        assertTrue(vm.ui.value.messages.isEmpty())
    }

    @Test fun `chat_conv_deleted closes the open thread and forgets the conversation`() {
        val vm = viewModel().loaded().openLoaded(5)
        await { runBlocking { dao.getConversations(1, 7) }.any { it.conversationId == 5L } }
        hiddenConversations += 5L
        vm.onRealtimeEvent(event(RealtimeEvent.ChatConvDeleted, """{"conversationId":5}"""))
        await { vm.ui.value.closeThread }
        assertFalse(vm.ui.value.conversations.any { it.id == 5L })
        await { storedIds(5).isEmpty() && runBlocking { dao.getConversations(1, 7) }.none { it.conversationId == 5L } }
        assertTrue(forgotten.contains(5L))
    }

    @Test fun `deleting a chat for me cleans up locally`() {
        val vm = viewModel().loaded().openLoaded(6)
        hiddenConversations += 6L
        vm.deleteConversation(vm.ui.value.conversations.first { it.id == 6L })
        await { requests.any { it.method == "DELETE" && it.path == "chat/conversations/6" } }
        await { vm.ui.value.closeThread && storedIds(6).isEmpty() && forgotten.contains(6L) }
    }

    // Room + Robolectric on a busy 2-core CI runner (two flavors' tests back to back) can exceed 3 s.
    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for condition")
            Thread.sleep(10)
        }
    }
}
