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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Tasks, sprints and agile config calls used by the Tasks page (`api/tasks.ts`). */
class TaskRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true },
) {
    // ── Lists ────────────────────────────────────────────────────────────
    // @api GET tasks
    // @api GET tasks/backlog
    // @api GET tasks/search
    fun loadSprintTasks(sprintId: Long, filters: TaskFilters): TaskListResponse =
        decode(api.execute(ApiRequest(path = sprintTasksQuery(sprintId, filters))))

    fun loadBacklog(filters: TaskFilters, limit: Int, offset: Int): BacklogResponse =
        decode(api.execute(ApiRequest(path = backlogQuery(filters, limit, offset))))

    fun search(q: String): List<Task> = decode(api.execute(ApiRequest(path = searchQuery(q))))

    // ── Metadata ─────────────────────────────────────────────────────────
    fun loadLabels(): List<TaskLabel> = decode(api.execute(ApiRequest(path = "tasks/labels")))

    fun loadAssignableUsers(): List<AssignableUser> =
        decode(api.execute(ApiRequest(path = "tasks/assignable-users")))

    /** Active projects only (`getProjects(false)`); the legacy array form. */
    fun loadProjects(): List<ProjectOption> {
        val element = json.parseToJsonElement(api.execute(ApiRequest(path = "projects")).bodyAsString())
        return if (element is JsonArray) json.decodeFromJsonElement<List<ProjectOption>>(element) else emptyList()
    }

    fun loadAvailableSprints(): List<AvailableSprint> =
        decode(api.execute(ApiRequest(path = "tasks/available-sprints")))

    fun loadAgileConfig(): AgileConfig = AgileConfig.from(decode(api.execute(ApiRequest(path = "agile/config"))))

    fun loadSprintStats(sprintId: Long): SprintStats = decode(api.execute(ApiRequest(path = "sprints/$sprintId/stats")))

    fun loadTeamSprints(): List<AvailableSprint> =
        decode<SprintListResponse>(api.execute(ApiRequest(path = "sprints"))).sprints

    // ── Sprint lifecycle ─────────────────────────────────────────────────
    fun startSprint(id: Long): SprintEnvelope = mutate("sprints/$id/start", Unit)

    fun completeSprint(id: Long, rolloverTo: String): SprintEnvelope =
        mutate("sprints/$id/complete", CompleteSprintPayload(rolloverTo))

    // ── Sprint Insights ──────────────────────────────────────────────────
    fun loadSprintTaskRows(id: Long): List<Task> = decode<SprintTasksResponse>(api.execute(ApiRequest(path = "sprints/$id/tasks"))).tasks

    fun loadBurndown(id: Long): BurndownResponse = decode(api.execute(ApiRequest(path = "sprints/$id/burndown")))

    // @api GET sprints/velocity/recent
    fun loadVelocity(limit: Int): VelocityResponse = decode(api.execute(ApiRequest(path = "sprints/velocity/recent?limit=$limit")))

    fun loadCumulativeFlow(id: Long): CfdResponse = decode(api.execute(ApiRequest(path = "sprints/$id/cumulative-flow")))

    fun loadCycleTime(id: Long): CycleResponse = decode(api.execute(ApiRequest(path = "sprints/$id/cycle-time")))

    fun loadRetrospective(id: Long): Retrospective? =
        decode<RetrospectiveResponse>(api.execute(ApiRequest(path = "sprints/$id/retrospective"))).retrospective

    fun saveRetrospective(id: Long, payload: RetrospectivePayload): Retrospective? =
        mutate<RetrospectivePayload, RetrospectiveResponse>("sprints/$id/retrospective", payload, "PUT").retrospective

    // ── Task writes ──────────────────────────────────────────────────────
    fun createBacklogTask(payload: CreateBacklogPayload): Task = mutate("tasks/backlog", payload)

    fun updateTask(id: Long, payload: UpdateTaskPayload): Task = mutate("tasks/$id", payload, "PUT")

    fun updateImportFields(id: Long, payload: ImportUpdatePayload): Task = mutate("tasks/$id", payload, "PUT")

    fun updateStatus(id: Long, status: String): Task =
        mutate("tasks/$id/status", TaskStatusPayload(status), "PATCH")

    fun scheduleTask(id: Long, date: String): Task = mutate("tasks/$id/schedule", SchedulePayload(date), "PATCH")

    fun unscheduleTask(id: Long): Task = mutate("tasks/$id/unschedule", Unit, "PATCH")

    fun assignSprint(id: Long, sprintId: Long?): Task =
        mutate("tasks/$id/assign-sprint", AssignSprintPayload(sprintId), "PATCH")

    fun deleteTask(id: Long): TaskMessageResponse = mutate("tasks/$id", Unit, "DELETE")

    fun carryForward(): CarryForwardResponse = mutate("tasks/carry-forward", Unit)

    // ── Detail ───────────────────────────────────────────────────────────
    fun loadDetail(id: Long): Task = decode(api.execute(ApiRequest(path = "tasks/$id/detail")))

    fun loadHistory(id: Long): List<TaskHistoryEntry> = decode(api.execute(ApiRequest(path = "tasks/$id/history")))

    fun loadComments(id: Long): List<TaskComment> = decode(api.execute(ApiRequest(path = "tasks/$id/comments")))

    fun addComment(id: Long, content: String): TaskComment =
        mutate("tasks/$id/comments", TaskCommentPayload(content))

    /** Multipart variant with a `file` part (`commentUpload.single('file')`). */
    fun addCommentWithFile(id: Long, content: String, fileName: String, mimeType: String, bytes: ByteArray): TaskComment {
        val boundary = "----aino" + System.nanoTime()
        val (type, body) = buildCommentMultipart(content, fileName, mimeType, bytes, boundary)
        try {
            // @api POST tasks/:id/comments
            return decode(api.execute(ApiRequest("POST", "tasks/$id/comments", headers = mapOf("Content-Type" to type), body = body)))
        } catch (error: ApiError.Http) {
            throw TaskFailure(serverMessage(error) ?: "Failed to add comment", error.statusCode, error)
        }
    }

    fun updateComment(taskId: Long, commentId: Long, content: String): TaskComment =
        mutate("tasks/$taskId/comments/$commentId", TaskCommentPayload(content), "PUT")

    fun deleteComment(taskId: Long, commentId: Long): TaskMessageResponse =
        mutate("tasks/$taskId/comments/$commentId", Unit, "DELETE")

    fun setBlocker(id: Long, blocked: Boolean, reason: String?): BlockResponse =
        mutate("tasks/$id/block", BlockPayload(blocked, reason), "PATCH")

    fun loadCriteria(id: Long): List<AcceptanceCriterion> =
        parseCriteria(JsonArray(decode<CriteriaResponse>(api.execute(ApiRequest(path = "tasks/$id/acceptance-criteria"))).criteria))

    fun saveCriteria(id: Long, criteria: List<CriterionPayload>): List<AcceptanceCriterion> =
        parseCriteria(JsonArray(mutate<CriteriaPayload, CriteriaResponse>("tasks/$id/acceptance-criteria", CriteriaPayload(criteria), "PUT").criteria))

    fun loadDependencies(id: Long): DependenciesResponse =
        decode(api.execute(ApiRequest(path = "tasks/$id/dependencies")))

    fun addDependency(id: Long, otherId: Long, type: String): JsonObject =
        mutate("tasks/$id/dependencies", DependencyPayload(otherId, type))

    fun removeDependency(id: Long, linkId: Long): TaskMessageResponse =
        mutate("tasks/$id/dependencies/$linkId", Unit, "DELETE")

    // @api GET tasks/lookup/quicksearch
    fun quickSearch(q: String): List<QuickTask> =
        decode<QuickSearchResponse>(api.execute(ApiRequest(path = "tasks/lookup/quicksearch?q=" + java.net.URLEncoder.encode(q, "UTF-8")))).tasks

    fun loadChildren(id: Long): ChildrenResponse = decode(api.execute(ApiRequest(path = "tasks/$id/children")))

    fun loadParent(id: Long): ParentTask? = decode<ParentResponse>(api.execute(ApiRequest(path = "tasks/$id/parent"))).parent

    fun setParent(id: Long, parentId: Long?): JsonObject = mutate("tasks/$id/parent", ParentPayload(parentId), "PATCH")

    fun loadCustomFields(): List<CustomFieldDef> = decode(api.execute(ApiRequest(path = "custom-fields")))

    fun loadCustomFieldValues(taskId: Long): JsonObject =
        decode<CustomFieldValuesResponse>(api.execute(ApiRequest(path = "custom-fields/task/$taskId"))).values

    fun saveCustomFieldValues(taskId: Long, values: JsonObject): JsonObject =
        mutate<CustomFieldValuesPayload, CustomFieldValuesResponse>("custom-fields/task/$taskId", CustomFieldValuesPayload(values), "PUT").values

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
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
}

class TaskFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)

/** `content` text part + optional `file` part, as the web `FormData`. */
internal fun buildCommentMultipart(
    content: String,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
    boundary: String,
): Pair<String, ByteArray> {
    val safeName = fileName.replace(Regex("[\\r\\n\"/\\\\]"), "_").take(255).ifBlank { "attachment" }
    val out = ByteArrayOutputStream()
    fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
    text("--$boundary\r\nContent-Disposition: form-data; name=\"content\"\r\n\r\n$content\r\n")
    text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
    text("Content-Type: $mimeType\r\n\r\n")
    out.write(bytes)
    text("\r\n--$boundary--\r\n")
    return "multipart/form-data; boundary=$boundary" to out.toByteArray()
}
