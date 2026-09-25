package app.aino.mobile.core.call.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * The local microphone + camera (web `getUserMedia`). The video track exists
 * whenever [withVideo] is set so it can be attached to peers once; turning the
 * camera off stops capture (camera LED off) and disables the track instead of
 * renegotiating. Mobile constraints: 640×480 @ 24 fps, front camera.
 */
class LocalMedia(context: Context, private val runtime: WebRtcRuntime, withVideo: Boolean) {
    private val appContext = context.applicationContext
    private val audioSource: AudioSource
    val audioTrack: AudioTrack
    private var videoSource: VideoSource? = null
    var videoTrack: VideoTrack? = null
        private set
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var capturing = false

    init {
        val (source, track) = runtime.createAudioTrack("aino-audio-${System.nanoTime()}")
        audioSource = source
        audioTrack = track
        if (withVideo) {
            val enumerator = cameraEnumerator()
            val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                val source = runtime.factory.createVideoSource(false)
                val helper = SurfaceTextureHelper.create("aino-camera", runtime.eglBase.eglBaseContext)
                val cam = enumerator.createCapturer(name, null)
                cam.initialize(helper, appContext, source.capturerObserver)
                videoSource = source
                textureHelper = helper
                capturer = cam
                videoTrack = runtime.factory.createVideoTrack("aino-video-${System.nanoTime()}", source).apply { setEnabled(false) }
            }
        }
    }

    val hasCamera: Boolean get() = videoTrack != null

    fun setMuted(muted: Boolean) {
        audioTrack.setEnabled(!muted)
    }

    /** Returns whether the camera is now on (false without a camera or the CAMERA permission). */
    fun setVideoEnabled(enabled: Boolean): Boolean {
        val cam = capturer ?: return false
        val allowed = appContext.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (enabled && allowed && !capturing) {
            runCatching { cam.startCapture(640, 480, 24) }.onSuccess { capturing = true }
        } else if (!enabled && capturing) {
            runCatching { cam.stopCapture() }
            capturing = false
        }
        videoTrack?.setEnabled(enabled && capturing)
        return enabled && capturing
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun dispose() {
        runCatching { if (capturing) capturer?.stopCapture() }
        capturing = false
        runCatching { capturer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { audioTrack.dispose() }
        runCatching { audioSource.dispose() }
        capturer = null
        videoTrack = null
        videoSource = null
        textureHelper = null
    }

    private fun cameraEnumerator(): CameraEnumerator =
        if (Camera2Enumerator.isSupported(appContext)) Camera2Enumerator(appContext) else Camera1Enumerator(true)
}
