package app.aino.mobile.feature.home

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.json.Json

class DashboardRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(nowEpochMs: Long = System.currentTimeMillis()): DashboardSnapshot {
        val status = json.decodeFromString<DashboardStatus>(
            api.execute(ApiRequest(path = "tracker/status")).bodyAsString(),
        )
        val tasks = runCatching {
            json.decodeFromString<TaskSummary>(
                api.execute(ApiRequest(path = "tracker/task-summary")).bodyAsString(),
            )
        }.getOrNull()
        return DashboardSnapshot(status, tasks, nowEpochMs)
    }
}