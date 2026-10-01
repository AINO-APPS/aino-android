package app.aino.mobile.core.db

import android.content.Context
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable side of the chat text send path. The in-process per-conversation
 * send queue owns a row while it is leased (its `nextAttemptAtEpochMs` is far in
 * the future, so [OutboxWorker] skips it and can't overtake an earlier media send).
 * The queue keeps retrying until it delivers; only when that queue is gone
 * (process death, ViewModel cleared, sign-in change) does [recover] release the
 * row to the worker.
 */
interface ChatOutbox {
    suspend fun persist(scope: CacheScope, conversationId: Long, content: String, replyToId: Long?, nowEpochMs: Long, clientMessageId: String)
    suspend fun delivered(scope: CacheScope, clientMessageId: String)
    /** Releases rows leased by a previous process (their in-memory queue is gone) to the worker. */
    suspend fun recover(scope: CacheScope)

    object None : ChatOutbox {
        override suspend fun persist(scope: CacheScope, conversationId: Long, content: String, replyToId: Long?, nowEpochMs: Long, clientMessageId: String) = Unit
        override suspend fun delivered(scope: CacheScope, clientMessageId: String) = Unit
        override suspend fun recover(scope: CacheScope) = Unit
    }
}

class OutboxCoordinator(
    private val context: Context,
    private val database: AinoDatabase = AinoDatabase.get(context),
    private val json: Json = Json,
) : ChatOutbox {
    /** [leaseMs] > 0: the caller sends the row itself; the worker is not woken and skips it until the lease ends. */
    suspend fun enqueueText(
        scope: CacheScope,
        conversationId: Long,
        content: String,
        replyToId: Long? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        clientMessageId: String = UUID.randomUUID().toString(),
        leaseMs: Long = 0,
    ): String {
        require(conversationId > 0) { "Conversation id must be positive" }
        val normalized = content.trim()
        require(normalized.isNotEmpty()) { "Message content is required" }
        require(normalized.length <= 5_000) { "Message content must not exceed 5000 characters" }
        require(clientMessageId.length in 1..64) { "Client message id must be 1-64 characters" }
        val entity = OutboxEntity(
            tenantId = scope.tenantId,
            userId = scope.userId,
            clientMessageId = clientMessageId,
            conversationId = conversationId,
            payloadJson = json.encodeToString(OutboxMessagePayload(normalized, replyToId)),
            createdAtEpochMs = nowEpochMs,
            nextAttemptAtEpochMs = if (leaseMs > 0) nowEpochMs + leaseMs else 0,
        )
        ScopedCache(scope, database.dao()).enqueue(entity)
        if (leaseMs <= 0) OutboxWorker.enqueue(context, scope)
        return clientMessageId
    }

    override suspend fun persist(scope: CacheScope, conversationId: Long, content: String, replyToId: Long?, nowEpochMs: Long, clientMessageId: String) {
        enqueueText(scope, conversationId, content, replyToId, nowEpochMs, clientMessageId, leaseMs = IN_PROCESS_LEASE_MS)
    }

    override suspend fun delivered(scope: CacheScope, clientMessageId: String) =
        database.dao().deleteOutbox(scope.tenantId, scope.userId, clientMessageId)

    override suspend fun recover(scope: CacheScope) {
        val now = System.currentTimeMillis()
        // Worker backoff never exceeds 15 min, so only in-process leases sit this far out.
        val leased = ScopedCache(scope, database.dao()).pending(Long.MAX_VALUE, limit = 100)
            .filter { it.nextAttemptAtEpochMs > now + LEASE_THRESHOLD_MS }
        // Re-put (not markOutboxRetry): releasing a lease must not spend the worker's retry budget.
        leased.forEach { database.dao().putOutbox(it.copy(nextAttemptAtEpochMs = now)) }
        if (leased.isNotEmpty()) OutboxWorker.enqueue(context, scope)
    }

    companion object {
        /** Effectively unbounded: the in-process queue holds the row until it delivers or [recover] releases it. */
        const val IN_PROCESS_LEASE_MS = 365L * 24 * 60 * 60_000L
        private const val LEASE_THRESHOLD_MS = 24 * 60 * 60_000L
    }
}
