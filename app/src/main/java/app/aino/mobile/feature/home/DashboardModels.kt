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
    val inProgress: Int = 0,
    val inReview: Int = 0,
    val activeTasks: List<ActiveTask> = emptyList(),
)

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

data class DashboardSnapshot(
    val status: TrackerStatus,
    val tasks: TaskSummary?,
    val todayEvents: List<DashboardEvent> = emptyList(),
    val tomorrowEvents: List<DashboardEvent> = emptyList(),
    val announcements: List<DashboardAnnouncement> = emptyList(),
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