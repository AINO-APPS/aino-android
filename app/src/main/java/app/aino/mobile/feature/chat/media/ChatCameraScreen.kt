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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val HOLD_MS = 400L
private const val MAX_VIDEO_MS = 60_000L
private const val MAX_VIDEO_BYTES = 25L * 1024 * 1024
private val RecordRed = Color(0xFFF44336)

/** Full-screen in-app camera. `recent` = newest-first MediaStore image/video uris the caller already loaded (may be empty). */
@SuppressLint("MissingPermission")
@Composable
fun ChatCameraScreen(
    recent: List<Uri>,
    onClose: () -> Unit,
    onCaptured: (List<MediaSendItem>) -> Unit,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val captured by rememberUpdatedState(onCaptured)
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    var hasCamera by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    var cameraDenied by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        cameraDenied = !it
    }
    var audioAsked by rememberSaveable { mutableStateOf(false) }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA) }

    var front by rememberSaveable { mutableStateOf(false) }
    var flashMode by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build(),
        )
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoSupported by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordStartedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hasCamera, front) {
        if (!hasCamera) return@LaunchedEffect
        val provider = context.cameraProvider()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        camera = try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, videoCapture).also { videoSupported = true }
        } catch (e: Exception) {
            videoSupported = false
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }.getOrNull()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ runCatching { future.get().unbindAll() } }, mainExecutor)
        }
    }
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }
    LaunchedEffect(error) { if (error != null) { delay(2500); error = null } }

    fun stopRecording() {
        recording?.stop()
        recording = null
        camera?.cameraControl?.enableTorch(false)
    }

    LaunchedEffect(recording) {
        while (recording != null) {
            elapsedMs = SystemClock.elapsedRealtime() - recordStartedAt
            if (elapsedMs >= MAX_VIDEO_MS) stopRecording()
            delay(100)
        }
    }

    fun takePhoto() {
        if (camera == null || busy) return
        busy = true
        val file = newChatMediaFile(context, "photo", "jpg")
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = front }
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    val (w, h) = jpegSize(file)
                    captured(listOf(MediaSendItem(chatMediaUri(context, file), "image/jpeg", w, h)))
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    error = "Couldn't take photo"
                }
            },
        )
    }

    fun startRecording() {
        if (camera == null || !videoSupported || recording != null) return
        val hasAudio = context.granted(Manifest.permission.RECORD_AUDIO)
        if (!hasAudio && !audioAsked) {
            audioAsked = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val file = newChatMediaFile(context, "video", "mp4")
        val options = FileOutputOptions.Builder(file).setFileSizeLimit(MAX_VIDEO_BYTES).build()
        if (flashMode == ImageCapture.FLASH_MODE_ON) camera?.cameraControl?.enableTorch(true)
        recordStartedAt = SystemClock.elapsedRealtime()
        elapsedMs = 0
        recording = videoCapture.output.prepareRecording(context, options)
            .apply { if (hasAudio) withAudioEnabled() }
            .start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    camera?.cameraControl?.enableTorch(false)
                    val ok = !event.hasError() ||
                        event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    if (ok && file.length() > 0) {
                        val (w, h) = videoSize(file)
                        captured(listOf(MediaSendItem(chatMediaUri(context, file), "video/mp4", w, h)))
                    } else {
                        file.delete()
                        error = "Couldn't record video"
                    }
                }
            }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        cam.cameraControl.setZoomRatio((state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio))
                    }
                },
            )
        } else if (cameraDenied) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("To capture photos and video, allow camera access.", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Allow",
                    color = Color(0xFF2C6BED),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { cameraPermission.launch(Manifest.permission.CAMERA) }.padding(8.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { stopRecording(); onClose() }) {
                Icon(Icons.Outlined.Close, contentDescription = "Close camera", tint = Color.White)
            }
            if (recording != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(RecordRed))
                    Spacer(Modifier.size(6.dp))
                    Text(formatElapsed(elapsedMs), color = Color.White, fontSize = 15.sp)
                }
            }
            IconButton(onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }) {
                val (icon, label) = when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Outlined.FlashAuto to "Flash auto"
                    ImageCapture.FLASH_MODE_ON -> Icons.Outlined.FlashOn to "Flash on"
                    else -> Icons.Outlined.FlashOff to "Flash off"
                }
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            error?.let { Text(it, color = Color.White, modifier = Modifier.padding(8.dp)) }
            if (recent.isNotEmpty() && recording == null) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    items(recent, key = { it.toString() }) { uri -> RecentThumb(uri) { captured(listOf(it)) } }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundControl(visible = recording == null) {
                    IconButton(onClick = onOpenGallery) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Open gallery", tint = Color.White)
                    }
                }
                Box(
                    Modifier.size(80.dp).pointerInput(camera, videoSupported) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val downAt = SystemClock.elapsedRealtime()
                            val hold = scope.launch { delay(HOLD_MS); startRecording() }
                            waitForUpOrCancellation()
                            hold.cancel()
                            val action = shutterAction(SystemClock.elapsedRealtime() - downAt, HOLD_MS)
                            when {
                                recording != null -> stopRecording()
                                action == ShutterAction.Photo || !videoSupported -> takePhoto()
                            }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    val progress = (elapsedMs.toFloat() / MAX_VIDEO_MS).coerceIn(0f, 1f)
                    val isRecording = recording != null
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 4.dp.toPx()
                        val inset = stroke / 2
                        drawCircle(Color.White, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                        drawCircle(
                            if (isRecording) RecordRed else Color.White,
                            radius = size.minDimension / 2 - stroke * if (isRecording) 3f else 2f,
                        )
                        if (isRecording) {
                            drawArc(
                                RecordRed, -90f, 360f * progress, useCenter = false,
                                topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(stroke),
                            )
                        }
                    }
                }
                RoundControl(visible = recording == null) {
                    IconButton(onClick = { front = !front }) {
                        Icon(Icons.Outlined.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
                    }
                }
            }
            if (recording == null) {
                Text(
                    "Tap for photo, hold for video",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RoundControl(visible: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (visible) Color(0x66000000) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) { if (visible) content() }
}

@Composable
private fun RecentThumb(uri: Uri, onPick: (MediaSendItem) -> Unit) {
    val context = LocalContext.current
    val mime = remember(uri) { runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "image/jpeg" }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .clickable { onPick(MediaSendItem(uri, mime)) },
    ) {
        AsyncImage(model = uri, contentDescription = "Recent media", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (mime.startsWith("video/")) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
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
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
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
