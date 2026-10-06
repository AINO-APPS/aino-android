package app.aino.mobile.core.db

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Room-like in-memory [AinoDao]: primary keys replace on upsert and every query honours the scope. */
class InMemoryAinoDao : AinoDao {
    val conversations = CopyOnWriteArrayList<ConversationEntity>()
    val messages = CopyOnWriteArrayList<MessageEntity>()

    override fun observeConversations(tenantId: Long, userId: Long): Flow<List<ConversationEntity>> = flowOf(conversationsOf(tenantId, userId))
    override suspend fun getConversations(tenantId: Long, userId: Long) = conversationsOf(tenantId, userId)
    override suspend fun upsertConversations(values: List<ConversationEntity>) {
        conversations.removeIf { row -> values.any { it.tenantId == row.tenantId && it.userId == row.userId && it.conversationId == row.conversationId } }
        conversations += values
    }
    override fun observeMessages(tenantId: Long, userId: Long, conversationId: Long): Flow<List<MessageEntity>> =
        flowOf(messagesOf(tenantId, userId, conversationId))
    override suspend fun getMessages(tenantId: Long, userId: Long, conversationId: Long) = messagesOf(tenantId, userId, conversationId)
    override suspend fun upsertMessages(values: List<MessageEntity>) {
        messages.removeIf { row -> values.any { it.tenantId == row.tenantId && it.userId == row.userId && it.messageId == row.messageId } }
        messages += values
    }
    override suspend fun clearConversationMessages(tenantId: Long, userId: Long, conversationId: Long) {
        messages.removeIf { it.tenantId == tenantId && it.userId == userId && it.conversationId == conversationId }
    }
    override suspend fun putOutbox(value: OutboxEntity) = Unit
    override suspend fun pendingOutbox(tenantId: Long, userId: Long, now: Long, limit: Int) = emptyList<OutboxEntity>()
    override suspend fun markOutboxRetry(tenantId: Long, userId: Long, clientMessageId: String, nextAttemptAt: Long) = Unit
    override suspend fun markOutboxFailed(tenantId: Long, userId: Long, clientMessageId: String) = Unit
    override suspend fun deleteOutbox(tenantId: Long, userId: Long, clientMessageId: String) = Unit
    override suspend fun deleteConversation(tenantId: Long, userId: Long, conversationId: Long) {
        conversations.removeIf { it.tenantId == tenantId && it.userId == userId && it.conversationId == conversationId }
    }
    override suspend fun clearConversations(tenantId: Long, userId: Long) {
        conversations.removeIf { it.tenantId == tenantId && it.userId == userId }
    }
    override suspend fun clearMessages(tenantId: Long, userId: Long) {
        messages.removeIf { it.tenantId == tenantId && it.userId == userId }
    }
    override suspend fun clearOutbox(tenantId: Long, userId: Long) = Unit

    private fun conversationsOf(tenantId: Long, userId: Long) =
        conversations.filter { it.tenantId == tenantId && it.userId == userId }.sortedByDescending { it.updatedAtEpochMs }

    private fun messagesOf(tenantId: Long, userId: Long, conversationId: Long) =
        messages.filter { it.tenantId == tenantId && it.userId == userId && it.conversationId == conversationId }.sortedBy { it.createdAtEpochMs }
}
