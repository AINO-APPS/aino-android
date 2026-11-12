package app.aino.mobile.feature.tasks

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class PagedBacklogTotalsTest {
    private lateinit var session: TicketSession
    private val models = ViewModelStore()
    private val original = """{"id":1,"project_id":3,"title":"Ship","container":"backlog","priority":"high","workflow_state_key":"todo","capabilities":{"can":{"edit":{"allowed":true}}}}"""
    @Volatile private var saved = original
    @Volatile private var firstPage = page(original, 100, 8, 60, 70, true)
    @Volatile private var laterPage: String? = null
    private val api = ApiClient { request ->
        val body = when {
            request.path.startsWith("tasks/backlog") && request.path.contains("offset=0") -> firstPage
            request.path.startsWith("tasks/backlog") && request.path.contains("offset=1") && laterPage != null -> laterPage!!
            request.path.startsWith("tasks/backlog") -> throw IOException("Later page unavailable")
            request.path == "tasks/1" || request.path == "tasks/2" ||
                request.path == "tasks/1/detail" || request.path == "tasks/2/detail" -> saved
            request.path.startsWith("projects") -> """[{"id":3,"name":"Project","capabilities":{"canCreateTickets":true}}]"""
            else -> "[]"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    }
    private lateinit var vm: TaskViewModel
    private lateinit var vmPlan: PlanViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
        vm = TaskViewModel(TaskRepository(api), ticketSession = session)
        vmPlan = PlanViewModel(TaskRepository(api), vm.ticketSession)
        models.put("tasks", vm)
        models.put("plan", vmPlan)
    }

    @After fun tearDown() {
        models.clear()
        session.close()
        Dispatchers.resetMain()
    }

    @Test fun movingALoadedTicketOutOfAPartialBacklogAdjustsServerTotalsAndSummary() {
        load()
        assertEquals(100, vmPlan.ui.value.backlogTotal)
        assertEquals(1, vmPlan.ui.value.backlog.size)

        saved = original.replace("\"backlog\"", "\"sprint\"")
        write(vmPlan.ui.value.backlog.single())
        await { vmPlan.ui.value.backlog.isEmpty() }

        assertEquals(99, vmPlan.ui.value.backlogTotal)
        assertEquals(BacklogSummary(99, mapOf("todo" to 59, "in_progress" to 40), mapOf("high" to 69, "medium" to 30)), vmPlan.ui.value.backlogSummary)
        assertEquals(8, vmPlan.ui.value.completedCount)
    }

    @Test fun aNewTicketUpdatesTheServerSummaryAndRepeatedRefreshesDoNotDrift() {
        load()
        repeat(2) { revision ->
            saved = original.replace("\"id\":1", "\"id\":2").replace("Ship", "Ship $revision")
            vm.ticketSession.onRealtimeEvent("task_updated", buildJsonObject { put("taskId", 2) })
            await { vmPlan.ui.value.backlog.any { it.id == 2L && it.title == "Ship $revision" } }
            assertEquals(101, vmPlan.ui.value.backlogTotal)
        }
        assertEquals(BacklogSummary(101, mapOf("todo" to 61, "in_progress" to 40), mapOf("high" to 71, "medium" to 30)), vmPlan.ui.value.backlogSummary)
    }

    @Test fun workflowAndPriorityChangesMoveSummaryBucketsWithoutChangingTheTotal() {
        load()
        saved = original.replace("\"todo\"", "\"in_progress\"").replace("\"high\"", "\"medium\"")
        write(vmPlan.ui.value.backlog.single())
        await { vmPlan.ui.value.backlog.single().priority == "medium" }
        assertEquals(100, vmPlan.ui.value.backlogTotal)
        assertEquals(BacklogSummary(100, mapOf("todo" to 59, "in_progress" to 41), mapOf("high" to 69, "medium" to 31)), vmPlan.ui.value.backlogSummary)
    }

    @Test fun completingAndReopeningATicketAdjustsBothSectionsWithoutDrift() {
        load()
        saved = original.replace("\"todo\"", "\"done\"").replace("\"id\":1", "\"id\":1,\"completed_at\":\"2026-11-03\"")
        write(vmPlan.ui.value.backlog.single())
        await { vmPlan.ui.value.completed.size == 1 }
        assertEquals(99, vmPlan.ui.value.backlogTotal)
        assertEquals(9, vmPlan.ui.value.completedCount)

        write(vmPlan.ui.value.completed.single())
        assertEquals(9, vmPlan.ui.value.completedCount)
        saved = original
        write(vmPlan.ui.value.completed.single())
        await { vmPlan.ui.value.backlog.size == 1 }
        assertEquals(100, vmPlan.ui.value.backlogTotal)
        assertEquals(8, vmPlan.ui.value.completedCount)
        assertEquals(BacklogSummary(100, mapOf("todo" to 60, "in_progress" to 40), mapOf("high" to 70, "medium" to 30)), vmPlan.ui.value.backlogSummary)
    }

    @Test fun aFullReloadDiscardsPreviousAdjustmentsAndUsesTheNewServerBaseline() {
        load()
        saved = original.replace("\"backlog\"", "\"scheduled\"")
        write(vmPlan.ui.value.backlog.single())
        await { vmPlan.ui.value.backlogTotal == 99 }
        firstPage = page(original, 80, 5, 40, 50, true)
        vmPlan.loadBacklog()
        await { vmPlan.ui.value.backlogTotal == 80 && vmPlan.ui.value.backlog.size == 1 }
        assertEquals(5, vmPlan.ui.value.completedCount)
        assertEquals(BacklogSummary(80, mapOf("todo" to 40, "in_progress" to 40), mapOf("high" to 50, "medium" to 30)), vmPlan.ui.value.backlogSummary)
    }

    @Test fun theNextPageResetsTheBaselineAndFurtherChangesAdjustItOnce() {
        load()
        saved = original.replace("\"backlog\"", "\"scheduled\"")
        write(vmPlan.ui.value.backlog.single())
        await { vmPlan.ui.value.backlogTotal == 99 && !vmPlan.ui.value.backlogLoadingMore }
        val second = original.replace("\"id\":1", "\"id\":2")
        laterPage = page(second, 99, 8, 59, 69, true)
        vmPlan.loadMoreBacklog()
        await { vmPlan.ui.value.backlog.map { it.id } == listOf(2L) && !vmPlan.ui.value.backlogLoadingMore }
        assertEquals(99, vmPlan.ui.value.backlogTotal)
        assertEquals(BacklogSummary(99, mapOf("todo" to 59, "in_progress" to 40), mapOf("high" to 69, "medium" to 30)), vmPlan.ui.value.backlogSummary)
        saved = second.replace("\"backlog\"", "\"scheduled\"")
        write(vmPlan.ui.value.backlog.single())
        await { vmPlan.ui.value.backlog.isEmpty() }
        assertEquals(98, vmPlan.ui.value.backlogTotal)
    }

    @Test fun deletingALoadedTicketReducesTheBaselineCounts() {
        load()
        vm.ticketSession.onRealtimeEvent("task_updated", buildJsonObject {
            put("taskId", 1)
            put("action", "deleted")
        })
        await { vmPlan.ui.value.backlog.isEmpty() }
        assertEquals(99, vmPlan.ui.value.backlogTotal)
        assertEquals(BacklogSummary(99, mapOf("todo" to 59, "in_progress" to 40), mapOf("high" to 69, "medium" to 30)), vmPlan.ui.value.backlogSummary)
    }

    @Test fun paginationTotalsDoNotCountLoadedCompletedWorkAsOpenWhenAggregateFieldsAreMissing() {
        val done = original.replace("\"id\":1", "\"id\":2,\"completed_at\":\"2026-11-03\"")
        firstPage = """{"tasks":[$original,$done],"pagination":{"total":2,"hasMore":false}}"""
        load()
        assertEquals(1, vmPlan.ui.value.backlogTotal)
        assertEquals(1, vmPlan.ui.value.completedCount)
    }

    private fun write(ticket: Task) {
        val written = AtomicBoolean()
        val detail = TicketDetailViewModel(ticket.id, TaskRepository(api), vm.ticketSession)
        detail.updateFields(ticket, buildJsonObject { put("title", "Ship") }) { written.set(true) }
        await { written.get() }
        androidx.lifecycle.ViewModelStore().apply { put("detail", detail); clear() }
    }

    @Test fun aNewlyLoadedBacklogTicketRaisesThePaginationTotal() {
        firstPage = """{"tasks":[$original],"completed_count":8,"pagination":{"total":108,"hasMore":true}}"""
        load()
        saved = original.replace("\"id\":1", "\"id\":2")
        vm.ticketSession.onRealtimeEvent("task_updated", buildJsonObject { put("taskId", 2) })
        await { vmPlan.ui.value.backlog.size == 2 }

        assertEquals(101, vmPlan.ui.value.backlogTotal)
        assertEquals(8, vmPlan.ui.value.completedCount)
    }

    private fun load() {
        vm.bind(userId = 1, role = "employee", teamName = null, agileEnabled = false)
        await { vmPlan.ui.value.planLoaded && !vmPlan.ui.value.backlogLoadingMore || vmPlan.ui.value.error != null }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for Tasks UI state", condition())
    }

    private fun page(rows: String, total: Int, completed: Int, todo: Int, high: Int, more: Boolean): String =
        """{"tasks":[$rows],"incomplete_total":$total,"completed_count":$completed,
            "summary":{"total":$total,"byStatus":{"todo":$todo,"in_progress":40},"byPriority":{"high":$high,"medium":30}},
            "pagination":{"total":${total + completed},"hasMore":$more}}"""
}
