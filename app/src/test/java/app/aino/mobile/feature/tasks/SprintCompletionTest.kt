package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SprintCompletionTest {
    @Test
    fun previewReadsTheServersTicketListsAndNumericDefaultDestination() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """
                {"sprint":{"id":10,"name":"Sprint","status":"active"},"defaultTarget":11,
                 "moving":[{"id":201,"title":"Parent","story_points":"5.0","completed":false},
                           {"id":202,"title":"Subtask","story_points":null,"completed":true}],
                 "staying":[{"id":203,"title":"Finished","completed":true}],"movingPoints":5}
            """.trimIndent().toByteArray())
        })

        val preview = repository.loadCompletionPreview(10)

        assertEquals("11", preview.defaultTarget)
        assertEquals(2, preview.incompleteTasks)
        assertEquals(1, preview.doneTasks)
        assertEquals(3, preview.totalTasks)
        assertEquals(5.0, preview.movingPoints, 0.0)
        assertNull(preview.supportsNewSprint)
    }

    @Test
    fun selectedNewSprintPreviewAndCompletionUseTheSameChoice() {
        val requests = mutableListOf<app.aino.mobile.core.network.ApiRequest>()
        val repository = TaskRepository(ApiClient { request ->
            requests += request
            ApiResponse(200, emptyMap(), if (request.method == "POST") "{}".toByteArray() else """
                {"supportsNewSprint":true,"defaultTarget":11,"defaultTargetType":"next","selectedTarget":"new",
                 "hasNext":true,"nextPlannedSprint":{"id":11,"name":"Next","status":"planned"},
                 "moving":[{"id":201,"title":"Done parent"},{"id":202,"title":"Done subtask","parent_task_id":201}],
                 "movingParents":[{"id":201,"title":"Done parent"}],"movingSubtasks":1,
                 "staying":[{"id":203,"title":"Stays"}],"movingPoints":8,
                 "newSprint":{"name":"Sprint 3","start_date":"2026-11-10","end_date":"2026-11-23"}}
            """.trimIndent().toByteArray())
        })

        val preview = repository.loadCompletionPreview(10, "new")
        repository.completeSprint(10, preview.selectedTarget!!)

        assertEquals("sprints/10/completion-preview?rolloverTo=new", requests.first().path)
        assertEquals("""{"rolloverTo":"new"}""", requests.last().body!!.toString(Charsets.UTF_8))
        assertTrue(preview.supportsNewSprint == true)
        assertEquals(listOf(201L), preview.movingParents!!.map { it.id })
        assertEquals(1, preview.movingSubtasks)
        assertEquals(listOf(203L), preview.staying.tickets.map { it.id })
        assertEquals(8.0, preview.movingPoints, 0.0)
        assertEquals("2026-11-10", preview.newSprint?.startDate)
    }

    @Test
    fun sprintListDecodesServerManagementCapability() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """
                {"sprints":[{"id":10,"name":"Sprint","status":"active","can_manage":true}],
                 "currentSprintId":10}
            """.trimIndent().toByteArray())
        })

        val sprint = repository.loadProjectSprints(7).sprints.single()

        assertEquals(true, sprint.canManage)
        assertTrue(canManageSprint(sprint, "employee"))
        assertTrue(!canManageSprint(sprint.copy(canManage = false), "team_lead"))
        assertTrue(canManageSprint(sprint.copy(canManage = null), "team_lead"))
    }
}
