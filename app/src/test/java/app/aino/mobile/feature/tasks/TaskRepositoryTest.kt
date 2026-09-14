package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskRepositoryTest {
    private fun client(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) = ApiClient { request ->
        captured += request
        ApiResponse(200, emptyMap(), body(request).toByteArray())
    }

    @Test
    fun decodesTheEnrichedTaskListAndStats() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(
            client(captured) {
                """
                {"tasks":[{"id":7,"title":"Ship 0.2.0","priority":"high","status":"in_progress",
                "comment_count":2,"issue_key":"AND-7","is_blocked":true,"blocked_reason":"waiting on API",
                "labels":[{"id":3,"name":"release","color":"#2383E2"}],
                "assignee":{"username":"vvronline","full_name":"Vishnu V R"},
                "project":{"id":1,"key":"AND","name":"Android"}}],
                "stats":{"total":1,"done":0,"inProgress":1,"percent":0}}
                """.trimIndent()
            },
        )

        val result = repository.loadToday("2026-09-14", TaskFilters())

        assertEquals("tasks?date=2026-09-14&assignee=me", captured.single().path)
        val task = result.tasks.single()
        assertEquals("AND-7", task.issueKey)
        assertEquals("waiting on API", task.blockedReason)
        assertEquals("Vishnu V R", task.assignee?.display())
        assertEquals("release", task.labels.single().name)
        assertEquals(1, result.stats.inProgress)
    }

    @Test
    fun decodesBacklogSummaryAndPagination() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(
            client(captured) {
                """{"tasks":[],"summary":{"total":12,"byStatus":{"pending":9},"byPriority":{"high":4}},
                   "pagination":{"limit":100,"offset":0,"total":12,"hasMore":false}}"""
            },
        )

        val result = repository.loadBacklog(TaskFilters(assigneeMine = false))

        assertEquals("tasks/backlog?limit=100&offset=0", captured.single().path)
        assertEquals(12, result.summary.total)
        assertEquals(4, result.summary.byPriority["high"])
        assertEquals(false, result.pagination.hasMore)
    }

    @Test
    fun backlogCreateDropsTheDateTheServerRejects() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(client(captured) { """{"id":9,"title":"Later"}""" })

        repository.createBacklogTask(CreateTaskPayload(title = "Later", date = "2026-09-14"))

        val body = captured.single().body!!.toString(Charsets.UTF_8)
        assertEquals("tasks/backlog", captured.single().path)
        assertEquals("POST", captured.single().method)
        assertTrue("date must not be sent to the backlog route", !body.contains("\"date\":\"2026-09-14\""))
    }

    @Test
    fun statusChangeUsesPatchAndDecodesTheEnrichedRow() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(client(captured) { """{"id":7,"title":"Ship","status":"done"}""" })

        val updated = repository.updateStatus(7, "done")

        assertEquals("PATCH", captured.single().method)
        assertEquals("tasks/7/status", captured.single().path)
        assertEquals("""{"status":"done"}""", captured.single().body!!.toString(Charsets.UTF_8))
        assertEquals("done", updated.status)
    }

    @Test
    fun surfacesTheServerErrorMessageVerbatim() {
        val repository = TaskRepository(
            ApiClient {
                throw ApiError.Http(
                    409,
                    """{"error":"WIP limit reached for \"In progress\" (3/3). Move a ticket out of this column first.","code":"WIP_EXCEEDED"}""",
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
    fun detailCarriesCommentsAndCommentPostSendsJsonContent() {
        val captured = mutableListOf<ApiRequest>()
        val repository = TaskRepository(
            client(captured) { request ->
                if (request.method == "GET") {
                    """{"id":7,"title":"Ship","comments":[{"id":1,"content":"ack","full_name":"Vishnu V R","created_at":"2026-09-14T10:00:00Z"}]}"""
                } else """{"id":2,"content":"on it","username":"vvronline"}"""
            },
        )

        val detail = repository.loadDetail(7)
        val comment = repository.addComment(7, "on it")

        assertEquals("tasks/7/detail", captured[0].path)
        assertEquals("Vishnu V R", detail.comments.single().author())
        assertEquals("tasks/7/comments", captured[1].path)
        assertEquals("""{"content":"on it"}""", captured[1].body!!.toString(Charsets.UTF_8))
        assertEquals("vvronline", comment.author())
    }
}
