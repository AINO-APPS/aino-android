package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskRepositoryTest {
    private fun client(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) = ApiClient { request ->
        captured += request
        ApiResponse(200, emptyMap(), body(request).toByteArray())
    }

    private fun body(request: ApiRequest) = request.body!!.toString(Charsets.UTF_8)

    @Test
    fun decodesAnEnrichedSprintRowWithNumericStoryPointsAndCriteria() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(
            client(captured) {
                """
                {"tasks":[{"id":7,"title":"Ship","priority":"high","status":"in_progress","story_points":"3.50",
                "sprint_id":12,"workflow_state_id":44,"work_item_type_id":2,"user_id":1,"assigned_to":2,
                "created_at":"2026-09-20T10:00:00.000Z","comment_count":2,"issue_key":"AND-7","is_blocked":true,
                "blocked_reason":"waiting","acceptance_criteria":[{"id":"a1","text":"Builds","done":true},{"text":"Ships"},7],
                "labels":[{"id":3,"name":"release","color":"#2383E2"}],
                "assignee":{"username":"vv","full_name":"Vishnu V R","avatar":"a.png"},
                "creator":{"username":"lead","full_name":null},
                "sprint":{"id":12,"name":"S12","status":"active","start_date":"2026-09-20","end_date":"2026-10-01"},
                "project":{"id":1,"key":"AND","name":"Android","color":"#10b981"}}],
                "stats":{"total":1,"done":0,"inProgress":1,"percent":0}}
                """.trimIndent()
            },
        )

        val result = repository.loadSprintTasks(12, TaskFilters(assignee = "me"))

        assertEquals("tasks?sprint_id=12&assignee=me", captured.single().path)
        val task = result.tasks.single()
        assertEquals(3.5, task.storyPoints!!, 0.0)
        assertEquals("AND-7", task.displayKey)
        assertEquals(listOf("Builds", "Ships"), task.acceptanceCriteria.map { it.text })
        assertTrue(task.acceptanceCriteria.first().done)
        assertEquals("lead", task.creator?.display())
        assertFalse(task.isBacklogItem)
        assertEquals(1, result.stats.inProgress)
    }

    @Test
    fun backlogToleratesOlderServersWithoutPagination() {
        val repository = TaskRepository(client(mutableListOf()) { """{"tasks":[{"id":1,"title":"x"}]}""" })
        val result = repository.loadBacklog(TaskFilters(), 25, 0)
        assertNull(result.pagination)
        assertTrue(result.tasks.single().isBacklogItem)
    }

    @Test
    fun agileConfigMergesOverTheFallback() {
        val repository = TaskRepository(
            client(mutableListOf()) {
                """{"settings":{"estimation_values":[1,2,"3"],"estimation_unit_label":"pts","enable_wip_limits":true,"enable_epics":false,"org_id":4},
                   "workItemTypes":[],"workflowStates":[{"id":9,"key":"todo","name":"Todo","color":"#111111","wip_limit":3,"is_initial":true}],"canEdit":true}"""
            },
        )
        val config = repository.loadAgileConfig()
        assertEquals(listOf("1", "2", "3"), config.pointScale)
        assertEquals("pts", config.unitLabel)
        assertTrue(config.features.wipLimits)
        assertFalse(config.features.epics)
        assertTrue(config.features.storyPoints)
        assertEquals(FALLBACK_WORK_ITEM_TYPES, config.workItemTypes)
        assertEquals(3, config.workflowStates.single().wipLimit)
        assertTrue(config.canEdit)
    }

    @Test
    fun decodesSprintsStatsAndProjects() {
        val repository = TaskRepository(
            client(mutableListOf()) { request ->
                when (request.path) {
                    "tasks/available-sprints" -> """[{"id":2,"name":"S2","start_date":"2026-09-20","end_date":"2026-10-01","status":"active","goal":null,"team_id":1,"team_name":"Core"}]"""
                    "sprints/2/stats" -> """{"sprint":{"id":2},"totals":{"tasks":4,"points":"13","doneTasks":1,"donePoints":5,"remainingPoints":8,"unestimatedTasks":1,"blockedTasks":0,"percentByPoints":38,"percentByTasks":25},"byState":[]}"""
                    "projects" -> """[{"id":1,"key":"WEB","name":"Web","color":null,"task_count":3,"lead_name":"x"}]"""
                    else -> """{"sprints":[{"id":3,"name":"S3","status":"planned","velocity_points":"21.00"}]}"""
                }
            },
        )
        assertEquals("Core", repository.loadAvailableSprints().single().teamName)
        assertEquals(13.0, repository.loadSprintStats(2).totals.points, 0.0)
        assertEquals("WEB", repository.loadProjects().single().key)
        assertEquals(21.0, repository.loadTeamSprints().single().velocityPoints!!, 0.0)
    }

    @Test
    fun writesUseTheWebVerbsAndBodies() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(client(captured) { request ->
            if (request.path.startsWith("sprints/")) """{"sprint":{"id":2,"status":"completed"}}""" else """{"id":7,"title":"t"}"""
        })

        repository.createBacklogTask(CreateBacklogPayload(title = "Later", labelIds = listOf(3), storyPoints = "5", projectId = 1))
        repository.scheduleTask(7, "2026-09-26")
        repository.unscheduleTask(7)
        repository.assignSprint(7, 2)
        repository.updateImportFields(7, ImportUpdatePayload(assignedTo = 4, dueDate = null))
        repository.completeSprint(2, "backlog")
        repository.startSprint(2)

        assertEquals(listOf("POST", "PATCH", "PATCH", "PATCH", "PUT", "POST", "POST"), captured.map { it.method })
        assertEquals(
            listOf("tasks/backlog", "tasks/7/schedule", "tasks/7/unschedule", "tasks/7/assign-sprint", "tasks/7", "sprints/2/complete", "sprints/2/start"),
            captured.map { it.path },
        )
        assertTrue(body(captured[0]).contains("\"label_ids\":[3]"))
        assertTrue(body(captured[0]).contains("\"story_points\":\"5\""))
        assertEquals("""{"date":"2026-09-26"}""", body(captured[1]))
        assertNull(captured[2].body)
        assertEquals("""{"sprint_id":2}""", body(captured[3]))
        assertEquals("""{"assigned_to":4,"due_date":null}""", body(captured[4]))
        assertEquals("""{"rolloverTo":"backlog"}""", body(captured[5]))
    }

    @Test
    fun surfacesTheServerErrorMessageVerbatim() {
        val repository = TaskRepository(
            ApiClient {
                throw ApiError.Http(
                    409,
                    """{"error":"WIP limit reached for \"In progress\" (3/3).","code":"WIP_EXCEEDED"}""",
                    "PATCH",
                    "https://next.aino.org.in/api/tasks/7/status",
                )
            },
        )
        try {
            repository.updateStatus(7, "in_progress")
            fail("expected a TaskFailure")
        } catch (failure: TaskFailure) {
            assertEquals(409, failure.statusCode)
            assertTrue(failure.message!!.startsWith("WIP limit reached"))
        }
    }

    @Test
    fun detailCommentsHistoryAndPanels() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(
            client(captured) { request ->
                when {
                    request.path == "tasks/7/detail" -> """{"id":7,"title":"Ship","comments":[{"id":1,"content":"<p>ack</p>","full_name":"Vishnu V R","user_id":2,"created_at":"2026-09-14T10:00:00Z","updated_at":"2026-09-14T11:00:00Z"}]}"""
                    request.path == "tasks/7/history" -> """[{"id":5,"action":"status_change","old_value":"pending","new_value":"done","username":"vv","created_at":"2026-09-14T10:00:00Z"}]"""
                    request.path == "tasks/7/dependencies" -> """{"blocks":[{"link_id":3,"type":"blocks","id":8,"title":"Other","status":"pending","is_blocked":false}],"blockedBy":[]}"""
                    request.path == "tasks/7/children" -> """{"children":[{"id":9,"title":"Child","type_name":"Story","type_color":"#10b981","state_name":"Done","is_terminal":true,"story_points":"2"}],"rollup":{"totalChildren":1,"doneChildren":1,"totalPoints":2,"donePoints":2,"percentByPoints":100,"percentByCount":100}}"""
                    request.path == "tasks/7/parent" -> """{"parent":null}"""
                    request.path == "tasks/7/acceptance-criteria" && request.method == "GET" -> """{"criteria":[{"id":"x","text":"A","done":false}]}"""
                    request.path == "tasks/7/acceptance-criteria" -> """{"criteria":[{"id":"x","text":"A","done":true}]}"""
                    request.path.startsWith("tasks/lookup/quicksearch") -> """{"tasks":[{"id":8,"title":"Other","story_points":null}]}"""
                    request.path == "tasks/7/block" -> """{"id":7,"is_blocked":true,"blocked_reason":"api"}"""
                    else -> """{"id":2,"content":"<p>on it</p>","username":"vv"}"""
                }
            },
        )

        val detail = repository.loadDetail(7)
        assertEquals("Vishnu V R", detail.comments.single().author())
        assertEquals("done", repository.loadHistory(7).single().newValue)
        assertEquals(3L, repository.loadDependencies(7).blocks.single().linkId)
        assertEquals(1, repository.loadChildren(7).rollup.doneChildren)
        assertNull(repository.loadParent(7))
        assertEquals("A", repository.loadCriteria(7).single().text)
        assertTrue(repository.saveCriteria(7, listOf(CriterionPayload("x", "A", true))).single().done)
        assertEquals(8L, repository.quickSearch("oth er").single().id)
        assertEquals("api", repository.setBlocker(7, true, "api").blockedReason)
        repository.addComment(7, "<p>on it</p>")
        repository.updateComment(7, 2, "<p>edit</p>")

        assertEquals("tasks/lookup/quicksearch?q=oth+er", captured.first { it.path.startsWith("tasks/lookup") }.path)
        assertEquals("""{"is_blocked":true,"blocked_reason":"api"}""", body(captured.first { it.path == "tasks/7/block" }))
        val put = captured.last()
        assertEquals("PUT", put.method)
        assertEquals("tasks/7/comments/2", put.path)
    }

    @Test
    fun commentMultipartCarriesContentAndFileParts() {
        val (type, bytes) = buildCommentMultipart("<p>hi</p>", "a\"b.txt", "text/plain", "data".toByteArray(), "BOUND")
        val text = bytes.toString(Charsets.UTF_8)
        assertEquals("multipart/form-data; boundary=BOUND", type)
        assertTrue(text.contains("name=\"content\"\r\n\r\n<p>hi</p>\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"a_b.txt\""))
        assertTrue(text.endsWith("data\r\n--BOUND--\r\n"))
    }
}
