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
    fun load(nowEpochMs: Long = System.currentTimeMillis()): DashboardSnapshot {
        val status = json.decodeFromString<TrackerStatus>(
            api.execute(ApiRequest(path = "tracker/status")).bodyAsString(),
        )
        val tasks = runCatching {
            json.decodeFromString<TaskSummary>(
                api.execute(ApiRequest(path = "tracker/task-summary")).bodyAsString(),
            )
        }.getOrNull()
        val today = LocalDate.now()
        fun start(day: LocalDate): String = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        fun events(from: LocalDate, to: LocalDate): List<DashboardEvent> = runCatching {
            json.decodeFromString<List<DashboardEvent>>(
                api.execute(
                    ApiRequest(path = "calendar?from=${URLEncoder.encode(start(from), "UTF-8")}&to=${URLEncoder.encode(start(to), "UTF-8")}"),
                ).bodyAsString(),
            )
        }.getOrDefault(emptyList())
        val announcements = runCatching {
            json.decodeFromString<AnnouncementEnvelope>(
                api.execute(ApiRequest(path = "notifications/announcements")).bodyAsString(),
            ).data
        }.getOrDefault(emptyList())
        return DashboardSnapshot(
            status = status,
            tasks = tasks,
            todayEvents = events(today, today.plusDays(1)),
            tomorrowEvents = events(today.plusDays(1), today.plusDays(2)),
            announcements = announcements,
            loadedAtEpochMs = nowEpochMs,
        )
    }
}