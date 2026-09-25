package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.ConversationEntity
import app.aino.mobile.core.db.MessageEntity
import app.aino.mobile.core.realtime.RealtimeDomain
import app.aino.mobile.core.realtime.RealtimeEvent
import app.aino.mobile.core.realtime.RealtimeReaction
import app.aino.mobile.core.realtime.RealtimeEnvelope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Serializable
data class ChatConversation(
    val id: Long,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("is_group") val isGroup: Boolean = false,
    @SerialName("group_avatar") val groupAvatar: String? = null,
    @SerialName("other_user_id") val otherUserId: Long? = null,
    @SerialName("other_username") val otherUsername: String? = null,
    @SerialName("other_full_name") val otherFullName: String? = null,
    @SerialName("other_avatar") val otherAvatar: String? = null,
    @SerialName("is_self_chat") val isSelfChat: Boolean = false,
    @SerialName("last_message") val lastMessage: String? = null,
    @SerialName("last_sender_name") val lastSenderName: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("last_file_name") val lastFileName: String? = null,
    @SerialName("last_deleted") val lastDeleted: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("member_count") val memberCount: Int? = null,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("is_favourite") val isFavourite: Boolean = false,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("is_meeting_chat") val isMeetingChat: Boolean = false,
    @SerialName("meeting_code") val meetingCode: String? = null,
) {
    fun title(): String = when {
        isGroup -> groupName?.takeIf(String::isNotBlank) ?: "Group"
        isSelfChat -> "Note to self"
        else -> otherFullName?.takeIf(String::isNotBlank)
            ?: otherUsername?.takeIf(String::isNotBlank)
            ?: "Conversation"
    }

    fun preview(): String = when {
        lastDeleted != null -> "Message deleted"
        !lastMessage.isNullOrBlank() -> lastMessage
        !lastFileName.isNullOrBlank() -> "Attachment: $lastFileName"
        else -> "No messages yet"
    }

    fun avatar(): String? = if (isGroup) groupAvatar else otherAvatar

    fun toEntity(scope: CacheScope): ConversationEntity = ConversationEntity(
        tenantId = scope.tenantId,
        userId = scope.userId,
        conversationId = id,
        title = title(),
        avatarUrl = avatar(),
        unreadCount = unreadCount.coerceAtLeast(0),
        updatedAtEpochMs = parseEpoch(lastMessageAt ?: updatedAt),
    )
}

fun ConversationEntity.toCachedConversation(): ChatConversation = ChatConversation(
    id = conversationId,
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs).toString(),
    groupName = title,
    isGroup = true,
    groupAvatar = avatarUrl,
    unreadCount = unreadCount,
)

private fun parseEpoch(value: String?): Long = runCatching {
    value?.let(Instant::parse)?.toEpochMilli()
}.getOrNull() ?: 0L

@Serializable
data class ChatPresence(
    val presence: String = "offline",
    val userStatus: String = "offline",
    val workMode: String? = null,
)

@Serializable
data class ChatUser(
    val id: Long,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("blocked_at") val blockedAt: String? = null,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

@Serializable
data class DirectConversationRequest(val userId: Long)

@Serializable
data class ConversationCreated(@SerialName("conversationId") val conversationId: Long)

@Serializable
data class ChatOk(val ok: Boolean = true)

@Serializable
data class ChatReaction(
    val emoji: String,
    val userId: Long,
    val fullName: String = "",
)

@Serializable
data class ChatMessage(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long? = null,
    @SerialName("sender_id") val senderId: Long,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("sender_avatar") val senderAvatar: String? = null,
    @SerialName("sender_username") val senderUsername: String? = null,
    @SerialName("reply_to_id") val replyToId: Long? = null,
    @SerialName("reply_content") val replyContent: String? = null,
    @SerialName("reply_sender_name") val replySenderName: String? = null,
    @SerialName("reply_file_url") val replyFileUrl: String? = null,
    @SerialName("reply_file_type") val replyFileType: String? = null,
    @SerialName("reply_file_name") val replyFileName: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("forwarded_from_id") val forwardedFromId: Long? = null,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    @SerialName("pinned_by") val pinnedBy: Long? = null,
    val starred: Boolean = false,
    @SerialName("format_type") val formatType: String? = null,
    val metadata: JsonElement? = null,
    @SerialName("client_msg_id") val clientMessageId: String? = null,
    @SerialName("delivered_to") val deliveredTo: List<Long> = emptyList(),
    val reactions: List<ChatReaction> = emptyList(),
    @SerialName("media_job_id") val mediaJobId: Long? = null,
    @SerialName("media_state") val mediaState: String? = null,
    @SerialName("media_stage") val mediaStage: String? = null,
    @SerialName("media_progress") val mediaProgress: Int? = null,
    @SerialName("media_failure_reason") val mediaFailureReason: String? = null,
    /** Sender-generated OpenGraph card (web `MessageBubble` `msg.link_preview`). */
    @SerialName("link_preview") val linkPreview: LinkPreview? = null,
    /** Present on `/chat/starred` rows (web StarredMessages "in {conversation_name}"). */
    @SerialName("conversation_name") val conversationName: String? = null,
    val deliveryState: String = "sent",
) {
    fun body(): String = when {
        deletedAt != null -> "Message deleted"
        !content.isNullOrBlank() -> content
        !fileName.isNullOrBlank() -> "Attachment: $fileName"
        else -> ""
    }

    fun toEntity(scope: CacheScope, fallbackConversationId: Long): MessageEntity = MessageEntity(
        tenantId = scope.tenantId,
        userId = scope.userId,
        messageId = id,
        conversationId = conversationId ?: fallbackConversationId,
        senderId = senderId,
        body = body(),
        createdAtEpochMs = parseEpoch(createdAt),
        deliveryState = deliveryState,
    )
}

fun MessageEntity.toCachedMessage(): ChatMessage = ChatMessage(
    id = messageId,
    conversationId = conversationId,
    senderId = senderId,
    content = body,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs).toString(),
    deliveryState = deliveryState,
)

