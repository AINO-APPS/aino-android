package app.aino.mobile.feature.leaves

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LeaveRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun loadLeaves(from: String, to: String): List<Leave> =
        decode(api.execute(ApiRequest(path = "leaves?start_date=$from&end_date=$to")))

    fun loadBalances(year: Int): List<LeaveBalance> =
        decode(api.execute(ApiRequest(path = "leaves/balance?year=$year")))

    /**
     * `/leave-policy/policies` is `requireSameOrg` only, so an employee can read
     * the catalogue they must apply against even though mutation is HR-only.
     */
    fun loadPolicies(): List<LeavePolicy> =
        decode(api.execute(ApiRequest(path = "leave-policy/policies")))

    fun loadHolidays(year: Int): List<Holiday> =
        decode(api.execute(ApiRequest(path = "leave-policy/holidays?year=$year")))

    /** The calendar route requires both bounds and 400s without them. */
    fun loadEvents(fromIso: String, toIso: String): List<CalendarEvent> = decode(
        api.execute(
            ApiRequest(
                path = "calendar?from=" + encode(fromIso) + "&to=" + encode(toIso),
            ),
        ),
    )

    fun apply(payload: ApplyLeavePayload): LeaveMessageResponse = mutate("leaves", payload)

    /** Pending leaves only; the server refuses to delete an approved leave. */
    fun cancel(id: Long): LeaveMessageResponse = mutate("leaves/$id", Unit, "DELETE")

    /** Pending deletes the row; approved raises a manager approval request. */
    fun withdraw(id: Long): LeaveMessageResponse = mutate("leaves/$id/withdraw", Unit)

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            val bytes = if (body is Unit) null else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw LeaveFailure(serverMessage(error) ?: "Leave action failed", error.statusCode, error)
        }
    }

    /**
     * Policy messages ("Half-day leave is not allowed…", quota exceeded, public
     * holidays cannot be withdrawn) only exist server-side, so they are shown
     * verbatim rather than replaced with a generic failure string.
     */
    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString())
}

class LeaveFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)
