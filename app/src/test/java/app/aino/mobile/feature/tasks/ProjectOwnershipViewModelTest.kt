package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectOwnershipViewModelTest {
    private lateinit var session: TicketSession
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
    }
    @After fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    @Test fun everyRoleCanSelectAnAccessibleProjectIncludingArchivedReadOnlyProjects() {
        val repository = TaskRepository(ApiClient { request ->
            response(when (request.path) {
                "projects?include_archived=1" -> """[
                    {"id":3,"name":"Platform","key":"PLT","capabilities":{"canCreateTickets":true}},
                    {"id":4,"name":"Archive","key":"OLD","is_archived":true,"capabilities":{"canCreateTickets":false}}
                ]"""
                "projects/mine" -> """[{"id":3,"name":"Platform","key":"PLT","capabilities":{"canCreateTickets":true}}]"""
                else -> "{}"
            })
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        shell.bind(1, "employee", "Team", agileEnabled = false, teamId = 8)
        await { shell.ui.value.teamResolved }
        assertTrue(shell.ui.value.planScope.projectScoped)
        assertEquals(3L, shell.ui.value.planScope.selectedProjectId)
        shell.selectProject(4)
        assertEquals(TeamScope(projectId = 4), shell.ui.value.teamScope)
        assertFalse(shell.ui.value.planScope.contains(Task(1, "Other", projectId = 3, teamId = 8)))
        assertTrue(shell.ui.value.planScope.contains(Task(2, "Archived", projectId = 4, teamId = 9)))
    }

    @Test fun createDefaultsToCurrentProjectAndRefusesAnUnavailableProject() {
        val project = ProjectOption(3, "PLT", "Platform", capabilities = ProjectCapabilities(canCreateTickets = true))
        session.writePlanScope(PlanScope(
            userId = 1, projectScoped = true, selectedProjectId = 3,
            projects = listOf(project), myProjects = listOf(project), teamResolved = true,
        ))
        var sentProject: Long? = null
        val repository = TaskRepository(ApiClient { request ->
            if (request.path == "tasks/backlog" && request.method == "POST") {
                sentProject = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.decodeToString())
                    .let { it as kotlinx.serialization.json.JsonObject }["project_id"].toString().toLongOrNull()
                response("""{"id":7,"title":"Ship","ticket_key":"PLT-7","project_id":3,"container":"backlog"}""")
            } else response("{}")
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        shell.openSheet(TaskSheet.CreateTicket)
        await { plan.ui.value.draft.projectId == 3L }
        plan.updateDraft { it.copy(title = "Ship", projectId = 99) }
        plan.submitBacklog()
        assertNull(sentProject)
        assertNotNull(plan.ui.value.error)
        plan.updateDraft { it.copy(projectId = 3) }
        plan.submitBacklog()
        await { session.store.state.value.tickets[7]?.displayKey == "PLT-7" }
        assertEquals(3L, sentProject)
    }

    @Test fun moveShowsServerPreviewBeforeConfirmAndPublishesTheCanonicalTicket() {
        val original = """{"id":7,"title":"Ship","ticket_key":"PLT-7","project_id":3,"sprint_id":10,
            "capabilities":{"can":{"canMoveProject":{"allowed":true}}}}"""
        val moved = """{"id":7,"title":"Ship","ticket_key":"OPS-21","project_id":4,"key_aliases":["PLT-7"],
            "project":{"id":4,"key":"OPS","name":"Operations","is_archived":false},
            "workflow_state_key":"ready","container":"backlog"}"""
        var saved = original
        val repository = TaskRepository(ApiClient { request ->
            response(when (request.path) {
                "tasks/7/detail" -> saved
                "tasks/7/history" -> "[]"
                "projects/mine" -> """[
                    {"id":3,"key":"PLT","name":"Source","capabilities":{"canCreateTickets":true}},
                    {"id":4,"key":"OPS","name":"Operations","capabilities":{"canCreateTickets":true}},
                    {"id":5,"key":"OLD","name":"Archived","is_archived":true,"capabilities":{"canCreateTickets":true}},
                    {"id":6,"key":"VIEW","name":"Viewer","capabilities":{"canCreateTickets":false}}
                ]"""
                "tasks/7/move-project/preview" -> {
                    require(request.body!!.decodeToString() == """{"project_id":4}""")
                    """{"target_project_id":4,"key_format":"OPS-<number>",
                        "workflow_state":{"id":22,"key":"ready","name":"Ready","category":"open"},"leaves_sprint":true}"""
                }
                "tasks/7/move-project" -> {
                    require(request.body!!.decodeToString() == """{"project_id":4}""")
                    saved = moved
                    """{"task":$moved,"moved":true,"cleared_sprint":{"id":10,"name":"Sprint"}}"""
                }
                else -> "{}"
            })
        })
        val detail = TicketDetailViewModel(7, repository, session)
        await { detail.ui.value.detail?.displayKey == "PLT-7" }
        detail.startProjectMove()
        await { detail.ui.value.moveProjects.size == 1 }
        detail.previewProjectMove(4)
        await { detail.ui.value.confirm != null }
        assertEquals("PLT-7", detail.ui.value.detail?.displayKey)
        assertTrue(detail.ui.value.confirm!!.message.contains("OPS-<number>"))
        assertTrue(detail.ui.value.confirm!!.message.contains("Ready"))
        assertTrue(detail.ui.value.confirm!!.message.contains("leave"))
        detail.acceptConfirm()
        await { detail.ui.value.detail?.displayKey == "OPS-21" }
        assertNull(detail.ui.value.detail?.sprintId)
        assertEquals(listOf("PLT-7"), detail.ui.value.detail?.keyAliases)
        assertEquals("ready", session.store.state.value.tickets[7]?.workflowStateKey)
    }

    @Test fun archivedAndViewerTicketsExposeOnlyTheActionsAllowedByTheServer() {
        for (reason in listOf("PROJECT_ARCHIVED", "READ_ONLY_ROLE")) {
            val ticket = """{"id":7,"title":"Read only","project_id":3,
                "project":{"id":3,"key":"PLT","name":"Platform","is_archived":${reason == "PROJECT_ARCHIVED"}},
                "capabilities":{"can":{"edit":{"allowed":false,"reason":"$reason"},
                    "canMoveProject":{"allowed":false,"reason":"$reason"},
                    "comment":{"allowed":true},"logWork":{"allowed":false,"reason":"$reason"}}}}"""
            val repository = TaskRepository(ApiClient { request ->
                response(when (request.path) {
                    "tasks/7/detail" -> ticket
                    "tasks/7/history" -> "[]"
                    else -> "{}"
                })
            })
            val detail = TicketDetailViewModel(7, repository, session)
            await { detail.ui.value.detail?.capabilities?.can?.edit?.reason == reason }
            assertEquals(setOf(TaskCapability.Comment), detail.ui.value.availableActions)
            detail.startEdit()
            detail.startProjectMove()
            detail.startLogWork()
            detail.startEstimateEdit(EstimateKind.Original)
            assertFalse(detail.ui.value.detailEditing)
            assertFalse(detail.ui.value.movePickerOpen)
            assertNull(detail.ui.value.worklogDraft)
            assertNull(detail.ui.value.estimateDraft)
            assertFalse(detail.ui.value.canLogWork)
            assertFalse(detail.ui.value.canEditEstimate)
            assertNotEquals("This action is not available.", capabilityReasonCopy(reason))
        }
    }

    @Test fun searchingAnOldKeyUsesServerResolutionAndShowsTheCurrentTicket() {
        val repository = TaskRepository(ApiClient { request ->
            response(when (request.path) {
                "tasks/by-key/OLD-7" -> """{"id":7,"title":"Moved ticket","ticket_key":"OPS-21",
                    "project_id":4,"project":{"id":4,"key":"OPS","name":"Operations","is_archived":true},
                    "key_aliases":["OLD-7"],"container":"backlog"}"""
                else -> "[]"
            })
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        shell.setSearch("old-7")
        await { !shell.ui.value.searching }
        assertEquals(listOf("OPS-21"), shell.searchResults.value.map { it.displayKey })
        assertTrue(shell.searchResults.value.single().project!!.archived)
        assertEquals(listOf("OLD-7"), shell.searchResults.value.single().keyAliases)
    }

    @Test fun changingProjectScopesBacklogAndTheCurrentSprintInsteadOfTheActorsTeam() {
        val projects = """[{"id":3,"key":"PLT","name":"Platform","capabilities":{"canCreateTickets":true}},
            {"id":4,"key":"OPS","name":"Operations","capabilities":{"canCreateTickets":true}}]"""
        val repository = TaskRepository(ApiClient { request ->
            response(when {
                request.path.startsWith("projects") -> projects
                request.path == "sprints?project_id=3" -> """{"sprints":[{"id":10,"name":"Platform sprint","project_id":3,"status":"active"}],"currentSprintId":10}"""
                request.path == "sprints?project_id=4" -> """{"sprints":[{"id":20,"name":"Ops sprint","project_id":4,"status":"active"}],"currentSprintId":20}"""
                request.path.startsWith("tasks/backlog") && request.path.contains("project_id=3") ->
                    """{"tasks":[{"id":7,"title":"Platform work","project_id":3,"team_id":99,"container":"backlog"}]}"""
                request.path.startsWith("tasks/backlog") && request.path.contains("project_id=4") ->
                    """{"tasks":[{"id":8,"title":"Ops work","project_id":4,"team_id":99,"container":"backlog"}]}"""
                else -> "{}"
            })
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        shell.bind(1, "employee", "Own team", agileEnabled = true, teamId = 8)
        await { shell.ui.value.currentSprintId == 10L }
        plan.loadBacklog()
        await { plan.ui.value.backlog.map { it.id } == listOf(7L) }
        assertEquals(listOf(10L), shell.ui.value.teamSprints.map { it.id })
        shell.selectProject(4)
        await { shell.ui.value.currentSprintId == 20L }
        plan.loadBacklog()
        await { plan.ui.value.backlog.map { it.id } == listOf(8L) }
        assertEquals(listOf(20L), shell.ui.value.teamSprints.map { it.id })
        assertEquals(4L, plan.ui.value.planScope.selectedProjectId)
    }

    @Test fun aServerWithoutProjectListingKeepsLegacyTeamScope() {
        val repository = TaskRepository(ApiClient { request ->
            response(if (request.path == "org/teams") """[{"id":8,"name":"Own team"}]""" else "{}")
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        shell.bind(1, "super_admin", "Own team", agileEnabled = false, teamId = 8)
        await { shell.ui.value.teamResolved }
        assertFalse(shell.ui.value.planScope.projectScoped)
        assertEquals(TeamScope(teamId = 8), shell.ui.value.teamScope)
    }

    @Test fun anArchivedTicketCanStillBeCommentedOnWhenTheServerAllowsIt() {
        val repository = TaskRepository(ApiClient { request ->
            response(when (request.path) {
                "tasks/7/detail" -> """{"id":7,"title":"Archive","project":{"id":3,"is_archived":true},
                    "capabilities":{"can":{"edit":{"allowed":false,"reason":"PROJECT_ARCHIVED"},"comment":{"allowed":true}}}}"""
                "tasks/7/comments" -> """{"id":1,"content":"Still readable","user_id":1}"""
                "tasks/7/history" -> "[]"
                else -> "{}"
            })
        })
        val detail = TicketDetailViewModel(7, repository, session)
        await { detail.ui.value.detail != null }
        detail.addComment(7, "Still readable")
        await { detail.ui.value.detailComments.isNotEmpty() }
        assertEquals("Still readable", detail.ui.value.detailComments.single().content)
        assertEquals(1, detail.ui.value.detail?.commentCount)
        assertFalse(detail.ui.value.detailEditing)
    }

    private fun response(body: String) = ApiResponse(200, emptyMap(), body.toByteArray())
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for UI state", condition())
    }
}
