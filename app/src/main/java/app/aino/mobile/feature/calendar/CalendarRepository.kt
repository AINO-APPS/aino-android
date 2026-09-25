package app.aino.mobile.feature.calendar

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.net.URLEncoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CalendarRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true },
) {
    /** `from`/`to` are ISO instants; the server returns events overlapping the range. */
    // @api GET calendar
    fun events(fromIso: String, toIso: String): List<CalendarEvent> =
        decode(api.execute(ApiRequest(path = "calendar?from=${enc(fromIso)}&to=${enc(toIso)}")))

    fun create(payload: CalendarEventPayload): CalendarEvent = mutate("calendar", payload, "POST")

    fun update(id: Long, payload: CalendarEventPayload): CalendarEvent {
        // @api PUT calendar/:id
        return mutate("calendar/$id", payload, "PUT")
    }

    fun delete(id: Long) {
        // @api DELETE calendar/:id
        mutate<Unit, JsonObject>("calendar/$id", Unit, "DELETE")
    }

    /** CalendarPage: the user's personal tasks incl. due items, for "Link to Task". */
    fun linkableTasks(today: String): List<CalendarTask> =
        decode<CalendarTaskList>(api.execute(ApiRequest(path = "tasks?date=$today&scope=personal&include_due=1"))).tasks

    fun searchPeople(term: String): List<ParticipantUser> =
        decode(api.execute(ApiRequest(path = "chat/search?q=${enc(term.trim())}")))

    fun createMeeting(request: CreateMeetingRequest): CreatedMeeting = mutate("meetings", request, "POST")

    fun conflicts(request: ConflictRequest): ConflictResponse = mutate("meetings/check-conflicts", request, "POST")

    fun meeting(code: String): MeetingDetail {
        // @api GET meetings/:code
        return decode(api.execute(ApiRequest(path = "meetings/${enc(code)}")))
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String): R {
        try {
            val bytes = if (body is Unit) ByteArray(0).takeIf { method != "DELETE" } else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            val message = runCatching {
                json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull()
            throw CalendarFailure(message ?: "Failed to save event", error)
        }
    }

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

class CalendarFailure(message: String, cause: Throwable) : Exception(message, cause)
