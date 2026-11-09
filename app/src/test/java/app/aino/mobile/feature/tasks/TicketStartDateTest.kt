package app.aino.mobile.feature.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TicketStartDateTest {
    private lateinit var harness: TicketSessionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = TicketSessionHarness()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test fun realtimeStartDateClearingDoesNotResurrectTheLegacyDateOrChangePlacement() {
        val initial = harness.original.dropLast(1) + ""","start_date":"2026-11-10","planned_date":"2026-11-10"}"""
        harness.detailResponse = initial
        harness.boardTicket = initial
        harness.loadBoardPlanAndDetail()
        assertEquals("2026-11-10", harness.detailViewModel.ui.value.detail?.startDate)
        harness.detailResponse = initial.replace("\"start_date\":\"2026-11-10\"", "\"start_date\":null")
        harness.boardTicket = harness.detailResponse
        harness.session.onRealtimeEvent("task_updated", buildJsonObject {
            put("taskId", 201)
            put("fields", buildJsonObject { put("start_date", JsonNull) })
        })

        harness.await { harness.detailViewModel.ui.value.detail?.startDate == null }
        assertNull(harness.boardViewModel.ui.value.tasks.single().startDate)
        assertEquals(10L, harness.detailViewModel.ui.value.detail?.sprintId)
        assertEquals("sprint", harness.detailViewModel.ui.value.detail?.container)
    }

    @Test fun backlogStartDateEditKeepsTheTicketInBacklogWithoutScheduledPlacement() {
        val row = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.detailResponse = row
        harness.planRows = row
        harness.loadBoardPlanAndDetail()
        harness.saved = row.dropLast(1) + ""","start_date":"2026-11-10","planned_date":"2026-11-10"}"""
        harness.detailViewModel.setStartDate(requireNotNull(harness.detailViewModel.ui.value.detail), "2026-11-10")
        harness.await { harness.viewModelPlan.ui.value.backlog.singleOrNull()?.startDate == "2026-11-10" }
        assertNull(harness.detailViewModel.ui.value.detail?.sprintId)
        assertEquals("backlog", harness.detailViewModel.ui.value.detail?.container)
        assertEquals(1, harness.writes)
        assertEquals("""{"start_date":"2026-11-10","planned_date":"2026-11-10"}""", harness.fieldPayload.toString())
    }

    @Test fun legacyDetailFallsBackButAnExplicitNullStartDateStaysCleared() {
        val legacy = harness.original.dropLast(1) + ""","planned_date":"2026-11-10"}"""
        harness.detailResponse = legacy
        harness.boardTicket = legacy
        harness.loadBoardPlanAndDetail()
        assertEquals("2026-11-10", harness.detailViewModel.ui.value.detail?.startDate)

        harness.detailResponse = legacy.dropLast(1) + ""","start_date":null}"""
        harness.detailViewModel.refreshDetail()
        harness.await { harness.detailReads >= 2 && !harness.detailViewModel.ui.value.detailLoading }
        assertNull(harness.detailViewModel.ui.value.detail?.startDate)
    }

    @Test fun oldServerEditResponseAndDateClearingKeepTheSprintAndDueDate() {
        val row = harness.original.dropLast(1) + ""","planned_date":"2026-11-10","due_date":"2026-11-12"}"""
        harness.detailResponse = row
        harness.boardTicket = row
        harness.loadBoardPlanAndDetail()
        harness.saved = row.replace("2026-11-10", "2026-11-11")
        harness.detailViewModel.setStartDate(requireNotNull(harness.detailViewModel.ui.value.detail), "2026-11-11")
        harness.await { harness.detailViewModel.ui.value.detail?.startDate == "2026-11-11" }
        harness.saved = row.replace("\"planned_date\":\"2026-11-10\"", "\"planned_date\":null")
        harness.detailViewModel.setStartDate(requireNotNull(harness.detailViewModel.ui.value.detail), "")
        harness.await { harness.writes == 2 && harness.detailViewModel.ui.value.detail?.startDate == null }
        assertEquals("""{"start_date":null,"planned_date":null}""", harness.fieldPayload.toString())
        assertEquals("2026-11-12", harness.detailViewModel.ui.value.detail?.dueDate)
        assertEquals(10L, harness.detailViewModel.ui.value.detail?.sprintId)
        assertEquals("sprint", harness.detailViewModel.ui.value.detail?.container)
    }

    @Test fun startAfterDueAndLegacyDateWritesAreRefused() {
        val row = harness.original.dropLast(1) + ""","start_date":"2026-11-10","due_date":"2026-11-12"}"""
        harness.detailResponse = row
        harness.boardTicket = row
        harness.loadBoardPlanAndDetail()
        val ticket = requireNotNull(harness.detailViewModel.ui.value.detail)
        harness.detailViewModel.setStartDate(ticket, "2026-11-13")
        assertEquals("Due date cannot be earlier than the start date", harness.detailViewModel.ui.value.error)
        harness.detailViewModel.updateFields(ticket, buildJsonObject { put("planned_date", "2026-11-13") })
        assertEquals(0, harness.writes)
        assertNull(harness.fieldPayload)
        assertEquals(ticket, harness.detailViewModel.ui.value.detail)
    }

    @Test fun equalDatesAndAtomicDateRangeEditsAreAllowed() {
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.dropLast(1) + ""","start_date":"2026-11-12","due_date":"2026-11-12"}"""
        harness.detailViewModel.updateFields(requireNotNull(harness.detailViewModel.ui.value.detail), buildJsonObject {
            put("start_date", "2026-11-12")
            put("due_date", "2026-11-12")
        })
        harness.await { harness.detailViewModel.ui.value.detail?.startDate == "2026-11-12" }
        assertEquals("2026-11-12", harness.detailViewModel.ui.value.detail?.dueDate)
        assertEquals(1, harness.writes)
    }

    @Test fun serverDateRefusalKeepsTheStoredTicketAndReportsTheError() {
        harness.loadBoardPlanAndDetail()
        val before = requireNotNull(harness.detailViewModel.ui.value.detail)
        harness.fieldError = """{"error":"Due date cannot be earlier than the start date"}"""
        harness.detailViewModel.setStartDate(before, "2026-11-13")
        harness.await { harness.detailViewModel.ui.value.error != null }
        assertEquals("Due date cannot be earlier than the start date", harness.detailViewModel.ui.value.error)
        assertEquals(before, harness.detailViewModel.ui.value.detail)
        assertEquals(before, harness.boardViewModel.ui.value.tasks.single())
        assertEquals(0, harness.writes)
    }

    @Test fun editCapabilityNotScheduleCapabilityAuthorizesStartDateChanges() {
        val row = harness.original.replace("\"schedule\":{\"allowed\":true}", "\"schedule\":{\"allowed\":false}")
        harness.detailResponse = row
        harness.boardTicket = row
        harness.loadBoardPlanAndDetail()
        harness.saved = row.dropLast(1) + ""","start_date":"2026-11-10"}"""
        harness.detailViewModel.setStartDate(requireNotNull(harness.detailViewModel.ui.value.detail), "2026-11-10")
        harness.await { harness.writes == 1 && harness.detailViewModel.ui.value.detail?.startDate == "2026-11-10" }
        assertEquals("sprint", harness.detailViewModel.ui.value.detail?.container)
    }

    @Test fun deniedEditCapabilityPreventsDateRequests() {
        val row = harness.original.replace("\"edit\":{\"allowed\":true}", "\"edit\":{\"allowed\":false,\"reason\":\"READ_ONLY\"}")
        harness.detailResponse = row
        harness.boardTicket = row
        harness.loadBoardPlanAndDetail()
        val before = requireNotNull(harness.detailViewModel.ui.value.detail)
        harness.detailViewModel.setStartDate(before, "2026-11-10")
        harness.detailViewModel.setDueDate(before, "2026-11-12")
        assertNull(harness.fieldPayload)
        assertEquals(0, harness.writes)
        assertEquals(before, harness.detailViewModel.ui.value.detail)
    }

    @Test fun startDateEditsDisplayReadableHistoryWithoutScheduleActions() {
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.dropLast(1) + ""","start_date":"2026-11-10"}"""
        harness.historyResponse = """[{"id":1,"action":"updated","field":"start_date","old_value":null,"new_value":"2026-11-10"}]"""

        harness.detailViewModel.setStartDate(requireNotNull(harness.detailViewModel.ui.value.detail), "2026-11-10")

        harness.await { harness.detailViewModel.ui.value.history.isNotEmpty() }
        assertEquals("updated start date: — → 2026-11-10", historyText(harness.detailViewModel.ui.value.history.single()).joinToString("") { it.second })
    }

    @Test fun editingStartDateSendsCompatibleDateFieldsAndKeepsPlacement() {
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.dropLast(1) + ""","start_date":"2026-11-10","planned_date":"2026-11-10"}"""

        harness.detailViewModel.setStartDate(requireNotNull(harness.detailViewModel.ui.value.detail), "2026-11-10")

        harness.await { harness.detailViewModel.ui.value.detail?.startDate == "2026-11-10" }
        assertEquals("""{"start_date":"2026-11-10","planned_date":"2026-11-10"}""", harness.fieldPayload.toString())
        assertEquals("2026-11-10", harness.boardViewModel.ui.value.tasks.single().startDate)
        assertEquals(10L, harness.detailViewModel.ui.value.detail?.sprintId)
        assertEquals("sprint", harness.detailViewModel.ui.value.detail?.container)
    }

    @Test fun dueBeforeStartIsRefusedWithoutChangingTheStoredTicket() {
        val row = harness.original.dropLast(1) + ""","start_date":"2026-11-10","due_date":"2026-11-12"}"""
        harness.detailResponse = row
        harness.boardTicket = row
        harness.loadBoardPlanAndDetail()
        val before = requireNotNull(harness.detailViewModel.ui.value.detail)

        harness.detailViewModel.setDueDate(before, "2026-11-09")

        harness.await { harness.detailViewModel.ui.value.error != null || harness.writes > 0 }
        assertEquals("Due date cannot be earlier than the start date", harness.detailViewModel.ui.value.error)
        assertEquals(0, harness.writes)
        assertEquals(before, harness.detailViewModel.ui.value.detail)
    }

    @Test fun detailAndBoardReadStartDateWithoutChangingSprintPlacement() {
        val row = harness.original.dropLast(1) + ""","start_date":"2026-11-10","planned_date":"2026-11-09","due_date":"2026-11-12"}"""
        harness.detailResponse = row
        harness.boardTicket = row
        harness.loadBoardPlanAndDetail()

        val detail = requireNotNull(harness.detailViewModel.ui.value.detail)
        assertEquals(LocalDate.of(2026, 11, 10), scheduleDateOf(detail))
        assertEquals(LocalDate.of(2026, 11, 10), scheduleDateOf(harness.boardViewModel.ui.value.tasks.single()))
        assertEquals(10L, detail.sprintId)
        assertEquals("sprint", detail.container)
    }
}
