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
) {
    val loggedByAttribution: String?
        get() = if (loggedById != authorId) "Logged by ${loggedBy?.display() ?: "Unknown person"}" else null
}

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
    val remainingMode: String = "auto",
    val remainingEstimate: String = "",
    val authorId: Long? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class LogWorkPayload(
    @SerialName("day_worked") val dayWorked: String,
    @SerialName("time_spent") val timeSpent: String,
    @SerialName("start_time") val startTime: String? = null,
    val comment: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("author_id") val authorId: Long? = null,
    @SerialName("remaining_mode") val remainingMode: String = "auto",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("remaining_estimate") val remainingEstimate: String? = null,
)

@Serializable
data class WorklogTicketSummary(
    val id: Long,
    @SerialName("time_spent_minutes") val timeSpentMinutes: Int,
    @SerialName("estimated_minutes") val estimatedMinutes: Int? = null,
    @SerialName("original_estimate_minutes") val originalEstimateMinutes: Int? = estimatedMinutes,
    @SerialName("remaining_estimate_minutes") val remainingEstimateMinutes: Int? = null,
)

@Serializable
data class WorklogMutationResponse(
    val worklog: TaskWorklog,
    val ticket: WorklogTicketSummary,
    val warnings: List<String> = emptyList(),
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class RemainingEstimatePayload(
    @SerialName("remaining_mode") val remainingMode: String = "auto",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("remaining_estimate") val remainingEstimate: String? = null,
)

@Serializable
data class DeleteWorklogResponse(val ticket: WorklogTicketSummary)

@Serializable
data class EstimateResponse(val ticket: WorklogTicketSummary)

enum class EstimateKind(val label: String, val field: String) {
    Original("Original estimate", "original_estimate"), Remaining("Remaining estimate", "remaining_estimate"),
}

data class EstimateDraft(val kind: EstimateKind, val value: String)

internal fun List<TaskWorklog>.newestFirst(): List<TaskWorklog> =
    sortedWith(compareByDescending<TaskWorklog> { it.dayWorked }.thenByDescending { it.createdAt }.thenByDescending { it.id })
