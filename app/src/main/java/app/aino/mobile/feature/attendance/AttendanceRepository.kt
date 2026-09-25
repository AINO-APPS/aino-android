package app.aino.mobile.feature.attendance

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.common.TrackerStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttendanceRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun loadPolicy(): AttendancePolicy {
        val body = api.execute(ApiRequest(path = "org/current")).bodyAsString()
        return if (body == "null") AttendancePolicy() else json.decodeFromString(body)
    }

    fun loadStatus(): TrackerStatus = decode(api.execute(ApiRequest(path = "tracker/status")))

    fun loadHistory(range: MonthRange): List<AttendanceDay> = decode(
        api.execute(
            ApiRequest(
                path = "tracker/history?from=${range.firstVisible}&to=${range.lastVisible}",
            ),
        ),
    )

    fun loadLeaves(range: MonthRange): List<LeaveOverlay> = decode(
        api.execute(
            ApiRequest(
                path = "leaves?start_date=${range.firstVisible}&end_date=${range.lastVisible}",
            ),
        ),
    )

    fun loadHolidays(year: Int): List<HolidayOverlay> = decode(
        api.execute(ApiRequest(path = "leave-policy/holidays?year=$year")),
    )

    // ---- Leaves tab (P3.3) ----

    /** Full leave records for a month (the Leaves tab list), same route as the calendar overlay. */
    fun loadLeaves(from: String, to: String): List<LeaveOverlay> = decode(
        api.execute(ApiRequest(path = "leaves?start_date=$from&end_date=$to")),
    )

    /** `/leave-policy/policies` is `requireSameOrg` only, so employees can read the catalogue they apply against. */
    fun loadLeavePolicies(): List<LeavePolicy> = decode(
        api.execute(ApiRequest(path = "leave-policy/policies")),
    )

    fun loadLeaveBalances(year: Int): List<LeaveBalance> = decode(
        api.execute(ApiRequest(path = "leave-policy/balances?year=$year")),
    )

    /** HR-only: every member's balances (`year=all`). */
    fun loadAllLeaveBalances(): List<UserLeaveBalance> = decode(
        api.execute(ApiRequest(path = "leave-policy/balances?year=all")),
    )

    fun applyLeave(payload: ApplyLeavePayload): LeaveMessageResponse =
        mutate<ApplyLeavePayload, LeaveMessageResponse>("leaves", payload)

    /** Pending leaves only; the server refuses to delete an approved leave. */
    fun cancelLeave(id: Long): LeaveMessageResponse = mutate("leaves/$id", Unit, "DELETE")

    /** Pending deletes the row; approved raises a manager approval request. */
    fun withdrawLeave(id: Long): LeaveMessageResponse = mutate("leaves/$id/withdraw", Unit)

    // ---- Analytics tab (P3.5) ----

    /** `tracker/analytics` accepts either `days` or an explicit `from`/`to` range. */
    fun loadAnalytics(days: Int?, from: String?, to: String?): List<AttendanceDay> {
        val query = if (days != null) "days=$days" else "from=$from&to=$to"
        return decode(api.execute(ApiRequest(path = "tracker/analytics?$query")))
    }

    fun loadWidgets(): TrackerWidgets = decode(api.execute(ApiRequest(path = "tracker/widgets")))

    fun loadNotificationMetrics(): NotificationMetrics =
        decode(api.execute(ApiRequest(path = "notifications/metrics?hours=24")))

    fun loadManualRequests(): List<ManualEntryRequest> = decode(
        api.execute(ApiRequest(path = "tracker/manual-entries")),
    )

    fun loadOvertimeRequests(): List<OvertimeRequest> = decode(
        api.execute(ApiRequest(path = "tracker/overtime-requests")),
    )

    fun loadEntries(date: String): List<RawTimeEntry> = decode(
        api.execute(ApiRequest(path = "tracker/entries/$date")),
    )

    fun submitManualEntry(payload: ManualEntryPayload): AttendanceMutationResponse =
        mutate<ManualEntryPayload, AttendanceMutationResponse>("tracker/manual-entry", payload)

    fun updateManualEntry(payload: ManualEntryPayload): AttendanceMutationResponse =
        mutate<ManualEntryPayload, AttendanceMutationResponse>("tracker/manual-entry/${payload.date}", payload, "PUT")

    fun submitOvertime(payload: OvertimePayload): AttendanceMutationResponse =
        mutate<OvertimePayload, AttendanceMutationResponse>("tracker/overtime-request", payload)

    fun clockIn(
        mode: WorkMode,
        proof: LocationProof?,
        fingerprintVerified: Boolean,
        wifiBssid: String? = null,
        faceDescriptor: List<Float>? = null,
    ): AttendanceActionResponse {
        return mutate<AttendanceActionRequest, AttendanceActionResponse>(
            "tracker/clock-in",
            AttendanceActionRequest(
                workMode = mode.name.lowercase(),
                latitude = proof?.latitude,
                longitude = proof?.longitude,
                accuracy = proof?.accuracyMeters,
                wifiBssid = wifiBssid,
                faceDescriptor = faceDescriptor,
                fingerprintVerified = fingerprintVerified.takeIf { it },
            ),
        )
    }

    fun clockOut(
        proof: LocationProof?,
        fingerprintVerified: Boolean,
        wifiBssid: String? = null,
        faceDescriptor: List<Float>? = null,
    ): AttendanceActionResponse = mutate<AttendanceActionRequest, AttendanceActionResponse>(
        "tracker/clock-out",
        AttendanceActionRequest(
            latitude = proof?.latitude,
            longitude = proof?.longitude,
            accuracy = proof?.accuracyMeters,
            wifiBssid = wifiBssid,
            faceDescriptor = faceDescriptor,
            fingerprintVerified = fingerprintVerified.takeIf { it },
        ),
    )

    fun startBreak() = mutate<Unit, AttendanceActionResponse>("tracker/break-start", Unit)
    fun endBreak() = mutate<Unit, AttendanceActionResponse>("tracker/break-end", Unit)

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            val bytes = if (body is Unit) ByteArray(0) else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            val parsed = runCatching { json.parseToJsonElement(error.responseBody).jsonObject }.getOrNull()
            val message = parsed?.get("error")?.jsonPrimitive?.content ?: "Attendance action failed"
            // The verification sheet classifies failures by the server's `code`
            // (OUTSIDE_GEOFENCE, FACE_MISMATCH, …) exactly like the web modal.
            val code = parsed?.get("code")?.jsonPrimitive?.content
            throw AttendanceFailure(message, error.statusCode, error, code)
        }
    }

    private inline fun <reified T> decode(response: app.aino.mobile.core.network.ApiResponse): T =
        json.decodeFromString(response.bodyAsString())
}

class AttendanceFailure(
    message: String,
    val statusCode: Int,
    cause: Throwable,
    val code: String? = null,
) : Exception(message, cause)