@Serializable
data class ReadReceipt(
    @SerialName("user_id") val userId: Long,
    @SerialName("last_read_at") val lastReadAt: String,
    @SerialName("full_name") val fullName: String,
)

@Serializable
data class ChatTypingEvent(val conversationId: Long, val userId: Long)

@Serializable
data class ChatReadReceiptEvent(val conversationId: Long, val userId: Long, val readAt: String)

@Serializable
data class ChatReactionEvent(
    val messageId: Long,
    val conversationId: Long,
    val userId: Long,
    val fullName: String = "",
    val emoji: String,
    val action: String,
)

@Serializable
data class ChatEditEvent(
    val messageId: Long,
    val conversationId: Long,
    val content: String,
    val editedAt: String,
)

@Serializable
data class ChatDeleteEvent(val messageId: Long, val conversationId: Long)

@Serializable
data class ChatPinEvent(
    val messageId: Long,
    val conversationId: Long,
    val pinned: Boolean,
    val pinnedBy: Long? = null,
    val pinnedByName: String? = null,
)

@PublishedApi
internal val CHAT_EVENT_JSON = Json { ignoreUnknownKeys = true }

inline fun <reified T> decodeChatRealtime(data: JsonElement?): T? =
    data?.let { runCatching { CHAT_EVENT_JSON.decodeFromJsonElement<T>(it) }.getOrNull() }

fun applyRealtimeReceipt(
    receipts: List<ReadReceipt>,
    event: ChatReadReceiptEvent,
): List<ReadReceipt> = receipts
    .filterNot { it.userId == event.userId }
    .plus(ReadReceipt(event.userId, event.readAt, receipts.firstOrNull { it.userId == event.userId }?.fullName.orEmpty()))

fun applyRealtimeReaction(
    messages: List<ChatMessage>,
    event: ChatReactionEvent,
): List<ChatMessage> = messages.map { message ->
    if (message.id != event.messageId) return@map message
    val withoutActorEmoji = message.reactions.filterNot {
        it.userId == event.userId && it.emoji == event.emoji
    }
    message.copy(
        reactions = if (event.action == "added") {
            withoutActorEmoji + ChatReaction(event.emoji, event.userId, event.fullName)
        } else {
            withoutActorEmoji
        },
    )
}

fun applyRealtimeEdit(messages: List<ChatMessage>, event: ChatEditEvent): List<ChatMessage> =
    messages.map { message ->
        if (message.id == event.messageId && message.deletedAt == null) {
            message.copy(content = event.content, editedAt = event.editedAt)
        } else message
    }

fun applyRealtimeDelete(
    messages: List<ChatMessage>,
    event: ChatDeleteEvent,
    deletedAt: String = Instant.now().toString(),
): List<ChatMessage> = messages.map { message ->
    if (message.id == event.messageId) {
        message.copy(
            content = "",
            fileName = null,
            fileUrl = null,
            fileType = null,
            fileSize = null,
            reactions = emptyList(),
            deletedAt = message.deletedAt ?: deletedAt,
            starred = false,
        )
    } else message
}

