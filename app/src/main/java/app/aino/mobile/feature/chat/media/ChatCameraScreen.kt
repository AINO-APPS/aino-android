package app.aino.mobile.feature.chat.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_VIDEO_MS = 60_000L
private const val MAX_VIDEO_BYTES = 25L * 1024 * 1024
private val RecordRed = Color(0xFFF44336)
private val ControlGray = Color(0xDD303133)

@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("MissingPermission")
@Composable
fun ChatCameraScreen(
    recent: List<Uri>,
    onClose: () -> Unit,
    onCaptured: (List<MediaSendItem>) -> Unit,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val captured by rememberUpdatedState(onCaptured)
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val session = remember { CaptureSession() }
    var hasCamera by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        denied = !it
    }
    var audioPending by remember { mutableStateOf(false) }
    var audioAsked by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioPending = false
        if (session.active && !granted) error = "Microphone unavailable. Tap record again to record without sound."
    }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    var mode by rememberSaveable { mutableStateOf(CameraMode.Photo) }
    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember {
        VideoCapture.withOutput(Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build())
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoSupported by remember { mutableStateOf(false) }
    var canFlip by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomPresets by remember { mutableStateOf(emptyList<Float>()) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var recordingStarted by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val blocked = busy || audioPending || camera == null || !foreground

    fun cancelCapture() {
        session.cancel()
        recording?.stop()
        recording = null
        camera?.cameraControl?.enableTorch(false)
        busy = false
    }

    fun close() {
        cancelCapture()
        onClose()
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                cancelCapture()
            } else if (event == Lifecycle.Event.ON_START) foreground = true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            session.dispose()
            recording?.stop()
            camera?.cameraControl?.enableTorch(false)
            provider?.unbindAll()
        }
    }

    LaunchedEffect(hasCamera, front) {
        if (!hasCamera) return@LaunchedEffect
        camera = null
        zoomPresets = emptyList()
        error = null
        try {
            val boundProvider = context.cameraProvider()
            provider = boundProvider
            val hasFront = boundProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            val hasBack = boundProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            canFlip = hasFront && hasBack
            if (!hasBack && hasFront && !front) {
                front = true
                return@LaunchedEffect
            }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            var waited = 0
            while (previewView.viewPort == null && waited < 1_000) { delay(16); waited += 16 }
            previewView.display?.rotation?.let { imageCapture.targetRotation = it; videoCapture.targetRotation = it }
            fun group(vararg cases: androidx.camera.core.UseCase) = androidx.camera.core.UseCaseGroup.Builder().apply {
                previewView.viewPort?.let(::setViewPort)
                cases.forEach(::addUseCase)
            }.build()
            boundProvider.unbindAll()
            camera = try {
                boundProvider.bindToLifecycle(owner, selector, group(preview, imageCapture, videoCapture)).also { videoSupported = true }
            } catch (_: Exception) {
                videoSupported = false
                mode = CameraMode.Photo
                boundProvider.unbindAll()
                boundProvider.bindToLifecycle(owner, selector, group(preview, imageCapture))
            }
            if (camera?.cameraInfo?.hasFlashUnit() != true) flash = ImageCapture.FLASH_MODE_OFF
            val zoom = camera?.cameraInfo?.zoomState?.value
            if (zoom != null) {
                zoomPresets = cameraZoomPresets(zoom.minZoomRatio, zoom.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(1f.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Couldn't open this camera. Close and try again."
        }
    }
    DisposableEffect(camera, owner) {
        val zoom = camera?.cameraInfo?.zoomState
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            zoomRatio = state.zoomRatio
            zoomPresets = cameraZoomPresets(state.minZoomRatio, state.maxZoomRatio)
        }
        zoom?.observe(owner, observer)
        onDispose { zoom?.removeObserver(observer) }
    }

    fun stopRecording() {
        // Finalize owns busy until the file is ready; stopping isn't another capture opportunity.
        recording?.stop()
        recording = null
        camera?.cameraControl?.enableTorch(false)
    }
    LaunchedEffect(recording) {
        while (recording != null) {
            elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= MAX_VIDEO_MS) stopRecording()
            delay(100)
        }
    }

    fun takePhoto() {
        if (blocked) return
        val token = session.begin() ?: return
        busy = true
        error = null
        val file = newChatMediaFile(context, "photo", "jpg")
        imageCapture.flashMode = flash
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = front }
        try {
            imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build(), executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (!session.finish(token)) { file.delete(); return }
                        busy = false
                        val (w, h) = jpegSize(file)
                        captured(listOf(MediaSendItem(chatMediaUri(context, file), "image/jpeg", w, h)))
                    }
                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        if (session.finish(token)) { busy = false; error = "Couldn't take photo" }
                    }
                })
        } catch (_: Exception) {
            file.delete()
            if (session.finish(token)) { busy = false; error = "Couldn't take photo" }
        }
    }

    fun startRecording() {
        if (blocked || !videoSupported) return
        val hasAudio = context.granted(Manifest.permission.RECORD_AUDIO)
        if (!hasAudio && !audioAsked) {
            audioAsked = true
            audioPending = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val token = session.begin() ?: return
        busy = true
        error = if (hasAudio) null else "Recording without sound"
        val file = newChatMediaFile(context, "video", "mp4")
        val options = FileOutputOptions.Builder(file).setFileSizeLimit(MAX_VIDEO_BYTES).build()
        if (flash == ImageCapture.FLASH_MODE_ON && camera?.cameraInfo?.hasFlashUnit() == true) camera?.cameraControl?.enableTorch(true)
        recordingStarted = false
        startedAt = SystemClock.elapsedRealtime()
        elapsed = 0
        try {
            recording = videoCapture.output.prepareRecording(context, options)
                .apply { if (hasAudio) withAudioEnabled() }
                .start(executor) { event ->
                    if (event is VideoRecordEvent.Start && session.active && session.busy) {
                        recordingStarted = true
                        startedAt = SystemClock.elapsedRealtime()
                    }
                    if (event is VideoRecordEvent.Finalize) {
                        if (!session.finish(token)) { file.delete(); return@start }
                        recording = null
                        busy = false
                        camera?.cameraControl?.enableTorch(false)
                        val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                            event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                        if (ok && file.length() > 0) {
                            val (w, h) = videoSize(file)
                            captured(listOf(MediaSendItem(chatMediaUri(context, file), "video/mp4", w, h)))
                        } else { file.delete(); error = "Couldn't record video" }
                    }
                }
        } catch (_: Exception) {
            file.delete()
            camera?.cameraControl?.enableTorch(false)
            if (session.finish(token)) { busy = false; error = "Couldn't start recording" }
        }
    }

    fun shutter() {
        if (recording != null) stopRecording()
        else if (cameraShutterAction(mode, 0, videoSupported) == ShutterAction.Video) startRecording()
        else takePhoto()
    }

    val currentStop by rememberUpdatedState(::stopRecording)
    val shutterInteractions = remember { MutableInteractionSource() }
    var holdRecording by remember { mutableStateOf(false) }
    LaunchedEffect(shutterInteractions) {
        shutterInteractions.interactions.collect { interaction ->
            if (holdRecording) {
                if (interaction is PressInteraction.Release) { holdRecording = false; currentStop() }
                else if (interaction is PressInteraction.Cancel) { holdRecording = false; cancelCapture() }
            }
        }
    }
    val isRecording = recording != null
    val shutterColor by animateColorAsState(if (isRecording || mode == CameraMode.Video) RecordRed else Color.White, label = "shutterColor")
    val shutterSize by animateFloatAsState(if (isRecording) .38f else .78f, label = "shutterSize")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        val landscape = maxWidth > maxHeight
        val compact = maxHeight < 500.dp
        val controlSize = if (compact) 48.dp else 56.dp
        val shutterDiameter = if (compact) 72.dp else 84.dp
        val bottomGap = if (compact) 8.dp else 16.dp
        Column(Modifier.fillMaxSize().padding(top = if (compact) 4.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (landscape) 48.dp else 0.dp)
                .clip(RoundedCornerShape(24.dp)).background(Color(0xFF141414))) {
                if (hasCamera) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().pointerInput(camera) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val cam = camera ?: return@detectTransformGestures
                            val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                            cam.cameraControl.setZoomRatio((state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio))
                        }
                    })
                }
                if (denied) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow camera access to capture photos and video.", color = Color.White)
                    IconButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                        Icon(HeroIcons.Camera, "Allow camera access", tint = Color.White)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    CameraControl(HeroIcons.XMark, "Close camera", controlSize, onClick = ::close)
                    if (isRecording) Text(if (recordingStarted) formatElapsed(elapsed) else "Starting…", color = Color.White, modifier = Modifier
                        .clip(RoundedCornerShape(24.dp)).background(ControlGray).padding(12.dp))
                    if (camera?.cameraInfo?.hasFlashUnit() == true) CameraControl(
                        if (flash == ImageCapture.FLASH_MODE_OFF) HeroIcons.BoltSlash else HeroIcons.Bolt,
                        when (flash) { ImageCapture.FLASH_MODE_ON -> "Flash on"; ImageCapture.FLASH_MODE_AUTO -> "Flash auto"; else -> "Flash off" },
                        controlSize, enabled = !blocked,
                    ) {
                        flash = if (mode == CameraMode.Video) {
                            if (flash == ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        } else when (flash) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .38f))
                    .padding(top = 8.dp, bottom = bottomGap), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (camera == null && hasCamera && error == null) Text("Opening camera…", color = Color.White, fontSize = 13.sp)
                    error?.let { Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp) }
                    if (zoomPresets.isNotEmpty()) Row(Modifier.clip(RoundedCornerShape(50)).background(ControlGray)) {
                        zoomPresets.forEach { ratio ->
                            val selected = kotlin.math.abs(zoomRatio - ratio) < .05f
                            Box(Modifier.size(48.dp).clip(CircleShape).background(if (selected) Color.White.copy(alpha = .16f) else Color.Transparent)
                                .selectable(selected = selected, enabled = camera != null, role = Role.RadioButton,
                                    onClick = { camera?.cameraControl?.setZoomRatio(ratio) }), contentAlignment = Alignment.Center) {
                                Text("${ratio.toInt()}×", color = Color.White, fontSize = 16.sp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = if (landscape) 48.dp else 28.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { cancelCapture(); onOpenGallery() }, enabled = !busy && !audioPending, modifier = Modifier.size(controlSize).clip(CircleShape).background(ControlGray)) {
                            if (recent.isNotEmpty()) AsyncImage(recent.first(), "Open gallery", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                            else Icon(HeroIcons.Photo, "Open gallery", tint = Color.White)
                        }
                        Box(Modifier.size(shutterDiameter).combinedClickable(
                            interactionSource = shutterInteractions, indication = null,
                            enabled = !blocked || isRecording,
                            onClick = ::shutter,
                            onLongClick = {
                                if (mode == CameraMode.Photo && !blocked && videoSupported) {
                                    holdRecording = true
                                    startRecording()
                                }
                            },
                            role = Role.Button,
                            onClickLabel = if (isRecording) "Stop recording" else if (mode == CameraMode.Video) "Record video" else "Take photo",
                        ).semantics {
                            contentDescription = if (isRecording) "Stop recording" else if (mode == CameraMode.Video) "Record video" else "Take photo"
                        }.clip(CircleShape).background(ControlGray).padding(6.dp).border(2.dp, Color.White.copy(alpha = .65f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize(shutterSize).clip(if (isRecording) RoundedCornerShape(6.dp) else CircleShape).background(shutterColor))
                            if (busy && !isRecording) CircularProgressIndicator(Modifier.fillMaxSize().padding(4.dp), color = Color.White, strokeWidth = 2.dp)
                            if (isRecording) CircularProgressIndicator(progress = { (elapsed.toFloat() / MAX_VIDEO_MS).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxSize(), color = RecordRed, strokeWidth = 3.dp)
                        }
                        CameraControl(HeroIcons.ArrowPathRoundedSquare, "Switch camera", controlSize, enabled = canFlip && !blocked) {
                            front = !front
                            flash = ImageCapture.FLASH_MODE_OFF
                        }
                    }
                }
            }
            Row(Modifier.padding(vertical = bottomGap).clip(RoundedCornerShape(50)).background(ControlGray).padding(4.dp)) {
                listOf(CameraMode.Video, CameraMode.Photo).forEach { item ->
                    Box(Modifier.width(100.dp).height(48.dp).clip(RoundedCornerShape(50))
                        .background(if (mode == item) Color.White.copy(alpha = .16f) else Color.Transparent)
                        .selectable(selected = mode == item, enabled = !blocked && (item != CameraMode.Video || videoSupported), role = Role.Tab,
                            onClick = { mode = item; if (item == CameraMode.Video && flash == ImageCapture.FLASH_MODE_AUTO) flash = ImageCapture.FLASH_MODE_OFF }),
                        contentAlignment = Alignment.Center) {
                        Text(item.name, color = if (item == CameraMode.Video && !videoSupported) Color.Gray else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraControl(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size).clip(CircleShape).background(ControlGray)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (!enabled) Color.Gray else if (label == "Flash on") Color(0xFFFFD54F) else Color.White, modifier = Modifier.size(26.dp))
            if (label == "Flash auto") Text("A", Modifier.align(Alignment.BottomEnd), color = Color.White, fontSize = 10.sp)
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
        if (cont.isActive) {
            runCatching { future.get() }.fold(cont::resume, cont::resumeWithException)
        }
    }, ContextCompat.getMainExecutor(this))
}

private fun jpegSize(file: File): Pair<Int?, Int?> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, opts)
    if (opts.outWidth <= 0) return null to null
    val rotation = runCatching {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val swap = rotation == ExifInterface.ORIENTATION_ROTATE_90 || rotation == ExifInterface.ORIENTATION_ROTATE_270 ||
        rotation == ExifInterface.ORIENTATION_TRANSPOSE || rotation == ExifInterface.ORIENTATION_TRANSVERSE
    return if (swap) opts.outHeight to opts.outWidth else opts.outWidth to opts.outHeight
}

private fun videoSize(file: File): Pair<Int?, Int?> = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot % 180 != 0) h to w else w to h
    } finally {
        r.release()
    }
}.getOrDefault(null to null)
