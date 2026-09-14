package app.aino.mobile.core.db

import android.content.Context
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OutboxCoordinator(
    private val context: Context,
    private val database: AinoDatabase = AinoDatabase.get(context),
    private val json: Json = Json,
) {
    suspend fun enqueueText(
        scope: CacheScope,
        conversationId: Long,
        content: String,
        replyToId: Long? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        clientMessageId: String = UUID.randomUUID().toString(),
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
        )
        ScopedCache(scope, database.dao()).enqueue(entity)
        OutboxWorker.enqueue(context, scope)
        return clientMessageId
    }
}