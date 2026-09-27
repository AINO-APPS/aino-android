package app.aino.mobile.core.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The conversation the user is looking at right now (Signal
 * `MessageNotifier.setVisibleThread` / `clearVisibleThread`). Set while the
 * thread screen is resumed, so a backgrounded app notifies normally again.
 */
object VisibleThread {
    @Volatile var conversationId: Long? = null
        private set

    private val _suppressed = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Conversations whose push was swallowed because they were on screen; the thread marks them read. */
    val suppressed: SharedFlow<Long> = _suppressed.asSharedFlow()

    fun set(conversationId: Long) { this.conversationId = conversationId }

    /** Clears only if [conversationId] is still the visible one, so a late pause can't clear a newer thread. */
    fun clear(conversationId: Long? = null) {
        if (conversationId == null || this.conversationId == conversationId) this.conversationId = null
    }

    fun isVisible(conversationId: Long): Boolean = this.conversationId == conversationId

    internal fun reportSuppressed(conversationId: Long) { _suppressed.tryEmit(conversationId) }
}
