package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.ChatOutbox
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.push.VisibleThread
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A text accepted by the server whose answer never reaches the phone (2-minute
 * read timeout, 90-second call timeout, or a connection dropped mid-response)
 * must never be sent a second time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatSendDuplicateTest {
    private val accepted = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val unknownCalls = java.util.concurrent.atomic.AtomicInteger()
    private val answered = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { VisibleThread.clear(); Dispatchers.resetMain() }

    private fun viewModel(): ChatViewModel {
        val api = ApiClient { request ->
                val body = when {
                request.path == "chat/conversations" ->
                    """[{"id":1,"updated_at":"2026-09-30T09:00:00Z","is_group":false,"other_user_id":8,"other_full_name":"Asha K","unread_count":0}]"""
                request.method == "POST" && request.path == "chat/conversations/1/messages" -> {
                    val json = request.body!!.toString(Charsets.UTF_8)
                    val content = Regex("\"content\":\"([^\"]*)\"").find(json)!!.groupValues[1]
                    val clientId = Regex("\"clientMsgId\":\"([^\"]*)\"").find(json)!!.groupValues[1]
                    accepted += content
                    // The server committed the row, then the response was lost in transit.
                    if (unknownCalls.getAndIncrement() == 0) {
                        answered.incrementAndGet()
                        throw ApiError.Network("POST", request.path, IOException("unexpected end of stream"))
                    }
                    answered.incrementAndGet()
                    """{"id":100,"conversation_id":1,"sender_id":1,"content":"$content",
                       "created_at":"2026-09-30T10:00:06Z","client_msg_id":"$clientId"}"""
                }
                else -> "[]"
            }
                            ApiResponse(201, emptyMap(), body.toByteArray())
                        }
        val vm = ChatViewModel(
            ChatRepository(api),
            { scope -> ChatCache(scope, ScopedCache(scope, ChatThreadCacheTest.InMemoryMessagesDao())) },
            ChatOutbox.None,
        )
        vm.setScope(1, 1)
        await { vm.ui.value.conversations.size == 1 && !vm.ui.value.loading }
        vm.prepareConversation(1)
        await { !vm.ui.value.threadLoading }
        return vm
    }

    @Test fun `a text whose answer was lost is never sent twice`() {
        val vm = viewModel()
        vm.updateComposer("hello")
        vm.sendMessage()
        await { vm.ui.value.queuedMessages.isEmpty() }
        Thread.sleep(250)
        assertEquals("the server saw the same message twice", listOf("hello"), accepted.toList())
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for condition")
            Thread.sleep(10)
        }
    }
}