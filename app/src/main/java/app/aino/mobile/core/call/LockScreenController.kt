package app.aino.mobile.core.call

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi

enum class LockScreenWindowStrategy { ACTIVITY_API, LEGACY_FLAGS }

object LockScreenPolicy {
    fun windowStrategy(apiLevel: Int): LockScreenWindowStrategy =
        if (apiLevel >= 27) LockScreenWindowStrategy.ACTIVITY_API
        else LockScreenWindowStrategy.LEGACY_FLAGS
}

/**
 * Owns the temporary lock-screen window state used while a call UI is visible.
 *
 * This is the native counterpart of the legacy LockScreen Expo module. Callers
 * must pair [showForCall] with [hideAfterCall]; the capability is deliberately
 * not installed permanently on the application's main activity.
 */
class LockScreenController {
    private var wakeLock: PowerManager.WakeLock? = null

    fun showForCall(activity: Activity) = setShowingForCall(activity, true)

    fun hideAfterCall(activity: Activity) = setShowingForCall(activity, false)

    fun setShowingForCall(activity: Activity, enabled: Boolean) {
        activity.runOnUiThread {
            applyWindowPolicy(activity, enabled)
            if (enabled) forceScreenOn(activity) else releaseWakeLock()
        }
    }

    /** Releases the short wake lock even if the activity has already gone away. */
    fun release() = releaseWakeLock()

    @SuppressLint("NewApi") // LockScreenPolicy selects this branch only on API 27+.
    private fun applyWindowPolicy(activity: Activity, enabled: Boolean) {
        when (LockScreenPolicy.windowStrategy(Build.VERSION.SDK_INT)) {
            LockScreenWindowStrategy.ACTIVITY_API -> {
                applyActivityApi(activity, enabled)
            }
            LockScreenWindowStrategy.LEGACY_FLAGS -> {
                @Suppress("DEPRECATION")
                val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                if (enabled) activity.window.addFlags(flags) else activity.window.clearFlags(flags)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun applyActivityApi(activity: Activity, enabled: Boolean) {
        activity.setShowWhenLocked(enabled)
        activity.setTurnScreenOn(enabled)
    }

    /** Best-effort OEM-compatible wake-up, bounded so it cannot pin the display on. */
    private fun forceScreenOn(context: Context) {
        try {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (power.isInteractive) return
            releaseWakeLock()
            @Suppress("DEPRECATION")
            val lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG,
            )
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            wakeLock = lock
        } catch (_: Throwable) {
            // Lock-screen surfacing is best-effort and must never crash a call.
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
            // A vendor PowerManager failure must not affect call teardown.
        } finally {
            wakeLock = null
        }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MILLIS = 15_000L
        const val WAKE_LOCK_TAG = "AINO:CallScreenWake"
    }
}