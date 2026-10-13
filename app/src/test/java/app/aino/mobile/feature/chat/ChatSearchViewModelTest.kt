package app.aino.mobile.feature.chat

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.push.VisibleThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchViewModelTest {
    private val store = ViewModelStore()
    private val releases = mutableListOf<CountDownLatch>()
    private lateinit var vm: ChatViewModel
    @Volatile private var intercept: (ApiRequest) -> String? = { null }
    @Volatile private var creationFails = false

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = ChatThreadCacheTest.InMemoryMessagesDao()
        val api = ApiClient { request ->
            val body = intercept(request) ?: when {
                request.path == "chat/conversations" && request.method == "GET" -> """[
                    {"id":1,"other_user_id":8,"other_full_name":"Asha"},
                    {"id":2,"other_user_id":9,"other_full_name":"Ravi"}]"""
                request.path == "chat/conversations" -> {
                    if (creationFails) throw ApiError.Http(403, """{"error":"Denied"}""", request.method, request.path)
                    """{"conversationId":1}"""
                }
                request.path.startsWith("chat/search?q=") -> users("Asha")
                request.path.startsWith("chat/search-messages?") -> messages(101, "needle")
                request.path.startsWith("chat/conversations/1/messages") -> messages(101, "needle")
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        }
        vm = ChatViewModel(ChatRepository(api), { scope -> ChatCache(scope, ScopedCache(scope, dao)) })
        store.put("chat", vm)
        vm.setScope(1, 1)
        await { vm.ui.value.conversations.size == 2 && !vm.ui.value.loading }
    }

    @After fun cleanup() {
        releases.forEach { it.countDown() }
        store.clear()
        VisibleThread.clear()
        Dispatchers.resetMain()
    }

    @Test fun `clear keeps search open while close atomically resets the session`() {
        search("needle")
        await { vm.ui.value.userResults.isNotEmpty() && vm.ui.value.messageResults.isNotEmpty() }
        vm.updateUserSearch("")
        assertTrue(vm.ui.value.listSearchOpen)
        assertEmptyListSearch()
        search("needle")
        await { vm.ui.value.messageResults.isNotEmpty() }
        vm.closeListSearch()
        assertFalse(vm.ui.value.listSearchOpen)
        assertEmptyListSearch()
        assertEquals(2, vm.ui.value.conversations.size)
        vm.openListSearch()
        assertEmptyListSearch()
    }

    @Test fun `short query cancels searches and removes both result sections immediately`() {
        search("needle")
        await { vm.ui.value.userResults.isNotEmpty() && vm.ui.value.messageResults.isNotEmpty() }
        vm.updateUserSearch("n")
        assertEquals("n", vm.ui.value.userSearch)
        assertTrue(vm.ui.value.listSearchOpen)
        assertTrue(vm.ui.value.userResults.isEmpty())
        assertTrue(vm.ui.value.messageResults.isEmpty())
        assertFalse(vm.ui.value.searching)
    }

    @Test fun `contact creation retains search on success and failure`() {
        search("needle")
        await { vm.ui.value.userResults.isNotEmpty() && vm.ui.value.messageResults.isNotEmpty() }
        val contacts = vm.ui.value.userResults
        val hits = vm.ui.value.messageResults
        creationFails = true
        vm.startDirect(contacts.single())
        await { !vm.ui.value.loading && vm.ui.value.error != null }
        assertEquals("needle", vm.ui.value.userSearch)
        assertTrue(vm.ui.value.listSearchOpen)
        assertNull(vm.ui.value.openConversationId)
        creationFails = false
        vm.startDirect(contacts.single())
        await { vm.ui.value.openConversationId == 1L }
        assertTrue(vm.ui.value.listSearchOpen)
        assertEquals("needle", vm.ui.value.userSearch)
        assertEquals(contacts, vm.ui.value.userResults)
        assertEquals(hits, vm.ui.value.messageResults)
    }

    @Test fun `opening and closing a message result preserves list search and the jump target`() {
        search("needle")
        await { vm.ui.value.messageResults.isNotEmpty() }
        vm.rememberJump(vm.ui.value.messageResults.single())
        vm.prepareConversation(1)
        assertEquals(101L, vm.ui.value.jumpToMessageId)
        assertTrue(vm.ui.value.listSearchOpen)
        vm.closeConversation(1)
        assertNull(vm.ui.value.selectedConversation)
        assertEquals("needle", vm.ui.value.userSearch)
        assertTrue(vm.ui.value.messageResults.isNotEmpty())
        vm.closeListSearch()
        assertEmptyListSearch()
    }

    @Test fun `late contact and message responses cannot revive a closed search even after same query reopens`() {
        val users = hold("chat/search?q=needle", users("Old contact"))
        val messages = hold("chat/search-messages?q=needle", messages(102, "old needle"))
        intercept = { request -> users.answer(request) ?: messages.answer(request) }
        search("needle")
        users.waitStarted()
        messages.waitStarted()
        vm.closeListSearch()
        assertEmptyListSearch()
        intercept = { null }
        search("needle")
        await { vm.ui.value.messageResults.firstOrNull()?.id == 101L && vm.ui.value.userResults.firstOrNull()?.fullName == "Asha" }
        users.release()
        messages.release()
        assertStable {
            assertEquals(listOf("Asha"), vm.ui.value.userResults.map { it.fullName })
            assertEquals(listOf(101L), vm.ui.value.messageResults.map { it.id })
        }
    }

    @Test fun `late failure cannot replace newer results or restore a closed search error`() {
        val old = hold("chat/search?q=needle", fail = true)
        intercept = old::answer
        search("needle")
        old.waitStarted()
        vm.updateUserSearch("asha")
        vm.searchUsers()
        await { !vm.ui.value.searching && vm.ui.value.userResults.isNotEmpty() }
        old.release()
        assertStable {
            assertEquals("asha", vm.ui.value.userSearch)
            assertNull(vm.ui.value.error)
            assertFalse(vm.ui.value.searching)
        }
        vm.closeListSearch()
        assertEmptyListSearch()
    }

    @Test fun `account reset invalidates outstanding search results`() {
        val old = hold("chat/search-messages?q=needle", messages(102, "old needle"))
        intercept = old::answer
        search("needle")
        old.waitStarted()
        vm.setScope(2, 2)
        assertFalse(vm.ui.value.listSearchOpen)
        assertEmptyListSearch()
        old.release()
        assertStable { assertEmptyListSearch() }
    }

    @Test fun `closing thread search leaves list search untouched and rejects its late result`() {
        search("needle")
        await { vm.ui.value.messageResults.isNotEmpty() }
        vm.prepareConversation(1)
        val old = hold("chat/search-messages?q=needle&convId=1", messages(102, "old needle"))
        intercept = old::answer
        vm.openThreadSearch()
        vm.updateThreadSearch("needle")
        old.waitStarted()
        vm.closeThreadSearch()
        assertEquals(1L, vm.ui.value.selectedConversation?.id)
        assertFalse(vm.ui.value.threadSearchOpen)
        assertEquals("", vm.ui.value.threadSearchQuery)
        assertEquals("needle", vm.ui.value.userSearch)
        assertTrue(vm.ui.value.listSearchOpen)
        assertNull(vm.ui.value.jumpToMessageId)
        old.release()
        assertStable {
            assertTrue(vm.ui.value.threadSearchMatches.isEmpty())
            assertNull(vm.ui.value.jumpToMessageId)
        }
    }

    @Test fun `closing or replacing a conversation resets only its thread search`() {
        vm.openListSearch()
        vm.updateUserSearch("asha")
        vm.prepareConversation(1)
        vm.openThreadSearch()
        vm.updateThreadSearch("needle")
        await { vm.ui.value.threadSearchMatches.isNotEmpty() }
        vm.prepareConversation(2)
        assertFalse(vm.ui.value.threadSearchOpen)
        assertEquals("", vm.ui.value.threadSearchQuery)
        assertTrue(vm.ui.value.threadSearchMatches.isEmpty())
        vm.openThreadSearch()
        vm.updateThreadSearch("ravi")
        vm.closeConversation(1)
        assertTrue(vm.ui.value.threadSearchOpen)
        vm.closeConversation(2)
        assertFalse(vm.ui.value.threadSearchOpen)
        assertEquals("", vm.ui.value.threadSearchQuery)
        assertEquals("asha", vm.ui.value.userSearch)
        assertTrue(vm.ui.value.listSearchOpen)
    }

    private fun search(query: String) {
        vm.openListSearch()
        vm.updateUserSearch(query)
        vm.searchUsers()
        vm.searchAllMessages(query)
    }

    private fun assertEmptyListSearch() {
        assertEquals("", vm.ui.value.userSearch)
        assertTrue(vm.ui.value.userResults.isEmpty())
        assertTrue(vm.ui.value.messageResults.isEmpty())
        assertFalse(vm.ui.value.searching)
        assertNull(vm.ui.value.error)
    }

    private fun users(name: String) = """[{"id":8,"full_name":"$name"}]"""
    private fun messages(id: Long, content: String) = """[{"id":$id,"conversation_id":1,"sender_id":8,"content":"$content","created_at":"2026-10-13T09:00:00Z"}]"""

    private fun hold(path: String, body: String = "[]", fail: Boolean = false) = HeldResponse(path, body, fail).also { releases += it.gate }

    private class HeldResponse(val path: String, val body: String, val fail: Boolean) {
        val gate = CountDownLatch(1)
        private val started = CountDownLatch(1)
        private val returned = CountDownLatch(1)
        fun answer(request: ApiRequest): String? {
            if (request.path != path) return null
            started.countDown()
            check(gate.await(5, TimeUnit.SECONDS)) { "Search response was never released" }
            returned.countDown()
            if (fail) throw ApiError.Http(403, """{"error":"Denied"}""", request.method, request.path)
            return body
        }
        fun waitStarted() { assertTrue("Search was not started", started.await(3, TimeUnit.SECONDS)) }
        fun release() { gate.countDown(); assertTrue(returned.await(3, TimeUnit.SECONDS)) }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            if (System.nanoTime() > end) throw AssertionError("Timed out waiting for search state")
            Thread.sleep(10)
        }
    }

    private fun assertStable(assertion: () -> Unit) {
        repeat(20) { assertion(); Thread.sleep(10) }
    }
}
