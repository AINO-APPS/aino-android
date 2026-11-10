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

    @Test fun worklogListRefreshPublishesBothEstimatesIncludingNullAndZero() {
        harness.detailResponse = harness.original.dropLast(1) + """, "original_estimate_minutes":120,"remaining_estimate_minutes":90}"""
        harness.worklogsResponse = """{"worklogs":[],"time_spent_minutes":30,"original_estimate_minutes":60,"remaining_estimate_minutes":0}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        assertEquals(60, harness.detailViewModel.ui.value.detail?.originalEstimateMinutes)
        assertEquals(0, harness.detailViewModel.ui.value.detail?.remainingEstimateMinutes)
        harness.worklogsResponse = """{"worklogs":[],"time_spent_minutes":30,"original_estimate_minutes":null,"remaining_estimate_minutes":null}"""
        harness.detailViewModel.loadWorklogs()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        assertNull(harness.boardViewModel.ui.value.tasks.single().originalEstimateMinutes)
        assertNull(harness.detailViewModel.ui.value.detail?.remainingEstimateMinutes)
    }

    @Test fun estimateSaveGatesEveryCompetingWorklogWriteUntilPublication() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"canEditEstimate\":{\"allowed\":true},\"logWork\":{\"allowed\":true}")
        harness.worklogsResponse = """{"worklogs":[{"id":7,"day_worked":"2026-11-01","minutes":30,"canEdit":true,"canDelete":true}],"time_spent_minutes":30}"""
        harness.estimateResponse = """{"ticket":{"id":201,"time_spent_minutes":30,"original_estimate_minutes":120,"remaining_estimate_minutes":90}}"""
        harness.worklogMutationResponse = """{"worklog":{"id":8,"day_worked":"2026-11-01","minutes":60},"ticket":{"id":201,"time_spent_minutes":90,"original_estimate_minutes":120,"remaining_estimate_minutes":30}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        val gate = TicketSessionHarness.HeldTicketResponse().also { harness.estimateWriteGate = it }
        model.startEstimateEdit(EstimateKind.Original)
        model.saveEstimate("2h")
        gate.awaitStarted()
        try {
            model.startLogWork()
            model.startEditWorklog(7)
            model.saveWorklog(WorklogDraft(timeSpent = "1h"))
            model.deleteWorklog(7)
            assertNull(model.ui.value.worklogDraft)
            assertFalse(model.ui.value.worklogBusy)
            assertNull(harness.worklogPayload)
        } finally { gate.release() }
        harness.await { !model.ui.value.estimateBusy }
        assertEquals(90, model.ui.value.detail?.remainingEstimateMinutes)
        model.saveWorklog(WorklogDraft(timeSpent = "1h"))
        harness.await { !model.ui.value.worklogBusy }
        assertEquals(90, model.ui.value.detail?.timeSpentMinutes)
        assertEquals(30, model.ui.value.detail?.remainingEstimateMinutes)
        assertEquals(2, harness.effortWrites)
    }

    @Test fun loggingWorkDefaultsToAutomaticRemainingAdjustment() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"logWork\":{\"allowed\":true}")
        harness.worklogMutationResponse = """{"worklog":{"id":3,"day_worked":"2026-11-03","minutes":60},"ticket":{"id":201,"time_spent_minutes":60}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        harness.detailViewModel.saveWorklog(WorklogDraft(timeSpent = "1h"))
        harness.await { !harness.detailViewModel.ui.value.worklogBusy }
        assertEquals("\"auto\"", harness.worklogPayload?.get("remaining_mode")?.toString())
        assertFalse(harness.worklogPayload?.containsKey("remaining_estimate") == true)
    }

    @Test fun loggingWorkSendsEachRemainingChoiceAndUsesServerEstimates() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"logWork\":{\"allowed\":true}")
        harness.worklogMutationResponse = """{"worklog":{"id":3,"day_worked":"2026-11-03","minutes":60},"ticket":{"id":201,"time_spent_minutes":60,"original_estimate_minutes":120,"remaining_estimate_minutes":30}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        for (mode in listOf("auto", "set", "leave")) {
            harness.detailViewModel.saveWorklog(WorklogDraft(timeSpent = "1h", remainingMode = mode, remainingEstimate = "30m"))
            harness.await { !harness.detailViewModel.ui.value.worklogBusy }
            assertRemainingPayload(mode)
            assertEquals(30, harness.detailViewModel.ui.value.detail?.remainingEstimateMinutes)
            assertEquals(120, harness.boardViewModel.ui.value.tasks.single().originalEstimateMinutes)
        }
    }

    private fun assertRemainingPayload(mode: String) {
        assertEquals("\"$mode\"", harness.worklogPayload?.get("remaining_mode")?.toString())
        if (mode == "set") assertEquals("\"30m\"", harness.worklogPayload?.get("remaining_estimate")?.toString())
        else assertFalse(harness.worklogPayload?.containsKey("remaining_estimate") == true)
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

    @Test fun editingWorkSendsEachRemainingChoiceAndRefreshesBothEstimates() {
        harness.worklogsResponse = """{"worklogs":[{"id":7,"day_worked":"2026-11-01","minutes":30,"canEdit":true}],"time_spent_minutes":30}"""
        harness.worklogMutationResponse = """{"worklog":{"id":7,"day_worked":"2026-11-01","minutes":60,"canEdit":true},"ticket":{"id":201,"time_spent_minutes":60,"original_estimate_minutes":120,"remaining_estimate_minutes":30}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        for (mode in listOf("auto", "set", "leave")) {
            harness.detailViewModel.startEditWorklog(7)
            assertEquals("auto", harness.detailViewModel.ui.value.worklogDraft?.remainingMode)
            harness.detailViewModel.saveWorklog(WorklogDraft(timeSpent = "1h", remainingMode = mode, remainingEstimate = "30m"))
            harness.await { !harness.detailViewModel.ui.value.worklogBusy }
            assertRemainingPayload(mode)
            assertEquals(listOf(7L), harness.detailViewModel.ui.value.worklogs.map { it.id })
            assertEquals(30, harness.detailViewModel.ui.value.detail?.remainingEstimateMinutes)
        }
    }

    @Test fun deletingWorkSendsEachRemainingChoiceAndRefreshesBothEstimates() {
        harness.worklogMutationResponse = """{"ticket":{"id":201,"time_spent_minutes":0,"original_estimate_minutes":120,"remaining_estimate_minutes":30}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        for (mode in listOf("auto", "set", "leave")) {
            harness.worklogsResponse = """{"worklogs":[{"id":7,"day_worked":"2026-11-01","minutes":30,"canDelete":true}],"time_spent_minutes":30}"""
            harness.detailViewModel.loadWorklogs()
            harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
            harness.detailViewModel.deleteWorklog(7, mode, "30m")
            harness.await { !harness.detailViewModel.ui.value.worklogBusy }
            assertRemainingPayload(mode)
            assertTrue(harness.detailViewModel.ui.value.worklogs.isEmpty())
            assertEquals(0, harness.detailViewModel.ui.value.detail?.timeSpentMinutes)
            assertEquals(30, harness.detailViewModel.ui.value.detail?.remainingEstimateMinutes)
        }
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

    @Test fun attendanceWarningsPersistAfterCreateAndEditUntilACleanSave() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"logWork\":{\"allowed\":true}")
        harness.worklogMutationResponse = """{"worklog":{"id":3,"day_worked":"2026-11-03","minutes":60,"canEdit":true},"ticket":{"id":201,"time_spent_minutes":60},"warnings":["ON_LEAVE","EXCEEDS_ATTENDANCE"]}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        model.saveWorklog(WorklogDraft(timeSpent = "1h"))
        harness.await { !model.ui.value.worklogBusy }
        assertNull(model.ui.value.worklogDraft)
        assertEquals(listOf("ON_LEAVE", "EXCEEDS_ATTENDANCE"), model.ui.value.worklogWarnings)
        model.startEditWorklog(3)
        model.dismissWorklog()
        assertEquals(2, model.ui.value.worklogWarnings.size)
        model.startEditWorklog(3)
        harness.worklogMutationResponse = harness.worklogMutationResponse.replace("[\"ON_LEAVE\",\"EXCEEDS_ATTENDANCE\"]", "[\"ON_LEAVE\"]")
        model.saveWorklog(WorklogDraft(timeSpent = "1h", dayWorked = "2026-11-02"))
        harness.await { !model.ui.value.worklogBusy }
        assertEquals(listOf("ON_LEAVE"), model.ui.value.worklogWarnings)
        harness.worklogMutationResponse = harness.worklogMutationResponse.replace("[\"ON_LEAVE\"]", "[]")
        model.startEditWorklog(3)
        model.saveWorklog(WorklogDraft(timeSpent = "1h"))
        harness.await { !model.ui.value.worklogBusy }
        assertTrue(model.ui.value.worklogWarnings.isEmpty())
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

    @Test fun inlineEstimatesUseServerCapabilityAndPublishForecastAndOverrun() {
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        assertFalse(model.ui.value.canEditEstimate)
        model.startEstimateEdit(EstimateKind.Original)
        model.saveEstimate("2h")
        assertNull(model.ui.value.estimateDraft)
        assertNull(harness.estimatePayload)
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"edit\":{\"allowed\":false},\"canEditEstimate\":{\"allowed\":true}")
        harness.estimateResponse = """{"ticket":{"id":201,"time_spent_minutes":150,"original_estimate_minutes":120,"remaining_estimate_minutes":120}}"""
        model.refreshDetail()
        harness.await { !model.ui.value.detailLoading && !model.ui.value.worklogsLoading }
        assertTrue(model.ui.value.canEditEstimate)
        model.startEstimateEdit(EstimateKind.Original)
        model.saveEstimate("2h")
        harness.await { !model.ui.value.estimateBusy }
        assertEquals("{\"original_estimate\":\"2h\"}", harness.estimatePayload.toString())
        assertNull(model.ui.value.estimateDraft)
        assertEquals(120, model.ui.value.detail?.originalEstimateMinutes)
        assertEquals(120, model.ui.value.detail?.remainingEstimateMinutes)
        assertTrue(model.ui.value.overEstimate)
        assertEquals(120, harness.boardViewModel.ui.value.tasks.single().originalEstimateMinutes)
        harness.estimateResponse = harness.estimateResponse.replace("\"remaining_estimate_minutes\":120", "\"remaining_estimate_minutes\":30")
        model.startEstimateEdit(EstimateKind.Remaining)
        assertEquals("2h", model.ui.value.estimateDraft?.value)
        model.saveEstimate("30m")
        harness.await { !model.ui.value.estimateBusy }
        assertEquals("{\"remaining_estimate\":\"30m\"}", harness.estimatePayload.toString())
        assertEquals(30, model.ui.value.detail?.remainingEstimateMinutes)
        harness.estimateResponse = """{"ticket":{"id":201,"time_spent_minutes":150,"original_estimate_minutes":0,"remaining_estimate_minutes":0}}"""
        model.startEstimateEdit(EstimateKind.Original)
        model.saveEstimate("0m")
        harness.await { !model.ui.value.estimateBusy }
        assertTrue(model.ui.value.overEstimate)
        harness.estimateResponse = """{"ticket":{"id":201,"time_spent_minutes":150,"original_estimate_minutes":null,"remaining_estimate_minutes":0}}"""
        model.startEstimateEdit(EstimateKind.Original)
        model.saveEstimate("")
        harness.await { !model.ui.value.estimateBusy }
        assertEquals("{\"original_estimate\":\"\"}", harness.estimatePayload.toString())
        assertNull(model.ui.value.detail?.originalEstimateMinutes)
        assertFalse(model.ui.value.overEstimate)
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

    @Test fun personPickerAndAuthorPayloadUseOnlyServerCapability() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"edit\":{\"allowed\":true},\"logWork\":{\"allowed\":true}")
        harness.worklogMutationResponse = """{"worklog":{"id":3,"author_id":2,"logged_by_id":1,"day_worked":"2026-11-03","minutes":60,"canEdit":true},"ticket":{"id":201,"time_spent_minutes":60}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        assertFalse(model.ui.value.canLogWorkForOthers)
        model.saveWorklog(WorklogDraft(timeSpent = "1h", authorId = 2))
        harness.await { !model.ui.value.worklogBusy }
        assertFalse(harness.worklogPayload?.containsKey("author_id") == true)
        harness.detailResponse = harness.detailResponse.replace("\"edit\":{\"allowed\":true}", "\"edit\":{\"allowed\":false},\"logWorkForOthers\":{\"allowed\":true}")
        model.refreshDetail()
        harness.await { !model.ui.value.detailLoading && !model.ui.value.worklogsLoading }
        assertTrue(model.ui.value.canLogWorkForOthers)
        model.saveWorklog(WorklogDraft(timeSpent = "1h", authorId = 2))
        harness.await { !model.ui.value.worklogBusy }
        assertEquals("2", harness.worklogPayload?.get("author_id")?.toString())
        model.startEditWorklog(3)
        assertEquals(2L, model.ui.value.worklogDraft?.authorId)
        model.saveWorklog(model.ui.value.worklogDraft!!.copy(timeSpent = "2h"))
        harness.await { !model.ui.value.worklogBusy }
        assertFalse(harness.worklogPayload?.containsKey("author_id") == true)
        model.startEditWorklog(3)
        model.saveWorklog(model.ui.value.worklogDraft!!.copy(authorId = 4))
        harness.await { !model.ui.value.worklogBusy }
        assertEquals("4", harness.worklogPayload?.get("author_id")?.toString())
    }

    @Test fun worklogsShowLoggedByOnlyWhenDifferentFromTheAuthor() {
        harness.worklogsResponse = """{"worklogs":[
            {"id":7,"author_id":2,"logged_by_id":1,"author":{"full_name":"Author"},"logged_by":{"full_name":"Admin"},"day_worked":"2026-11-03","minutes":60},
            {"id":8,"author_id":2,"logged_by_id":2,"author":{"full_name":"Author"},"logged_by":{"full_name":"Author"},"day_worked":"2026-11-03","minutes":30}
        ],"time_spent_minutes":90}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val rows = harness.detailViewModel.ui.value.worklogs.associateBy { it.id }
        assertEquals("Logged by Admin", rows[7L]?.loggedByAttribution)
        assertNull(rows[8L]?.loggedByAttribution)
    }

    @Test fun serverCapabilitiesHideLogEditAndDeleteWithoutInferringFromRoles() {
        harness.detailResponse = harness.original.replace("\"logWork\":{\"allowed\":true}", "\"logWork\":{\"allowed\":false}")
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

    @Test fun failedEstimateKeepsInlineDraftAndServerForecastUntilRetry() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"canEditEstimate\":{\"allowed\":true}")
            .dropLast(1) + ""","original_estimate_minutes":60,"remaining_estimate_minutes":30}"""
        harness.estimateError = """{"code":"INVALID_ESTIMATE","error":"Enter an estimate using w, d, h or m, or 0m."}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        model.startEstimateEdit(EstimateKind.Remaining)
        model.saveEstimate("abc")
        harness.await { !model.ui.value.estimateBusy }
        assertEquals("abc", model.ui.value.estimateDraft?.value)
        assertEquals("Enter an estimate using w, d, h or m, or 0m.", model.ui.value.estimateError)
        assertEquals(30, model.ui.value.detail?.remainingEstimateMinutes)
        model.updateEstimateDraft("0m")
        assertNull(model.ui.value.estimateError)
        harness.estimateError = null
        harness.estimateResponse = """{"ticket":{"id":201,"time_spent_minutes":0,"original_estimate_minutes":60,"remaining_estimate_minutes":0}}"""
        model.saveEstimate("0m")
        harness.await { !model.ui.value.estimateBusy }
        assertNull(model.ui.value.estimateDraft)
        assertEquals(0, model.ui.value.detail?.remainingEstimateMinutes)
    }

    @Test fun staleTicketRefreshCannotUndoSavedOriginalAndRemainingEstimates() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"canEditEstimate\":{\"allowed\":true}")
        harness.estimateResponse = """{"ticket":{"id":201,"time_spent_minutes":45,"original_estimate_minutes":120,"remaining_estimate_minutes":75}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        val gate = TicketSessionHarness.HeldTicketResponse().also { harness.detailResponseGate = it }
        model.refreshDetail()
        gate.awaitStarted()
        model.startEstimateEdit(EstimateKind.Original)
        model.saveEstimate("2h")
        harness.await { !model.ui.value.estimateBusy }
        gate.release()
        harness.await { !model.ui.value.detailLoading && !model.ui.value.worklogsLoading }
        assertEquals(120, model.ui.value.detail?.originalEstimateMinutes)
        assertEquals(75, model.ui.value.detail?.remainingEstimateMinutes)
        assertEquals(45, model.ui.value.detail?.timeSpentMinutes)
    }

    @Test fun staleWorklogReadCannotUndoAnInlineEstimateSave() {
        harness.detailResponse = harness.original.replace("\"edit\":{\"allowed\":true}", "\"canEditEstimate\":{\"allowed\":true}")
        harness.estimateResponse = """{"ticket":{"id":201,"time_spent_minutes":45,"original_estimate_minutes":120,"remaining_estimate_minutes":75}}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        val model = harness.detailViewModel
        val gate = TicketSessionHarness.HeldTicketResponse().also { harness.worklogsReadGate = it }
        model.loadWorklogs()
        gate.awaitStarted()
        model.startEstimateEdit(EstimateKind.Original)
        model.saveEstimate("2h")
        harness.await { !model.ui.value.estimateBusy }
        gate.release()
        harness.await { !model.ui.value.worklogsLoading }
        assertEquals(45, model.ui.value.detail?.timeSpentMinutes)
        assertEquals(120, model.ui.value.detail?.originalEstimateMinutes)
        assertEquals(75, harness.boardViewModel.ui.value.tasks.single().remainingEstimateMinutes)
    }

    @Test fun failedDeleteKeepsWorklogAndShowsInlineErrorForRetry() {
        harness.worklogsResponse = """{"worklogs":[{"id":7,"day_worked":"2026-11-01","minutes":30,"canDelete":true}],"time_spent_minutes":30}"""
        harness.worklogError = """{"code":"INVALID_ESTIMATE","error":"Choose auto, set or leave for the remaining estimate."}"""
        harness.loadBoardPlanAndDetail()
        harness.await { !harness.detailViewModel.ui.value.worklogsLoading }
        harness.detailViewModel.deleteWorklog(7, "set", "abc")
        harness.await { !harness.detailViewModel.ui.value.worklogBusy }
        assertEquals(7L, harness.detailViewModel.ui.value.worklogs.single().id)
        assertEquals("Choose auto, set or leave for the remaining estimate.", harness.detailViewModel.ui.value.worklogError)
        assertNull(harness.detailViewModel.ui.value.error)
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
