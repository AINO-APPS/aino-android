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
    fun loadSprintTasks(
        sprintId: Long,
        filters: TaskFilters,
        teamScope: TeamScope = TeamScope(),
    ): TaskListResponse = decode(api.execute(ApiRequest(path = sprintTasksQuery(sprintId, filters, teamScope))))

    /** The sprint's cancelled Tickets, which the board response leaves out. */
    fun loadCancelledSprintTasks(sprintId: Long, teamScope: TeamScope = TeamScope()): List<Task> =
        loadSprintTasks(sprintId, TaskFilters(status = CANCELLED_STATE), teamScope).tasks

    // @api GET sprints/:id/board
    fun loadSprintBoard(sprintId: Long): SprintBoardResponse =
        decode(api.execute(ApiRequest(path = "sprints/$sprintId/board")))

    fun loadBacklog(
        filters: TaskFilters,
        limit: Int,
        offset: Int,
        teamScope: TeamScope = TeamScope(),
    ): BacklogResponse = decode(api.execute(ApiRequest(path = backlogQuery(filters, limit, offset, teamScope))))

    fun loadPlanPage(limit: Int, offset: Int, teamScope: TeamScope = TeamScope()): BacklogResponse =
        decode(api.execute(ApiRequest(
            path = backlogQuery(TaskFilters(), limit, offset, teamScope) + "&include_completed=1&completed_since=0001-01-01",
        )))

    /**
     * Drain the server's backlog pages before applying Plan filters.
     * Never filter a single page and present its count as the collection total.
     *
     * The backlog is `sprint_id IS NULL` on the server, and that is the whole
     * rule: a ticket inside a sprint belongs to that sprint's board and to
     * nothing else. Sprint work is loaded by [loadSprintTasks] for the board and
     * by the planner's sprint pane, never folded in here.
     */
    fun loadPlanTasks(teamScope: TeamScope = TeamScope()): List<Task> {
        val tasks = linkedMapOf<Long, Task>()
        var offset = 0
        do {
            val page = loadPlanPage(500, offset, teamScope)
            page.tasks.forEach { tasks[it.id] = it }
            offset += page.tasks.size
            val hasMore = page.pagination?.let { it.hasMore || offset < it.total } ?: false
            check(!hasMore || page.tasks.isNotEmpty()) { "Backlog pagination did not advance" }
        } while (hasMore)
        return tasks.values.toList()
    }

    fun search(q: String): List<Task> = decode(api.execute(ApiRequest(path = searchQuery(q))))

    /**
     * `GET /tasks/scheduled` — every unsprinted ticket with a planned day only.
     * The date window still goes to the overlays endpoint (leave and holidays
     * are calendar context, not the task set).
     */
    fun loadScheduled(filters: TaskFilters, teamScope: TeamScope = TeamScope()): List<Task> =
        decode<TaskListResponse>(api.execute(ApiRequest(path = scheduledQuery(filters, teamScope)))).tasks

    /**
     * `GET /org/teams` — the admin's team picker options. The endpoint returns a
     * plain array of team rows (see `modules/organization/teamListing.ts`).
     */
    fun loadTeams(): List<TeamOption> =
        decode<JsonArray>(api.execute(ApiRequest(path = "org/teams"))).mapNotNull { element ->
            val row = element.jsonObject
            val id = row["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            TeamOption(id, row["name"]?.jsonPrimitive?.content.orEmpty())
        }

    // ── Next-generation surfaces ─────────────────────────────────────────
    /** Current and historical keys resolve to the canonical ticket on the server. */
    fun loadByKey(key: String): Task {
        val encoded = java.net.URLEncoder.encode(key, "UTF-8")
        return decode(api.execute(ApiRequest(path = "tasks/by-key/$encoded")))
    }

    /** `GET /tasks/epics` — epics with their rollups. */
        fun loadEpics(): List<EpicSummary> =
            decode<EpicListResponse>(api.execute(ApiRequest(path = "tasks/epics"))).epics

        /** `GET /tasks/schedule-overlays` — read-only leave, holidays and meetings. */
        fun loadScheduleOverlays(from: String, to: String): ScheduleOverlays =
            decode(api.execute(ApiRequest(path = "tasks/schedule-overlays?from=$from&to=$to")))

        /** `GET /sprints/:id/completion-preview` — the carryover pre-flight. */
        // @api GET sprints/:param/completion-preview
        fun loadCompletionPreview(id: Long, rolloverTo: String? = null): CompletionPreview {
            val query = rolloverTo?.let { "?rolloverTo=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            val path = "sprints/$id/completion-preview" + query
            return decode(api.execute(ApiRequest(path = path)))
        }

        /** `GET /sprints/:id/baseline` — committed points and the scope delta. */
        fun loadBaseline(id: Long): BaselineResponse =
            decode(api.execute(ApiRequest(path = "sprints/$id/baseline")))

    /** `GET /user/preferences` — the per-user Tasks preferences shared with web. */
    fun loadPreferences(): TaskPreferences =
        decode(api.execute(ApiRequest(path = "user/preferences")))

    /** `PUT /user/preferences` — a partial update; the server keeps the rest. */
    fun savePreferences(patch: JsonObject): TaskPreferences =
        mutate("user/preferences", patch, "PUT")

    // ── Time tracking ────────────────────────────────────────────────────
    // @api GET tasks/:id/worklogs
    fun loadWorklogs(id: Long): TaskWorklogList {
        val response = decode<JsonObject>(api.execute(ApiRequest(path = "tasks/$id/worklogs")))
        return json.decodeFromJsonElement<TaskWorklogList>(response).copy(
            hasEstimates = "original_estimate_minutes" in response && "remaining_estimate_minutes" in response,
        )
    }

    // @api POST tasks/:id/worklogs
    fun logWork(id: Long, payload: LogWorkPayload): WorklogMutationResponse =
        mutate("tasks/$id/worklogs", payload)

    // @api PATCH tasks/:id/worklogs/:worklogId
    fun editWorklog(id: Long, worklogId: Long, payload: LogWorkPayload): WorklogMutationResponse =
        mutate("tasks/$id/worklogs/$worklogId", payload, "PATCH")

    // @api DELETE tasks/:id/worklogs/:worklogId
    fun deleteWorklog(id: Long, worklogId: Long, payload: RemainingEstimatePayload = RemainingEstimatePayload()): DeleteWorklogResponse =
        mutate("tasks/$id/worklogs/$worklogId", payload, "DELETE")

    // @api PATCH tasks/:id/estimates
    fun setEstimates(id: Long, fields: JsonObject): EstimateResponse =
        mutate("tasks/$id/estimates", fields, "PATCH")

    /** `GET /tracker/task-time` — the timesheet's per-task view. */
    fun loadTaskTime(from: String, to: String): List<TaskTimeDay> =
        decode(api.execute(ApiRequest(path = "tracker/task-time?from=$from&to=$to")))

    // ── Ranked reorder ───────────────────────────────────────────────────
    /** `PATCH /tasks/:id/rank` — the server computes the LexoRank from the neighbours. */
    fun rankTask(id: Long, beforeId: Long?, afterId: Long?): Task =
        mutate("tasks/$id/rank", RankPayload(beforeId, afterId), "PATCH")

    // ── Bulk actions ─────────────────────────────────────────────────────
    /** `POST /tasks/bulk` — one request for a multi-select action. */
    fun bulkAction(payload: BulkPayload): BulkResult = mutate("tasks/bulk", payload)

    // ── Metadata ─────────────────────────────────────────────────────────
    fun loadLabels(): List<TaskLabel> = decode(api.execute(ApiRequest(path = "tasks/labels")))

    fun loadAssignableUsers(): List<AssignableUser> =
        decode(api.execute(ApiRequest(path = "tasks/assignable-users")))

    /** Active projects only (`getProjects(false)`); the legacy array form. */
    fun loadProjects(): List<ProjectOption> {
        val element = json.parseToJsonElement(api.execute(ApiRequest(path = "projects")).bodyAsString())
        return if (element is JsonArray) json.decodeFromJsonElement<List<ProjectOption>>(element) else emptyList()
    }

    fun loadAccessibleProjects(): List<ProjectOption>? {
        val element = decode<kotlinx.serialization.json.JsonElement>(
            api.execute(ApiRequest(path = "projects?include_archived=1")),
        )
        return if (element is JsonArray) json.decodeFromJsonElement<List<ProjectOption>>(element) else null
    }

    fun loadMyProjects(): List<ProjectOption> =
        decode(api.execute(ApiRequest(path = "projects/mine")))

    fun loadProjectSprints(projectId: Long): SprintListResponse =
        decode(api.execute(ApiRequest(path = "sprints?project_id=$projectId")))

    fun loadAvailableSprints(): List<AvailableSprint> =
        decode(api.execute(ApiRequest(path = "tasks/available-sprints")))

    fun loadAgileConfig(projectId: Long? = null): AgileConfig =
        AgileConfig.from(decode(api.execute(ApiRequest(path = "agile/config" + (projectId?.let { "?project_id=$it" } ?: "")))))

    fun loadSprintStats(sprintId: Long): SprintStats = decode(api.execute(ApiRequest(path = "sprints/$sprintId/stats")))

    /** `GET /sprints[?team_id=…]` — rows plus the server-selected current sprint. */
    fun loadTeamSprints(teamId: Long? = null): SprintListResponse =
        decode(api.execute(ApiRequest(path = "sprints" + (teamId?.let { "?team_id=$it" } ?: ""))))

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

    /** Inline property edits: `PUT /tasks/:id` with only the changed keys (the server keeps the rest). */
    fun patchFields(id: Long, fields: JsonObject): Task = mutate("tasks/$id", fields, "PUT")

    // @api POST sprints/:id/pause
    // @api POST sprints/:id/resume
    fun pauseSprint(id: Long): SprintEnvelope = mutate("sprints/$id/pause", Unit)
    fun resumeSprint(id: Long): SprintEnvelope = mutate("sprints/$id/resume", Unit)

    /** The sprint goal is editable inline from the planner (D21). */
    fun updateSprintGoal(id: Long, goal: String): SprintEnvelope =
        mutate("sprints/$id", SprintGoalPayload(goal), "PUT")

    fun updateStatus(id: Long, status: String): Task =
        mutate("tasks/$id/status", TaskStatusPayload(status), "PATCH")

    fun scheduleTask(id: Long, date: String): Task = mutate("tasks/$id/schedule", SchedulePayload(date), "PATCH")

    fun unscheduleTask(id: Long): Task = mutate("tasks/$id/unschedule", Unit, "PATCH")

    fun assignSprint(id: Long, sprintId: Long?): Task =
        mutate("tasks/$id/assign-sprint", AssignSprintPayload(sprintId), "PATCH")

    fun previewProjectMove(id: Long, projectId: Long): ProjectMovePreview =
        mutate("tasks/$id/move-project/preview", ProjectMovePayload(projectId))

    fun moveProject(id: Long, projectId: Long): Task =
        mutate<ProjectMovePayload, ProjectMoveResponse>("tasks/$id/move-project", ProjectMovePayload(projectId)).task

    fun deleteTask(id: Long): CancelTaskResponse = mutate("tasks/$id", Unit, "DELETE")

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
            throw TaskFailure(serverMessage(error) ?: "Task action failed", error.statusCode, error,
                code = runCatching { json.parseToJsonElement(error.responseBody).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull())
        }
    }

    /**
     * Capability errors use their shared client copy; other server messages
     * remain verbatim so validation and policy detail is not lost.
     */
    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        val body = json.parseToJsonElement(error.responseBody).jsonObject
        val code = body["code"]?.jsonPrimitive?.content
        capabilityFailureCopy(code) ?: body["error"]?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Decode a response body.
     *
     * A shape mismatch used to vanish into `runCatching { }.getOrNull()` at every
     * call site, so four features rendered nothing with no log, no error and no
     * failing test. Re-throw a `TaskFailure` that names the endpoint so the
     * degradation is observable instead of silent.
     */
        private inline fun <reified T> decode(response: ApiResponse): T {
            val body = response.bodyAsString()
            return try {
                json.decodeFromString(body.ifBlank { "{}" })
            } catch (error: Exception) {
                if (app.aino.mobile.BuildConfig.DEBUG) {
                    android.util.Log.w(
                        "TaskRepository",
                        "decode ${T::class.simpleName} failed: ${error.message} body=${body.take(200)}",
                    )
                }
                throw TaskFailure(
                    "Unexpected ${T::class.simpleName} response: ${error.message}",
                    statusCode = 0,
                    cause = error,
                )
            }
        }
    }

class TaskFailure(message: String, val statusCode: Int, cause: Throwable, val code: String? = null) : Exception(message, cause)

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
