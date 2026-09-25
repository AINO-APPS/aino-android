package app.aino.mobile.feature.manager

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `client/src/api/organization.ts`'s `manager` namespace (`pages/manager` folder).
 * All responses are bare JSON (no `{success,data}` envelope).
 */
class ManagerRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    // ── Team attendance ──────────────────────────────────────────────────────

    fun teamAttendance(date: String?): List<TeamAttendanceMember> = load {
        // @api GET manager/team-attendance
        decode(api.execute(ApiRequest(path = "manager/team-attendance" + query("date" to date))))
    }

    // ── Team analytics ───────────────────────────────────────────────────────

    fun teamAnalytics(days: String?, from: String?, to: String?): TeamAnalyticsResponse = load {
        // @api GET manager/team-analytics
        decode(api.execute(ApiRequest(path = "manager/team-analytics" + query("days" to days, "from" to from, "to" to to))))
    }

    // ── Approvals ─────────────────────────────────────────────────────────────

    fun approvals(status: String?): List<ApprovalRow> = load {
        // @api GET manager/approvals
        decode(api.execute(ApiRequest(path = "manager/approvals" + query("status" to status))))
    }

    fun approve(id: Long): ManagerMessageResponse =
        // @api POST manager/approvals/:id/approve
        mutate("manager/approvals/$id/approve", Unit, "POST")

    fun reject(id: Long, reason: String?): ManagerMessageResponse {
        val body = buildJsonObject { put("reject_reason", reason.orEmpty()) }
        // @api POST manager/approvals/:id/reject
        return mutate("manager/approvals/$id/reject", body, "POST")
    }

    fun bulkAction(ids: List<Long>, action: String, reason: String?): BulkApprovalResponse {
        val body = buildJsonObject {
            put("ids", buildJsonArray { ids.forEach { add(it) } })
            put("action", action)
            put("reject_reason", reason.orEmpty())
        }
        // @api POST manager/approvals/bulk
        return mutate("manager/approvals/bulk", body, "POST")
    }

    // ── My requests ───────────────────────────────────────────────────────────

    fun myRequests(status: String? = "all"): List<ApprovalRow> = load {
        // @api GET manager/my-requests
        decode(api.execute(ApiRequest(path = "manager/my-requests" + query("status" to status))))
    }

    // ── Team member details ──────────────────────────────────────────────────

    fun memberOverview(userId: Long): MemberOverviewResponse = load {
        // @api GET manager/member/:userId/overview
        decode(api.execute(ApiRequest(path = "manager/member/$userId/overview")))
    }

    fun memberHours(userId: Long, from: String?, to: String?): List<MemberHourRow> = load {
        // @api GET manager/member/:userId/hours
        decode(api.execute(ApiRequest(path = "manager/member/$userId/hours" + query("from" to from, "to" to to))))
    }

    fun memberLeaves(userId: Long, from: String?, to: String? = null): List<MemberLeaveRow> = load {
        // @api GET manager/member/:userId/leaves
        decode(api.execute(ApiRequest(path = "manager/member/$userId/leaves" + query("from" to from, "to" to to))))
    }

    fun memberRequests(userId: Long): List<ApprovalRow> = load {
        // @api GET manager/member/:userId/requests
        decode(api.execute(ApiRequest(path = "manager/member/$userId/requests")))
    }

    fun memberTasks(userId: Long, date: String? = null): List<MemberTaskRow> = load {
        // @api GET manager/member/:userId/tasks
        decode(api.execute(ApiRequest(path = "manager/member/$userId/tasks" + query("date" to date))))
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private fun query(vararg params: Pair<String, String?>): String {
        val present = params.filter { it.second != null }
        if (present.isEmpty()) return ""
        return present.joinToString("&", prefix = "?") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
    }

    private inline fun <T> load(block: () -> T): T {
        try {
            return block()
        } catch (error: ApiError.Http) {
            throw ManagerFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            val bytes = if (body is Unit) ByteArray(0) else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw ManagerFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
}

/** [serverMessage] is the route's `{ error }`; callers fall back to the web's copy when it is absent. */
class ManagerFailure(val serverMessage: String?, val statusCode: Int, cause: Throwable) :
    Exception(serverMessage ?: "HTTP $statusCode", cause)

/** `e.response?.data?.error || fallback` */
fun Throwable.managerMessage(fallback: String): String = (this as? ManagerFailure)?.serverMessage ?: fallback
