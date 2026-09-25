package app.aino.mobile.feature.tasks

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.LenientDoubleSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Task shapes mirror the platform's enriched task row
 * (`server/routes/tasks/_helpers/enrich.ts`): the raw `tasks` column set plus
 * `labels`, `comment_count`, `assignee`, `creator`, `sprint`, `project` and
 * `issue_key`. Every field is optional/defaulted because older rows predate
 * the agile and project columns. `story_points` is NUMERIC (a quoted string).
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
data class TaskSprint(
    val id: Long,
    val name: String? = null,
    val status: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
)

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
    @Serializable(with = LenientDoubleNullableSerializer::class)
    @SerialName("story_points") val storyPoints: Double? = null,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("blocked_reason") val blockedReason: String? = null,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("issue_key") val issueKey: String? = null,
    @SerialName("sprint_id") val sprintId: Long? = null,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("parent_task_id") val parentTaskId: Long? = null,
    @SerialName("work_item_type_id") val workItemTypeId: Long? = null,
    @SerialName("workflow_state_id") val workflowStateId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("acceptance_criteria") val acceptanceCriteriaRaw: JsonElement? = null,
    val labels: List<TaskLabel> = emptyList(),
    val assignee: TaskPerson? = null,
    val creator: TaskPerson? = null,
    val project: TaskProject? = null,
    val sprint: TaskSprint? = null,
    /** Only present on `GET /tasks/:id/detail`. */
    val comments: List<TaskComment> = emptyList(),
) {
    /** `issue_key || "#id"` (TaskCard / BacklogTab). */
    val displayKey: String get() = issueKey ?: "#$id"

    /** Backlog = neither a planner date nor a sprint (TaskDetailModal `isBacklogItem`). */
    val isBacklogItem: Boolean get() = date == null && sprintId == null

    val acceptanceCriteria: List<AcceptanceCriterion> get() = parseCriteria(acceptanceCriteriaRaw)
}

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
    val summary: BacklogSummary? = null,
    val pagination: BacklogPagination? = null,
)

