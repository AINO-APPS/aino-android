package app.aino.mobile.feature.tasks

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SprintCompletionViewModelTest {
    private lateinit var harness: CompletionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = CompletionHarness()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test fun defaultsToTheServersNextPlannedSprintRatherThanLocalOrdering() {
        harness.board.beginComplete()
        harness.await { harness.board.ui.value.completionPreview != null }

        assertEquals("next", harness.board.ui.value.rolloverTo)
        assertEquals("Next from server", harness.board.ui.value.rolloverOptions.single().name)
        assertEquals("11", harness.board.ui.value.completionPreview?.defaultTarget)
        assertEquals("sprints/10/completion-preview", harness.previews().single().path)
        harness.board.completeSprint()
        harness.await { harness.completions().isNotEmpty() }
        assertEquals("""{"rolloverTo":"next"}""", harness.completions().single().body!!.toString(Charsets.UTF_8))
    }

    @Test fun defaultBacklogWorksWithoutAnyNextSprint() {
        harness.preview = """{"supportsNewSprint":true,"defaultTarget":"backlog","defaultTargetType":"backlog",
            "selectedTarget":"backlog","hasNext":false,"moving":[],"staying":[],"movingPoints":0}"""
        harness.board.beginComplete()
        harness.await { harness.board.ui.value.completionPreview != null }

        assertEquals("backlog", harness.board.ui.value.rolloverTo)
        assertTrue(harness.board.ui.value.rolloverOptions.isEmpty())
    }

    @Test fun newSprintUsesServerDatesAndCountsAndSendsThePreviewedChoice() {
        harness.board.beginComplete()
        harness.await { harness.board.ui.value.completionPreview != null }
        harness.board.setRollover("new")
        harness.await { harness.board.ui.value.completionPreview?.selectedTarget == "new" }
        val preview = harness.board.ui.value.completionPreview!!

        assertEquals(listOf(201L), preview.movingParents!!.map { it.id })
        assertEquals(2, preview.movingSubtasks)
        assertEquals(listOf(204L), preview.staying.tickets.map { it.id })
        assertEquals(8.0, preview.movingPoints, 0.0)
        assertEquals("2026-11-10", preview.newSprint?.startDate)
        assertEquals("2026-11-23", preview.newSprint?.endDate)
        harness.board.completeSprint()
        harness.await { harness.completions().isNotEmpty() }
        assertEquals("""{"rolloverTo":"new"}""", harness.completions().single().body!!.toString(Charsets.UTF_8))
        assertEquals("sprints/10/completion-preview?rolloverTo=new", harness.previews().last().path)
    }

    @Test fun explicitBacklogIsPreviewedBeforeSendingIt() {
        harness.board.beginComplete()
        harness.await { harness.board.ui.value.completionPreview != null }
        harness.board.setRollover("backlog")
        harness.await { harness.board.ui.value.completionPreview?.selectedTarget == "backlog" }
        harness.board.completeSprint()
        harness.await { harness.completions().isNotEmpty() }

        assertEquals("sprints/10/completion-preview?rolloverTo=backlog", harness.previews().last().path)
        assertEquals("""{"rolloverTo":"backlog"}""", harness.completions().single().body!!.toString(Charsets.UTF_8))
    }

    @Test fun legacyServerGetsOnlyItsNumericDefaultAndNeverNewTargetRequests() {
        harness.preview = """{"defaultTarget":11,"moving":[{"id":201,"title":"Parent"}],"staying":[],"movingPoints":5}"""
        harness.board.beginComplete()
        harness.await { harness.board.ui.value.completionPreview != null }
        harness.board.setRollover("new")
        harness.board.completeSprint()
        harness.await { harness.completions().isNotEmpty() }

        assertEquals("11", harness.board.ui.value.rolloverTo)
        assertEquals(listOf("sprints/10/completion-preview"), harness.previews().map { it.path })
        assertEquals("""{"rolloverTo":"11"}""", harness.completions().single().body!!.toString(Charsets.UTF_8))
    }

    @Test fun failedPreviewIsVisibleAndCannotCompleteUntilRetried() {
        harness.rejectPreviews = true
        harness.board.beginComplete()
        harness.await { harness.board.ui.value.completionPreviewError != null }
        harness.board.completeSprint()

        assertEquals("Preview offline", harness.board.ui.value.completionPreviewError)
        assertTrue(harness.completions().isEmpty())
        harness.rejectPreviews = false
        harness.board.retryCompletionPreview()
        harness.await { harness.board.ui.value.completionPreview != null }
        assertTrue(harness.board.ui.value.canCompleteSprint)
    }

    @Test fun lateChoicePreviewCannotReplaceANewerSelection() {
        harness.board.beginComplete()
        harness.await { harness.board.ui.value.completionPreview != null }
        val gate = TicketSessionHarness.HeldTicketResponse()
        harness.newPreviewGate = gate
        harness.board.setRollover("new")
        gate.awaitStarted()
        assertFalse(harness.board.ui.value.canCompleteSprint)
        harness.board.setRollover("backlog")
        harness.await { harness.board.ui.value.completionPreview?.selectedTarget == "backlog" }
        gate.release()
        harness.await { harness.newPreviewReturned }

        assertEquals("backlog", harness.board.ui.value.rolloverTo)
        assertEquals("backlog", harness.board.ui.value.completionPreview?.selectedTarget)
        assertTrue(harness.board.ui.value.canCompleteSprint)
    }
}

