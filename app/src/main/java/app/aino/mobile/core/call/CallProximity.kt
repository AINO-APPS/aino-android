package app.aino.mobile.core.call

import android.content.Context
import android.annotation.SuppressLint
import android.os.PowerManager

enum class CallAudioRoute { Earpiece, Speaker, Headset, Unknown }

fun shouldUseCallProximity(active: Boolean, videoActive: Boolean, route: CallAudioRoute): Boolean =
    active && !videoActive && route == CallAudioRoute.Earpiece

internal class CallProximity(context: Context) {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private val lock = power?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "aino:call-ear-screen")
        ?.apply { setReferenceCounted(false) }

    // Route/session teardown releases this lock; a timeout would light the screen during a long call.
    @SuppressLint("Wakelock", "WakelockTimeout")
    fun update(eligible: Boolean): Boolean {
        val wakeLock = lock ?: return false
        try {
            if (eligible && !wakeLock.isHeld) wakeLock.acquire()
            if (!eligible && wakeLock.isHeld) wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        } catch (error: RuntimeException) {
            android.util.Log.w("AinoCall", "Could not update proximity screen lock", error)
        }
        return wakeLock.isHeld
    }
}
