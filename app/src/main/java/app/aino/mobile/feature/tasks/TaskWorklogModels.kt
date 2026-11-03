package app.aino.mobile.feature.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskWorklog(
    val id: Long,
    @SerialName("task_id") val taskId: Long = 0,
    @SerialName("author_id") val authorId: Long = 0,
    @SerialName("logged_by_id") val loggedById: Long = 0,
    val author: TaskPerson? = null,
    @SerialName("logged_by") val loggedBy: TaskPerson? = null,
    @SerialName("day_worked") val dayWorked: String,
    @SerialName("start_time") val startTime: String? = null,
    val minutes: Int,
    val comment: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
)

@Serializable
data class TaskWorklogList(
    val worklogs: List<TaskWorklog> = emptyList(),
    @SerialName("time_spent_minutes") val timeSpentMinutes: Int = 0,
)

data class WorklogDraft(
    val dayWorked: String = java.time.LocalDate.now().toString(),
    val timeSpent: String = "",
    val startTime: String = "",
    val comment: String = "",
)

@Serializable
data class LogWorkPayload(
    @SerialName("day_worked") val dayWorked: String,
    @SerialName("time_spent") val timeSpent: String,
    @SerialName("start_time") val startTime: String? = null,
    val comment: String? = null,
)

@Serializable
data class WorklogTicketSummary(
    val id: Long,
    @SerialName("time_spent_minutes") val timeSpentMinutes: Int,
    @SerialName("estimated_minutes") val estimatedMinutes: Int? = null,
)

@Serializable
data class WorklogMutationResponse(
    val worklog: TaskWorklog,
    val ticket: WorklogTicketSummary,
)

@Serializable
data class DeleteWorklogResponse(val ticket: WorklogTicketSummary)

internal fun List<TaskWorklog>.newestFirst(): List<TaskWorklog> =
    sortedWith(compareByDescending<TaskWorklog> { it.dayWorked }.thenByDescending { it.createdAt }.thenByDescending { it.id })
