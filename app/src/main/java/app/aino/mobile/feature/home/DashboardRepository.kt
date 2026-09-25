package app.aino.mobile.feature.home

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.common.TrackerStatus
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.net.URLEncoder

class DashboardRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private inline fun <reified T> get(path: String): T =
        json.decodeFromString(api.execute(ApiRequest(path = path)).bodyAsString())

    fun loadStatus(): TrackerStatus = get("tracker/status")

    fun loadTaskSummary(): TaskSummary = get("tracker/task-summary")

    // @api GET calendar
    fun loadEvents(from: LocalDate, to: LocalDate): List<DashboardEvent> {
        fun start(day: LocalDate): String = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        return get("calendar?from=${enc(start(from))}&to=${enc(start(to))}")
    }

    // @api GET notifications/announcements
    fun loadAnnouncements(): List<DashboardAnnouncement> =
        get<AnnouncementEnvelope>("notifications/announcements").data

    fun loadActiveSprint(): ActiveSprint? = runCatching<ActiveSprint> { get("sprints/active") }.getOrNull()

    fun loadSprintTasks(sprintId: Long): List<SprintTask> =
        runCatching { get<SprintTasksEnvelope>("sprints/$sprintId/tasks").tasks }.getOrDefault(emptyList())

    fun loadBacklog(): List<SprintTask> =
        runCatching { get<SprintTasksEnvelope>("tasks/backlog?assignee=me").tasks }.getOrDefault(emptyList())

    fun loadApprovals(): List<Approval> =
        runCatching { get<List<Approval>>("manager/approvals?status=pending") }.getOrDefault(emptyList())

    fun approveRequest(id: Long) = runCatching {
        api.execute(ApiRequest(method = "POST", path = "manager/approvals/$id/approve", body = ByteArray(0)))
    }

    fun rejectRequest(id: Long) = runCatching {
        api.execute(ApiRequest(method = "POST", path = "manager/approvals/$id/reject", body = "{}".toByteArray()))
    }

    /**
     * P2.10 — Promise.allSettled semantics: every source fails independently so
     * a partial failure never blanks the page. The status is the only hard
     * requirement (it drives the work timer); everything else degrades to empty.
     */
    fun load(isManager: Boolean, nowEpochMs: Long = System.currentTimeMillis()): DashboardSnapshot {
        val status = loadStatus()
        val tasks = runCatching { loadTaskSummary() }.getOrNull()
        val today = LocalDate.now()
        val todayEvents = runCatching { loadEvents(today, today.plusDays(1)) }.getOrDefault(emptyList())
        val tomorrowEvents = runCatching { loadEvents(today.plusDays(1), today.plusDays(2)) }.getOrDefault(emptyList())
        val announcements = runCatching { loadAnnouncements() }.getOrDefault(emptyList())
        val sprint = loadActiveSprint()
        val sprintTasks = sprint?.let { loadSprintTasks(it.id) } ?: emptyList()
        val backlogTasks = if (sprint == null) loadBacklog() else emptyList()
        val approvals = if (isManager) loadApprovals() else emptyList()
        return DashboardSnapshot(
            status = status,
            tasks = tasks,
            todayEvents = todayEvents,
            tomorrowEvents = tomorrowEvents,
            announcements = announcements,
            sprint = sprint,
            sprintTasks = sprintTasks,
            backlogTasks = backlogTasks,
            approvals = approvals,
            loadedAtEpochMs = nowEpochMs,
        )
    }
}