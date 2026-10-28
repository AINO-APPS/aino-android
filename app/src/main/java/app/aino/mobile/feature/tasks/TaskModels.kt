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
    /** Date-only planning metadata. Never affects backlog membership (D2). */
    @SerialName("planned_date") val plannedDate: String? = null,
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
    @SerialName("team_id") val teamId: Long? = null,
    /** LexoRank string, unique per team. Server-computed from before/after ids. */
    val rank: String? = null,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("parent_task_id") val parentTaskId: Long? = null,
    @SerialName("work_item_type_id") val workItemTypeId: Long? = null,
    /** The workflow state is the only status (D4). */
    @SerialName("workflow_state_id") val workflowStateId: Long? = null,
    @SerialName("workflow_state_key") val workflowStateKey: String? = null,
    @SerialName("workflow_state_category") val workflowStateCategory: String? = null,
    /** Planned effort in minutes, shown against [loggedMinutes]. */
    @SerialName("estimated_minutes") val estimatedMinutes: Int? = null,
    /** Minutes logged by timers started from this task; rolls into the timesheet. */
    @SerialName("logged_minutes") val loggedMinutes: Int = 0,
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

    /**
     * Backlog membership: not in a sprint.
     *
     * A planned date is metadata and never removes a task from the backlog (D2),
     * so this matches the server's `t.sprint_id IS NULL` rule.
     */
    val isBacklogItem: Boolean get() = sprintId == null

    /** True when the task has a planner day, which is what "Move to Backlog" clears. */
    val isScheduled: Boolean get() = plannedDate != null

    /** The single definition of complete: the server derives `completed_at` (D5). */
    val isCompleted: Boolean get() = completedAt != null

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
    /** Completed work is excluded by default; this is how much is hidden. */
    @SerialName("completed_count") val completedCount: Int? = null,
    @SerialName("incomplete_total") val incompleteTotal: Int? = null,
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
    /** The sprint's running number within its team; the heading shows "#3". */
    @SerialName("sprint_number") val sprintNumber: Int? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class)
    @SerialName("velocity_points") val velocityPoints: Double? = null,
) {
    /**
     * The sprint's number, from `sprint_number` or the trailing digits of its
     * name ("Sprint #3" → 3). The heading already says "Sprint", so repeating
     * the stored name would hide the number.
     */
    val number: Int?
        get() = sprintNumber ?: Regex("(\\d+)\\s*$").find(name)?.groupValues?.get(1)?.toIntOrNull()
}

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
    /** Allowed `from -> to` state pairs. Absent/empty means "not restricted". */
    val transitions: List<WorkflowTransition> = emptyList(),
)

