package app.aino.mobile.feature.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Task shapes mirror the platform's enriched task row
 * (`server/routes/tasks/_helpers/enrich.ts`): the raw `tasks` column set plus
 * `labels`, `comment_count`, `assignee`, `creator`, `sprint`, `project` and
 * `issue_key`. Every field is optional/defaulted because the tasks surface is
 * still a free-form baseline contract (MIG-0202) and older rows predate the
 * agile and project columns.
 */
@Serializable
data class TaskLabel(val id: Long, val name: String, val color: String = "#6366f1")

@Serializable
data class TaskPerson(
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val avatar: String? = null,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank)
        ?: username?.takeIf(String::isNotBlank)
        ?: "Unassigned"
}

@Serializable
data class TaskProject(val id: Long, val key: String? = null, val name: String? = null, val color: String? = null)

@Serializable
data class TaskSprint(val id: Long, val name: String? = null, val status: String? = null)

@Serializable
data class Task(
    val id: Long,
    val title: String,
    val description: String? = null,
    val priority: String = "medium",
    val status: String = "pending",
    val date: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("assigned_to") val assignedTo: Long? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("story_points") val storyPoints: Int? = null,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("blocked_reason") val blockedReason: String? = null,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("issue_key") val issueKey: String? = null,
    val labels: List<TaskLabel> = emptyList(),
    val assignee: TaskPerson? = null,
    val creator: TaskPerson? = null,
    val project: TaskProject? = null,
    val sprint: TaskSprint? = null,
)

@Serializable
data class TaskStats(
    val total: Int = 0,
    val done: Int = 0,
    val inProgress: Int = 0,
    val percent: Int = 0,
)

@Serializable
data class TaskListResponse(val tasks: List<Task> = emptyList(), val stats: TaskStats = TaskStats())

@Serializable
data class BacklogPagination(
    val limit: Int = 100,
    val offset: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class BacklogSummary(
    val total: Int = 0,
    val byStatus: Map<String, Int> = emptyMap(),
    val byPriority: Map<String, Int> = emptyMap(),
)

@Serializable
data class BacklogResponse(
    val tasks: List<Task> = emptyList(),
    val summary: BacklogSummary = BacklogSummary(),
    val pagination: BacklogPagination = BacklogPagination(),
)

@Serializable
data class TaskComment(
    val id: Long,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("user_id") val userId: Long? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
) {
    fun author(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

/** `GET /tasks/:id/detail` returns the enriched task with `comments` appended. */
@Serializable
data class TaskDetail(
    val id: Long,
    val title: String,
    val description: String? = null,
    val priority: String = "medium",
    val status: String = "pending",
    val date: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("story_points") val storyPoints: Int? = null,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("blocked_reason") val blockedReason: String? = null,
    @SerialName("issue_key") val issueKey: String? = null,
    val labels: List<TaskLabel> = emptyList(),
    val assignee: TaskPerson? = null,
    val creator: TaskPerson? = null,
    val project: TaskProject? = null,
    val sprint: TaskSprint? = null,
    val comments: List<TaskComment> = emptyList(),
)

@Serializable
data class AssignableUser(
    val id: Long,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

@Serializable
data class CreateTaskPayload(
    val title: String,
    val description: String? = null,
    val priority: String = "medium",
    val date: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("assigned_to") val assignedTo: Long? = null,
    @SerialName("label_ids") val labelIds: List<Long> = emptyList(),
)

@Serializable
data class TaskStatusPayload(val status: String)

@Serializable
data class TaskCommentPayload(val content: String)

@Serializable
data class CarryForwardResponse(val message: String = "", val carried: Int = 0)

@Serializable
data class TaskMessageResponse(val message: String = "")
