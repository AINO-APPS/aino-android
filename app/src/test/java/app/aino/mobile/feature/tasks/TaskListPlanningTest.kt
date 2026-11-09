package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskListPlanningTest {
    @Test fun taskListMapsServerCalendarToUpcomingAndKeepsServerTabOrderWithoutSprints() {
        val scope = PlanScope(
            projectScoped = true, selectedProjectId = 3,
            projects = listOf(ProjectOption(id = 3, type = "task_list", planTabs = listOf("list", "board", "calendar"))),
        )
        val ui = TaskUiState(planScope = scope)
        assertEquals(listOf("list", "board", "upcoming"), ui.visibleTabs.map(TaskTab::key))
        assertEquals("Upcoming", tabLabel(ui.visibleTabs.last()))
        assertEquals(TaskTab.Upcoming, TaskTab.fromKey("upcoming"))
        assertEquals(listOf("list", "board", "upcoming"), TaskShellUiState(planScope = scope).visibleTabs.map(TaskTab::key))
    }
}
