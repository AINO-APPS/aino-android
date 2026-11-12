package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class ProjectTicketCollectionTest {
    @Test fun projectCollectionCarriesRangeFiltersAndPaginationWithoutLegacyPlacement() {
        var path = ""
        val repository = TaskRepository(ApiClient { request ->
            path = request.path
            ApiResponse(200, emptyMap(), """{
                "tasks":[{"id":7,"title":"Ship","project_id":3,"start_date":"2026-11-09","due_date":"2026-11-12"}],
                "pagination":{"offset":500,"hasMore":true}
            }""".toByteArray())
        })

        val result = repository.loadProjectTickets(
            projectId = 3,
            query = ProjectTicketQuery(
                scope = "calendar", from = "2026-11-09", to = "2026-11-30",
                key = "OPS-7", title = "Ship & review", workItemTypeId = "2",
                startOn = "2026-11-09", dueOn = "2026-11-12", sort = "start_date", direction = "desc",
            ),
            filters = TaskFilters(assignee = "unassigned", priority = "high", status = "review"),
            offset = 500,
        )

        val parameters = path.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals("tasks", path.substringBefore('?'))
        assertEquals(mapOf(
            "scope" to "calendar", "project_id" to "3", "start_date" to "2026-11-09",
            "end_date" to "2026-11-30", "key" to "OPS-7", "title" to "Ship & review",
            "work_item_type_id" to "2", "start_on" to "2026-11-09", "due_on" to "2026-11-12",
            "sort" to "start_date", "direction" to "desc", "assignee" to "unassigned",
            "priority" to "high", "status" to "review", "offset" to "500",
        ), parameters)
        assertEquals("2026-11-09", result.tasks.single().startDate)
        assertEquals(500, result.pagination.offset)
        assertTrue(result.pagination.hasMore)
    }
}
