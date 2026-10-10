package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.network.NETWORK_ERROR_MESSAGE
import app.aino.mobile.core.push.VisibleThread
import app.aino.mobile.core.realtime.RealtimeEnvelope
import app.aino.mobile.core.realtime.RealtimeEvent
import app.aino.mobile.core.realtime.RoutedRealtimeEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A text sent over the socket counts as delivered only once the server echoes its clientMsgId. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSocketSendTest {
    private val requests = CopyOnWriteArrayList<ApiRequest>()
    private val frames = CopyOnWriteArrayList<RealtimeEnvelope>()
    private val dao = ChatThreadCacheTest.InMemoryMessagesDao()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { VisibleThread.clear(); Dispatchers.resetMain() }

    private val api = ApiClient { request ->
        requests += request
        val body = when {
            request.path == "chat/conversations" ->
                """[{"id":1,"updated_at":"2026-10-06T09:00:00Z","is_group":true,"name":"Team","unread_count":0}]"""
            request.path == "chat/conversations/1/members" -> """[{"id":8,"full_name":"Asha"},{"id":1,"full_name":"Me"}]"""
            request.method == "POST" && request.path == "chat/conversations/1/messages" ->
                """{"id":77,"conversation_id":1,"sender_id":1,"content":"hi @Asha","created_at":"2026-10-06T10:00:00Z"}"""
            request.method != "GET" -> """{"ok":true}"""
            else -> "[]"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    }

    private fun restSends() = requests.count { it.method == "POST" && it.path == "chat/conversations/1/messages" }

    private fun viewModel(socketAccepts: Boolean, echo: Boolean): ChatViewModel {
        lateinit var vm: ChatViewModel
        vm = ChatViewModel(
            ChatRepository(api),
            { scope -> ChatCache(scope, ScopedCache(scope, dao)) },
            socketEchoTimeoutMs = 300,
        )
        vm.setRealtimeSender { envelope ->
            if (envelope.type != "chat_message") return@setRealtimeSender true
            frames += envelope
            if (socketAccepts && echo) {
                val clientId = envelope.data!!.jsonObject["clientMsgId"]!!.jsonPrimitive.content
                Thread {
                    Thread.sleep(50)
                    val data = Json.parseToJsonElement(
                        """{"id":78,"conversationId":1,"senderId":1,"content":"hi @Asha","createdAt":"2026-10-06T10:00:00Z","clientMsgId":"$clientId"}""",
                    )
                    vm.onRealtimeEvent(RoutedRealtimeEvent(RealtimeEvent.ChatMessage, data))
                }.start()
            }
            socketAccepts
        }
        vm.setScope(1, 1)
        await { vm.ui.value.conversations.isNotEmpty() && !vm.ui.value.loading }
        vm.prepareConversation(1)
        await { vm.ui.value.members.isNotEmpty() }
        vm.updateComposer("hi @")
        vm.addMention(vm.ui.value.members.first { it.id == 8L })
        return vm
    }

    @Test fun `an echoed socket send is not repeated over REST`() {
        val vm = viewModel(socketAccepts = true, echo = true)
        vm.sendMessage()
        await { frames.isNotEmpty() && vm.ui.value.queuedMessages.isEmpty() }
        Thread.sleep(500)
        assertEquals(1, frames.size)
        assertEquals(0, restSends())
    }

    @Test fun `a socket send without an echo falls back to REST once`() {
        val vm = viewModel(socketAccepts = true, echo = false)
        vm.sendMessage()
        await { restSends() == 1 }
        await { vm.ui.value.queuedMessages.isEmpty() }
        assertEquals(1, frames.size)
        Thread.sleep(200)
        assertEquals(1, restSends())
    }

    @Test fun `a refused socket frame goes straight to REST`() {
        val vm = viewModel(socketAccepts = false, echo = false)
        vm.sendMessage()
        await { restSends() == 1 }
        await { vm.ui.value.queuedMessages.isEmpty() }
        Thread.sleep(200)
    }

    @Test fun `action errors never show the raw transport string`() {
        val network = ApiError.Network("POST", "chat/x", java.io.IOException("reset"))
        assertEquals(NETWORK_ERROR_MESSAGE, actionError(network, "Could not update reaction"))
        assertEquals("Blocked", actionError(ChatFailure("Blocked", 403, network), "Could not send"))
        assertEquals("Could not send", actionError(ApiError.Http(400, "{}", "POST", "x"), "Could not send"))
    }

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for condition")
            Thread.sleep(10)
        }
    }
}
