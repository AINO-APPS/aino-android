package app.aino.mobile.core.call

import android.app.Activity
import android.view.WindowManager

/**
 * Keeps the display on for as long as a call is live: a 1:1 call (audio or video, from
 * dialing until hang-up), a group call / meeting, or an incoming call that is still ringing.
 * Without it the system screen timeout blanks the screen mid-call when nobody touches it.
 */
object CallScreenAwake {
    fun shouldKeepScreenOn(call: ActiveCallUi, meetingActive: Boolean, incomingRinging: Boolean): Boolean =
        (call.visible && call.endMessage == null) || meetingActive || incomingRinging

    /** Applies [FLAG_KEEP_SCREEN_ON][WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]; no wake lock permission needed. */
    fun apply(activity: Activity, keepOn: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keepOn) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }
}
