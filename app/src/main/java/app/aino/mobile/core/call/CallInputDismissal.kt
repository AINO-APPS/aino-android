package app.aino.mobile.core.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a call surface is on screen and every editor must therefore let go of
 * text entry.
 *
 * This lives in `core` rather than in the chat because a ring is a shell-level
 * overlay: `IncomingCallScreen` can appear while the chat composer is not
 * composed at all (mid-navigation, or with the thread behind the ring). A
 * composer-local boundary missed the ring entirely, which is why the keyboard
 * and the emoji drawer stayed painted over the incoming call.
 *
 * The call surfaces raise it; every editor observes it.
 */
object CallInputDismissal {
    private val _requested = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested

    /** A call surface is on screen: every editor must close its drawer and drop focus. */
    fun request() {
        _requested.value = true
    }

    /** The call surface is gone. */
    fun release() {
        _requested.value = false
    }
}