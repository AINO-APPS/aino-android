package app.aino.mobile.feature.tasks

import kotlinx.serialization.Serializable
import java.net.URLEncoder

/** Project collection reads are independent of legacy placement and sprint membership. */
data class ProjectTicketQuery(
    val scope: String = "list",
    val from: String = "",
    val to: String = "",
    val undated: Boolean = false,
    val key: String = "",
    val title: String = "",
    val workItemTypeId: String = "",
    val startOn: String = "",
    val dueOn: String = "",
    val sort: String = "key",
    val direction: String = "asc",
)

@Serializable
data class ProjectTicketPagination(val offset: Int = 0, val hasMore: Boolean = false)

@Serializable
data class ProjectTicketCollection(
    val tasks: List<Task> = emptyList(),
    val pagination: ProjectTicketPagination = ProjectTicketPagination(),
)

internal fun projectTicketsQuery(projectId: Long, query: ProjectTicketQuery, filters: TaskFilters, offset: Int): String {
    require(projectId > 0 && offset >= 0)
    require(query.scope in setOf("list", "calendar"))
    val parameters = linkedMapOf(
        "scope" to query.scope, "project_id" to "$projectId", "offset" to "$offset",
        "sort" to query.sort, "direction" to query.direction,
        "start_date" to query.from, "end_date" to query.to,
        "key" to query.key, "title" to query.title, "work_item_type_id" to query.workItemTypeId,
        "start_on" to query.startOn, "due_on" to query.dueOn,
        "assignee" to filters.assignee, "label" to filters.label, "priority" to filters.priority,
        "status" to filters.status, "search" to filters.search.trim(),
    )
    if (query.undated) parameters["undated"] = "1"
    return "tasks?" + parameters.filterValues(String::isNotBlank).entries.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, "UTF-8")}"
    }
}
