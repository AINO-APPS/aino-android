package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.net.URLDecoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Creating a ticket from the Backlog shows "Created …" and then the ticket must
 * show up in the Backlog list. This drives the real [TaskViewModel] against a
 * stand-in server that enforces the platform's own rules, transcribed from
 * `aino-platform`:
 *
 *  - containers are exclusive (ADR-015, `server/modules/tasks/tasks.collection.ts`):
 *    backlog = `sprint_id IS NULL AND planned_date IS NULL AND due_date IS NULL`;
 *    a date moves a ticket to Scheduled.
 *  - ownership (`server/modules/tasks/tasks.ownership.ts` `resolveCreateTeamId`):
 *    an explicit `team_id` is honoured for org admins only; otherwise the ticket
 *    inherits the sprint's team, else the creator's team.
 *  - visibility (`server/modules/tasks/tasks.policy.ts` `resolveTaskVisibility` /
 *    `buildVisibilityPredicate`): an admin asking for a team gets `t.team_id = <team>`,
 *    an admin with no team gets `t.team_id IS NOT NULL`, a member gets their team.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BacklogCreateVisibilityTest {

    private data class Row(
        val id: Long,
        val title: String,
        val teamId: Long?,
        val sprintId: Long?,
        val plannedDate: String?,
        val dueDate: String?,
        val createdBy: Long,
    )

    private class FakePlatform {
        val tasks = mutableListOf<Row>()
        var nextId = 100L
        /** The actor `bind()` declared: their role and their own team. */
        var role: String = "employee"
        var ownTeamId: Long? = null
        var userId: Long = 1L
        val teams = listOf(10L to "Payments", 20L to "Support")
        /** Query strings the list endpoint was asked for, for assertions. */
        val requests = mutableListOf<String>()
        /** Bodies sent to `POST tasks/backlog`, for assertions. */
        val creates = mutableListOf<String>()

        private fun isAdmin() = role in setOf("super_admin", "hr_admin", "platform_admin")

        private fun parse(path: String): Pair<String, Map<String, String>> {
            val q = path.indexOf('?')
            if (q < 0) return path to emptyMap()
            val params = path.substring(q + 1).split('&').filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            return path.substring(0, q) to params
        }

        private fun json(text: String) = ApiResponse(200, emptyMap(), text.toByteArray())

        fun handle(request: ApiRequest): ApiResponse {
            val (path, params) = parse(request.path)
            return when {
                path == "tasks/backlog" && request.method == "GET" -> list(params)
                path == "tasks/backlog" && request.method == "POST" -> create(request)
                path == "tasks/carry-forward" -> json("""{"carried":0}""")
                path == "tasks/labels" -> json("[]")
                path == "tasks/assignable-users" -> json("[]")
                path == "projects" -> json("[]")
                path == "tasks/available-sprints" -> json("[]")
                path == "agile/config" -> json("{}")
                path == "user/preferences" -> json("{}")
                path == "org/teams" -> json(teams.joinToString(",", "[", "]") { (id, name) -> """{"id":$id,"name":"$name"}""" })
                else -> json("[]")
            }
        }

        /** `buildTaskCollection(scope: 'backlog')` + `buildVisibilityPredicate`. */
        private fun list(params: Map<String, String>): ApiResponse {
            val wantedTeam = params["team_id"]?.toLongOrNull()
            val unassigned = params["unassigned"] == "1"
            requests += "GET ${params.entries.joinToString("&") { "${it.key}=${it.value}" }}"
            val rows = tasks.filter { t ->
                // Backlog = no sprint and no planned day; a due date is metadata.
                val backlog = t.sprintId == null && t.plannedDate == null
                val visible = when {
                    // A member without a team falls back to the personal rule.
                    !isAdmin() && ownTeamId == null -> t.createdBy == userId
                    !isAdmin() -> t.teamId != null && t.teamId == ownTeamId
                    unassigned -> t.teamId == null
                    wantedTeam != null -> t.teamId == wantedTeam
                    // Org-wide: legacy unowned rows are confined to the Unassigned bucket.
                    else -> t.teamId != null
                }
                backlog && visible
            }
            val body = rows.joinToString(",", """{"tasks":[""", """],"pagination":{"total":${rows.size},"hasMore":false}}""") { r ->
                """{"id":${r.id},"title":"${r.title}","user_id":${r.createdBy},"team_id":${r.teamId ?: "null"},"sprint_id":${r.sprintId ?: "null"}}"""
            }
            return json(body)
        }

        /** `POST /tasks/backlog` → `resolveCreateTeamId` → INSERT. */
        private fun create(request: ApiRequest): ApiResponse {
            val body = request.body!!.toString(Charsets.UTF_8)
            creates += body
            fun field(name: String): String? =
                Regex("\"$name\":(\"([^\"]*)\"|(null)|(-?\\d+))").find(body)?.let { m ->
                    m.groupValues[2].takeIf { it.isNotEmpty() } ?: m.groupValues[4].takeIf { it.isNotEmpty() }
                }
            val explicitTeam = field("team_id")?.toLongOrNull()
            // resolveCreateTeamId: explicit team only for admins; else the creator's team.
            val teamId = when {
                explicitTeam != null -> if (isAdmin()) explicitTeam else null
                else -> ownTeamId
            }
            val row = Row(
                id = nextId++,
                title = field("title").orEmpty(),
                teamId = teamId,
                sprintId = field("sprint_id")?.toLongOrNull(),
                plannedDate = null,
                dueDate = field("due_date")?.takeIf { it != "null" },
                createdBy = userId,
            )
            tasks += row
            return json(
                """{"id":${row.id},"title":"${row.title}","user_id":${row.createdBy},"team_id":${row.teamId ?: "null"},"sprint_id":null,"priority":"medium"}""",
            )
        }
    }

    private val platform = FakePlatform()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun viewModel(): TaskViewModel {
        val api = ApiClient { request -> platform.handle(request) }
        val vm = TaskViewModel(TaskRepository(api))
        vm.bind(
            userId = platform.userId,
            role = platform.role,
            teamName = null,
            agileEnabled = false,
            teamId = platform.ownTeamId,
        )
        waitFor { vm.ui.value.planLoaded || vm.ui.value.backlogLoading.not() }
        return vm
    }

    private fun createTicket(vm: TaskViewModel, title: String, dueDate: String = "") {
        vm.openSheet(TaskSheet.CreateTicket)
        vm.updateDraft { it.copy(title = title, dueDate = dueDate) }
        vm.submitBacklog()
        waitFor { vm.ui.value.creating.not() && platform.tasks.isNotEmpty() }
    }

    // ── The reported symptom ─────────────────────────────────────────────

    @Test
    fun aTicketCreatedFromTheBacklogShowsUpInTheBacklog() {
        val vm = viewModel()
        createTicket(vm, "Printer broken")
        assertEquals("one create request", 1, platform.creates.size)

        vm.loadBacklog()
        waitFor { vm.ui.value.backlog.any { it.title == "Printer broken" } }

        assertEquals(
            "a created ticket must be visible in the backlog it was created from",
            listOf("Printer broken"),
            vm.ui.value.backlog.map { it.title },
        )
    }

    @Test
    fun anAdminBrowsingATeamCreatesTheTicketInThatTeam() {
        platform.role = "super_admin"
        platform.ownTeamId = null
        val vm = viewModel()
        waitFor { vm.ui.value.teams.isNotEmpty() }
        vm.selectTeam(20L)
        waitFor { vm.ui.value.selectedTeamId == 20L }

        createTicket(vm, "Support escalation")

        vm.loadBacklog()
        waitFor { platform.tasks.any { it.title == "Support escalation" } }
        // Web does this (`useBacklog.handleAddBacklog`: "An admin viewing a
        // team's backlog creates the ticket there.").
        assertTrue(
            "the create must name the team being browsed; sent: ${platform.creates}",
            platform.creates.single().contains("\"team_id\":20"),
        )
        assertEquals(
            "the admin created the ticket while looking at Support's backlog, " +
                "so it must be created there rather than in no team at all; asked: ${platform.requests}",
            20L,
            platform.tasks.first { it.title == "Support escalation" }.teamId,
        )
    }

    @Test
    fun aTicketWithADueDateIsStillWhereTheUserCreatedIt() {
        platform.role = "employee"
        platform.ownTeamId = 10L
        val vm = viewModel()
        createTicket(vm, "Renew SSL", dueDate = "2026-12-31")

        vm.loadBacklog()
        waitFor { platform.tasks.any { it.title == "Renew SSL" } }
        waitFor { vm.ui.value.backlog.any { it.title == "Renew SSL" } }
        assertTrue(
            "a ticket created from the Backlog with a due date must stay there — " +
                "a deadline is not a container; backlog=${vm.ui.value.backlog.map { it.title }}",
            vm.ui.value.backlog.any { it.title == "Renew SSL" },
        )
    }
}