@Serializable
data class TaskComment(
    val id: Long,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("user_id") val userId: Long? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val avatar: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_type") val fileType: String? = null,
) {
    fun author(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

@Serializable
data class TaskHistoryEntry(
    val id: Long,
    val action: String = "",
    val field: String? = null,
    @SerialName("old_value") val oldValue: String? = null,
    @SerialName("new_value") val newValue: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
)

data class AcceptanceCriterion(val id: String?, val text: String, val done: Boolean)

@Serializable
data class AssignableUser(
    val id: Long,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val avatar: String? = null,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

/** `GET /tasks/available-sprints` row (org admins also get `team_id` / `team_name`). */
@Serializable
data class AvailableSprint(
    val id: Long,
    val name: String = "",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    val status: String = "planned",
    val goal: String? = null,
    @SerialName("team_id") val teamId: Long? = null,
    @SerialName("team_name") val teamName: String? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class)
    @SerialName("velocity_points") val velocityPoints: Double? = null,
)

@Serializable
data class SprintListResponse(val sprints: List<AvailableSprint> = emptyList())

@Serializable
data class SprintEnvelope(val sprint: AvailableSprint? = null)

@Serializable
data class SprintTotals(
    @Serializable(with = LenientIntSerializer::class) val tasks: Int = 0,
    @Serializable(with = LenientDoubleSerializer::class) val points: Double = 0.0,
    @Serializable(with = LenientIntSerializer::class) val doneTasks: Int = 0,
    @Serializable(with = LenientDoubleSerializer::class) val donePoints: Double = 0.0,
    @Serializable(with = LenientDoubleSerializer::class) val remainingPoints: Double = 0.0,
    @Serializable(with = LenientIntSerializer::class) val unestimatedTasks: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val blockedTasks: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val percentByPoints: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val percentByTasks: Int = 0,
)

@Serializable
data class SprintStats(val totals: SprintTotals = SprintTotals())

/** Lightweight `GET /projects` row for pickers (full CRUD lives in the Projects page). */
@Serializable
data class ProjectOption(
    val id: Long,
    val key: String = "",
    val name: String = "",
    val color: String? = null,
)

// ─── Agile config (`GET /agile/config`, AgileConfigContext) ─────────────────

@Serializable
data class PriorityScheme(val key: String, val label: String, val color: String)

@Serializable
data class AgileSettings(
    @SerialName("estimation_type") val estimationType: String? = null,
    @SerialName("estimation_values") val estimationValues: List<JsonPrimitive>? = null,
    @SerialName("estimation_unit_label") val estimationUnitLabel: String? = null,
    @SerialName("priority_scheme") val priorityScheme: List<PriorityScheme>? = null,
    @SerialName("enable_story_points") val enableStoryPoints: Boolean? = null,
    @SerialName("enable_epics") val enableEpics: Boolean? = null,
    @SerialName("enable_dependencies") val enableDependencies: Boolean? = null,
    @SerialName("enable_acceptance_criteria") val enableAcceptanceCriteria: Boolean? = null,
    @SerialName("enable_blockers") val enableBlockers: Boolean? = null,
    @SerialName("enable_wip_limits") val enableWipLimits: Boolean? = null,
)

@Serializable
data class WorkItemType(
    val id: Long = 0,
    val key: String = "",
    val name: String = "",
    val color: String = "#6366f1",
    val icon: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_epic") val isEpic: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class WorkflowState(
    val id: Long = 0,
    val key: String = "",
    val name: String = "",
    val category: String? = null,
    val color: String = "#6b7280",
    @SerialName("wip_limit") val wipLimit: Int? = null,
    @SerialName("is_initial") val isInitial: Boolean = false,
    @SerialName("is_terminal") val isTerminal: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class AgileConfigResponse(
    val settings: AgileSettings? = null,
    val workItemTypes: List<WorkItemType> = emptyList(),
    val workflowStates: List<WorkflowState> = emptyList(),
    val canEdit: Boolean = false,
)

data class AgileFeatures(
    val storyPoints: Boolean = true,
    val epics: Boolean = true,
    val dependencies: Boolean = true,
    val acceptanceCriteria: Boolean = true,
    val blockers: Boolean = true,
    val wipLimits: Boolean = false,
)

/** Merged config: the server's rows over `FALLBACK_CONFIG` (AgileConfigContext `refresh`). */
data class AgileConfig(
    val workItemTypes: List<WorkItemType> = FALLBACK_WORK_ITEM_TYPES,
    val workflowStates: List<WorkflowState> = FALLBACK_WORKFLOW_STATES,
    val pointScale: List<String> = listOf("0.5", "1", "2", "3", "5", "8", "13", "21", "34"),
    val unitLabel: String = "SP",
    val features: AgileFeatures = AgileFeatures(),
    val canEdit: Boolean = false,
) {
    fun type(id: Long?): WorkItemType? = id?.let { wanted -> workItemTypes.firstOrNull { it.id == wanted && it.id != 0L } }

    companion object {
        fun from(response: AgileConfigResponse): AgileConfig {
            val s = response.settings ?: AgileSettings()
            val fallback = AgileConfig()
            return AgileConfig(
                workItemTypes = response.workItemTypes.ifEmpty { FALLBACK_WORK_ITEM_TYPES },
                workflowStates = response.workflowStates.ifEmpty { FALLBACK_WORKFLOW_STATES },
                pointScale = s.estimationValues?.mapNotNull { it.contentOrNull } ?: fallback.pointScale,
                unitLabel = s.estimationUnitLabel ?: "SP",
                features = AgileFeatures(
                    storyPoints = s.enableStoryPoints ?: true,
                    epics = s.enableEpics ?: true,
                    dependencies = s.enableDependencies ?: true,
                    acceptanceCriteria = s.enableAcceptanceCriteria ?: true,
                    blockers = s.enableBlockers ?: true,
                    wipLimits = s.enableWipLimits ?: false,
                ),
                canEdit = response.canEdit,
            )
        }
    }
}

val FALLBACK_WORK_ITEM_TYPES = listOf(
    WorkItemType(0, "story", "Story", "#10b981", "BookOpen", isDefault = true, sortOrder = 1),
    WorkItemType(0, "bug", "Bug", "#ef4444", "Bug", sortOrder = 2),
    WorkItemType(0, "task", "Task", "#6366f1", "Circle", sortOrder = 3),
    WorkItemType(0, "epic", "Epic", "#8b5cf6", "Target", isEpic = true, sortOrder = 4),
)

val FALLBACK_WORKFLOW_STATES = listOf(
    WorkflowState(0, "pending", "To Do", "open", "#6b7280", isInitial = true, sortOrder = 1),
    WorkflowState(0, "in_progress", "In Progress", "in_progress", "#f59e0b", sortOrder = 2),
    WorkflowState(0, "in_review", "In Review", "in_review", "#3b82f6", sortOrder = 3),
    WorkflowState(0, "done", "Done", "done", "#10b981", isTerminal = true, sortOrder = 4),
)

// ─── Payloads ───────────────────────────────────────────────────────────────

/** `POST /tasks/backlog` (useBacklog `handleAddBacklog`). */
@Serializable
data class CreateBacklogPayload(
    val title: String,
    val description: String = "",
    val priority: String = "medium",
    @SerialName("assigned_to") val assignedTo: Long? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("label_ids") val labelIds: List<Long>? = null,
    @SerialName("sprint_id") val sprintId: Long? = null,
    @SerialName("story_points") val storyPoints: String? = null,
    @SerialName("work_item_type_id") val workItemTypeId: Long? = null,
    @SerialName("project_id") val projectId: Long? = null,
)

/** `PUT /tasks/:id` from the detail editor (useTaskDetail `saveDetailEdit`). */
@Serializable
data class UpdateTaskPayload(
    val title: String,
    val description: String,
    val priority: String,
    @SerialName("assigned_to") val assignedTo: Long? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("sprint_id") val sprintId: Long? = null,
    @SerialName("label_ids") val labelIds: List<Long> = emptyList(),
    @SerialName("story_points") val storyPoints: String? = null,
    @SerialName("work_item_type_id") val workItemTypeId: Long? = null,
    @SerialName("project_id") val projectId: Long? = null,
)

/** `PUT /tasks/:id` from the sprint import panel (only assignee + due date). */
@Serializable
data class ImportUpdatePayload(
    @SerialName("assigned_to") val assignedTo: Long? = null,
    @SerialName("due_date") val dueDate: String? = null,
)

@Serializable
data class TaskStatusPayload(val status: String)

@Serializable
data class TaskCommentPayload(val content: String)

@Serializable
data class SchedulePayload(val date: String)

@Serializable
data class AssignSprintPayload(@SerialName("sprint_id") val sprintId: Long?)

@Serializable
data class CompleteSprintPayload(val rolloverTo: String)

@Serializable
data class BlockPayload(@SerialName("is_blocked") val isBlocked: Boolean, @SerialName("blocked_reason") val blockedReason: String?)

@Serializable
data class CarryForwardResponse(val message: String = "", val carried: Int = 0)

@Serializable
data class TaskMessageResponse(val message: String = "")

/** `acceptance_criteria` is JSONB `[{ id, text, done, doneAt, doneBy }]`; tolerate anything else. */
internal fun parseCriteria(raw: JsonElement?): List<AcceptanceCriterion> {
    val array = raw as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        AcceptanceCriterion(
            id = (obj["id"] as? JsonPrimitive)?.contentOrNull,
            text = text,
            done = (obj["done"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}
