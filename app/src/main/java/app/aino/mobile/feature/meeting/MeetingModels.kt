package app.aino.mobile.feature.meeting

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A `meeting_participants` row joined with the user (`GET /meetings/:code`, `/:id/participants`). */
@Serializable
data class MeetingMember(
    @SerialName("user_id") val userId: Long,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    val avatar: String? = null,
    val role: String? = null,
    /** `invited` / `joined` / `left` / `declined`. */
    val status: String? = null,
) {
    fun display(): String = fullName ?: username ?: "Participant"
}

/** `GET /meetings/:code` (all `meetings` columns + organizer + participants) and list rows. */
@Serializable
data class Meeting(
    val id: Long,
    val title: String? = null,
    val description: String? = null,
    @SerialName("meeting_code") val meetingCode: String? = null,
    @SerialName("created_by") val createdBy: Long? = null,
    @SerialName("organizer_id") val organizerId: Long? = null,
    @SerialName("conversation_id") val conversationId: Long? = null,
    /** `scheduled` / `active` / `ended` / `cancelled`. */
    val status: String? = null,
    @SerialName("is_huddle") val isHuddle: Boolean? = null,
    val settings: JsonObject? = null,
    @SerialName("organizer_name") val organizerName: String? = null,
    @SerialName("organizer_avatar") val organizerAvatar: String? = null,
    @SerialName("calendar_title") val calendarTitle: String? = null,
    @SerialName("calendar_start") val calendarStart: String? = null,
    /** List rows only; Postgres `COUNT(*)` arrives as a string. */
    @SerialName("participant_count") val participantCount: JsonElement? = null,
    @SerialName("my_status") val myStatus: String? = null,
    val participants: List<MeetingMember> = emptyList(),
) {
    /** Web `MeetingRoom`: host = organizer or creator. */
    fun isHost(userId: Long?): Boolean = userId != null && (organizerId == userId || createdBy == userId)

    /** HuddleAutoJoin: `settings.callType === "video"`, anything else is a voice call. */
    fun isVideoCall(): Boolean = settings?.get("callType")?.jsonPrimitive?.contentOrNull == "video"

    fun isEnded(): Boolean = status == "ended" || status == "cancelled"

    fun participantTotal(): Int? = (participantCount as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

/** `GET /meetings/:code/messages` row (REST uses `client_msg_id`; WS uses `clientMsgId`). */
@Serializable
data class MeetingMessageDto(
    val id: Long? = null,
    @SerialName("client_msg_id") val clientMsgIdRest: String? = null,
    val clientMsgId: String? = null,
    @SerialName("sender_id") val senderId: Long? = null,
    @SerialName("sender_name") val senderName: String? = null,
    val text: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    val system: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `PUT /meetings/:id` — only non-null fields change; settings shallow-merge server-side. */
@Serializable
data class MeetingUpdate(val title: String? = null, val description: String? = null, val settings: JsonObject? = null)

@Serializable
data class AddParticipantRequest(@SerialName("user_id") val userId: Long)

@Serializable
data class AddParticipantResponse(
    val message: String? = null,
    val hasConflict: Boolean? = null,
    val conflictTitle: String? = null,
)

@Serializable
data class MeetingMessageResponse(val message: String? = null)

@Serializable
data class MeetingUserHit(
    val id: Long,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    val avatar: String? = null,
) {
    fun display(): String = fullName ?: username ?: "Participant"
}

/** `POST /meetings/:code/hls/start` (publisher only gets ingest creds). */
@Serializable
data class HlsBroadcast(
    val broadcastId: String? = null,
    val ingestUrl: String? = null,
    val hlsUrl: String? = null,
    val mediaServer: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class HlsStatus(
    val live: Boolean = false,
    val hlsUrl: String? = null,
    val hostId: Long? = null,
    val startedAt: String? = null,
    val mediaServer: String? = null,
    val ingestUrl: String? = null,
    val broadcastId: String? = null,
)

@Serializable
data class HlsStopRequest(val broadcastId: String)

@Serializable
data class HlsStopResponse(val ok: Boolean = false, val alreadyStopped: Boolean? = null)

/** `chat/conversations/:id/files` answers snake_case; tolerate camelCase too (the web reads `fileUrl`). */
data class UploadedFile(val url: String, val name: String, val size: Long)

fun JsonObject.uploadedFile(): UploadedFile? {
    fun s(vararg keys: String) = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
    val url = s("file_url", "fileUrl") ?: return null
    return UploadedFile(url, s("file_name", "fileName") ?: "File", s("file_size", "fileSize")?.toLongOrNull() ?: 0)
}
