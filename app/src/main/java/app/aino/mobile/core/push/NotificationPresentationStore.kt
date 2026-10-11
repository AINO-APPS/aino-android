package app.aino.mobile.core.push

import android.content.Context

/** Receipt acceptance never closes a retryable blocked/failed presentation. */
internal class NotificationPresentationStore(context: Context, private val account: String) {
    private val prefs = context.getSharedPreferences("aino_notification_delivery", Context.MODE_PRIVATE)

    fun handled(key: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong("$account:$key", 0).let { it > 0 && nowMs - it <= 86_400_000 }

    fun record(key: String, outcome: PostingOutcome, nowMs: Long = System.currentTimeMillis()) {
        if (outcome != PostingOutcome.Posted && outcome !is PostingOutcome.Suppressed) return
        val retained = retainedDedupeEntries(prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap(), nowMs)
        prefs.edit().clear().apply {
            retained.forEach { (k, v) -> putLong(k, v) }
            putLong("$account:$key", nowMs)
        }.commit()
    }
}
