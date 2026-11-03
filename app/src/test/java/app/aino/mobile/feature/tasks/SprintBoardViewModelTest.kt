package app.aino.mobile.feature.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SprintBoardViewModelTest {
    private lateinit var harness: TicketSessionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = TicketSessionHarness()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test
    fun failedUndoKeepsANewerTicketAndOnlyOptimisticallyChangesStatus() {
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"workflow_state_id\":101", "\"workflow_state_id\":102")
            .replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"in_progress\"")
        harness.boardViewModel.moveTask(harness.boardViewModel.ui.value.tasks.single(), WorkflowState(102, "in_progress", "In Progress"))
        val notice = runBlocking { withTimeout(5_000) { harness.session.notices.first() } }
        assertEquals("Undo", notice.actionLabel)
        harness.detailResponse = harness.saved.replace("Ship release", "Edited after move")
        harness.detailViewModel.refreshDetail()
        harness.await { harness.detailViewModel.ui.value.detail?.title == "Edited after move" }
        harness.holdAndRejectNextStatusWrite()

        notice.action?.invoke()
        assertTrue(harness.awaitStatusWrite())
        assertEquals("Edited after move", harness.boardViewModel.ui.value.tasks.single().title)
        assertEquals("todo", harness.boardViewModel.ui.value.tasks.single().workflowStateKey)
        harness.detailResponse = harness.saved.replace("Ship release", "Newer during Undo")
        harness.detailViewModel.refreshDetail()
        harness.await { harness.detailViewModel.ui.value.detail?.title == "Newer during Undo" }
        harness.finishStatusWrite()
        harness.await { harness.boardViewModel.ui.value.error != null }
        assertEquals("Newer during Undo", harness.boardViewModel.ui.value.tasks.single().title)
        assertEquals("in_progress", harness.boardViewModel.ui.value.tasks.single().workflowStateKey)
    }

    @Test
    fun failedUndoWithoutANewerTicketRestoresTheWholeCurrentCopy() {
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"workflow_state_id\":101", "\"workflow_state_id\":102")
            .replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"in_progress\"")
        harness.boardViewModel.moveTask(harness.boardViewModel.ui.value.tasks.single(), WorkflowState(102, "in_progress", "In Progress"))
        val notice = runBlocking { withTimeout(5_000) { harness.session.notices.first() } }
        harness.holdAndRejectNextStatusWrite()
        notice.action?.invoke()
        assertTrue(harness.awaitStatusWrite())
        harness.finishStatusWrite()
        harness.await { harness.boardViewModel.ui.value.error != null }
        assertEquals("in_progress", harness.boardViewModel.ui.value.tasks.single().workflowStateKey)
        assertEquals(102L, harness.boardViewModel.ui.value.tasks.single().workflowStateId)
    }

    @Test
    fun bareCancellationResponseCanBeEnrichedByLaterHistoryLoads() {
        harness.boardTicket = harness.original.replace("\"can\":{", "\"can\":{\"cancel\":{\"allowed\":true},")
        harness.detailResponse = harness.boardTicket
        harness.loadBoardPlanAndDetail()
        harness.boardViewModel.requestDelete(harness.boardViewModel.ui.value.tasks.single())
        harness.boardViewModel.acceptConfirm()
        harness.await { harness.boardViewModel.ui.value.tasks.isEmpty() }
        harness.boardTicket = ""
        harness.cancelledRows = harness.original.replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
        harness.boardViewModel.loadSprint()
        harness.await { harness.boardViewModel.ui.value.cancelled.size == 1 }
        assertEquals("cancelled", harness.boardViewModel.ui.value.cancelled.single().workflowStateKey)
        assertTrue(harness.boardViewModel.ui.value.tasks.isEmpty())
    }

    @Test
    fun lateHistoryReadKeepsATicketCancelledWhileItWasLoading() {
        harness.boardTicket = harness.original.replace("\"can\":{", "\"can\":{\"cancel\":{\"allowed\":true},")
        harness.detailResponse = harness.boardTicket
        harness.loadBoardPlanAndDetail()
        val gate = TicketSessionHarness.HeldTicketResponse().also { harness.cancelledReadGate = it }
        harness.boardViewModel.loadSprint()
        gate.awaitStarted()
        harness.cancelResponse = harness.boardTicket.replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
        harness.boardViewModel.requestDelete(harness.boardViewModel.ui.value.tasks.single())
        harness.boardViewModel.acceptConfirm()
        harness.await { harness.boardViewModel.ui.value.cancelled.size == 1 }
        val statsBefore = harness.sprintStatsReads
        gate.release()
        // The statistics read follows history, so it confirms the held response was applied.
        harness.await { harness.sprintStatsReads > statsBefore }
        assertEquals(listOf(201L), harness.boardViewModel.ui.value.cancelled.map { it.id })
        assertTrue(harness.boardViewModel.ui.value.tasks.isEmpty())
    }

    @Test
    fun cancelledHistoryLoadFailureIsVisible() {
        harness.loadBoardPlanAndDetail()
        harness.rejectCancelledReads = true
        harness.boardViewModel.loadSprint()
        harness.await { harness.boardViewModel.ui.value.error != null }
        assertEquals("History offline", harness.boardViewModel.ui.value.error)
        assertEquals(1, harness.boardViewModel.ui.value.tasks.size)
    }

    @Test
    fun realtimeRefreshesALoadedBoardOnceAfterRepeatedRequests() {
        harness.loadBoardPlanAndDetail()
        repeat(3) {
            val before = harness.boardReads
            harness.viewModel.loadSprint()
            harness.await { harness.boardReads > before }
        }
        val before = harness.boardReads

        harness.session.onRealtimeEvent("task_updated", kotlinx.serialization.json.buildJsonObject {
            put("taskId", kotlinx.serialization.json.JsonPrimitive(201))
            put("action", kotlinx.serialization.json.JsonPrimitive("updated"))
        })

        harness.await { harness.boardReads > before }
        Thread.sleep(500)
        assertEquals(before + 1, harness.boardReads)
    }

    @Test
    fun creatingFromAColumnAppearsOnTheBoardEvenWhenRefreshFails() {
        val board = harness.boardViewModel
        harness.loadBoardPlanAndDetail()
        board.loadSprint()
        harness.await { board.ui.value.tasks.size == 1 }
        harness.rejectBoardReads = true
        harness.saved = """{"id":202,"title":"New column ticket","container":"sprint","sprint_id":10,"workflow_state_id":102,"workflow_state_key":"in_progress"}"""

        harness.viewModelPlan.openCreateForColumn(WorkflowState(102, "in_progress", "In Progress"))
        harness.viewModelPlan.updateDraft { it.copy(title = "New column ticket") }
        harness.viewModelPlan.submitBacklog()
        harness.await { board.ui.value.tasks.any { it.id == 202L } }

        assertEquals("in_progress", board.ui.value.tasks.single { it.id == 202L }.workflowStateKey)
        assertEquals(10L, board.ui.value.tasks.single { it.id == 202L }.sprintId)
    }

    @Test
    fun completingWithRolloverUpdatesPlacementInBoardPlanSearchAndOpenDetail() {
        val board = harness.boardViewModel
        harness.loadBoardPlanAndDetail()
        board.loadSprint()
        harness.await { board.ui.value.tasks.size == 1 }
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.detailResponse = harness.saved
        harness.planRows = harness.saved

        board.setRollover("backlog")
        board.completeSprint()
        harness.await {
            board.ui.value.tasks.isEmpty() &&
                harness.viewModelPlan.ui.value.backlog.size == 1 &&
                harness.detailViewModel.ui.value.detail?.container == "backlog"
        }

        assertEquals("backlog", harness.viewModel.searchResults.value.single().container)
        assertEquals(null, harness.detailViewModel.ui.value.detail?.sprintId)
        assertEquals("backlog", harness.completedRollover)
    }

    @Test
    fun rollingIntoAnotherSprintShowsTheNewSprintInSearchDetailAndItsBoard() {
        val board = harness.boardViewModel
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":11")
        harness.detailResponse = harness.saved

        board.setRollover("11")
        board.completeSprint()
        harness.await { harness.detailViewModel.ui.value.detail?.sprintId == 11L && !board.ui.value.lifecycleBusy }
        assertEquals(11L, harness.viewModel.searchResults.value.single().sprintId)
        assertEquals("sprint", harness.viewModel.searchResults.value.single().container)
        assertEquals("11", harness.completedRollover)
        assertEquals(emptyList<Long>(), board.ui.value.tasks.map { it.id })

        harness.viewModel.selectSprint(11)
        harness.await { board.ui.value.tasks.singleOrNull()?.sprintId == 11L }
        assertEquals(listOf(201L), board.ui.value.tasks.map { it.id })
        assertEquals("sprint", harness.detailViewModel.ui.value.detail?.container)
    }

    @Test
    fun reconnectRefreshesAnAlreadyLoadedBoardWhileAnotherTabIsVisible() {
        harness.loadBoardPlanAndDetail()
        harness.viewModel.selectTab(TaskTab.Backlog)
        harness.await { harness.viewModel.ui.value.tab == TaskTab.Backlog && harness.viewModelPlan.ui.value.planLoaded }
        harness.boardColumnCount = 7

        harness.session.onReconnect()
        harness.await { harness.boardViewModel.ui.value.sprintBoard?.columns?.singleOrNull()?.count == 7 }

        assertEquals(7, harness.boardViewModel.ui.value.sprintBoard?.columns?.single()?.count)
    }
}