private class CompletionHarness {
    val requests = Collections.synchronizedList(mutableListOf<ApiRequest>())
    @Volatile var rejectPreviews = false
    @Volatile var newPreviewGate: TicketSessionHarness.HeldTicketResponse? = null
    @Volatile var newPreviewReturned = false
    @Volatile var preview = """
        {"sprint":{"id":10,"name":"Sprint","status":"active"},"supportsNewSprint":true,"defaultTarget":11,
         "defaultTargetType":"next","selectedTarget":"next","hasNext":true,
         "nextPlannedSprint":{"id":11,"name":"Next from server","status":"planned"},
         "moving":[{"id":201,"title":"Done parent"},{"id":202,"title":"Done subtask","parent_task_id":201},
                   {"id":203,"title":"Open subtask","parent_task_id":201}],
         "movingParents":[{"id":201,"title":"Done parent"}],"movingSubtasks":2,
         "staying":[{"id":204,"title":"Finished ticket"}],"movingPoints":8}
    """.trimIndent()
    val session = TicketSession()
    val board = SprintBoardViewModel(TaskRepository(ApiClient { request ->
        requests += request
        val body = when {
            "completion-preview" in request.path -> {
                if (rejectPreviews) throw java.io.IOException("Preview offline")
                val target = request.path.substringAfter("rolloverTo=", "next")
                val response = preview.replace("\"selectedTarget\":\"next\"", "\"selectedTarget\":\"$target\"")
                    .let { if (target == "new") it.dropLast(1) + ""","newSprint":{"name":"Sprint 3","start_date":"2026-11-10","end_date":"2026-11-23"}}""" else it }
                if (target == "new") (newPreviewGate?.hold(response) ?: response).also { newPreviewReturned = true }
                else response
            }
            request.path == "sprints/10/board" -> """{"sprint":{"id":10,"name":"Sprint"},"columns":[],"tickets":[]}"""
            request.path == "sprints?project_id=7" -> """{"sprints":[{"id":11,"name":"Legacy next","status":"planned","project_id":7}]}"""
            else -> "{}"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    }), session)
    private val store = ViewModelStore().apply { put("board", board) }

    init {
        session.writePlanScope(PlanScope(
            userId = 1, projectScoped = true, selectedProjectId = 7,
            selectedSprintId = 10, currentSprintId = 10,
            sprints = listOf(
                AvailableSprint(10, "Sprint", status = "active", projectId = 7),
                AvailableSprint(99, "Other project", status = "planned", projectId = 8),
            ),
        ))
    }

    fun previews() = synchronized(requests) { requests.filter { "completion-preview" in it.path } }
    fun completions() = synchronized(requests) { requests.filter { it.path == "sprints/10/complete" } }
    fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Completion state did not settle: ${board.ui.value}", condition())
    }
    fun close() {
        newPreviewGate?.release()
        store.clear()
        session.close()
    }
}
