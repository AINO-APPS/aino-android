package app.aino.mobile.core.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for the microphone (and camera for video) at the point of use, then
 * runs the action either way — like the web's `getUserMedia` fallback, a
 * denied camera joins with video off and a denied mic joins muted.
 */
@Composable
fun rememberCallPermissions(): (video: Boolean, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending[0]?.invoke()
        pending[0] = null
    }
    return remember(launcher) {
        { video, action ->
            val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video })
                .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) {
                action()
            } else {
                pending[0] = action
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}

fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
