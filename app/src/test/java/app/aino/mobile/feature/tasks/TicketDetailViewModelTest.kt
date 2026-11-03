package app.aino.mobile.feature.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketDetailViewModelTest {
    private lateinit var harness: TicketSessionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = TicketSessionHarness()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test fun blockerWriteUpdatesDetailBoardAndSearchFromTheAuthoritativeTicket() {
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.saved = harness.original.dropLast(1) + ""","is_blocked":true,"blocked_reason":"Waiting"}"""
        harness.detailResponse = harness.saved

        harness.detailViewModel.setBlocker(201, true, "Waiting")

        harness.await {
            harness.detailViewModel.ui.value.detail?.isBlocked == true &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.isBlocked == true &&
                harness.viewModel.searchResults.value.singleOrNull()?.isBlocked == true
        }
        assertEquals(2, harness.detailReads)
        assertEquals("Waiting", harness.detailViewModel.ui.value.detail?.blockedReason)
    }

    @Test fun parentWriteUpdatesEveryLoadedProjection() {
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.saved = harness.original.dropLast(1) + ""","parent_task_id":77}"""
        harness.detailResponse = harness.saved

        harness.detailViewModel.setParent(201, 77)

        harness.await {
            harness.detailViewModel.ui.value.detail?.parentTaskId == 77L &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.parentTaskId == 77L &&
                harness.viewModel.searchResults.value.singleOrNull()?.parentTaskId == 77L
        }
        assertEquals(2, harness.detailReads)
    }

    @Test fun timerStopUpdatesDetailAndListRowsTogether() {
        harness.loadBoardPlanAndDetail()
        harness.detailViewModel.stopTimer(201)
        harness.await {
            !harness.detailViewModel.ui.value.timerBusy &&
                harness.detailViewModel.ui.value.detail?.loggedMinutes == 5 &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.loggedMinutes == 5
        }
        assertEquals(5, harness.boardViewModel.ui.value.sprintBoard?.tickets?.single()?.loggedMinutes)
    }

    @Test fun separateTicketRoutesKeepEditingTabsHistoryAndTimersIsolated() {
        harness.loadBoardPlanAndDetail()
        val other = TicketDetailViewModel(202, harness.repository, harness.session)
        val models = androidx.lifecycle.ViewModelStore().apply { put("other", other) }
        try {
            harness.detailViewModel.startEdit()
            harness.detailViewModel.setDetailTab(DetailTab.History)
            harness.await { !other.ui.value.detailLoading }

            assertEquals(201L, harness.detailViewModel.ui.value.ticketId)
            assertEquals(202L, other.ui.value.ticketId)
            assertTrue(harness.detailViewModel.ui.value.detailEditing)
            assertFalse(other.ui.value.detailEditing)
            assertEquals(DetailTab.Comments, other.ui.value.detailTab)
            assertNull(other.ui.value.detail)
            assertEquals("Ship release", harness.detailViewModel.ui.value.detail?.title)
        } finally {
            models.clear()
        }
    }

    @Test fun endingTheSessionClearsAnAlreadyOpenDetail() {
        harness.loadBoardPlanAndDetail()
        harness.detailViewModel.startEdit()
        harness.session.close()

        assertNull(harness.detailViewModel.ui.value.detail)
        assertTrue(harness.detailViewModel.ui.value.history.isEmpty())
        assertTrue(harness.detailViewModel.ui.value.detailComments.isEmpty())
        assertFalse(harness.detailViewModel.ui.value.detailEditing)
        assertFalse(harness.detailViewModel.ui.value.timerRunning)
    }

    @Test fun schedulingFromDetailKeepsTheViewActionAfterTheRouteCloses() {
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"container\":\"sprint\"", "\"container\":\"scheduled\"")
            .replace("\"sprint_id\":10", "\"sprint_id\":null")
        harness.detailViewModel.schedule(201, "2026-11-03", closeAfter = true)
        harness.await { harness.detailViewModel.ui.value.closed }

        val notice = runBlocking { withTimeout(1_000) { harness.session.notices.first() } }
        assertEquals("View", notice.actionLabel)
        assertEquals("2026-11-03", notice.scheduledDate)
    }

    @Test fun savingChecklistPublishesTheFullTicketToTheLoadedRows() {
        harness.loadBoardPlanAndDetail()
        harness.detailResponse = harness.original.dropLast(1) +
            ""","acceptance_criteria":[{"id":"build","text":"Builds","done":true}]}"""

        harness.detailViewModel.saveCriteria(201, listOf(CriterionPayload("build", "Builds", true)))

        harness.await {
            harness.detailViewModel.ui.value.detail?.acceptanceCriteria?.singleOrNull()?.done == true &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.acceptanceCriteria?.singleOrNull()?.done == true
        }
    }

    @Test fun unscheduleUndoStillWorksAfterTheDetailViewModelIsCleared() {
        harness.loadBoardPlanAndDetail()
        val day = java.time.LocalDate.now().plusDays(1).toString()
        val scheduled = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"scheduled\",\"planned_date\":\"$day\"")
        harness.detailResponse = scheduled
        val route = TicketDetailViewModel(201, harness.repository, harness.session)
        val models = androidx.lifecycle.ViewModelStore().apply { put("route", route) }
        try {
            harness.await { !route.ui.value.detailLoading }
            harness.scheduledViewModel.loadScheduled()
            harness.await { harness.scheduledViewModel.ui.value.scheduledLoaded }
            harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
                .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
            route.unschedule(201, "Ship release", closeAfter = true, ask = false)
            harness.await { route.ui.value.closed && harness.scheduledViewModel.ui.value.scheduled.isEmpty() }
            val notice = runBlocking { withTimeout(1_000) { harness.session.notices.first() } }
            assertEquals("Undo", notice.actionLabel)
            models.clear()

            harness.saved = scheduled
            notice.action!!.invoke()

            harness.await { harness.scheduledViewModel.ui.value.scheduled.singleOrNull()?.plannedDate == day }
        } finally {
            models.clear()
        }
    }
}
