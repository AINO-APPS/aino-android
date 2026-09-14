package app.aino.mobile.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AinoDao {
    @Query("SELECT * FROM conversations WHERE tenantId = :tenantId AND userId = :userId ORDER BY updatedAtEpochMs DESC")
    fun observeConversations(tenantId: Long, userId: Long): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(values: List<ConversationEntity>)

    @Query("SELECT * FROM messages WHERE tenantId = :tenantId AND userId = :userId AND conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeMessages(tenantId: Long, userId: Long, conversationId: Long): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(values: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutbox(value: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE tenantId = :tenantId AND userId = :userId AND state = 'pending' AND nextAttemptAtEpochMs <= :now ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun pendingOutbox(tenantId: Long, userId: Long, now: Long, limit: Int): List<OutboxEntity>

    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptAtEpochMs = :nextAttemptAt WHERE tenantId = :tenantId AND userId = :userId AND clientMessageId = :clientMessageId")
    suspend fun markOutboxRetry(tenantId: Long, userId: Long, clientMessageId: String, nextAttemptAt: Long)

    @Query("UPDATE outbox SET state = 'failed' WHERE tenantId = :tenantId AND userId = :userId AND clientMessageId = :clientMessageId")
    suspend fun markOutboxFailed(tenantId: Long, userId: Long, clientMessageId: String)

    @Query("DELETE FROM outbox WHERE tenantId = :tenantId AND userId = :userId AND clientMessageId = :clientMessageId")
    suspend fun deleteOutbox(tenantId: Long, userId: Long, clientMessageId: String)

    @Query("DELETE FROM conversations WHERE tenantId = :tenantId AND userId = :userId")
    suspend fun clearConversations(tenantId: Long, userId: Long)

    @Query("DELETE FROM messages WHERE tenantId = :tenantId AND userId = :userId")
    suspend fun clearMessages(tenantId: Long, userId: Long)

    @Query("DELETE FROM outbox WHERE tenantId = :tenantId AND userId = :userId")
    suspend fun clearOutbox(tenantId: Long, userId: Long)

    @Transaction
    suspend fun clearScope(tenantId: Long, userId: Long) {
        clearOutbox(tenantId, userId)
        clearMessages(tenantId, userId)
        clearConversations(tenantId, userId)
    }
}