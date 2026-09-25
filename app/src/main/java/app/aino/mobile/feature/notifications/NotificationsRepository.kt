package app.aino.mobile.feature.notifications

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The four `/api/notifications` operations NotificationBell uses (via
 * `client/src/api/notes.ts`). The bell fetches page 1 only (server default
 * `per_page` 50); `/metrics`, `/metrics/events` and `/announcements` are not
 * used by the bell.
 */
class NotificationsRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun list(): NotificationsPage = try {
        decode(api.execute(ApiRequest(path = "notifications")))
    } catch (error: ApiError.Http) {
        throw NotificationsFailure(serverMessage(error) ?: "Failed to fetch notifications", error.statusCode, error)
    }

    fun markRead(id: Long) {
        // @api POST notifications/:id/read
        mutate<JsonObject>("notifications/$id/read", "POST")
    }

    fun markAllRead() {
        mutate<JsonObject>("notifications/read-all", "POST")
    }

    fun delete(id: Long) {
        // @api DELETE notifications/:id
        mutate<JsonObject>("notifications/$id", "DELETE")
    }

    private inline fun <reified R> mutate(path: String, method: String = "POST"): R {
        try {
            // OkHttp requires a body for POST; DELETE goes without one.
            val bytes = ByteArray(0).takeIf { method != "DELETE" }
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw NotificationsFailure(serverMessage(error) ?: "An error occurred", error.statusCode, error)
        }
    }

    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
}

class NotificationsFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)
