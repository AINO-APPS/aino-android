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

    fun clear(activity: Activity) {
        isCallActive = false
        setAutoEnter(activity, false)
    }

    private fun buildParams(ratio: PipRatio): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(ratio.toRational()).build()

    private fun PipRatio.toRational() = Rational(width, height)
}