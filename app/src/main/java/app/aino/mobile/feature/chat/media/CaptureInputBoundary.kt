package app.aino.mobile.feature.chat

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Signal's `stopInput`: whatever takes the screen away from the editor ends text
 * entry first - the camera, the gallery, or a call arriving over a half-typed
 * message.
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
 * The editor side of a call taking the screen. The chat has TWO keyboards: the
 * system IME, and [ComposerPanel] - the emoji / GIF / attachment drawer, which is
 * inline Compose content. Clearing focus and hiding the IME alone leaves that
 * drawer painted over the incoming call, which is the reported bug.
 *
 * [onClosePanel] closes the composer's own drawer. It is read inside the effect
 * body rather than being an effect key: keying on the drawer's state made the
 * boundary re-run and re-close the drawer the instant the user opened it, which
 * broke the emoji keyboard outright.
 */
@Composable
internal fun CallEntryBoundary(
    onClosePanel: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = androidx.activity.compose.LocalActivity.current
    val requested by app.aino.mobile.core.call.CallInputDismissal.requested.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        val window = activity?.window
        if (requested) {
            onClosePanel()
            focus.clearFocus(force = true)
            keyboard?.hide()
            // A ring can arrive while a text field still holds focus; without this
            // the IME slides back up over the call as the window re-lays out.
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        } else {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    }
                }
            }
        
