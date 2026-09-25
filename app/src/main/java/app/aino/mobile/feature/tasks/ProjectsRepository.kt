package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Projects admin calls (`pages/Projects.tsx`, `api/tasks.ts`, `server/routes/projects.ts`). */
class ProjectsRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = true; encodeDefaults = true },
) {
    /**
     * `getProjects(includeArchived, { limit, offset })` — always sends `paginate=1`
     * but, like the web, accepts the legacy plain array from an older server.
     */
    fun loadProjects(includeArchived: Boolean, limit: Int, offset: Int): ProjectPage {
        val element = json.parseToJsonElement(
            api.execute(ApiRequest(path = "projects?" + projectsQuery(includeArchived, limit, offset))).bodyAsString().ifBlank { "{}" },
        )
        return when (element) {
            is JsonArray -> json.decodeFromJsonElement<List<Project>>(element).let { ProjectPage(it, it.size) }
            is JsonObject -> if (element["projects"] is JsonArray) {
                val envelope = json.decodeFromJsonElement<ProjectsEnvelope>(element)
                ProjectPage(envelope.projects, envelope.pagination?.total ?: envelope.projects.size)
            } else ProjectPage(emptyList(), 0)
            else -> ProjectPage(emptyList(), 0)
        }
    }

    /** `getProject(id)` — not used by the web page itself; kept for detail refreshes. */
    fun loadProject(id: Long): Project = decode(api.execute(ApiRequest(path = "projects/$id")))

    fun loadAssignableUsers(): List<AssignableUser> = decode(api.execute(ApiRequest(path = "tasks/assignable-users")))

    fun createProject(payload: ProjectPayload): Project = mutate("projects", payload, "POST", "Save failed")

    fun updateProject(id: Long, payload: ProjectPayload): Project = mutate("projects/$id", payload, "PUT", "Save failed")

    fun archiveProject(id: Long, archived: Boolean): Project = mutate("projects/$id/archive", ArchivePayload(archived), "PATCH", "Failed")

    /**
     * `deleteProject(id, { force })`. A 409 `PROJECT_NOT_EMPTY` becomes
     * [ProjectNotEmptyException] so the caller can offer the force confirm.
     */
    fun deleteProject(id: Long, force: Boolean = false): DeleteProjectResponse {
        val path = if (force) "projects/$id?force=1" else "projects/$id"
        try {
            // @api DELETE projects/:id
            return decode(api.execute(ApiRequest("DELETE", path)))
        } catch (error: ApiError.Http) {
            val body = runCatching { json.parseToJsonElement(error.responseBody).jsonObject }.getOrNull()
            val message = body?.get("error")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            if (error.statusCode == 409 && body?.get("code")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } == "PROJECT_NOT_EMPTY") {
                val count = body["task_count"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
                throw ProjectNotEmptyException(count, message ?: "PROJECT_NOT_EMPTY")
            }
            throw TaskFailure(message ?: if (force) "Failed to force-delete" else "Failed to delete", error.statusCode, error)
        }
    }

    fun loadProjectTasks(id: Long, limit: Int, offset: Int): ProjectTaskPage {
        // @api GET projects/:id/tasks
        val envelope = decode<ProjectTasksEnvelope>(api.execute(ApiRequest(path = "projects/$id/tasks?limit=$limit&offset=$offset")))
        return ProjectTaskPage(envelope.tasks, envelope.pagination?.total ?: envelope.tasks.size)
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String, fallback: String): R {
        try {
            return decode(api.execute(ApiRequest(method, path, body = json.encodeToString(body).toByteArray())))
        } catch (error: ApiError.Http) {
            throw TaskFailure(serverError(json, error) ?: fallback, error.statusCode, error)
        }
    }

    private inline fun <reified T> decode(response: ApiResponse): T = json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
}

internal fun projectsQuery(includeArchived: Boolean, limit: Int, offset: Int): String =
    (if (includeArchived) "include_archived=1&" else "") + "paginate=1&limit=$limit&offset=$offset"

/** The server's `{ "error": "..." }` text, surfaced verbatim. */
internal fun serverError(json: Json, error: ApiError.Http): String? = runCatching {
    json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
}.getOrNull()
