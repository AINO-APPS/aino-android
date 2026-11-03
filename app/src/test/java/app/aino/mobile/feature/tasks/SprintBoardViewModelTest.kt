package app.aino.mobile.feature.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun creatingFromAColumnAppearsOnTheBoardEvenWhenRefreshFails() {
        val board = harness.boardViewModel
        harness.loadBoardPlanAndDetail()
        board.loadSprint()
        harness.await { board.ui.value.tasks.size == 1 }
        harness.rejectBoardReads = true
        harness.saved = """{"id":202,"title":"New column ticket","container":"sprint","sprint_id":10,"workflow_state_id":102,"workflow_state_key":"in_progress"}"""

        harness.viewModel.openCreateForColumn(WorkflowState(102, "in_progress", "In Progress"))
        harness.viewModel.updateDraft { it.copy(title = "New column ticket") }
        harness.viewModel.submitBacklog()
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
        harness.await { harness.viewModel.ui.value.searchResults.size == 1 }
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.detailResponse = harness.saved
        harness.planRows = harness.saved

        board.setRollover("backlog")
        board.completeSprint()
        harness.await {
            board.ui.value.tasks.isEmpty() &&
                harness.viewModel.ui.value.backlog.size == 1 &&
                harness.detailViewModel.ui.value.detail?.container == "backlog"
        }

        assertEquals("backlog", harness.viewModel.ui.value.searchResults.single().container)
        assertEquals(null, harness.detailViewModel.ui.value.detail?.sprintId)
        assertEquals("backlog", harness.completedRollover)
    }

    @Test
    fun rollingIntoAnotherSprintShowsTheNewSprintInSearchDetailAndItsBoard() {
        val board = harness.boardViewModel
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.ui.value.searchResults.size == 1 }
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":11")
        harness.detailResponse = harness.saved

        board.setRollover("11")
        board.completeSprint()
        harness.await { harness.detailViewModel.ui.value.detail?.sprintId == 11L && !board.ui.value.lifecycleBusy }
        assertEquals(11L, harness.viewModel.ui.value.searchResults.single().sprintId)
        assertEquals("sprint", harness.viewModel.ui.value.searchResults.single().container)
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
        harness.await { harness.viewModel.ui.value.tab == TaskTab.Backlog && harness.viewModel.ui.value.planLoaded }
        harness.boardColumnCount = 7

        harness.session.onReconnect()
        harness.await { harness.boardViewModel.ui.value.sprintBoard?.columns?.singleOrNull()?.count == 7 }

        assertEquals(7, harness.boardViewModel.ui.value.sprintBoard?.columns?.single()?.count)
    }
}