fun applyRealtimePin(
    messages: List<ChatMessage>,
    event: ChatPinEvent,
    pinnedAt: String = Instant.now().toString(),
): List<ChatMessage> = messages.map { message ->
    if (message.id == event.messageId && message.deletedAt == null) {
        message.copy(
            pinnedAt = if (event.pinned) message.pinnedAt ?: pinnedAt else null,
            pinnedBy = if (event.pinned) event.pinnedBy else null,
        )
    } else message
}

/** Exact outbound shape accepted by `handleChatTyping`. */
fun typingEnvelope(conversationId: Long): RealtimeEnvelope = RealtimeEnvelope(
    type = "chat_typing",
    data = buildJsonObject { put("conversationId", conversationId) },
)

@Serializable
data class ReactionRequest(val emoji: String)

@Serializable data class EditMessageRequest(val content: String)
@Serializable data class ToggleStarResponse(val ok: Boolean = true, val starred: Boolean)
@Serializable data class ToggleMessagePinResponse(val ok: Boolean = true, val pinned: Boolean)
@Serializable data class ForwardMessageRequest(val conversationIds: List<Long>)

@Serializable
data class MediaJobResponse(val ok: Boolean = true, val mediaJobId: Long? = null)

@Serializable
data class ConversationMember(
    val id: Long,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val avatar: String? = null,
    val role: String = "member",
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

/** Web `MentionInput`: the `@word` being typed at the end of the text, or null. */
fun activeMentionQuery(text: String): String? = Regex("@(\\w*)$").find(text)?.groupValues?.get(1)

/** Web `MentionInput`: up to 6 members whose full name or username contains the query. */
fun mentionSuggestions(members: List<ConversationMember>, query: String, currentUserId: Long?): List<ConversationMember> {
    val q = query.lowercase()
    return members.filter {
        it.id != currentUserId && (it.fullName.orEmpty().lowercase().contains(q) || it.username.orEmpty().lowercase().contains(q))
    }.take(6)
}

/** Replaces the trailing `@query` with `@Full Name ` (web inserts `full_name || username`). */
fun insertMention(text: String, member: ConversationMember): String {
    val start = text.lastIndexOf('@').takeIf { it >= 0 } ?: return text
    return text.substring(0, start) + "@" + member.display() + " "
}

@Serializable
data class CallLog(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("caller_id") val callerId: Long,
    @SerialName("call_type") val callType: String = "voice",
    val status: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val duration: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("caller_name") val callerName: String? = null,
    @SerialName("caller_avatar") val callerAvatar: String? = null,
    @SerialName("other_user_id") val otherUserId: Long? = null,
    @SerialName("other_name") val otherName: String? = null,
    @SerialName("other_avatar") val otherAvatar: String? = null,
    @SerialName("is_group") val isGroup: Boolean = false,
    @SerialName("group_name") val groupName: String? = null,
) {
    fun title(currentUserId: Long?): String = when {
        isGroup -> groupName ?: "Group call"
        callerId == currentUserId -> otherName ?: "Outgoing call"
        else -> callerName ?: "Incoming call"
    }
}

@Serializable data class TogglePinResponse(val pinned: Boolean)
@Serializable data class ToggleFavouriteResponse(val favourite: Boolean)
@Serializable data class ToggleMuteResponse(val muted: Boolean, val mutedUntil: String? = null)
@Serializable data class ToggleArchiveResponse(val archived: Boolean)
@Serializable data class MuteRequest(val duration: String? = null)

// HTTP chat endpoint contracts. Field names intentionally mirror the server's
// snake_case SQL projections and camelCase request/response envelopes.
@Serializable data class CreateGroupRequest(val name: String, val userIds: List<Long>)
@Serializable data class GroupUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val avatar: String? = null,
    val postPolicy: String? = null,
    val addPolicy: String? = null,
    val addUserIds: List<Long>? = null,
    val removeUserIds: List<Long>? = null,
)
@Serializable data class SetParticipantRoleRequest(val role: String)
@Serializable data class TransferOwnerRequest(val userId: Long)
@Serializable data class RoleResponse(val ok: Boolean = true, val role: String)
@Serializable data class UnreadResponse(val ok: Boolean = true, val unread: Boolean)
@Serializable data class BlockResponse(val ok: Boolean = true, val blocked: Boolean)

@Serializable data class SendMessageRequest(
    val content: String,
    val replyToId: Long? = null,
    val clientMsgId: String? = null,
)

