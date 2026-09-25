package app.aino.mobile.core.db

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class OutboxMessagePayload(val content: String, val replyToId: Long? = null)

@Serializable
private data class ChatSendRequest(
    val content: String,
    val clientMsgId: String,
    val replyToId: Long? = null,
)

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scope = runCatching {
            CacheScope(inputData.getLong(TENANT_ID, 0), inputData.getLong(USER_ID, 0))
        }.getOrElse { return@withContext Result.failure() }
        val container = app.aino.mobile.core.AppContainer.get(applicationContext)
        if (container.tokens.getToken().isNullOrBlank()) return@withContext Result.failure()
        val api = container.api
        val dao = AinoDatabase.get(applicationContext).dao()
        val now = System.currentTimeMillis()
        val pending = dao.pendingOutbox(scope.tenantId, scope.userId, now, 50)
        var shouldRetry = false

        for (item in pending) {
            val decoded = runCatching { JSON.decodeFromString<OutboxMessagePayload>(item.payloadJson) }
            if (decoded.isFailure) {
                dao.markOutboxFailed(scope.tenantId, scope.userId, item.clientMessageId)
                continue
            }
            val payload = decoded.getOrThrow()
            val body = JSON.encodeToString(ChatSendRequest(payload.content, item.clientMessageId, payload.replyToId)).toByteArray()
            val status = try {
                api.execute(ApiRequest("POST", "chat/conversations/${item.conversationId}/messages", body = body)).statusCode
            } catch (error: ApiError.Http) {
                error.statusCode
            } catch (_: Exception) {
                null
            }
            when (val decision = deliveryDecision(status, item.attempts, now)) {
                DeliveryDecision.Delivered -> dao.deleteOutbox(scope.tenantId, scope.userId, item.clientMessageId)
                DeliveryDecision.Failed -> dao.markOutboxFailed(scope.tenantId, scope.userId, item.clientMessageId)
                is DeliveryDecision.Retry -> {
                    dao.markOutboxRetry(scope.tenantId, scope.userId, item.clientMessageId, decision.nextAttemptAtEpochMs)
                    shouldRetry = true
                }
            }
        }
        if (shouldRetry) Result.retry() else Result.success()
    }

    companion object {
        private const val TENANT_ID = "tenant_id"
        private const val USER_ID = "user_id"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun enqueue(context: Context, scope: CacheScope) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setInputData(Data.Builder().putLong(TENANT_ID, scope.tenantId).putLong(USER_ID, scope.userId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "aino-outbox-${scope.tenantId}-${scope.userId}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, scope: CacheScope) {
            WorkManager.getInstance(context).cancelUniqueWork("aino-outbox-${scope.tenantId}-${scope.userId}")
        }
    }
}