package app.aino.mobile.feature.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketWorklogsTest {
    private lateinit var harness: TicketSessionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = TicketSessionHarness()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test fun loggingWorkUpdatesListAndSharedTimeSpentWithTheEnteredFields() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"edit\":{\"allowed\":true},\"logWork\":{\"allowed\":true}")
        harness.worklogMutationResponse = """{"worklog":{"id":3,"day_worked":"2026-11-02","minutes":150,"comment":"Review","start_time":"09:30:00","canEdit":true,"canDelete":true},"ticket":{"id":201,"time_spent_minutes":150,"logged_minutes":150,"estimated_minutes":null},"warnings":[]}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        harness.detailViewModel.startLogWork()
        assertEquals(java.time.LocalDate.now().toString(), harness.detailViewModel.ui.value.worklogDraft?.dayWorked)
        harness.detailViewModel.saveWorklog(WorklogDraft(dayWorked = "2026-11-02", timeSpent = "2h 30m", startTime = "09:30", comment = "Review"))
        harness.await { harness.detailViewModel.ui.value.worklogs.any { it.id == 3L } }

        val ui = harness.detailViewModel.ui.value
        assertEquals(150, ui.detail?.timeSpentMinutes)
        assertEquals(150, harness.boardViewModel.ui.value.tasks.single().loggedMinutes)
        assertNull(ui.worklogDraft)
        assertEquals("2026-11-02", harness.worklogPayload?.get("day_worked")?.toString()?.trim('"'))
        assertEquals("2h 30m", harness.worklogPayload?.get("time_spent")?.toString()?.trim('"'))
        assertEquals("09:30", harness.worklogPayload?.get("start_time")?.toString()?.trim('"'))
        assertEquals("Review", harness.worklogPayload?.get("comment")?.toString()?.trim('"'))
    }

    @Test fun editingWorklogKeepsItsIdentityAndReplacesTimeSpentFromServer() {
        harness.worklogsResponse = """{"worklogs":[{"id":7,"day_worked":"2026-11-01","minutes":30,"comment":"Before","canEdit":true,"canDelete":true}],"time_spent_minutes":30}"""
        harness.worklogMutationResponse = """{"worklog":{"id":7,"day_worked":"2026-11-03","minutes":60,"comment":"Corrected","canEdit":true,"canDelete":true},"ticket":{"id":201,"time_spent_minutes":60}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        harness.detailViewModel.startEditWorklog(7)
        assertEquals("30m", harness.detailViewModel.ui.value.worklogDraft?.timeSpent)
        harness.detailViewModel.saveWorklog(WorklogDraft(dayWorked = "2026-11-03", timeSpent = "1h", comment = "Corrected"))
        harness.await { !harness.detailViewModel.ui.value.worklogBusy }

        val ui = harness.detailViewModel.ui.value
        assertEquals(listOf(7L), ui.worklogs.map { it.id })
        assertEquals("Corrected", ui.worklogs.single().comment)
        assertEquals(60, ui.detail?.timeSpentMinutes)
        assertEquals(60, harness.boardViewModel.ui.value.tasks.single().loggedMinutes)
        assertEquals(kotlinx.serialization.json.JsonNull, harness.worklogPayload?.get("start_time"))
    }

    @Test fun deletingWorklogRemovesItAndUsesServerTotal() {
        harness.worklogsResponse = """{"worklogs":[{"id":7,"day_worked":"2026-11-01","minutes":30,"canDelete":true}],"time_spent_minutes":30}"""
        harness.worklogMutationResponse = """{"ticket":{"id":201,"time_spent_minutes":0}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        harness.detailViewModel.deleteWorklog(7)
        harness.await { !harness.detailViewModel.ui.value.worklogBusy }

        assertTrue(harness.detailViewModel.ui.value.worklogs.isEmpty())
        assertEquals(0, harness.detailViewModel.ui.value.detail?.timeSpentMinutes)
        assertEquals(0, harness.boardViewModel.ui.value.tasks.single().loggedMinutes)
    }

    @Test fun invalidDurationStaysInlineAndKeepsDraftAndExistingWork() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"logWork\":{\"allowed\":true}")
        harness.worklogError = """{"code":"INVALID_DURATION","error":"Enter a positive duration such as 2h 30m."}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        harness.detailViewModel.startLogWork()
        val draft = WorklogDraft(timeSpent = "0m", comment = "Keep my notes")
        harness.detailViewModel.saveWorklog(draft)
        harness.await { !harness.detailViewModel.ui.value.worklogBusy }

        val ui = harness.detailViewModel.ui.value
        assertEquals("Enter a positive duration such as 2h 30m.", ui.worklogDurationError)
        assertEquals(draft, ui.worklogDraft)
        assertTrue(ui.worklogs.isEmpty())
        assertEquals(0, ui.detail?.timeSpentMinutes)
        assertNull(ui.error)
    }

    @Test fun realtimeWorklogChangeRefreshesListAndTimeSpent() {
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        harness.worklogsResponse = """{"worklogs":[{"id":8,"day_worked":"2026-11-03","minutes":45}],"time_spent_minutes":45}"""
        harness.detailResponse = harness.original.dropLast(1) + ""","time_spent_minutes":45,"logged_minutes":45}"""
        harness.boardTicket = harness.detailResponse
        harness.session.onRealtimeEvent("task_updated", kotlinx.serialization.json.buildJsonObject {
            put("taskId", kotlinx.serialization.json.JsonPrimitive(201))
            put("action", kotlinx.serialization.json.JsonPrimitive("updated"))
        })
        harness.await {
            val ui = harness.detailViewModel.ui.value
            ui.worklogs.any { it.id == 8L } && !ui.worklogsLoading && ui.detail?.timeSpentMinutes == 45
        }
        assertEquals(45, harness.detailViewModel.ui.value.detail?.timeSpentMinutes)
    }

    @Test fun serverCapabilitiesHideLogEditAndDeleteWithoutInferringFromRoles() {
        harness.worklogsResponse = """{"worklogs":[{"id":9,"author_id":1,"day_worked":"2026-11-03","minutes":20,"canEdit":false,"canDelete":false}],"time_spent_minutes":20}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        assertFalse(model.ui.value.canLogWork)
        assertFalse(model.ui.value.worklogs.single().canEdit)
        assertFalse(model.ui.value.worklogs.single().canDelete)
        model.startLogWork()
        model.startEditWorklog(9)
        model.deleteWorklog(9)
        assertNull(model.ui.value.worklogDraft)
        assertNull(harness.worklogPayload)

        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"edit\":{\"allowed\":false},\"logWork\":{\"allowed\":true}")
        model.refreshDetail()
        harness.await { !model.ui.value.detailLoading }
        assertTrue(model.ui.value.canLogWork)
        model.startLogWork()
        assertTrue(model.ui.value.worklogDraft != null)
    }

    @Test fun lateWorklogReadCannotUndoSuccessfulCorrection() {
        harness.worklogsResponse = """{"worklogs":[{"id":7,"day_worked":"2026-11-01","minutes":30,"canEdit":true}],"time_spent_minutes":30}"""
        harness.worklogMutationResponse = """{"worklog":{"id":7,"day_worked":"2026-11-03","minutes":60,"canEdit":true},"ticket":{"id":201,"time_spent_minutes":60}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val gate = TicketSessionHarness.HeldTicketResponse().also { harness.worklogsReadGate = it }
        harness.detailViewModel.loadWorklogs()
        gate.awaitStarted()
        harness.detailViewModel.startEditWorklog(7)
        harness.detailViewModel.saveWorklog(WorklogDraft(timeSpent = "1h"))
        harness.await { !harness.detailViewModel.ui.value.worklogBusy }
        gate.release()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        assertEquals(60, harness.detailViewModel.ui.value.worklogs.single().minutes)
        assertEquals(60, harness.detailViewModel.ui.value.detail?.timeSpentMinutes)
    }

    @Test fun lateTicketRefreshCannotRollBackTimeSpentAfterLoggingWork() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"logWork\":{\"allowed\":true}")
        harness.worklogMutationResponse = """{"worklog":{"id":3,"day_worked":"2026-11-03","minutes":60},"ticket":{"id":201,"time_spent_minutes":60}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val gate = TicketSessionHarness.HeldTicketResponse().also { harness.detailResponseGate = it }
        harness.detailViewModel.refreshDetail()
        gate.awaitStarted()
        harness.detailViewModel.startLogWork()
        harness.detailViewModel.saveWorklog(WorklogDraft(timeSpent = "1h"))
        harness.await { !harness.detailViewModel.ui.value.worklogBusy }
        gate.release()
        harness.await { !harness.detailViewModel.ui.value.detailLoading && !harness.detailViewModel.ui.value.worklogsLoading }
        assertEquals(60, harness.detailViewModel.ui.value.detail?.timeSpentMinutes)
        assertEquals(listOf(3L), harness.detailViewModel.ui.value.worklogs.map { it.id })
    }

    @Test fun openingTicketLoadsNewestWorklogsAndAuthoritativeTimeSpent() {
        harness.worklogsResponse = """{"worklogs":[
            {"id":1,"day_worked":"2026-11-01","minutes":30,"comment":"Earlier"},
            {"id":2,"day_worked":"2026-11-03","minutes":60,"comment":"Today","canEdit":true,"canDelete":true}
        ],"time_spent_minutes":90}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }

        val ui = harness.detailViewModel.ui.value
        assertEquals(listOf(2L, 1L), ui.worklogs.map { it.id })
        assertEquals(90, ui.detail?.timeSpentMinutes)
        assertEquals(90, harness.boardViewModel.ui.value.tasks.single().loggedMinutes)
    }
}
