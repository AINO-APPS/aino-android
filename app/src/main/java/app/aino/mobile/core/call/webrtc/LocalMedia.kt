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
 * when [withVideo] is set or video is first enabled; turning the
 * camera off stops capture (camera LED off) and disables the track instead of
 * renegotiating. Capture 1280×720 @ 30 fps (Signal `Camera`), front camera;
 * the sender's encoding tier downscales as bandwidth requires.
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
    var videoError: String? = null
        private set

    init {
        val (source, track) = runtime.createAudioTrack("aino-audio-${System.nanoTime()}")
        audioSource = source
        audioTrack = track
        if (withVideo) prepareVideo()
    }

    private fun prepareVideo() {
        if (videoTrack != null) return
        try {
            val enumerator = cameraEnumerator()
            val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                val source = runtime.factory.createVideoSource(false)
                val helper = SurfaceTextureHelper.create("aino-camera", runtime.eglBase.eglBaseContext)
                val cam = enumerator.createCapturer(name, null)
                videoSource = source
                textureHelper = helper
                capturer = cam
                cam.initialize(helper, appContext, source.capturerObserver)
                videoTrack = runtime.factory.createVideoTrack("aino-video-${System.nanoTime()}", source).apply { setEnabled(false) }
            } else {
                videoError = "No camera is available"
            }
        } catch (error: RuntimeException) {
            android.util.Log.w("AinoCall", "Could not prepare camera", error)
            videoError = "Could not access the camera"
            runCatching { capturer?.dispose() }.onFailure { android.util.Log.w("AinoCall", "Camera cleanup failed", it) }
            runCatching { textureHelper?.dispose() }.onFailure { android.util.Log.w("AinoCall", "Camera texture cleanup failed", it) }
            runCatching { videoSource?.dispose() }.onFailure { android.util.Log.w("AinoCall", "Camera source cleanup failed", it) }
            capturer = null
            textureHelper = null
            videoSource = null
        }
    }

    val hasCamera: Boolean get() = videoTrack != null

    fun setMuted(muted: Boolean) {
        audioTrack.setEnabled(!muted)
    }

    /** Returns whether the camera is now on (false without a camera or the CAMERA permission). */
    fun setVideoEnabled(enabled: Boolean): Boolean {
        videoError = null
        val allowed = appContext.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (enabled && !allowed) {
            videoError = "Camera permission is required to turn on video"
            return false
        }
        if (enabled) prepareVideo()
        val cam = capturer ?: return false
        if (enabled && allowed && !capturing) {
            try {
                cam.startCapture(1280, 720, 30)
                capturing = true
            } catch (error: RuntimeException) {
                android.util.Log.w("AinoCall", "Could not start camera", error)
                videoError = "Could not start the camera"
            }
        } else if (!enabled && capturing) {
            try {
                cam.stopCapture()
                capturing = false
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                android.util.Log.w("AinoCall", "Camera stop interrupted", error)
                videoError = "Could not stop the camera. Try turning it off again."
            }
        }
        videoTrack?.setEnabled(capturing)
        return capturing
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
