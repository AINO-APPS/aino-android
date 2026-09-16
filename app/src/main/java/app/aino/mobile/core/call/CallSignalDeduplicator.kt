package app.aino.mobile.core.call

/** Bounded insertion-ordered set for reliable-delivery call signal identifiers. */
class CallSignalDeduplicator(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<String>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun remember(signalId: String?): Boolean {
        if (signalId.isNullOrBlank()) return true
        if (!seen.add(signalId)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }

    @Synchronized
    fun forget(signalId: String?) {
        if (!signalId.isNullOrBlank()) seen.remove(signalId)
    }

    @Synchronized
    fun clear() = seen.clear()
}