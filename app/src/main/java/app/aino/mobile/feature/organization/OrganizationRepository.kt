package app.aino.mobile.feature.organization

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `client/src/api/organization.ts` + the task-label / sprint calls the
 * Organization page makes (`client/src/api/tasks.ts`). All responses are bare
 * JSON (no `{success,data}` envelope).
 */
class OrganizationRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    // ── Organization ────────────────────────────────────────────────────────

    /** `null` body means the user is not in an org. */
    fun currentOrg(): OrgInfo? = load {
        json.decodeFromString<OrgInfo?>(api.execute(ApiRequest(path = "org/current")).bodyAsString().ifBlank { "null" })
    }

    fun createOrg(name: String): CreateOrgResponse =
        mutate("org", buildJsonObject { put("name", name) }, "POST")

    // ── Members (pickers) ───────────────────────────────────────────────────

    /**
     * Active members for the head/lead pickers. The route caps `per_page` at
     * 100, so every page is fetched until `total` is reached.
     */
    fun activeMembers(orgId: Long?): List<OrgMember> = load {
        val all = mutableListOf<OrgMember>()
        var page = 1
        while (page <= MAX_MEMBER_PAGES) {
            val query = query("is_active" to "true", "org_id" to orgId?.toString(), "per_page" to "100", "page" to "$page")
            // @api GET org/members
            val result: OrgMembersPage = decode(api.execute(ApiRequest(path = "org/members" + query)))
            all += result.data
            if (result.data.isEmpty() || all.size >= result.total) break
            page++
        }
        all
    }

    // ── Departments ─────────────────────────────────────────────────────────

    fun departments(orgId: Long?): List<Department> = load {
        // @api GET org/departments
        decode(api.execute(ApiRequest(path = "org/departments" + query("org_id" to orgId?.toString()))))
    }

    fun createDepartment(name: String, headId: Long?, orgId: Long?) {
        val body = buildJsonObject {
            put("name", name)
            put("head_id", headId.toJson())
            if (orgId != null) put("org_id", orgId)
        }
        mutate<JsonObject, JsonObject>("org/departments", body, "POST")
    }

    fun updateDepartment(id: Long, name: String, headId: Long?) {
        val body = buildJsonObject {
            put("name", name)
            put("head_id", headId.toJson())
        }
        // @api PUT org/departments/:id
        mutate<JsonObject, JsonObject>("org/departments/$id", body, "PUT")
    }

    fun deleteDepartment(id: Long) {
        // @api DELETE org/departments/:id
        mutate<Unit, JsonObject>("org/departments/$id", Unit, "DELETE")
    }

    // ── Teams ───────────────────────────────────────────────────────────────

    fun teams(orgId: Long?): List<Team> = load {
        // @api GET org/teams
        decode(api.execute(ApiRequest(path = "org/teams" + query("org_id" to orgId?.toString()))))
    }

    fun createTeam(name: String, departmentId: Long?, leadId: Long?, orgId: Long?) {
        val body = buildJsonObject {
            put("name", name)
            put("department_id", departmentId.toJson())
            put("lead_id", leadId.toJson())
            if (orgId != null) put("org_id", orgId)
        }
        mutate<JsonObject, JsonObject>("org/teams", body, "POST")
    }

    fun updateTeam(id: Long, name: String, departmentId: Long?, leadId: Long?) {
        val body = buildJsonObject {
            put("name", name)
            put("department_id", departmentId.toJson())
            put("lead_id", leadId.toJson())
        }
        // @api PUT org/teams/:id
        mutate<JsonObject, JsonObject>("org/teams/$id", body, "PUT")
    }

    fun deleteTeam(id: Long) {
        // @api DELETE org/teams/:id
        mutate<Unit, JsonObject>("org/teams/$id", Unit, "DELETE")
    }

    fun teamSprintConfig(teamId: Long): SprintConfig = load {
        // @api GET org/teams/:id/sprint-config
        decode(api.execute(ApiRequest(path = "org/teams/$teamId/sprint-config")))
    }

    fun updateTeamSprintConfig(teamId: Long, weeks: Int, startDate: String?, mode: String) {
        val body = buildJsonObject {
            put("sprint_duration_weeks", weeks)
            put("sprint_start_date", startDate?.let(::JsonPrimitive) ?: JsonNull)
            put("sprint_mode", mode)
        }
        // @api PUT org/teams/:id/sprint-config
        mutate<JsonObject, JsonObject>("org/teams/$teamId/sprint-config", body, "PUT")
    }

    // ── Sprints (pause / resume from the team edit panel) ───────────────────

    fun activeSprint(): Sprint? = load {
        decode<SprintEnvelope>(api.execute(ApiRequest(path = "sprints/active"))).sprint
    }

    fun pauseSprint(sprintId: Long): Sprint? {
        // @api POST sprints/:id/pause
        return mutate<Unit, SprintEnvelope>("sprints/$sprintId/pause", Unit, "POST").sprint
    }

    fun resumeSprint(sprintId: Long): Sprint? {
        // @api POST sprints/:id/resume
        return mutate<Unit, SprintEnvelope>("sprints/$sprintId/resume", Unit, "POST").sprint
    }

    // ── Org chart ───────────────────────────────────────────────────────────

    fun orgChart(orgId: Long?): OrgChart = load {
        // @api GET org/chart
        decode(api.execute(ApiRequest(path = "org/chart" + query("org_id" to orgId?.toString()))))
    }

    // ── Task labels (manager) ───────────────────────────────────────────────

    fun taskLabels(): List<TaskLabel> = load {
        decode(api.execute(ApiRequest(path = "tasks/labels/manage")))
    }

    fun createTaskLabel(name: String, color: String): TaskLabel =
        mutate("tasks/labels", buildJsonObject { put("name", name); put("color", color) }, "POST")

    fun updateTaskLabel(id: Long, name: String, color: String): TaskLabel {
        // @api PUT tasks/labels/:id
        return mutate("tasks/labels/$id", buildJsonObject { put("name", name); put("color", color) }, "PUT")
    }

    fun deleteTaskLabel(id: Long) {
        // @api DELETE tasks/labels/:id
        mutate<Unit, JsonObject>("tasks/labels/$id", Unit, "DELETE")
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun Long?.toJson() = this?.let(::JsonPrimitive) ?: JsonNull

    private fun query(vararg params: Pair<String, String?>): String {
        val present = params.filter { it.second != null }
        if (present.isEmpty()) return ""
        return present.joinToString("&", prefix = "?") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
    }

    private inline fun <T> load(block: () -> T): T {
        try {
            return block()
        } catch (error: ApiError.Http) {
            throw OrganizationFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            // OkHttp requires a body for POST/PUT; DELETE may go without one.
            val bytes = if (body is Unit) ByteArray(0).takeIf { method != "DELETE" } else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw OrganizationFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })

    private companion object {
        const val MAX_MEMBER_PAGES = 50
    }
}

/** [serverMessage] is the route's `{ error }`; callers fall back to the web's copy when it is absent. */
class OrganizationFailure(val serverMessage: String?, val statusCode: Int, cause: Throwable) :
    Exception(serverMessage ?: "HTTP $statusCode", cause)

/** `e.response?.data?.error || fallback` */
fun Throwable.orgMessage(fallback: String): String = (this as? OrganizationFailure)?.serverMessage ?: fallback
