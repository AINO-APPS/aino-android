package app.aino.mobile.feature.notes

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var server: JsonObject = Json.parseToJsonElement(
        """{"pages":[{"id":"a","title":"A","content":"<p>a</p>"},{"id":"b","title":"B","content":"<p>b</p>"}],"todos":[{"id":"t"}],"activePageId":"a"}""",
    ).jsonObject
    private val puts = mutableListOf<JsonObject>()
    private var failPut: ApiError? = null

    private val api = ApiClient { request: ApiRequest ->
        when {
            request.method == "GET" && request.path == "notes" -> ApiResponse(200, emptyMap(), """{"data":$server}""".toByteArray())
            request.method == "PUT" && request.path == "notes" -> {
                failPut?.let { throw it }
                val data = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject["data"]!!.jsonObject
                puts += data
                server = data
                ApiResponse(200, emptyMap(), """{"ok":true}""".toByteArray())
            }
            else -> ApiResponse(200, emptyMap(), """{"users":[]}""".toByteArray())
        }
    }

    private class MemoryDrafts : NotesDraftStore {
        val saved = mutableMapOf<Long, Pair<PendingChanges, JsonObject>>()
        override fun read(userId: Long) = saved[userId]
        override fun write(userId: Long, pending: PendingChanges, subset: JsonObject) { saved[userId] = pending to subset }
        override fun clear(userId: Long) { saved.remove(userId) }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(drafts: NotesDraftStore? = null) = NotesViewModel(NotesRepository(api), drafts, dispatcher)

    @Test
    fun titleEditsSaveAfterTheDebounceAndMergeOntoTheFreshServerCopy() = runTest(dispatcher) {
        val vm = vm()
        vm.bindUser(7)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.ui.value.pages.map { it.id })
        vm.setTitle("a", "A2")
        // The web edits page B meanwhile.
        server = server.with("pages" to JsonArray(server.pages().map { if (it.id == "b") it.raw.with("content" to JsonPrimitive("<p>web</p>")) else it.raw }))
        advanceTimeBy(9_000)
        assertTrue(puts.isEmpty())
        advanceTimeBy(2_000)
        advanceUntilIdle()
        val saved = puts.single()
        assertEquals("A2", saved.pages().first { it.id == "a" }.title)
        assertEquals("<p>web</p>", saved.pages().first { it.id == "b" }.content)
        assertEquals(server["todos"], saved["todos"])
        assertEquals("<p>web</p>", vm.ui.value.page("b")!!.content)
        assertTrue(vm.ui.value.save == SaveStatus.Saved || vm.ui.value.save == SaveStatus.Idle)
    }

    @Test
    fun openingAndClosingAPageWithoutEditsNeverRewritesContent() = runTest(dispatcher) {
        val vm = vm()
        vm.bindUser(7)
        advanceUntilIdle()
        vm.openEditor("b")
        vm.closeEditor("b")
        advanceUntilIdle()
        puts.forEach { saved -> assertEquals("<p>b</p>", saved.pages().first { it.id == "b" }.content) }
        assertEquals("<p>b</p>", server.pages().first { it.id == "b" }.content)
    }

    @Test
    fun editorChangesAreCommittedAndFlushed() = runTest(dispatcher) {
        val vm = vm()
        vm.bindUser(7)
        advanceUntilIdle()
        vm.openEditor("a")
        val session = vm.editor!!
        val block = session.doc.blocks.single()
        val value = session.fieldValue(block)
        session.onValueChange(block.id, TextFieldValue(value.text + "!", TextRange(value.text.length + 1)))
        vm.flush()
        advanceUntilIdle()
        val page = server.pages().first { it.id == "a" }
        assertEquals("<p>a!</p>", page.content)
        assertEquals(7L, page.lastEditedBy)
    }

    @Test
    fun structuralChangesSaveImmediatelyWithWebShapedPages() = runTest(dispatcher) {
        val vm = vm()
        vm.bindUser(7)
        advanceUntilIdle()
        val id = vm.createFromTemplate("journal")
        advanceTimeBy(1_000)
        advanceUntilIdle()
        val saved = server.pages().first { it.id == id }
        assertTrue(saved.title.startsWith("Journal — "))
        assertEquals(7L, saved.createdBy)
        val journal = server.folders().single { it.name == "Journal" }
        assertEquals(journal.id, saved.folderId)
        vm.deletePage(id)
        advanceUntilIdle()
        assertTrue(server.pages().none { it.id == id })
    }

    @Test
    fun oversizedNotebooksSurfaceTheServerErrorWithoutRetryLoops() = runTest(dispatcher) {
        val drafts = MemoryDrafts()
        failPut = ApiError.Http(400, """{"error":"Notebook data too large (max 2 MB)"}""", "PUT", "notes")
        val vm = vm(drafts)
        vm.bindUser(7)
        advanceUntilIdle()
        vm.togglePin("a")
        advanceUntilIdle()
        assertEquals(SaveStatus.Failed, vm.ui.value.save)
        assertEquals(NOTEBOOK_TOO_LARGE, vm.ui.value.saveError)
        assertTrue(drafts.saved.containsKey(7L))
        // The pending edit survives a restart and is replayed once saving works again.
        failPut = null
        val restarted = vm(drafts)
        restarted.bindUser(7)
        advanceUntilIdle()
        assertEquals(true, server.pages().first { it.id == "a" }.pinned)
        assertTrue(drafts.saved.isEmpty())
    }
}
