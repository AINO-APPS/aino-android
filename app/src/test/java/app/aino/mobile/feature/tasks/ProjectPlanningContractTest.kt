package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPlanningContractTest {
    @Test
    fun projectReadsCarryTheServersPlanningContract() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """[{
                "id":3,"key":"FLOW","name":"Flow","type":"kanban",
                "planTabs":["backlog","board"],"kanban_backlog_enabled":true,"wip_enabled":true,
                "availableTypes":["scrum","kanban","task_list"],"changeableTypes":["kanban","task_list"],
                "capabilities":{"canAdminister":true,"canChangeType":true}
            }]""".toByteArray())
        })

        for (projects in listOf(repository.loadProjects(), repository.loadAccessibleProjects()!!, repository.loadMyProjects())) {
            val project = projects.single()
            assertEquals("kanban", project.type)
            assertEquals(listOf("backlog", "board"), project.planTabs)
            assertTrue(project.kanbanBacklogEnabled)
            assertTrue(project.wipEnabled)
            assertEquals(listOf("scrum", "kanban", "task_list"), project.availableTypes)
            assertEquals(listOf("kanban", "task_list"), project.changeableTypes)
            assertEquals("Kanban", project.typeDisplayName)
            assertTrue(project.capabilities!!.canAdminister)
            assertTrue(project.capabilities.canChangeType)

            val scope = PlanScope(
                projects = projects,
                projectScoped = true,
                selectedProjectId = project.id,
            )
            assertEquals("kanban", scope.projectType)
            assertEquals(listOf("backlog", "board"), scope.planTabs)
            assertTrue(scope.kanbanBacklogEnabled)
            assertTrue(scope.wipEnabled)
            assertTrue(scope.canChangeType)
        }
    }

    @Test
    fun oldProjectResponsesDefaultOnlyTheirMissingTypeContractToScrum() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(
                200,
                emptyMap(),
                """[{"id":4,"key":"OLD","name":"Old project",
                    "capabilities":{"canAdminister":true,"canCreateTickets":true}}]""".toByteArray(),
            )
        })

        for (projects in listOf(repository.loadProjects(), repository.loadAccessibleProjects()!!, repository.loadMyProjects())) {
            val project = projects.single()
            val scope = PlanScope(projects = projects, projectScoped = true, selectedProjectId = project.id)

            assertEquals("scrum", project.type)
            assertEquals(listOf("backlog", "sprint", "reports"), project.planTabs)
            assertEquals("Scrum", project.typeDisplayName)
            assertTrue(project.availableTypes.isEmpty())
            assertTrue(project.changeableTypes.isEmpty())
            assertFalse(project.kanbanBacklogEnabled)
            assertFalse(project.wipEnabled)
            assertTrue(project.capabilities!!.canAdminister)
            assertTrue(project.capabilities.canCreateTickets)
            assertFalse(project.capabilities.canChangeType)
            assertEquals("scrum", scope.projectType)
            assertEquals(listOf("backlog", "sprint", "reports"), scope.planTabs)
            assertFalse(scope.kanbanBacklogEnabled)
            assertFalse(scope.wipEnabled)
            assertFalse(scope.canChangeType)
        }
    }

    @Test
    fun selectedProjectKeepsTheServersTabsAndSwitchesEvenWithoutTheAgileFeature() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """[
                {"id":3,"type":"kanban","planTabs":["board"],"wip_enabled":true,
                    "capabilities":{"canAdminister":false,"canChangeType":true}},
                {"id":4,"type":"task_list","planTabs":["calendar","list","board"],
                    "kanban_backlog_enabled":false,"wip_enabled":false,"capabilities":{"canChangeType":false}},
                {"id":5,"type":"scrum","planTabs":[],"capabilities":{"canChangeType":false}}
            ]""".toByteArray())
        })
        val scope = PlanScope(
            projects = repository.loadAccessibleProjects()!!,
            projectScoped = true,
            selectedProjectId = 3,
            agileEnabled = false,
        )

        assertEquals("kanban", scope.projectType)
        assertEquals(listOf("board"), scope.planTabs)
        assertFalse(scope.kanbanBacklogEnabled)
        assertTrue(scope.wipEnabled)
        assertTrue(scope.canChangeType)

        val taskList = scope.copy(selectedProjectId = 4)
        assertEquals("task_list", taskList.projectType)
        assertEquals(listOf("calendar", "list", "board"), taskList.planTabs)
        assertFalse(taskList.wipEnabled)
        assertFalse(taskList.canChangeType)
        assertEquals(emptyList<String>(), scope.copy(selectedProjectId = 5).planTabs)
    }

    @Test
    fun aProjectWithoutCapabilitiesDoesNotGrantTypeChanges() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """[{"id":4,"key":"OLD","name":"Old project"}]""".toByteArray())
        })
        val scope = PlanScope(projects = repository.loadProjects(), projectScoped = true, selectedProjectId = 4)

        assertFalse(scope.canChangeType)
        assertEquals("scrum", scope.projectType)
        assertEquals(listOf("backlog", "sprint", "reports"), scope.planTabs)
    }
}
