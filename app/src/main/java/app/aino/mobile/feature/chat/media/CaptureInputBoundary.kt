package app.aino.mobile.feature.chat

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * What the call boundary must do for one frame of the chat thread. Kept pure and
 * platform-free so the PiP transitions are unit-testable without an activity: a
 * Compose UI test cannot observe a host-state-driven recomposition, and this is
 * the logic that used to keep the keyboard over a call.
 */
data class CallEntryDecision(val endEntry: Boolean, val hideIme: Boolean)

object CallEntryPolicy {
    /**
     * [surfaceCovering] - an incoming call, an active 1:1 call or a meeting is
     * over the chat. [inPip] - the last state the activity reported for
     * picture-in-picture, so both edges of a PiP transition are covered: a call
     * left in PiP is still a call and must not hand the keyboard back when the
     * window returns, and the release must not restore it either.
     */
    fun decide(active: Boolean, surfaceCovering: Boolean, inPip: Boolean): CallEntryDecision = when {
        !active -> CallEntryDecision(endEntry = false, hideIme = false)
        // A call surface always ends entry; the IME is hidden only once the
        // window is back in view, because requesting a hide while the activity
        // is in PiP is ignored by the system anyway.
        surfaceCovering || inPip -> CallEntryDecision(endEntry = true, hideIme = true)
        else -> CallEntryDecision(endEntry = true, hideIme = false)
    }
}

/**
 * Signal's `stopInput`: whatever takes the screen away from the editor ends text
 * entry first - the camera, the gallery, or a call arriving over a half-typed
 * message. Leaving it running paints the keyboard over the incoming/active call.
 */
@Composable
internal fun CaptureInputBoundary(active: Boolean) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) {
        if (active) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}

/**
 * The same boundary for a call surface, plus the one transition a Compose flag
 * cannot see on its own: the activity coming back out of picture-in-picture.
 * See [CallEntryPolicy] for what each frame demands.
 */
@Composable
internal fun CallEntryBoundary(
    activity: Activity?,
    surfaceCovering: Boolean,
    active: Boolean,
    inPip: Boolean,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The key includes the PiP transition itself: entering the last or leaving it
    // re-runs this effect, so the release cannot come back holding the keyboard.
    val pipTransition = if (inPip) "enter" else "leave"
    LaunchedEffect(active, surfaceCovering, pipTransition) {
        val decision = CallEntryPolicy.decide(active, surfaceCovering, inPip)
        if (!decision.endEntry) return@LaunchedEffect
        focus.clearFocus(force = true)
        if (decision.hideIme) keyboard?.hide()
        activity?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
    }
    // The call is over while this activity sits in system PiP: the PiP window and
    // the chat's task would be left as a permanent floating window running the
    // chat list, so the activity comes forward. This is the only exit that does
    // not go through the call screen's own "close".
    LaunchedEffect(surfaceCovering, inPip) {
        if (app.aino.mobile.core.call.CallPipExit.shouldRestore(active, surfaceCovering, inPip)) {
            activity?.let { app.aino.mobile.core.call.CallPipExit.restoreAfterCall(it) }
        }
    }
}