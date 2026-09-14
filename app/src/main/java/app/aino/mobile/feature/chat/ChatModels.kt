package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.ConversationEntity
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

@Serializable
data class DirectConversationRequest(val userId: Long)

@Serializable
data class ConversationCreated(@SerialName("conversationId") val conversationId: Long)

@Serializable
data class ChatOk(val ok: Boolean = true)

fun totalUnread(conversations: List<ChatConversation>): Int =
    conversations.sumOf { it.unreadCount.coerceAtLeast(0) }

fun presenceLabel(value: ChatPresence?): String = when {
    value == null || value.presence != "online" -> "Offline"
    value.userStatus == "available" -> value.workMode?.replaceFirstChar(Char::uppercase) ?: "Online"
    else -> value.userStatus.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

fun shouldRefreshConversationList(eventType: String): Boolean = eventType in setOf(
    "chat_message",
    "chat_group_created",
    "chat_group_added",
    "chat_group_removed",
    "chat_group_updated",
    "chat_conv_deleted",
    "chat_conv_archived",
    "chat_conv_muted",
)