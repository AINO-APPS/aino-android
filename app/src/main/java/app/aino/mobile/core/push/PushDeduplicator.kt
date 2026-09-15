package app.aino.mobile.core.push

import android.content.Context

class PushDeduplicator(context: Context) {
    private val prefs = context.getSharedPreferences("aino_push_dedupe", Context.MODE_PRIVATE)

    @Synchronized
    fun accept(key: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        require(key.isNotBlank())
        val cutoff = nowEpochMs - TTL_MS
        val current = retainedDedupeEntries(
            prefs.all.mapNotNull { (storedKey, value) -> (value as? Long)?.let { storedKey to it } }.toMap(),
            nowEpochMs,
            MAX_KEYS - 1,
        )
        if (key in current) return false
        prefs.edit().clear().apply {
            current.forEach { (storedKey, timestamp) -> putLong(storedKey, timestamp) }
            putLong(key, nowEpochMs)
        }.apply()
        return true
    }

    private companion object {
        const val MAX_KEYS = 256
        const val TTL_MS = 24 * 60 * 60 * 1_000L
    }
}

fun retainedDedupeEntries(values: Map<String, Long>, nowEpochMs: Long, limit: Int = 255): Map<String, Long> {
    require(limit >= 0)
    val cutoff = nowEpochMs - 24 * 60 * 60 * 1_000L
    return values.filterValues { it >= cutoff }.entries.sortedByDescending { it.value }.take(limit).associate { it.toPair() }
}