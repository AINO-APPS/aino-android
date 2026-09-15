package app.aino.mobile.core.db

import kotlinx.coroutines.flow.Flow

/** Prevents feature code from issuing unscoped cache queries by construction. */
class ScopedCache(private val scope: CacheScope, private val dao: AinoDao) {
    fun conversations(): Flow<List<ConversationEntity>> = dao.observeConversations(scope.tenantId, scope.userId)

    suspend fun conversationSnapshot(): List<ConversationEntity> =
        dao.getConversations(scope.tenantId, scope.userId)

    fun messages(conversationId: Long): Flow<List<MessageEntity>> {
        require(conversationId > 0)
        return dao.observeMessages(scope.tenantId, scope.userId, conversationId)
    }

    suspend fun messageSnapshot(conversationId: Long): List<MessageEntity> {
        require(conversationId > 0)
        return dao.getMessages(scope.tenantId, scope.userId, conversationId)
    }

    suspend fun upsertConversations(values: List<ConversationEntity>) {
        require(values.all(::belongsToScope))
        dao.upsertConversations(values)
    }

    suspend fun replaceConversations(values: List<ConversationEntity>) {
        require(values.all(::belongsToScope))
        dao.replaceConversations(scope.tenantId, scope.userId, values)
    }

    suspend fun upsertMessages(values: List<MessageEntity>) {
        require(values.all(::belongsToScope))
        dao.upsertMessages(values)
    }

    suspend fun replaceMessages(conversationId: Long, values: List<MessageEntity>) {
        require(conversationId > 0)
        require(values.all { belongsToScope(it) && it.conversationId == conversationId })
        dao.replaceMessages(scope.tenantId, scope.userId, conversationId, values)
    }

    suspend fun enqueue(value: OutboxEntity) {
        require(belongsToScope(value))
        require(value.clientMessageId.isNotBlank())
        dao.putOutbox(value)
    }

    suspend fun pending(now: Long, limit: Int = 50): List<OutboxEntity> {
        require(limit in 1..100)
        return dao.pendingOutbox(scope.tenantId, scope.userId, now, limit)
    }

    suspend fun clear() = dao.clearScope(scope.tenantId, scope.userId)

    private fun belongsToScope(value: ConversationEntity) = value.tenantId == scope.tenantId && value.userId == scope.userId
    private fun belongsToScope(value: MessageEntity) = value.tenantId == scope.tenantId && value.userId == scope.userId
    private fun belongsToScope(value: OutboxEntity) = value.tenantId == scope.tenantId && value.userId == scope.userId
}