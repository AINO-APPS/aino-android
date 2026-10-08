package app.aino.mobile.core.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * App-wide haptic vocabulary. Uses [View.performHapticFeedback], so it follows the
 * system "touch feedback" setting and needs no VIBRATE permission.
 */
@Stable
class AinoHaptics internal constructor(private val view: View) {
    /** Light tap for ordinary buttons and tab switches. */
    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** On/off controls (mute, camera, speaker, member pick). */
    fun toggle() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.KEYBOARD_TAP,
    )

    /** A completed positive action (send, accept, create, clock in). */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** A negative / destructive action (hang up, decline, delete). */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Crossing a threshold (swipe action armed, segment snap). */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE else HapticFeedbackConstants.KEYBOARD_TAP,
    )

    private fun perform(constant: Int) {
        runCatching { view.performHapticFeedback(constant) }
    }
}

@Composable
fun rememberAinoHaptics(): AinoHaptics {
    val view = LocalView.current
    return remember(view) { AinoHaptics(view) }
}
