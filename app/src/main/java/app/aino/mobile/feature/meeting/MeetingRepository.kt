package app.aino.mobile.feature.meeting

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `client/src/api/meetings.ts`. The web UI itself only calls [meeting],
 * [messages] and create/conflicts (Calendar); list / update / cancel /
 * participants / HLS have no web screen, so Android exposes them here for
 * parity without inventing UI (product decision 2026-09-25).
 */
class MeetingRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false },
) {
    // @api GET meetings
    fun list(status: String? = null, limit: Int = 20, offset: Int = 0): List<Meeting> =
        decode(api.execute(ApiRequest(path = "meetings?limit=$limit&offset=$offset" + (status?.let { "&status=${enc(it)}" } ?: ""))))

    fun meeting(code: String): Meeting {
        // @api GET meetings/:code
        return decode(api.execute(ApiRequest(path = "meetings/${enc(code)}")))
    }

    fun messages(code: String, limit: Int = 200, sinceId: Long? = null): List<MeetingMessageDto> {
        // @api GET meetings/:code/messages
        return decode(api.execute(ApiRequest(path = "meetings/${enc(code)}/messages?limit=$limit" + (sinceId?.let { "&since=$it" } ?: ""))))
    }

    /** MeetingParticipants "Search to add…" (`searchChatUsers`). */
    fun searchUsers(term: String): List<MeetingUserHit> =
        decode(api.execute(ApiRequest(path = "chat/search?q=${enc(term.trim())}")))

    fun update(id: Long, update: MeetingUpdate): Meeting {
        // @api PUT meetings/:id
        return mutate("meetings/$id", update, "PUT")
    }

    fun cancel(id: Long): MeetingMessageResponse {
        // @api DELETE meetings/:id
        return mutate("meetings/$id", Unit, "DELETE")
    }

    fun participants(id: Long): List<MeetingMember> {
        // @api GET meetings/:id/participants
        return decode(api.execute(ApiRequest(path = "meetings/$id/participants")))
    }

    fun addParticipant(id: Long, userId: Long): AddParticipantResponse {
        // @api POST meetings/:id/participants
        return mutate("meetings/$id/participants", AddParticipantRequest(userId), "POST")
    }

    fun removeParticipant(id: Long, userId: Long): MeetingMessageResponse {
        // @api DELETE meetings/:id/participants/:userId
        return mutate("meetings/$id/participants/$userId", Unit, "DELETE")
    }

    fun startHls(code: String): HlsBroadcast {
        // @api POST meetings/:code/hls/start
        return mutate("meetings/${enc(code)}/hls/start", Unit, "POST")
    }

    fun hlsStatus(code: String): HlsStatus {
        // @api GET meetings/:code/hls/status
        return decode(api.execute(ApiRequest(path = "meetings/${enc(code)}/hls/status")))
    }

    fun stopHls(code: String, broadcastId: String): HlsStopResponse {
        // @api POST meetings/:code/hls/stop
        return mutate("meetings/${enc(code)}/hls/stop", HlsStopRequest(broadcastId), "POST")
    }

    /** Web `sendChatFile`: upload into the meeting's conversation, then send `meeting_chat` with the URL. */
    fun uploadChatFile(conversationId: Long, fileName: String, mimeType: String, bytes: ByteArray): UploadedFile {
        val boundary = "aino-${UUID.randomUUID()}"
        val safeName = fileName.replace(Regex("[\\r\\n\\\"/\\\\]"), "_").take(255).ifBlank { "file" }
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: $mimeType\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val response = api.execute(
            ApiRequest(
                "POST",
                "chat/conversations/$conversationId/files",
                headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                body = body,
            ),
        )
        return json.parseToJsonElement(response.bodyAsString()).jsonObject.uploadedFile()
            ?: error("Upload response had no file URL")
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String): R {
        try {
            val bytes = if (body is Unit) ByteArray(0).takeIf { method != "DELETE" } else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            val message = runCatching {
                json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull()
            throw MeetingFailure(message ?: "Request failed", error)
        }
    }

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

class MeetingFailure(message: String, cause: Throwable) : Exception(message, cause)
