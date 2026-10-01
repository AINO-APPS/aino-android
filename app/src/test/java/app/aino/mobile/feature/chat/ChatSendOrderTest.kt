package app.aino.mobile.feature.chat

import android.net.Uri
import app.aino.mobile.core.db.ChatOutbox
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.push.VisibleThread
import app.aino.mobile.feature.chat.media.MediaPreparer
import app.aino.mobile.feature.chat.media.PreparedMedia
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/** Signal per-conversation send order: text typed during an upload is sent after it, never before. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatSendOrderTest {
    private val sent = CopyOnWriteArrayList<String>()
    private val uploadGate = CountDownLatch(1)
    @Volatile private var uploadStatus = 201
    @Volatile private var textFailuresLeft = 0
    private var nextId = 100L

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { uploadGate.countDown(); VisibleThread.clear(); Dispatchers.resetMain() }

    private fun viewModel(): ChatViewModel {
        val api = ApiClient { request ->
            val body = when {
                request.path == "chat/conversations" ->
                    """[{"id":1,"updated_at":"2026-09-30T09:00:00Z","is_group":false,"other_user_id":8,"other_full_name":"Asha K","unread_count":0}]"""
                request.method == "POST" && request.path == "chat/conversations/1/files" -> {
                    uploadGate.await(5, TimeUnit.SECONDS)
                    if (uploadStatus >= 400) throw ApiError.Http(uploadStatus, """{"error":"File type not allowed"}""", "POST", request.path)
                    sent += "file"
                    """{"id":${nextId++},"conversation_id":1,"sender_id":1,"created_at":"2026-09-30T10:00:05Z",
                       "file_url":"/u/a.jpg","file_name":"photo.jpg","file_type":"image/jpeg","file_size":3}"""
                }
                request.method == "POST" && request.path == "chat/conversations/1/messages" -> {
                    if (textFailuresLeft > 0) {
                        textFailuresLeft--
                        throw ApiError.Network("POST", request.path, java.io.IOException("offline"))
                    }
                    val json = request.body!!.toString(Charsets.UTF_8)
                    val content = Regex("\"content\":\"([^\"]*)\"").find(json)!!.groupValues[1]
                    val clientId = Regex("\"clientMsgId\":\"([^\"]*)\"").find(json)!!.groupValues[1]
                    sent += "text:$content"
                    """{"id":${nextId++},"conversation_id":1,"sender_id":1,"content":"$content",
                       "created_at":"2026-09-30T10:00:06Z","client_msg_id":"$clientId"}"""
                }
                else -> "[]"
            }
            ApiResponse(201, emptyMap(), body.toByteArray())
        }
        val preparer = MediaPreparer { request ->
            PreparedMedia(request.fileName, request.mimeType, 3, open = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        }
        return ChatViewModel(
            ChatRepository(api),
            { scope -> ChatCache(scope, ScopedCache(scope, ChatThreadCacheTest.InMemoryMessagesDao())) },
            ChatOutbox.None,
            mediaPreparer = preparer,
        ).also { vm ->
            vm.setScope(1, 1)
            await { vm.ui.value.conversations.size == 1 && !vm.ui.value.loading }
            vm.prepareConversation(1)
            await { !vm.ui.value.threadLoading }
        }
    }

    private fun ChatViewModel.type(text: String) { updateComposer(text); sendMessage() }

    @Test fun `text sent during an upload shows at once and is sent right after the image`() {
        val vm = viewModel()
        vm.sendMedia(listOf(MediaUploadSpec(Uri.parse("content://media/photo.jpg"), "image/jpeg")), "", viewOnce = false, highQuality = false)
        vm.type("after the photo")

        assertEquals(listOf("after the photo"), vm.ui.value.queuedMessages.map { it.content })
        assertTrue(vm.ui.value.queuedMessages.single().sequence > vm.ui.value.pendingMedia.single().sequence)
        Thread.sleep(200)
        assertTrue("text must wait for the upload ahead of it", sent.isEmpty())

        uploadGate.countDown()
        await { sent.size == 2 && vm.ui.value.queuedMessages.isEmpty() && vm.ui.value.pendingMedia.isEmpty() }
        assertEquals(listOf("file", "text:after the photo"), sent)
        assertEquals(listOf(100L, 101L), vm.ui.value.messages.map(ChatMessage::id))
    }

    @Test fun `text with nothing queued ahead is sent immediately and replaced by the server row`() {
        val vm = viewModel()
        vm.type("hello")
        await { sent == listOf("text:hello") && vm.ui.value.queuedMessages.isEmpty() }
        assertEquals(listOf("hello"), vm.ui.value.messages.map { it.content })
    }

    @Test fun `a rejected upload fails its bubble without blocking later messages`() {
        uploadStatus = 400
        uploadGate.countDown()
        val vm = viewModel()
        vm.sendMedia(listOf(MediaUploadSpec(Uri.parse("content://media/photo.jpg"), "image/jpeg")), "", viewOnce = false, highQuality = false)
        vm.type("still delivered")
        await { sent == listOf("text:still delivered") && vm.ui.value.pendingMedia.singleOrNull()?.state == PendingMediaState.Failed }
        assertEquals("File type not allowed", vm.ui.value.pendingMedia.single().error)
    }

    @Test fun `several texts keep their order`() {
        val vm = viewModel()
        listOf("one", "two", "three").forEach { vm.type(it) }
        await { sent.size == 3 }
        assertEquals(listOf("text:one", "text:two", "text:three"), sent)
    }

    @Test fun `a text that hits a dropped connection keeps its place until it is delivered`() {
        textFailuresLeft = 1
        val vm = viewModel()
        vm.type("first")
        vm.type("second")
        await { sent.size == 2 && vm.ui.value.queuedMessages.isEmpty() }
        assertEquals(listOf("text:first", "text:second"), sent)
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for condition")
            Thread.sleep(10)
        }
    }
}
