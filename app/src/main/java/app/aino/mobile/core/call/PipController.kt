package app.aino.mobile.core.call

import android.app.Activity
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi

/**
 * Native call PiP controller, with no Expo/JS bridge ownership.
 *
 * The activity owner forwards its user-leave and PiP-mode callbacks to
 * [onUserLeaveHint] and [onPictureInPictureModeChanged]. API 31+ uses Android's
 * seamless auto-enter; API 26–30 explicitly enters from the user-leave callback.
 */
class PipController(
    private val onModeChanged: (Boolean) -> Unit = {},
) {
    @Volatile
    var isCallActive: Boolean = false
        private set

    private var aspectRatio = PipRatio(1, 1)

    fun isSupported(activity: Activity): Boolean = PipPolicy.isSupported(
        Build.VERSION.SDK_INT,
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
    )

    fun setCallActive(
        activity: Activity,
        active: Boolean,
        aspectWidth: Int,
        aspectHeight: Int,
    ) {
        isCallActive = active
        aspectRatio = PipPolicy.safeRatio(aspectWidth, aspectHeight)
        setAutoEnter(activity, active)
    }

    fun enter(activity: Activity, aspectWidth: Int, aspectHeight: Int): Boolean {
        if (!isSupported(activity)) return false
        val ratio = PipPolicy.safeRatio(aspectWidth, aspectHeight)
        aspectRatio = ratio
        return try {
            activity.runOnUiThread {
                activity.enterPictureInPictureMode(buildParams(ratio))
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("NewApi") // PipPolicy uses this path only on API 31+.
    fun setAutoEnter(activity: Activity, enabled: Boolean): Boolean {
        if (!isSupported(activity) || !PipPolicy.usesSystemAutoEnter(Build.VERSION.SDK_INT)) {
            return false
        }
        return try {
            activity.runOnUiThread {
                applyAutoEnter(activity, enabled)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyAutoEnter(activity: Activity, enabled: Boolean) {
        activity.setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio.toRational())
                .setAutoEnterEnabled(enabled)
                .build(),
        )
    }

    fun onUserLeaveHint(activity: Activity): Boolean {
        val advertised = activity.packageManager.hasSystemFeature(
            PackageManager.FEATURE_PICTURE_IN_PICTURE,
        )
        if (!PipPolicy.shouldEnterOnUserLeave(
                Build.VERSION.SDK_INT,
                advertised,
                isCallActive,
            )
        ) return false
        return enter(activity, aspectRatio.width, aspectRatio.height)
    }

    fun onPictureInPictureModeChanged(isInPip: Boolean) {
        try {
            onModeChanged(isInPip)
        } catch (_: Throwable) {
            // Activity lifecycle callbacks must remain crash-safe.
        }
    }

        /**
         * Leaving PiP must also disarm auto-enter. Leaving it enabled bounced the
         * activity straight back into a PiP window, which the user saw as the app
         * still being stuck in PiP after the call ended.
         */
        fun onPipExited(activity: Activity, isInPip: Boolean) {
            if (!isInPip) setAutoEnter(activity, false)
        }

    fun clear(activity: Activity) {
        isCallActive = false
        setAutoEnter(activity, false)
    }

    private fun buildParams(ratio: PipRatio): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(ratio.toRational()).build()

    private fun PipRatio.toRational() = Rational(width, height)
}

/** Whether the activity is currently in system PiP; call/meeting screens drop their chrome. */
object PipState {
    val inPip = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * Leaving picture-in-picture when there is no call screen left to close.
 *
 * The call surfaces are the only places that finish a call, so a call that ended
 * while the activity sat in PiP used to leave the PiP window - and the task
 * behind it - floating over the launcher, showing the chat list, with nothing
 * alive that would ever bring it back. Signal restores the conversation from its
 * PiP activity's `onPictureInPictureModeChanged`; here the same restoration is
 * asked of the activity itself.
 */
object CallPipExit {
    /**
     * True when this frame is the one that must be pulled out of PiP: the
     * activity is in PiP and no call surface is left (the call screen went away
     * without closing the PiP window).
     *
     * Deliberately independent of the thread's RESUMED state: a PiP window is
     * never "resumed" in the way a full-screen activity is, and gating on that
     * was what left the window stranded.
     */
    fun shouldRestore(surfaceCovering: Boolean, inPip: Boolean): Boolean = inPip && !surfaceCovering

    /**
     * Leaves system PiP with the chat restored to full screen. Every step is
     * best-effort and independent: the window is gone as soon as the activity
     * comes forward, whichever of these the OEM actually honours.
     */
    fun restoreAfterCall(activity: Activity) {
        clearAutoEnter(activity)
        runCatching { activity.startActivity(activity.intent) }
        runCatching { activity.moveTaskToBack(false) }
    }

    /** Auto-enter must be off before leaving PiP, or the activity bounces straight back in. */
    fun clearAutoEnter(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
            )
        }
    }
}