/** One edge of the tenant's workflow graph (`GET /agile/config`). */
@Serializable
data class WorkflowTransition(
    @SerialName("from_state_id") val fromStateId: Long = 0,
    @SerialName("to_state_id") val toStateId: Long = 0,
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
    /** Allowed transitions. Empty means the workflow is unrestricted. */
    val transitions: List<WorkflowTransition> = emptyList(),
) {
    fun type(id: Long?): WorkItemType? = id?.let { wanted -> workItemTypes.firstOrNull { it.id == wanted && it.id != 0L } }

    /**
     * May this ticket move to [toStateId]?
     *
     * Falls back to "allowed" when the tenant has no transition rows, so a
     * tenant on the default workflow is never blocked by a missing config, and
     * an older server that omits the field degrades to today's behaviour.
     */
    fun canTransition(fromStateId: Long?, toStateId: Long?): Boolean {
        if (transitions.isEmpty()) return true
        if (fromStateId == null || toStateId == null) return true
        if (fromStateId == toStateId) return true
        return transitions.any { it.fromStateId == fromStateId && it.toStateId == toStateId }
    }

    /** The states this ticket may be moved to, in configured order.
         *
         * States that carry no id (the `id: 0` fallback set, used before
         * `/agile/config` resolves) cannot be matched against the graph at all, so
         * they are always offered rather than silently dropped. */
        fun legalTargets(fromStateId: Long?): List<WorkflowState> =
            workflowStates.filter { it.id == 0L || canTransition(fromStateId, it.id) }

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
                transitions = response.transitions,
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
    WorkflowState(0, "pending", "New", "open", "#6b7280", isInitial = true, sortOrder = 1),
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
    /** Created straight into a board column starts in that workflow state. */
    @SerialName("workflow_state_id") val workflowStateId: Long? = null,
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

/** `PUT /sprints/:id` — the only field the planner edits inline. */
@Serializable
data class SprintGoalPayload(val goal: String)

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

// ─── Next-generation payloads and responses ─────────────────────────────────

/** `PATCH /tasks/:id/rank` — the server computes the LexoRank from the neighbours. */
@Serializable
data class RankPayload(
    @SerialName("beforeId") val beforeId: Long? = null,
    @SerialName("afterId") val afterId: Long? = null,
)

/** `POST /tasks/bulk` — one request for a multi-select action. */
@Serializable
data class BulkPayload(
    val ids: List<Long>,
    val action: String,
    val value: String? = null,
)

/** One ticket's outcome inside a bulk response. `code` is present for the
 *  failure kinds the server can name (`INVALID_TRANSITION`, `WIP_EXCEEDED`,
 *  `SPRINT_TEAM_MISMATCH`); `previous` carries the values needed to undo. */
@Serializable
data class BulkItemResult(
    val id: Long,
    val ok: Boolean = false,
    val error: String? = null,
    val code: String? = null,
)

/** `POST /tasks/bulk` response — counts plus the per-ticket results. */
@Serializable
data class BulkResult(
    val action: String = "",
    val requested: Int = 0,
    val changed: Int = 0,
    val failed: Int = 0,
    val results: List<BulkItemResult> = emptyList(),
) {
    /** The failures a user needs to read, in the server's own words. */
    val failures: List<BulkItemResult> get() = results.filterNot { it.ok }
}

/** `GET /tasks/epics` — an epic with its rollup.
 *
 *  The server spreads the epic row and nests the counts under `rollup` with
 *  camelCase keys (`routes/tasks/epics.ts`), so the rollup is a second object
 *  rather than flat snake_case fields on the epic. */
@Serializable
data class EpicSummary(
    val id: Long,
    val title: String = "",
    @SerialName("issue_key") val issueKey: String? = null,
    @SerialName("story_points") val storyPoints: Double? = null,
    val rollup: EpicRollup = EpicRollup(),
) {
    val totalChildren: Int get() = rollup.totalChildren
    val doneChildren: Int get() = rollup.doneChildren
    val totalPoints: Double get() = rollup.totalPoints
    val donePoints: Double get() = rollup.donePoints
    val percentByPoints: Int get() = rollup.percentByPoints
}

/** The rollup block the epics endpoint computes per epic. */
@Serializable
data class EpicRollup(
    val totalChildren: Int = 0,
    val doneChildren: Int = 0,
    val totalPoints: Double = 0.0,
    val donePoints: Double = 0.0,
    val percentByPoints: Int = 0,
    val percentByCount: Int = 0,
)

/** `GET /tasks/epics` — the response envelope. */
@Serializable
data class EpicListResponse(
    val epics: List<EpicSummary> = emptyList(),
)

/** `GET /tasks/schedule-overlays` — read-only background context.
 *
 *  The three collections carry different row shapes, so they are kept apart
 *  rather than flattened into one DTO. */
@Serializable
data class ScheduleOverlays(
    val leave: List<ScheduleOverlay> = emptyList(),
    val holidays: List<ScheduleOverlay> = emptyList(),
    val meetings: List<ScheduleOverlay> = emptyList(),
) {
    val isEmpty: Boolean get() = leave.isEmpty() && holidays.isEmpty() && meetings.isEmpty()

    /** Every row with its `kind` set, for surfaces that treat them uniformly. */
    val all: List<ScheduleOverlay>
        get() = leave.map { it.copy(kind = "leave") } +
            holidays.map { it.copy(kind = "holiday") } +
            meetings.map { it.copy(kind = "meeting") }

    /** Rows of one kind, already carrying that `kind`. */
    fun of(type: String): List<ScheduleOverlay> = when (type) {
        "leave" -> leave.map { it.copy(kind = "leave") }
        "holiday" -> holidays.map { it.copy(kind = "holiday") }
        "meeting" -> meetings.map { it.copy(kind = "meeting") }
        else -> emptyList()
    }
}

/** One overlay row. Only `kind` is synthesised client-side; which of the
 *  optional fields are present depends on the kind:
 *  leave carries `date`/`leave_type`/`duration`/`user_name`,
 *  holidays carry `date`/`name` (mapped to `label`),
 *  meetings carry `title`/`start_time`/`end_time`. */
@Serializable
data class ScheduleOverlay(
    val id: Long? = null,
    val kind: String = "",
    val date: String? = null,
    val label: String = "",
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("leave_type") val leaveType: String? = null,
    val duration: Double? = null,
    val name: String? = null,
    val title: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
) {
    /** The human label, whichever field the row happens to carry. */
    val displayLabel: String
        get() = label.ifBlank { name ?: title ?: userName ?: kind.replaceFirstChar { it.uppercase() } }

    /** The day this overlay belongs to, whether it is a date or a timestamp. */
    val day: String? get() = date ?: startTime?.take(10)
}

/** `GET /sprints/:id/completion-preview` — the carryover pre-flight. */
@Serializable
data class CompletionPreview(
    val sprint: CompletionPreviewSprint? = null,
    val defaultTarget: String? = null,
    val moving: Int = 0,
    val staying: Int = 0,
    val movingPoints: Double = 0.0,
) {
    val doneTasks: Int get() = staying
    val totalTasks: Int get() = moving + staying
    val incompleteTasks: Int get() = moving
}

@Serializable
data class CompletionPreviewSprint(
    val id: Long = 0,
    val name: String = "",
    val status: String = "",
)

/** `GET /sprints/:id/baseline` — committed points and the scope delta. */
@Serializable
data class BaselineResponse(
    val baseline: SprintBaseline? = null,
    val delta: ScopeDelta = ScopeDelta(),
) {
    val committedPoints: Double get() = baseline?.committedPoints ?: 0.0
    val addedPoints: Double get() = delta.addedPoints
    val removedPoints: Double get() = delta.removedPoints
}

/** The scope change since the baseline was captured. */
@Serializable
data class ScopeDelta(
    val addedPoints: Double = 0.0,
    val removedPoints: Double = 0.0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
)

/** The stored baseline snapshot. */
@Serializable
data class SprintBaseline(
    @SerialName("committed_points") val committedPoints: Double = 0.0,
    @SerialName("committed_task_ids") val committedTaskIds: List<Long> = emptyList(),
    @SerialName("captured_at") val capturedAt: String? = null,
)

/** `GET /user/preferences` — the per-user Tasks preferences shared with web. */
@Serializable
data class TaskPreferences(
    @SerialName("show_completed") val showCompleted: Boolean = false,
    @SerialName("card_density") val cardDensity: String = "compact",
    @SerialName("default_team_id") val defaultTeamId: Long? = null,
) {
    val isCompact: Boolean get() = cardDensity == "compact"
}

/** `GET /tasks/:id/timer` and `POST /tasks/:id/timer/start`. */
@Serializable
data class TaskTimerState(
    @SerialName("taskId") val taskId: Long = 0,
    val running: Boolean = false,
    @SerialName("startedAt") val startedAt: String? = null,
    val time: TaskTimeSummary = TaskTimeSummary(),
)

/** `POST /tasks/:id/timer/stop`. */
@Serializable
data class TaskTimerStop(
    @SerialName("taskId") val taskId: Long = 0,
    val running: Boolean = false,
    val minutes: Int = 0,
    val time: TaskTimeSummary = TaskTimeSummary(),
)

/** Logged against estimated, with the overrun made explicit. */
@Serializable
data class TaskTimeSummary(
    val estimated: Int = 0,
    val logged: Int = 0,
    val remaining: Int = 0,
    val percent: Int = 0,
    val over: Boolean = false,
)

/** `GET /tracker/task-time` — the timesheet's per-task view. */
@Serializable
data class TaskTimeDay(
    val date: String,
    val minutes: Int = 0,
    val entries: List<TaskTimeEntry> = emptyList(),
)

@Serializable
data class TaskTimeEntry(
    @SerialName("task_id") val taskId: Long,
    val title: String,
    @SerialName("issue_key") val issueKey: String? = null,
    val date: String,
    val minutes: Int = 0,
)

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
