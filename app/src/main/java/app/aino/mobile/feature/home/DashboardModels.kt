package app.aino.mobile.feature.home

import app.aino.mobile.core.common.TrackerStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ActiveTask(
    val title: String,
    val priority: String? = null,
    val status: String? = null,
)

@Serializable
data class TaskSummary(
    val total: Int = 0,
    val done: Int = 0,
    val pending: Int = 0,
    @SerialName("inProgress") val inProgressRaw: Int? = null,
    @SerialName("in_progress") val inProgressSnake: Int? = null,
    @SerialName("inReview") val inReviewRaw: Int? = null,
    @SerialName("in_review") val inReviewSnake: Int? = null,
    val activeTasks: List<ActiveTask> = emptyList(),
) {
    val inProgress: Int get() = inProgressRaw ?: inProgressSnake ?: 0
    val inReview: Int get() = inReviewRaw ?: inReviewSnake ?: 0
}

@Serializable
data class DashboardEvent(
    val id: Long,
    val title: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("all_day") val allDay: Boolean = false,
    val color: String? = null,
    @SerialName("meeting_code") val meetingCode: String? = null,
)

@Serializable
data class DashboardAnnouncement(
    val id: String,
    val message: String,
    val type: String = "announcement",
)

@Serializable
data class AnnouncementEnvelope(val data: List<DashboardAnnouncement> = emptyList())

@Serializable
data class ActiveSprint(val id: Long, val name: String)

/**
 * `GET /sprints/active` answers with an envelope, not a bare sprint — the
 * envelope is why the home card's sprint mode never rendered (it decoded the
 * body straight into [ActiveSprint] and swallowed the failure).
 */
@Serializable
data class ActiveSprintEnvelope(val sprint: ActiveSprint? = null)

/**
 * The subset of the enriched task row (`server/routes/tasks/_helpers/enrich.ts`)
 * the home card reads.
 *
 * Deliberately not `feature.tasks.Task`: features may not import each other
 * (`scripts/check-module-boundaries.mjs`), and this card needs a fifth of it.
 * There is no `status` field — the workflow state is the only status, so
 * completion is `completed_at` and the label is `workflow_state_name`.
 */
@Serializable
data class DashboardTask(
    val id: Long,
    val title: String,
    val priority: String? = null,
    @SerialName("planned_date") val plannedDate: String? = null,
    @SerialName("assigned_to") val assignedTo: Long? = null,
    @SerialName("workflow_state_key") val workflowStateKey: String? = null,
    @SerialName("workflow_state_name") val workflowStateName: String? = null,
    @SerialName("workflow_state_category") val workflowStateCategory: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
) {
    /** The team's own word for the state; the category is a reporting bucket. */
    val stateLabel: String
        get() = workflowStateName?.takeIf(String::isNotBlank)
            ?: when (workflowStateCategory) {
                "in_progress" -> "In Progress"
                "in_review" -> "In Review"
                "done" -> "Done"
                else -> "Pending"
            }
}

@Serializable
data class DashboardTaskList(val tasks: List<DashboardTask> = emptyList())

@Serializable
data class Approval(
    val id: Long,
    val type: String,
    @SerialName("requester_name") val requesterName: String? = null,
    @SerialName("requester_username") val requesterUsername: String? = null,
)

data class DashboardSnapshot(
    val status: TrackerStatus,
    val tasks: TaskSummary?,
    val todayEvents: List<DashboardEvent> = emptyList(),
    val tomorrowEvents: List<DashboardEvent> = emptyList(),
    val announcements: List<DashboardAnnouncement> = emptyList(),
    /** The home "My Work" card: the running sprint's tickets, or this week's. */
    val myWork: MyWork = MyWork(MyWorkMode.Week, "This Week"),
    val approvals: List<Approval> = emptyList(),
)

/** 7-day window ending on [today] (dashboard date-strip; retained helper). */
fun dashboardWeek(today: java.time.LocalDate): List<java.time.LocalDate> =
    (6 downTo 0).map { today.minusDays(it.toLong()) }

/** `getGreeting()` from Dashboard.tsx: <12 morning, <17 afternoon, else evening. */
fun dashboardGreeting(hour: Int): String =
    if (hour < 12) "Good Morning" else if (hour < 17) "Good Afternoon" else "Good Evening"
