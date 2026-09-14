package app.aino.mobile.core.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "conversations",
    primaryKeys = ["tenantId", "userId", "conversationId"],
    indices = [Index(value = ["tenantId", "userId", "updatedAtEpochMs"])],
)
data class ConversationEntity(
    val tenantId: Long,
    val userId: Long,
    val conversationId: Long,
    val title: String,
    val avatarUrl: String?,
    val unreadCount: Int,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["tenantId", "userId", "messageId"],
    indices = [Index(value = ["tenantId", "userId", "conversationId", "createdAtEpochMs"])],
)
data class MessageEntity(
    val tenantId: Long,
    val userId: Long,
    val messageId: Long,
    val conversationId: Long,
    val senderId: Long,
    val body: String,
    val createdAtEpochMs: Long,
    val deliveryState: String,
)

@Entity(
    tableName = "outbox",
    primaryKeys = ["tenantId", "userId", "clientMessageId"],
    indices = [Index(value = ["tenantId", "userId", "state", "nextAttemptAtEpochMs"])],
)
data class OutboxEntity(
    val tenantId: Long,
    val userId: Long,
    val clientMessageId: String,
    val conversationId: Long,
    val payloadJson: String,
    val state: String = "pending",
    val attempts: Int = 0,
    val nextAttemptAtEpochMs: Long = 0,
    val createdAtEpochMs: Long,
)

data class CacheScope(val tenantId: Long, val userId: Long) {
    init {
        require(tenantId > 0) { "A tenant-scoped cache requires a positive tenant id" }
        require(userId > 0) { "A tenant-scoped cache requires a positive user id" }
    }
}