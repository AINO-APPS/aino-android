package app.aino.mobile.feature.meeting

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the lobby shows about the group (from the chat list row and `GET /members`). */
@Serializable
data class GroupCallGroup(
    val id: Long,
    @SerialName("group_name") val name: String? = null,
    @SerialName("group_avatar") val avatar: String? = null,
    @SerialName("member_count") val memberCount: Int? = null,
)

@Serializable
data class GroupCallMember(
    val id: Long,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    val avatar: String? = null,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

/** `GET chat/conversations/:id/active-call` (204 when none). */
@Serializable
data class LobbyActiveCall(
    val meetingId: Long,
    val meetingCode: String,
    val callType: String = "voice",
    val participants: List<LobbyParticipant> = emptyList(),
)

@Serializable
data class LobbyParticipant(val id: Long, val fullName: String? = null, val avatar: String? = null)

@Serializable
private data class StartGroupCallRequest(
    val title: String,
    @SerialName("conversation_id") val conversationId: Long,
    val huddle: Boolean,
    val settings: StartGroupCallSettings,
)

@Serializable
private data class StartGroupCallSettings(val allowScreenShare: Boolean, val callType: String, val ring: Boolean)

@Serializable
private data class StartedGroupCall(val id: Long, @SerialName("meeting_code") val meetingCode: String)

/** The lobby's view of a group call: who is in it, and whether we start or join. */
data class GroupCallLobbyState(
    val group: GroupCallGroup? = null,
    val members: List<GroupCallMember> = emptyList(),
    val active: LobbyActiveCall? = null,
    val loading: Boolean = true,
    val starting: Boolean = false,
    val error: String? = null,
)

/** "Start call" when nobody is in the group's call, otherwise "Join call". */
fun lobbyPrimaryLabel(active: LobbyActiveCall?): String = if (active != null && active.participants.isNotEmpty()) "Join call" else "Start call"

/** "No one else is here" / "Ana is in this call" / "Ana and 2 others are in this call". */
fun lobbyPresenceText(active: LobbyActiveCall?, selfId: Long?): String {
    val others = active?.participants.orEmpty().filter { it.id != selfId }
    val first = others.firstOrNull()?.fullName?.substringBefore(' ') ?: "Someone"
    return when (others.size) {
        0 -> "No one else is here"
        1 -> "$first is in this call"
        2 -> "$first and 1 other are in this call"
        else -> "$first and ${others.size - 1} others are in this call"
    }
}

/**
 * Group-call lobby I/O. Lives in the meeting feature (the call transport)
 * and talks to the chat endpoints directly, so the chat feature does not
 * depend on it.
 */
class GroupCallRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    // @api GET chat/conversations/:id/active-call
    fun activeCall(conversationId: Long): LobbyActiveCall? {
        val body = api.execute(ApiRequest(path = "chat/conversations/$conversationId/active-call")).bodyAsString()
        return body.takeIf(String::isNotBlank)?.let { json.decodeFromString<LobbyActiveCall>(it) }
    }

    // @api GET chat/conversations/:id/members
    fun members(conversationId: Long): List<GroupCallMember> =
        json.decodeFromString(api.execute(ApiRequest(path = "chat/conversations/$conversationId/members")).bodyAsString())

    /** The group's row from the conversation list (name, photo, member count). */
    fun group(conversationId: Long): GroupCallGroup? =
        json.decodeFromString<List<GroupCallGroup>>(api.execute(ApiRequest(path = "chat/conversations")).bodyAsString())
            .firstOrNull { it.id == conversationId }

    /** Creates the huddle bound to the group; [ring] = false starts it silently with a Join row in the chat. */
    // @api POST meetings
    fun start(conversationId: Long, title: String?, callType: String, ring: Boolean): String {
        val body = StartGroupCallRequest(
            title = title?.takeIf(String::isNotBlank) ?: "Group call",
            conversationId = conversationId,
            huddle = true,
            settings = StartGroupCallSettings(allowScreenShare = true, callType = callType, ring = ring),
        )
        try {
            val response = api.execute(ApiRequest("POST", "meetings", body = json.encodeToString(body).toByteArray()))
            return json.decodeFromString<StartedGroupCall>(response.bodyAsString()).meetingCode
        } catch (error: ApiError.Http) {
            val message = runCatching { json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw MeetingFailure(message ?: "Could not start the group call", error)
        }
    }
}
