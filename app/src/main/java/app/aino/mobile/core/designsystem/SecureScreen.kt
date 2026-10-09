package app.aino.mobile.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Windows currently asking for FLAG_SECURE, so overlapping screens do not clear each other's flag. */
private val secureHolders = HashMap<Window, Int>()

/**
 * Blocks screenshots, screen recording and the recents thumbnail while this
 * composable is on screen (salary slips, view-once media). Works inside a
 * dialog (its own window) and on a regular screen (the activity window).
 */
@Composable
fun SecureScreen() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        ?: LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        window?.let(::acquireSecure)
        onDispose { window?.let(::releaseSecure) }
    }
}

internal fun acquireSecure(window: Window) {
    val count = secureHolders[window] ?: 0
    if (count == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    secureHolders[window] = count + 1
}

internal fun releaseSecure(window: Window) {
    val count = (secureHolders[window] ?: return) - 1
    if (count > 0) {
        secureHolders[window] = count
    } else {
        secureHolders.remove(window)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
