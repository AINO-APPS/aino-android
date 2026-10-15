package app.aino.mobile.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Signal screen lock: after the app has been in the background for the chosen
 * timeout, the next return asks for the fingerprint / face / phone PIN.
 * A ringing or ongoing call never locks (the call UI must stay reachable).
 */
object AppLock {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile private var backgroundedAt: Long? = null
    /** First foreground of the process counts as a return from "forever" in the background. */
    @Volatile private var started = false

    internal fun shouldLock(enabled: Boolean, timeoutMs: Long, backgroundedAtMs: Long?, nowMs: Long, inCall: Boolean): Boolean =
        enabled && !inCall && (backgroundedAtMs == null || nowMs - backgroundedAtMs >= timeoutMs)

    fun onBackground(nowMs: Long = System.currentTimeMillis()) {
        if (!_locked.value) backgroundedAt = nowMs
    }

    fun onForeground(prefs: ChatPrefs, inCall: Boolean, signedIn: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val since = if (started) backgroundedAt else null
        started = true
        if (signedIn && shouldLock(prefs.screenLock, prefs.screenLockTimeoutMs, since, nowMs, inCall)) _locked.value = true
        backgroundedAt = null
    }

    fun unlock() { _locked.value = false }

    /** Turning the lock off or signing out must not leave the app locked. */
    fun reset() { _locked.value = false; backgroundedAt = null }
}
