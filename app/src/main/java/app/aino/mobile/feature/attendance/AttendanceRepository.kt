package app.aino.mobile.feature.attendance

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.feature.home.DashboardStatus
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

    fun loadStatus(): DashboardStatus = decode(api.execute(ApiRequest(path = "tracker/status")))

    fun clockIn(mode: WorkMode, proof: LocationProof?, fingerprintVerified: Boolean): AttendanceActionResponse {
        return mutate(
            "tracker/clock-in",
            AttendanceActionRequest(
                workMode = mode.name.lowercase(),
                latitude = proof?.latitude,
                longitude = proof?.longitude,
                accuracy = proof?.accuracyMeters,
                fingerprintVerified = fingerprintVerified.takeIf { it },
            ),
        )
    }

    fun clockOut(proof: LocationProof?, fingerprintVerified: Boolean): AttendanceActionResponse = mutate(
        "tracker/clock-out",
        AttendanceActionRequest(
            latitude = proof?.latitude,
            longitude = proof?.longitude,
            accuracy = proof?.accuracyMeters,
            fingerprintVerified = fingerprintVerified.takeIf { it },
        ),
    )

    fun startBreak() = mutate<Unit>("tracker/break-start", Unit)
    fun endBreak() = mutate<Unit>("tracker/break-end", Unit)

    private inline fun <reified T> mutate(path: String, body: T): AttendanceActionResponse {
        try {
            val bytes = if (body is Unit) ByteArray(0) else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest("POST", path, body = bytes)))
        } catch (error: ApiError.Http) {
            val message = runCatching {
                json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull() ?: "Attendance action failed"
            throw AttendanceFailure(message, error.statusCode, error)
        }
    }

    private inline fun <reified T> decode(response: app.aino.mobile.core.network.ApiResponse): T =
        json.decodeFromString(response.bodyAsString())
}

class AttendanceFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)