package app.aino.mobile.feature.tasks

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class PlanScopeBehaviorTest {
    private val api = TeamPlanApi()
    private lateinit var session: TicketSession
    private lateinit var viewModel: TaskViewModel
    private lateinit var planViewModel: PlanViewModel
    private lateinit var boardViewModel: SprintBoardViewModel
    private lateinit var reader: TaskViewModel
    private lateinit var viewModels: ViewModelStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
        viewModel = TaskViewModel(TaskRepository(api), ticketSession = session)
        planViewModel = PlanViewModel(TaskRepository(api), session)
        boardViewModel = SprintBoardViewModel(TaskRepository(api), session)
        reader = TaskViewModel(TaskRepository(api), ticketSession = session)
        viewModels = ViewModelStore().apply {
            put("tasks", viewModel)
            put("plan", planViewModel)
            put("board", boardViewModel)
            put("reader", reader)
        }
    }

    @After
    fun tearDown() {
        api.releaseBlockedRequest()
        viewModels.clear()
        session.close()
        Dispatchers.resetMain()
    }

    @Test
    fun returningToAViewedTeamShowsItsCachedTicketsBeforeRefreshCompletes() {
        viewModel.bind(
            userId = 1, role = "super_admin", teamName = "Team One", agileEnabled = true,
            teamId = 11, sprintEnabled = true, customFieldsEnabled = true,
        )
        await { boardViewModel.ui.value.tasks.map { it.id } == listOf(401L) }
        viewModel.loadBacklog()
        viewModel.loadScheduled()
        await {
            planViewModel.ui.value.planLoaded && planViewModel.ui.value.backlog.map { it.id } == listOf(301L) &&
                viewModel.ui.value.scheduled.map { it.id } == listOf(501L)
        }
        val filters = TaskFilters(priority = "medium")
        viewModel.setFilters(filters)

        viewModel.selectTeam(12, persist = false)
        assertTrue(boardViewModel.ui.value.tasks.none { it.teamId == 11L })
        assertTrue(planViewModel.ui.value.backlog.none { it.teamId == 11L })
        assertTrue(viewModel.ui.value.scheduled.none { it.teamId == 11L })
        await {
            planViewModel.ui.value.planLoaded && planViewModel.ui.value.backlog.map { it.id } == listOf(302L) &&
                boardViewModel.ui.value.tasks.map { it.id } == listOf(402L) &&
                viewModel.ui.value.scheduled.map { it.id } == listOf(502L)
        }

        api.blockNextTeamOneLoad()
        viewModel.selectTeam(11, persist = false)
        assertTrue("the team refresh should be in flight", api.awaitBlockedRequest())

        assertEquals(listOf(301L), planViewModel.ui.value.backlog.map { it.id })
        assertEquals(listOf(401L), boardViewModel.ui.value.tasks.map { it.id })
        assertEquals(listOf(501L), viewModel.ui.value.scheduled.map { it.id })
        assertEquals(11L, viewModel.ui.value.selectedTeamId)
        assertEquals("Team One ticket", planViewModel.ui.value.backlog.single().title)
        await { reader.ui.value.selectedTeamId == 11L && reader.ui.value.filters == filters }
        assertEquals(1L, reader.ui.value.userId)
        assertEquals("super_admin", reader.ui.value.role)
        assertEquals("Team One", reader.ui.value.teamName)
        assertEquals(11L, reader.ui.value.teamId)
        assertEquals(21L, reader.ui.value.selectedSprintId)
        assertEquals(listOf(21L), reader.ui.value.teamSprints.map { it.id })
        assertEquals(listOf(11L, 12L), reader.ui.value.teams.map { it.id })
        assertTrue(reader.ui.value.agileEnabled && reader.ui.value.sprintsEnabled && reader.ui.value.customFieldsEnabled)
        assertEquals(listOf("Mobile"), reader.ui.value.labels.map { it.name })
        assertEquals(listOf("Alex"), reader.ui.value.assignableUsers.map { it.fullName })
        assertEquals(listOf("Client"), reader.ui.value.projects.map { it.name })
        assertEquals(listOf("Ready"), reader.ui.value.agile.workflowStates.map { it.name })

        api.releaseBlockedRequest()
        await { planViewModel.ui.value.backlog.singleOrNull()?.title == "Team One refreshed" }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for Tasks UI state", condition())
    }

    private class TeamPlanApi : ApiClient {
        private val blockNextTeamOneLoad = AtomicBoolean(false)
        private val blockedRequest = CountDownLatch(1)
        private val releaseRequest = CountDownLatch(1)
        @Volatile private var teamOneTitle = "Team One ticket"
        @Volatile private var blockTeamOne = false

        override fun execute(request: ApiRequest): ApiResponse {
            if (blockTeamOne && (request.path.contains("team_id=11") || request.path == "sprints/21/board")) {
                if (blockNextTeamOneLoad.compareAndSet(true, false)) blockedRequest.countDown()
                releaseRequest.await(5, TimeUnit.SECONDS)
            }
            val teamTwo = request.path.contains("team_id=12") || request.path == "sprints/22/board"
            val team = if (teamTwo) 12 else 11
            val sprint = if (teamTwo) 22 else 21
            val body = when {
                request.path == "org/teams" -> """[{"id":11,"name":"Team One"},{"id":12,"name":"Team Two"}]"""
                request.path == "tasks/available-sprints" ->
                    """[{"id":21,"name":"One","team_id":11,"status":"active"},{"id":22,"name":"Two","team_id":12,"status":"active"}]"""
                request.path.startsWith("sprints?team_id=") ->
                    """{"sprints":[{"id":$sprint,"name":"Sprint","team_id":$team,"status":"active"}],"currentSprintId":$sprint}"""
                request.path == "sprints/21/board" || request.path == "sprints/22/board" ->
                    """{"sprint":{"id":$sprint,"name":"Sprint","team_id":$team},"tickets":[{"id":${if (teamTwo) 402 else 401},"title":"Board","team_id":$team,"sprint_id":$sprint,"container":"sprint"}]}"""
                request.path.startsWith("tasks/scheduled?") ->
                    """{"tasks":[{"id":${if (teamTwo) 502 else 501},"title":"Scheduled","team_id":$team,"container":"scheduled","planned_date":"${java.time.LocalDate.now()}"}]}"""
                request.path.startsWith("tasks/backlog?") -> {
                    val task = if (teamTwo) {
                        """{"id":302,"title":"Team Two ticket","team_id":12,"container":"backlog"}"""
                    } else {
                        """{"id":301,"title":"$teamOneTitle","team_id":11,"container":"backlog"}"""
                    }
                    """{"tasks":[$task],"pagination":{"total":1,"hasMore":false}}"""
                }
                request.path == "tasks/labels" -> """[{"id":7,"name":"Mobile"}]"""
                request.path == "tasks/assignable-users" -> """[{"id":1,"full_name":"Alex"}]"""
                request.path == "projects" -> """[{"id":8,"name":"Client"}]"""
                request.path == "agile/config" -> """{"workflowStates":[{"id":101,"key":"todo","name":"Ready"}]}"""
                request.path == "user/preferences" -> "{}"
                request.path == "tasks/schedule-overlays" || request.path.startsWith("tasks/schedule-overlays?") -> "{}"
                else -> "{}"
            }
            return ApiResponse(200, emptyMap(), body.toByteArray())
        }

        fun blockNextTeamOneLoad() {
            teamOneTitle = "Team One refreshed"
            blockTeamOne = true
            blockNextTeamOneLoad.set(true)
        }

        fun awaitBlockedRequest(): Boolean = blockedRequest.await(5, TimeUnit.SECONDS)

        fun releaseBlockedRequest() {
            releaseRequest.countDown()
        }
    }
}
