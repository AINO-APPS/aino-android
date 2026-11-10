package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
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
import java.util.concurrent.CopyOnWriteArrayList

/** Project ownership and a due date must not hide a newly-created backlog ticket. */
@OptIn(ExperimentalCoroutinesApi::class)
class BacklogCreateVisibilityTest {
    private lateinit var session: TicketSession
    private val creates = CopyOnWriteArrayList<String>()
    private val rows = CopyOnWriteArrayList<String>()
    private val repository = TaskRepository(ApiClient { request ->
        val body = when {
            request.path.startsWith("projects") -> """[
                {"id":10,"name":"Payments","capabilities":{"canCreateTickets":true}},
                {"id":20,"name":"Support","capabilities":{"canCreateTickets":true}}
            ]"""
            request.path == "tasks/backlog" && request.method == "POST" -> {
                val payload = request.body!!.decodeToString()
                creates += payload
                val row = """{"id":100,"container":"backlog",${payload.removePrefix("{")}"""
                rows += row
                row
            }
            request.path.startsWith("tasks/backlog?") -> {
                val projectId = Regex("project_id=(\\d+)").find(request.path)?.groupValues?.get(1)
                val matching = rows.filter { Json.parseToJsonElement(it).jsonObject["project_id"].toString() == projectId }
                """{"tasks":[${matching.joinToString(",")}]}"""
            }
            request.path == "tasks/labels" || request.path == "tasks/assignable-users" -> "[]"
            else -> "{}"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    })
    private lateinit var shell: TasksShellViewModel
    private lateinit var plan: PlanViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
        shell = TasksShellViewModel(repository, ticketSession = session)
        plan = PlanViewModel(repository, session)
    }

    @After fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    @Test fun aTicketCreatedFromTheBacklogShowsUpInTheBacklog() {
        bind("employee")
        create("Printer broken")
        reload()
        assertEquals(listOf("Printer broken"), plan.ui.value.backlog.map { it.title })
    }

    @Test fun anAdminBrowsingAProjectCreatesTheTicketInThatProjectWithoutTeamParameters() {
        bind("super_admin")
        shell.selectProject(20)
        await { plan.ui.value.planScope.selectedProjectId == 20L }
        create("Support escalation")
        reload()
        val payload = Json.parseToJsonElement(creates.single()).jsonObject
        assertEquals("20", payload["project_id"].toString())
        assertFalse(payload.containsKey("team_id"))
        assertEquals(20L, plan.ui.value.backlog.single().projectId)
    }

    @Test fun aTicketWithADueDateIsStillWhereTheUserCreatedIt() {
        bind("employee")
        create("Renew SSL", "2026-12-31")
        reload()
        assertEquals("Renew SSL", plan.ui.value.backlog.single().title)
        assertEquals("2026-12-31", plan.ui.value.backlog.single().dueDate)
    }

    private fun bind(role: String) {
        shell.bind(1, role, "Team", agileEnabled = false, teamId = 99)
        await { plan.ui.value.planScope.selectedProjectId == 10L }
    }

    private fun create(title: String, dueDate: String = "") {
        shell.openSheet(TaskSheet.CreateTicket)
        await { plan.ui.value.draft.projectId == plan.ui.value.planScope.selectedProjectId }
        plan.updateDraft { it.copy(title = title, dueDate = dueDate) }
        plan.submitBacklog()
        await { creates.isNotEmpty() && !plan.ui.value.creating }
    }

    private fun reload() {
        plan.loadBacklog()
        await { plan.ui.value.planLoaded && plan.ui.value.backlog.isNotEmpty() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for project backlog", condition())
    }
}