@Serializable data class SharedChatFile(
    val id: Long,
    @SerialName("file_url") val fileUrl: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("sender_id") val senderId: Long,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("sender_avatar") val senderAvatar: String? = null,
)

@Serializable data class LinkPreview(
    val url: String,
    val title: String = "",
    val description: String = "",
    val image: String? = null,
    val siteName: String = "",
)

/** Web `ChatInputBar` `URL_RE`: the first http(s) URL in the draft. */
fun firstLinkIn(text: String): String? = Regex("https?://[^\\s<]+").find(text)?.value

/**
 * Web `useMessageActions` send path: the WS `chat_message` frame is the only
 * send route that carries `mentions` and a sender `linkPreview`.
 */
fun chatMessageEnvelope(
    conversationId: Long,
    content: String,
    clientMsgId: String,
    replyToId: Long?,
    mentions: Collection<Long>,
    linkPreview: LinkPreview?,
): RealtimeEnvelope = RealtimeEnvelope(
    type = "chat_message",
    data = buildJsonObject {
        put("conversationId", conversationId)
        put("content", content)
        put("clientMsgId", clientMsgId)
        replyToId?.let { put("replyToId", it) }
        if (mentions.isNotEmpty()) put("mentions", kotlinx.serialization.json.JsonArray(mentions.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        linkPreview?.let { put("linkPreview", Json.encodeToJsonElement(LinkPreview.serializer(), it)) }
    },
)

@Serializable data class ViewMessageResponse(val fileUrl: String? = null, val viewed: Boolean? = null)
@Serializable data class CallActionRequest(val conversationId: Long)
@Serializable data class CallActionResponse(val ok: Boolean = true, val status: String)
@Serializable data class DeleteCallsRequest(val ids: List<Long> = emptyList(), val all: Boolean = false)
@Serializable data class DeleteCallsResponse(val ok: Boolean = true, val deleted: Int)

@Serializable data class CreatePollRequest(
    val question: String,
    val options: List<String>,
    val multiSelect: Boolean = false,
)
@Serializable data class PollVoteRequest(val optionIdx: Int)
@Serializable data class PollVoter(val userId: Long, val fullName: String)
@Serializable data class ChatPoll(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("creator_id") val creatorId: Long,
    val question: String,
    val options: List<String>,
    @SerialName("multi_select") val multiSelect: Boolean = false,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val votes: Map<Int, List<PollVoter>> = emptyMap(),
)
@Serializable data class CreatePollResponse(val ok: Boolean = true, val poll: ChatPoll, val messageId: Long)
@Serializable data class PollVoteResponse(
    val ok: Boolean = true,
    val votes: Map<Int, List<Long>> = emptyMap(),
)

data class QueuedMessage(
    val clientMessageId: String,
    val conversationId: Long,
    val senderId: Long,
    val content: String,
    val createdAtEpochMs: Long,
)

/** Presentation model for a chronological native chat thread. */
sealed interface ThreadItem {
    val key: String

    data class DateSeparator(val date: LocalDate) : ThreadItem {
        override val key: String = "date-$date"
    }

    data class Message(
        val message: ChatMessage,
        val startsGroup: Boolean,
        val endsGroup: Boolean,
    ) : ThreadItem {
        override val key: String = "server-${message.id}"
    }

    data class Queued(val message: QueuedMessage) : ThreadItem {
        override val key: String = "queued-${message.clientMessageId}"
    }
}

/**
 * Key of the oldest unread incoming message (Signal-style "N unread messages"
 * divider sits directly above it). [newestFirst] is the reversed thread.
 */
fun unreadDividerKey(newestFirst: List<ThreadItem>, unread: Int, currentUserId: Long?): String? {
    if (unread <= 0) return null
    var seen = 0
    for (item in newestFirst) {
        if (item is ThreadItem.Message && item.message.senderId != currentUserId && ++seen == unread) return item.key
    }
    return null
}

/**
 * Build the visual thread without changing server chronology.
 * Consecutive messages group only when they share sender/day and are at most
 * five minutes apart. That keeps sender labels and bubble tails truthful.
 */
fun buildThreadItems(
    messages: List<ChatMessage>,
    queued: List<QueuedMessage>,
    currentUserId: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ThreadItem> {
    val positioned = buildList {
        messages.forEach { message ->
            add(PositionedThreadItem(parseEpoch(message.createdAt), message = message))
        }
        queued.filter { currentUserId == null || it.senderId == currentUserId }.forEach { message ->
            add(PositionedThreadItem(message.createdAtEpochMs, queued = message))
        }
    }.sortedWith(compareBy<PositionedThreadItem> { it.epochMs }.thenBy { it.message?.id ?: Long.MAX_VALUE })

    val result = mutableListOf<ThreadItem>()
    var currentDay: LocalDate? = null
    positioned.forEachIndexed { index, item ->
        val day = Instant.ofEpochMilli(item.epochMs).atZone(zoneId).toLocalDate()
        if (day != currentDay) {
            result += ThreadItem.DateSeparator(day)
            currentDay = day
        }
        val message = item.message
        if (message == null) {
            result += ThreadItem.Queued(requireNotNull(item.queued))
            return@forEachIndexed
        }
        val previous = positioned.getOrNull(index - 1)
        val next = positioned.getOrNull(index + 1)
        val startsGroup = !sameMessageGroup(previous, item, day, zoneId)
        val endsGroup = !sameMessageGroup(item, next, day, zoneId)
        result += ThreadItem.Message(message, startsGroup, endsGroup)
    }
    return result
}

private fun sameMessageGroup(
    first: PositionedThreadItem?,
    second: PositionedThreadItem?,
    day: LocalDate,
    zoneId: ZoneId,
): Boolean {
    val a = first?.message ?: return false
    val b = second?.message ?: return false
    return a.senderId == b.senderId &&
        Instant.ofEpochMilli(first.epochMs).atZone(zoneId).toLocalDate() == day &&
        Instant.ofEpochMilli(second.epochMs).atZone(zoneId).toLocalDate() == day &&
        second.epochMs - first.epochMs in 0..300_000L
}

private data class PositionedThreadItem(
    val epochMs: Long,
    val message: ChatMessage? = null,
    val queued: QueuedMessage? = null,
)

fun totalUnread(conversations: List<ChatConversation>): Int =
    conversations.sumOf { it.unreadCount.coerceAtLeast(0) }

fun forwardDestinations(
    conversations: List<ChatConversation>,
    query: String,
): List<ChatConversation> {
    val normalized = query.trim()
    return conversations.filter { conversation ->
        !conversation.isArchived && (
            normalized.isEmpty() || listOf(
                conversation.title(),
                conversation.otherUsername.orEmpty(),
                conversation.groupName.orEmpty(),
            ).joinToString(" ").contains(normalized, ignoreCase = true)
        )
    }
}

fun presenceLabel(value: ChatPresence?): String = when {
    value == null || value.presence != "online" -> "Offline"
    value.userStatus == "available" -> value.workMode?.replaceFirstChar(Char::uppercase) ?: "Online"
    else -> value.userStatus.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/**
 * Whether a realtime event invalidates the conversation list (A-100).
 *
 * Previously a hardcoded 8-item set that both missed 12 real chat events and
 * listed `chat_group_updated`, which the server never emits. It is now derived
 * from the registry: an event refreshes the list when its reaction policy says
 * so, which keeps this in lockstep with the parity guard.
 *
 * Ephemeral events (`chat_typing`) deliberately return false — a typing
 * indicator must never trigger a network reload.
 */
fun shouldRefreshConversationList(eventType: String): Boolean {
    val event = RealtimeEvent.from(eventType) ?: return false
    if (event.domain != RealtimeDomain.Chat) return false
    return when (event.reaction) {
        RealtimeReaction.Refetch, RealtimeReaction.PatchThenReconcile -> true
        else -> false
    }
}

fun reconcileQueuedMessages(
    queued: List<QueuedMessage>,
    messages: List<ChatMessage>,
    currentUserId: Long?,
): List<QueuedMessage> {
    if (currentUserId == null || queued.isEmpty()) return queued
    val authoritative = messages.filter { it.senderId == currentUserId }
        .map { runCatching { Instant.parse(it.createdAt).toEpochMilli() }.getOrDefault(0L) }
        .sorted()
        .toMutableList()
    return queued.sortedBy { it.createdAtEpochMs }.filter { pending ->
        val match = authoritative.indexOfFirst { it >= pending.createdAtEpochMs }
        if (match >= 0) {
            authoritative.removeAt(match)
            false
        } else true
    }
}
/** `POST /meetings` body for a group call (fields are explicit: chat JSON omits defaults). */
@Serializable
data class GroupCallRequest(
    val title: String,
    @SerialName("conversation_id") val conversationId: Long,
    val huddle: Boolean,
    val settings: GroupCallSettings,
)

@Serializable
data class GroupCallSettings(val allowScreenShare: Boolean, val callType: String)

@Serializable
data class CreatedGroupCall(val id: Long, @SerialName("meeting_code") val meetingCode: String)
