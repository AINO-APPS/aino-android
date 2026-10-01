package app.aino.mobile.feature.chat

internal const val THREAD_PAGE_SIZE = 50

/** Non-critical thread requests (receipts, pins, members) wait for the open transition to settle. */
internal const val THREAD_SETTLE_MS = 250L

/** Top conversations whose cached thread is preloaded into memory after the list loads. */
internal const val WARM_THREADS = 8

/** Messages kept per thread in memory, and read back from disk when a thread opens cold. */
internal const val CACHED_THREAD_MESSAGES = 100

/** Older history is requested once the top of the thread is this many rows away. */
internal const val OLDER_PREFETCH_ITEMS = 15

data class CachedThread(val messages: List<ChatMessage>, val receipts: List<ReadReceipt> = emptyList())

/**
 * Last opened threads kept in memory (Signal reopens a conversation from its
 * message cache), so a thread paints on the first frame of the transition.
 */
class ThreadMessageCache(private val maxThreads: Int = 20, private val maxMessages: Int = CACHED_THREAD_MESSAGES) {
    private val threads = object : LinkedHashMap<Long, CachedThread>(maxThreads, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedThread>) = size > maxThreads
    }

    @Synchronized fun get(conversationId: Long): CachedThread? = threads[conversationId]

    @Synchronized fun contains(conversationId: Long): Boolean = threads.containsKey(conversationId)

    @Synchronized fun put(conversationId: Long, messages: List<ChatMessage>, receipts: List<ReadReceipt>? = null) {
        if (messages.isEmpty()) {
            threads.remove(conversationId)
            return
        }
        val kept = if (messages.size > maxMessages) messages.subList(messages.size - maxMessages, messages.size).toList() else messages
        threads[conversationId] = CachedThread(kept, receipts ?: threads[conversationId]?.receipts.orEmpty())
    }

    @Synchronized fun putReceipts(conversationId: Long, receipts: List<ReadReceipt>) {
        threads[conversationId]?.let { threads[conversationId] = it.copy(receipts = receipts) }
    }

    @Synchronized fun clear() = threads.clear()
}

/**
 * [next] with every unchanged message replaced by its instance from [current]
 * (and [current] itself when nothing changed), so Compose skips those rows.
 */
fun reuseUnchanged(current: List<ChatMessage>, next: List<ChatMessage>): List<ChatMessage> {
    if (current.isEmpty() || next.isEmpty()) return next
    val byId = current.associateBy(ChatMessage::id)
    var changed = next.size != current.size
    val merged = next.mapIndexed { index, message ->
        val old = byId[message.id]
        val kept = if (old == message) old else message
        if (!changed && current[index] !== kept) changed = true
        kept
    }
    return if (changed) merged else current
}

/**
 * Folds the newest page into the open thread without dropping older pages
 * already scrolled in. A full page that does not reach the loaded rows may
 * leave a gap, so the thread restarts from that page.
 */
fun mergeLatestPage(current: List<ChatMessage>, latest: List<ChatMessage>, pageSize: Int = THREAD_PAGE_SIZE): List<ChatMessage> {
    if (latest.isEmpty()) return latest
    val oldestLatest = latest.minOf(ChatMessage::id)
    val contiguous = latest.size >= pageSize && current.any { it.id >= oldestLatest }
    val older = if (contiguous) current.filter { it.id < oldestLatest } else emptyList()
    return reuseUnchanged(current, older + latest)
}

/**
 * An older page requested before [before] only joins [current] while that row is
 * still loaded; if the thread was reset meanwhile it would leave a permanent gap.
 */
fun canMergeOlderPage(current: List<ChatMessage>, before: Long): Boolean = current.any { it.id == before }

/**
 * Replaces the rows older than [before] with the server's [page]. A short page
 * means the start of history, so stale cached rows beyond it are dropped.
 */
fun mergeOlderPage(current: List<ChatMessage>, page: List<ChatMessage>, before: Long, pageSize: Int = THREAD_PAGE_SIZE): List<ChatMessage> {
    val windowStart = if (page.size >= pageSize) page.minOf(ChatMessage::id) else Long.MIN_VALUE
    return reuseUnchanged(
        current,
        current.filter { it.id < windowStart } + page.filter { it.id < before } + current.filter { it.id >= before },
    )
}

/**
 * Keys of rows that arrived at the newest end after the thread's first
 * layout (Signal's item animator only animates live inserts). The first page,
 * older pages and large catch-up bursts never animate.
 */
class ThreadArrivals(private val burstLimit: Int = 20) {
    private var known: Set<String>? = null
    private val arrived = HashSet<String>()

    fun update(newestFirstKeys: List<String>): Set<String> {
        val previous = known
        known = newestFirstKeys.toHashSet()
        if (!previous.isNullOrEmpty()) {
            val fresh = newestFirstKeys.takeWhile { it !in previous }
            if (fresh.size <= burstLimit) arrived += fresh
        }
        arrived.retainAll(known.orEmpty())
        return arrived.toSet()
    }
}

/** LazyColumn content type so composition slots are reused between rows of the same shape. */
fun ThreadItem.contentType(currentUserId: Long?): Int = when (this) {
    is ThreadItem.DateSeparator -> 0
    is ThreadItem.Queued -> 1
    is ThreadItem.PendingUpload -> 2
    is ThreadItem.Message -> when {
        message.formatType == "system" -> 3
        message.formatType == "meeting" -> 4
        else -> 5 + (if (message.senderId == currentUserId) 1 else 0) + (if (message.fileUrl.isNullOrBlank()) 0 else 2)
    }
}
