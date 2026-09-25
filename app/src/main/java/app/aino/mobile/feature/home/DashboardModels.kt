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
data class ActiveSprint(
    val id: Long,
    val name: String,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
)

@Serializable
data class SprintTask(
    val id: Long,
    val title: String,
    val status: String = "todo",
    val priority: String? = null,
)

@Serializable
data class SprintTasksEnvelope(val tasks: List<SprintTask> = emptyList())

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
    val sprint: ActiveSprint? = null,
    val sprintTasks: List<SprintTask> = emptyList(),
    val backlogTasks: List<SprintTask> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val loadedAtEpochMs: Long,
)

fun liveDurations(status: TrackerStatus, loadedAtEpochMs: Long, nowEpochMs: Long): Pair<Long, Long> {
    var floorSeconds = status.floorMinutes.coerceAtLeast(0) * 60L
    var breakSeconds = status.breakMinutes.coerceAtLeast(0) * 60L
    val elapsed = ((nowEpochMs - loadedAtEpochMs).coerceAtLeast(0)) / 1_000
    if (status.state == "on_floor") floorSeconds += elapsed
    if (status.state == "on_break") breakSeconds += elapsed
    return floorSeconds to breakSeconds
}
/** 7-day window ending on [today] (dashboard date-strip; retained helper). */
fun dashboardWeek(today: java.time.LocalDate): List<java.time.LocalDate> =
    (6 downTo 0).map { today.minusDays(it.toLong()) }

/** `getGreeting()` from Dashboard.tsx: <12 morning, <17 afternoon, else evening. */
fun dashboardGreeting(hour: Int): String =
    if (hour < 12) "Good Morning" else if (hour < 17) "Good Afternoon" else "Good Evening"
