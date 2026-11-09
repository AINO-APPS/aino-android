package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectBoardViewModelTest {
    private lateinit var session: TicketSession
    private lateinit var viewModel: ProjectBoardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
    }

    @After
    fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    @Test
    fun selectedKanbanProjectLoadsServerColumnsAndFiltersCardsWithoutChangingOccupancy() {
        val paths = mutableListOf<String>()
        val repository = TaskRepository(ApiClient { request ->
            paths += request.path
            ApiResponse(
                200,
                emptyMap(),
                """
                {
                  "project":{"id":17,"key":"FLOW","name":"Flow","type":"kanban",
                    "planTabs":["board"],"enable_wip_limits":true,
                    "capabilities":{"canCreateTickets":true}},
                  "sprint":null,
                  "columns":[
                    {"stateId":21,"count":2,"limit":3,"accepting":true,
                     "state":{"id":21,"key":"in_progress","name":"In Progress","category":"open"}},
                    {"stateId":22,"count":1,"limit":3,"accepting":true,
                     "state":{"id":22,"key":"review","name":"Review","category":"in_review"}}
                  ],
                  "tickets":[
                    {"id":71,"title":"Ship the board","project_id":17,"workflow_state_id":21,
                     "workflow_state_key":"in_progress","priority":"high"},
                    {"id":72,"title":"Write release notes","project_id":17,"workflow_state_id":21,
                     "workflow_state_key":"in_progress","priority":"low"},
                    {"id":73,"title":"Review copy","project_id":17,"workflow_state_id":22,
                     "workflow_state_key":"review","priority":"high"}
                  ]
                }
                """.trimIndent().toByteArray(),
            )
        })
        viewModel = ProjectBoardViewModel(repository, session)
        val project = ProjectOption(
            id = 17,
            key = "FLOW",
            name = "Flow",
            type = "kanban",
            planTabs = listOf("board"),
            wipEnabled = true,
        )

        session.writePlanScope(
            PlanScope(
                projects = listOf(project),
                projectScoped = true,
                selectedProjectId = project.id,
                filters = TaskFilters(priority = "high"),
            ),
        )
        val state = runBlocking {
            withTimeout(5_000) { viewModel.ui.first { it.board != null } }
        }

        assertEquals(listOf("projects/17/board"), paths)
        assertEquals(listOf("In Progress", "Review"), state.columns.mapNotNull { it.state?.name })
        assertEquals(2, state.columns.first().count)
        assertEquals(listOf(71L), state.tasksForColumn(state.columns.first()).map { it.id })
        assertTrue(state.serverProject?.wipEnabled == true)
    }
}
