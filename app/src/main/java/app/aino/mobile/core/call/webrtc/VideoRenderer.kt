package app.aino.mobile.core.call.webrtc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * A WebRTC video track in Compose. [overlay] puts the surface above sibling
 * video surfaces (the local preview over the remote feed); [fit] letterboxes
 * (screen shares, web `object-fit: contain`) instead of cropping.
 */
@Composable
fun VideoRenderer(
    track: VideoTrack?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    overlay: Boolean = false,
    fit: Boolean = false,
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(WebRtcRuntime.shared(context).eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            // Must be set before the surface attaches to the window.
            setZOrderMediaOverlay(overlay)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    DisposableEffect(track, renderer) {
        runCatching { track?.addSink(renderer) }
        onDispose {
            // The peer may already have disposed a remote track.
            runCatching { track?.removeSink(renderer) }
            renderer.clearImage()
        }
    }
    AndroidView(
        factory = { renderer },
        modifier = modifier,
        update = {
            it.setMirror(mirror)
            it.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        },
    )
}
