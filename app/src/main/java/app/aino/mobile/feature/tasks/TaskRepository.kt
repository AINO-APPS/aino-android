package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TaskRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun loadToday(date: String, filters: TaskFilters): TaskListResponse =
        decode(api.execute(ApiRequest(path = taskQuery("tasks", filters, date))))

    fun loadBacklog(filters: TaskFilters, offset: Int = 0, limit: Int = 100): BacklogResponse = decode(
        api.execute(
            ApiRequest(
                path = taskQuery(
                    "tasks/backlog",
                    filters,
                    extra = listOf("limit" to limit.toString(), "offset" to offset.toString()),
                ),
            ),
        ),
    )

    fun loadLabels(): List<TaskLabel> = decode(api.execute(ApiRequest(path = "tasks/labels")))

    fun loadAssignableUsers(): List<AssignableUser> =
        decode(api.execute(ApiRequest(path = "tasks/assignable-users")))

    fun loadDetail(id: Long): TaskDetail = decode(api.execute(ApiRequest(path = "tasks/$id/detail")))

    fun createTask(payload: CreateTaskPayload): Task = mutate("tasks", payload)

    /** Backlog items carry no date; the server rejects one on this route. */
    fun createBacklogTask(payload: CreateTaskPayload): Task =
        mutate("tasks/backlog", payload.copy(date = null))

    fun updateStatus(id: Long, status: String): Task =
        mutate("tasks/$id/status", TaskStatusPayload(status), "PATCH")

    fun deleteTask(id: Long): TaskMessageResponse = mutate("tasks/$id", Unit, "DELETE")

    fun carryForward(): CarryForwardResponse = mutate("tasks/carry-forward", Unit)

    fun addComment(id: Long, content: String): TaskComment =
        mutate("tasks/$id/comments", TaskCommentPayload(content))

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            val bytes = if (body is Unit) null else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw TaskFailure(serverMessage(error) ?: "Task action failed", error.statusCode, error)
        }
    }

    /**
     * The server returns `{ "error": "..." }` for every handled failure. Surface
     * that text verbatim so policy messages (WIP limits, creator-only delete,
     * cross-org assignment) reach the user instead of a generic string.
     */
    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString())
}

class TaskFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)
