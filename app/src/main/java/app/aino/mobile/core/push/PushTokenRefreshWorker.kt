package app.aino.mobile.core.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signal's `FcmRefreshJob`: keeps the server's copy of this phone's FCM token
 * current even when the app is never opened. Runs daily, and soon after a failed
 * registration, so a dropped token heals without waiting for the next launch.
 */
class PushTokenRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(FORCE, false)
        if (PushTokenRegistrar(applicationContext).syncCurrentTokenNoRetry(force)) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "aino-push-token-refresh"
        private const val RETRY = "aino-push-token-retry"
        private const val FORCE = "force"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Daily re-registration (idempotent; keeps an already scheduled period). */
        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<PushTokenRefreshWorker>(24, TimeUnit.HOURS)
                    .setInputData(androidx.work.Data.Builder().putBoolean(FORCE, true).build())
                    .setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        /** A registration failed (offline, 5xx): retry with backoff once the network is up. */
        fun retrySoon(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<PushTokenRefreshWorker>()
                    .setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context).apply {
                    cancelUniqueWork(PERIODIC)
                    cancelUniqueWork(RETRY)
                }
            }
        }
    }
}
