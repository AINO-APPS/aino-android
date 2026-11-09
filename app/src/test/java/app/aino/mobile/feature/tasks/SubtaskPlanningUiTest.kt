package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubtaskPlanningUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun backlogNestsFlatSubtaskFromLoadedTicketStore() {
        val repository = TaskRepository(ApiClient { ApiResponse(200, emptyMap(), "{}".toByteArray()) })
        val shell = TaskViewModel(repository)
        val plan = PlanViewModel(repository, shell.ticketSession)
        val parent = Task(7, "Parent work", container = "backlog")
        val child = Task(8, "Flat child", container = "backlog", parentTaskId = 7, isSubtask = true)
        val ui = TaskUiState(
            tickets = mapOf(parent.id to parent, child.id to child),
            planTicketIds = listOf(parent.id),
            planLoaded = true,
        )
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                BacklogPage(ui, shell, plan, onOpen = {})
            }
        }
        compose.onNodeWithText("Flat child").assertIsDisplayed()
        assertTrue(
            compose.onNodeWithText("Flat child").fetchSemanticsNode().boundsInRoot.left >
                compose.onNodeWithText("Parent work").fetchSemanticsNode().boundsInRoot.left,
        )
        shell.ticketSession.close()
    }

    @Test fun backlogKeepsCompletedSubtasksVisibleIndentedUnderTheirOpenParent() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """
                {"tasks":[{"id":7,"title":"Parent work","container":"backlog",
                  "subtasks":[{"id":8,"title":"Finished child","container":"backlog",
                    "parent_task_id":7,"is_subtask":true,"completed_at":"2026-11-09"}]}]}
            """.trimIndent().toByteArray())
        })
        val parent = repository.loadPlanPage(500, 0).tasks.single()
        val shell = TaskViewModel(repository)
        val plan = PlanViewModel(repository, shell.ticketSession)
        val child = parent.subtasks.orEmpty().single().copy(title = "Updated child")
        val ui = TaskUiState(tickets = mapOf(parent.id to parent, child.id to child), planTicketIds = listOf(parent.id), planLoaded = true)
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                BacklogPage(ui, shell, plan, onOpen = {})
            }
        }
        compose.onNodeWithText("Updated child").assertIsDisplayed()
        assertTrue(
            compose.onNodeWithText("Updated child").fetchSemanticsNode().boundsInRoot.left >
                compose.onNodeWithText("Parent work").fetchSemanticsNode().boundsInRoot.left,
        )
        shell.ticketSession.close()
    }

    @Test fun sprintPlanNestsFlatSubtasksAndOnlyOffersServerAllowedParentPlacement() {
        val repository = TaskRepository(ApiClient { ApiResponse(200, emptyMap(), "{}".toByteArray()) })
        val shell = TaskViewModel(repository)
        val plan = PlanViewModel(repository, shell.ticketSession)
        val scope = PlanScope(
            agileEnabled = true, selectedSprintId = 10,
            sprints = listOf(AvailableSprint(10, "Sprint 10", "active")),
        )
        val parent = Task(7, "Parent work", container = "sprint", sprintId = 10, storyPoints = 5.0,
            capabilities = TaskCapabilities(can = TaskCapabilityActions(placeInSprint = TaskCapabilityDecision(true))))
        val child = Task(8, "Child work", container = "sprint", sprintId = 10, parentTaskId = 7,
            isSubtask = true, storyPoints = 13.0,
            capabilities = TaskCapabilities(can = TaskCapabilityActions(placeInSprint = TaskCapabilityDecision(false, "SUBTASK_FOLLOWS_PARENT"))))
        val tickets = listOf(child, parent).associateBy { it.id }
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                SprintPlanPage(
                    TaskUiState(planScope = scope),
                    SprintBoardUiState(planScope = scope, tickets = tickets, boardTicketIds = listOf(8, 7)),
                    shell, plan, onOpen = {},
                )
            }
        }
        compose.onNodeWithText("5 SP").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Move to backlog").assertCountEquals(1)
        compose.onNodeWithText("Child work").performScrollTo().assertIsDisplayed()
        val childBounds = compose.onNodeWithText("Child work").fetchSemanticsNode().boundsInRoot
        val parentBounds = compose.onNodeWithText("Parent work").fetchSemanticsNode().boundsInRoot
        assertTrue(childBounds.top > parentBounds.top)
        assertTrue(childBounds.left > parentBounds.left)
        shell.ticketSession.close()
    }

    @Test fun kanbanBacklogNestsServerSubtasksWithoutHidingTheirCards() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """
                {"project":{"id":3,"type":"kanban","planTabs":["board","backlog"],"kanban_backlog_enabled":true},
                 "tickets":[{"id":7,"title":"Parent work","project_id":3,"subtasks":[
                    {"id":8,"title":"Child work","is_subtask":true,"parent_task_id":7,"project_id":3}]}]}
            """.trimIndent().toByteArray())
        })
        val response = repository.loadProjectBacklog(3)
        val shell = TaskViewModel(repository)
        val scope = PlanScope(projectScoped = true, selectedProjectId = 3, projects = listOf(response.project))
        val board = ProjectBoardUiState(planScope = scope, backlog = response, backlogLoaded = true,
            tickets = response.tickets.associateBy(Task::id), backlogTicketIds = listOf(7))
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                KanbanBoardPage(TaskUiState(planScope = scope), board, shell,
                    backlog = true, onRefresh = {}, onRetry = {}, onOpen = {})
            }
        }
        compose.onNodeWithText("Child work").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Child work").fetchSemanticsNode().boundsInRoot.left >
            compose.onNodeWithText("Parent work").fetchSemanticsNode().boundsInRoot.left)
        shell.ticketSession.close()
    }
}
