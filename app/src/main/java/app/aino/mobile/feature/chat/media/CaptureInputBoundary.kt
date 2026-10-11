package app.aino.mobile.feature.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Also covers entry after a permission dialog, when the keyboard can regain focus. */
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
