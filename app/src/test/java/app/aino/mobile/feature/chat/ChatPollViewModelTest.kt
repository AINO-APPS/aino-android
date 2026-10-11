package app.aino.mobile.feature.chat

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.push.VisibleThread
import app.aino.mobile.core.realtime.RealtimeEvent
import app.aino.mobile.core.realtime.RoutedRealtimeEvent
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPollViewModelTest {
    private val paths = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val releases = mutableListOf<CountDownLatch>()
    private val store = ViewModelStore()
    @Volatile private var pollJson = pollJson(emptyMap())
    @Volatile private var loadFailure = false
    @Volatile private var voteFailure = false
    @Volatile private var createFailure = false
    @Volatile private var loadGate: CountDownLatch? = null
    @Volatile private var voteGate: CountDownLatch? = null
    @Volatile private var createGate: CountDownLatch? = null
    @Volatile private var afterVote: (() -> Unit)? = null
    private lateinit var vm: ChatViewModel
    private val message = ChatMessage(100, 5, 2, "Lunch?", "2026-10-10T10:00:00Z", formatType = "poll",
        metadata = Json.parseToJsonElement("""{"pollId":42,"options":["Soup","Salad"]}"""))

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = ChatThreadCacheTest.InMemoryMessagesDao()
        val api = ApiClient { request ->
            paths += request.path
            val body = when {
                request.path == "chat/conversations" -> """[{"id":5,"is_group":true,"group_name":"Lunch"}]"""
                request.path == "chat/polls/42" -> {
                    val snapshot = pollJson
                    loadGate?.await(5, TimeUnit.SECONDS)
                    if (loadFailure) throw ApiError.Network(request.method, request.path, IOException("offline"))
                    snapshot
                }
                request.path == "chat/polls/42/vote" -> {
                    bodies += request.body!!.decodeToString()
                    voteGate?.await(5, TimeUnit.SECONDS)
                    if (voteFailure) throw ApiError.Network(request.method, request.path, IOException("offline"))
                    afterVote?.invoke()
                    """{"ok":true,"votes":{"0":[1]}}"""
                }
                request.path == "chat/conversations/5/polls" -> {
                    bodies += request.body!!.decodeToString()
                    createGate?.await(5, TimeUnit.SECONDS)
                    if (createFailure) throw ApiError.Network(request.method, request.path, IOException("offline"))
                    """{"ok":true,"poll":$pollJson,"messageId":100}"""
                }
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        }
        vm = ChatViewModel(ChatRepository(api), { scope -> ChatCache(scope, ScopedCache(scope, dao)) })
        store.put("poll", vm)
        vm.setScope(1, 1)
        await { vm.ui.value.conversations.isNotEmpty() && !vm.ui.value.loading }
        vm.prepareConversation(5)
        await { vm.ui.value.selectedConversation?.id == 5L && !vm.ui.value.threadLoading }
        paths.clear()
    }

    @After fun cleanup() {
        releases.forEach { it.countDown() }
        store.clear()
        VisibleThread.clear()
        Dispatchers.resetMain()
    }

    @Test fun `outgoing calls capture the target and wait for single confirmation`() {
        vm.setCallsEnabled(true)
        val target = ChatConversation(8, otherUserId = 2, otherFullName = "Sam")
        vm.startCall(target, "voice")
        assertEquals(target, vm.ui.value.pendingCall?.conversation)
        assertEquals("voice", vm.ui.value.pendingCall?.callType)
        assertTrue(paths.none { it.contains("call") })
        val accepted = vm.takePendingCall()
        assertEquals(target, accepted?.conversation)
        assertNull(vm.takePendingCall())
        vm.startCall(target, "video")
        vm.cancelPendingCall()
        assertNull(vm.ui.value.pendingCall)
        assertTrue(paths.none { it.contains("call") })
    }

    @Test fun `disabled and blocked calls never create requests and account changes clear them`() {
        val target = ChatConversation(8, otherUserId = 2)
        vm.startCall(target, "voice")
        assertNull(vm.ui.value.pendingCall)
        vm.setCallsEnabled(true)
        vm.startCall(target.copy(isBlocked = true), "video")
        assertNull(vm.ui.value.pendingCall)
        vm.startCall(target, "video")
        assertNotNull(vm.ui.value.pendingCall)
        vm.setScope(null, null)
        assertNull(vm.ui.value.pendingCall)
    }

    @Test fun `failed load stays disabled and retry clears error using server data`() {
        loadFailure = true
        vm.loadPoll(42)
        await { vm.ui.value.pollStates[42]?.loadError != null && vm.ui.value.pollStates[42]?.loading == false }
        assertNull(vm.ui.value.polls[42])
        assertFalse(vm.ui.value.pollStates.getValue(42).canVote(null))
        assertNull(vm.ui.value.error)
        loadFailure = false
        pollJson = pollJson(mapOf(0 to listOf(1L, 2L)))
        vm.loadPoll(42)
        await { vm.ui.value.polls[42] != null && vm.ui.value.pollStates[42]?.loading == false }
        assertNull(vm.ui.value.pollStates.getValue(42).loadError)
        assertEquals(2, pollTally(vm.ui.value.polls[42], 2, 1).total)
    }

    @Test fun `unloaded invalid and closed polls never send a vote`() {
        vm.votePoll(message, 0)
        assertFalse(paths.contains("chat/polls/42/vote"))
        load()
        vm.votePoll(message, -1)
        vm.votePoll(message, 2)
        assertFalse(paths.contains("chat/polls/42/vote"))
        pollJson = pollJson(emptyMap(), closed = true)
        vm.loadPoll(42)
        await { vm.ui.value.polls[42]?.closedAt != null && vm.ui.value.pollStates[42]?.loading == false }
        vm.votePoll(message, 0)
        assertFalse(paths.contains("chat/polls/42/vote"))
    }

    @Test fun `pending vote blocks duplicate taps failed vote retains original tally and retry works`() {
        pollJson = pollJson(mapOf(0 to listOf(2L)))
        load()
        val original = vm.ui.value.polls.getValue(42)
        voteFailure = true
        val gate = gate().also { voteGate = it }
        vm.votePoll(message, 1)
        await { paths.contains("chat/polls/42/vote") }
        assertTrue(vm.ui.value.pollStates.getValue(42).voting)
        assertEquals(1, vm.ui.value.pollStates.getValue(42).pendingOptionIndex)
        vm.votePoll(message, 0)
        assertEquals(1, paths.count { it == "chat/polls/42/vote" })
        assertEquals(original, vm.ui.value.polls[42])
        gate.countDown()
        await { vm.ui.value.pollStates[42]?.voteError != null && vm.ui.value.pollStates[42]?.voting == false }
        assertNull(vm.ui.value.pollStates.getValue(42).pendingOptionIndex)
        assertEquals(original, vm.ui.value.polls[42])
        voteFailure = false
        afterVote = { pollJson = pollJson(mapOf(1 to listOf(1L, 2L))) }
        vm.votePoll(message, 1)
        await { pollTally(vm.ui.value.polls[42], 2, 1).options[1].count == 2 && vm.ui.value.pollStates[42]?.voting == false }
        assertNull(vm.ui.value.pollStates.getValue(42).voteError)
        assertEquals("Member 1", vm.ui.value.polls.getValue(42).votes.getValue(1).first().fullName)
    }

    @Test fun `successful vote with failed authoritative refresh blocks voting until load retry`() {
        load()
        afterVote = { loadFailure = true }
        vm.votePoll(message, 0)
        await { vm.ui.value.pollStates[42]?.loadError != null && vm.ui.value.pollStates[42]?.voting == false }
        assertNull(vm.ui.value.pollStates.getValue(42).voteError)
        assertTrue(vm.ui.value.pollStates.getValue(42).loadError!!.startsWith("Vote sent"))
        assertFalse(vm.ui.value.pollStates.getValue(42).canVote(vm.ui.value.polls[42]))
        loadFailure = false
        pollJson = pollJson(mapOf(0 to listOf(1L)))
        vm.loadPoll(42)
        await { vm.ui.value.pollStates[42]?.loadError == null && vm.ui.value.pollStates[42]?.loading == false }
        assertTrue(pollTally(vm.ui.value.polls[42], 2, 1).options[0].mine)
    }

    @Test fun `single replacements and multi toggles use the authoritative response not optimistic math`() {
        for (multi in listOf(false, true)) {
            pollJson = pollJson(mapOf(0 to listOf(1L)), multi = multi)
            vm.loadPoll(42)
            await { vm.ui.value.polls[42]?.multiSelect == multi && vm.ui.value.pollStates[42]?.loading == false }
            afterVote = { pollJson = pollJson(if (multi) mapOf(0 to listOf(1L), 1 to listOf(1L)) else mapOf(1 to listOf(1L)), multi = multi) }
            vm.votePoll(message, 1)
            await { pollTally(vm.ui.value.polls[42], 2, 1).options[1].mine && vm.ui.value.pollStates[42]?.voting == false }
            assertEquals(multi, pollTally(vm.ui.value.polls[42], 2, 1).options[0].mine)
            afterVote = { pollJson = pollJson(if (multi) mapOf(0 to listOf(1L)) else emptyMap(), multi = multi) }
            vm.votePoll(message, 1)
            await { !pollTally(vm.ui.value.polls[42], 2, 1).options[1].mine && vm.ui.value.pollStates[42]?.voting == false }
        }
    }

    @Test fun `realtime refresh during an in flight load is replayed once and stays reactive`() {
        val gate = gate().also { loadGate = it }
        vm.loadPoll(42)
        await { paths.count { it == "chat/polls/42" } == 1 }
        pollJson = pollJson(mapOf(1 to listOf(2L)))
        vm.onRealtimeEvent(RoutedRealtimeEvent(
            RealtimeEvent.ChatPollVote, Json.parseToJsonElement("""{"pollId":42,"conversationId":5}"""),
        ))
        gate.countDown()
        await { pollTally(vm.ui.value.polls[42], 2, 1).total == 1 && vm.ui.value.pollStates[42]?.loading == false }
        assertEquals(2, paths.count { it == "chat/polls/42" })
    }

    @Test fun `realtime refresh during the post vote load is replayed after authoritative completion`() {
        load()
        val gate = gate().also { loadGate = it }
        afterVote = { pollJson = pollJson(mapOf(0 to listOf(1L))) }
        vm.votePoll(message, 0)
        await { paths.count { it == "chat/polls/42" } == 2 }
        pollJson = pollJson(mapOf(0 to listOf(1L, 2L)))
        vm.onRealtimeEvent(RoutedRealtimeEvent(
            RealtimeEvent.ChatPollVote, Json.parseToJsonElement("""{"pollId":42,"conversationId":5}"""),
        ))
        vm.votePoll(message, 1)
        assertEquals(1, paths.count { it == "chat/polls/42/vote" })
        gate.countDown()
        await { pollTally(vm.ui.value.polls[42], 2, 1).total == 2 && vm.ui.value.pollStates[42]?.loading == false && vm.ui.value.pollStates[42]?.voting == false }
        assertEquals(3, paths.count { it == "chat/polls/42" })
    }

    @Test fun `creation is validated duplicate safe retains error and completes only on success`() {
        vm.createPoll("Q".repeat(501), listOf("A", "B"), false)
        assertNotNull(vm.ui.value.pollCreation.error)
        assertFalse(paths.contains("chat/conversations/5/polls"))
        createFailure = true
        val gate = gate().also { createGate = it }
        vm.createPoll(" Lunch? ", listOf(" Soup ", "Salad", "  "), false)
        await { paths.contains("chat/conversations/5/polls") }
        assertTrue(vm.ui.value.pollCreation.sending)
        vm.createPoll("Different", listOf("X", "Y"), true)
        assertEquals(1, paths.count { it == "chat/conversations/5/polls" })
        gate.countDown()
        await { vm.ui.value.pollCreation.error != null && !vm.ui.value.pollCreation.sending }
        assertNull(vm.ui.value.pollCreation.createdPollId)
        val body = Json.parseToJsonElement(bodies.last()).jsonObject
        assertEquals("\"Lunch?\"", body.getValue("question").toString())
        assertEquals("[\"Soup\",\"Salad\"]", body.getValue("options").toString())
        createFailure = false
        vm.createPoll(" Lunch? ", listOf(" Soup ", "Salad"), false)
        await { vm.ui.value.pollCreation.createdPollId == 42L }
        assertFalse(vm.ui.value.pollCreation.sending)
        assertNull(vm.ui.value.pollCreation.error)
        vm.createPoll("Duplicate", listOf("X", "Y"), true)
        assertEquals(2, paths.count { it == "chat/conversations/5/polls" })
        vm.resetPollCreation()
        assertEquals(PollCreationState(), vm.ui.value.pollCreation)
    }

    @Test fun `account reset prevents an old poll request repopulating cleared state`() {
        val gate = gate().also { loadGate = it }
        vm.loadPoll(42)
        await { paths.contains("chat/polls/42") }
        vm.setScope(null, null)
        gate.countDown()
        Thread.sleep(50)
        assertTrue(vm.ui.value.polls.isEmpty())
        assertTrue(vm.ui.value.pollStates.isEmpty())
    }

    private fun load() {
        vm.loadPoll(42)
        await { vm.ui.value.polls[42] != null && vm.ui.value.pollStates[42]?.loading == false }
    }

    private fun gate() = CountDownLatch(1).also(releases::add)
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Poll state did not settle: ${vm.ui.value.pollStates}", predicate())
    }

    private fun pollJson(votes: Map<Int, List<Long>>, multi: Boolean = false, closed: Boolean = false): String {
        val entries = votes.entries.joinToString(",") { (index, ids) ->
            "\"$index\":[${ids.joinToString(",") { "{\"userId\":$it,\"fullName\":\"Member $it\"}" }}]"
        }
        return """{"id":42,"conversation_id":5,"creator_id":1,"question":"Lunch?","options":["Soup","Salad"],"multi_select":$multi,"closed_at":${if (closed) "\"2026-10-10\"" else "null"},"votes":{$entries}}"""
    }
}
