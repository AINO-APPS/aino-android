package app.aino.mobile.feature.attendance.verify

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine

/** Live coaching quality, mirroring FaceCapture's none/weak/good detector hints. */
enum class FaceQuality { None, Weak, Good }

// Auto-capture tuning (FaceCapture.tsx): fire once a face is steadily "good"
// on N consecutive analysis frames.
private const val AUTO_CONSECUTIVE_HITS = 2

/**
 * `FaceCapture` equivalent (P3.7): front-camera preview with a face frame
 * guide, coaching hints and steady-face auto-capture.
 *
 * Note: the web computes a face-api.js 128-float descriptor in the browser.
 * No on-device model produces a compatible descriptor, so on Android a
 * confident in-frame face gates the platform biometric proof instead
 * (`fingerprint_verified`, the server-sanctioned native fallback).
 */
@Composable
fun FaceCaptureBox(
    autoCapture: Boolean,
    captureLabel: String,
    capturingLabel: String,
    capturing: Boolean,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hasPermission) {
            Column(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp))
                    .background(colors.bgSecondary),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Face, null, Modifier.size(30.dp), tint = colors.textMuted)
                Text(
                    if (permissionDenied) "Camera permission is required for face verification"
                    else "Requesting camera permission…",
                    color = colors.textSecondary,
                    fontSize = 0.82.rem,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (permissionDenied) {
                    Text(
                        "Grant camera access",
                        color = colors.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 0.82.rem,
                        modifier = Modifier.padding(top = 8.dp).clickable { launcher.launch(Manifest.permission.CAMERA) },
                    )
                }
            }
        } else {
            CameraCapture(autoCapture, capturing, onCapture)
        }
        // Always offer the explicit verify action: the server's native path is
        // the device identity check, so a denied/broken camera must not block clocking.
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (capturing) colors.primary.copy(alpha = 0.5f) else colors.primary)
                .clickable(enabled = !capturing, onClick = onCapture)
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (capturing) capturingLabel else captureLabel,
                color = colors.onAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 0.88.rem,
            )
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraCapture(autoCapture: Boolean, capturing: Boolean, onCapture: () -> Unit) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var quality by remember { mutableStateOf(FaceQuality.None) }
    var hits by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var fired by remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build(),
        )
    }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            detector.close()
        }
    }

    LaunchedEffect(Unit) {
        val provider = runCatching {
            suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    { cont.resumeWith(runCatching { future.get() }) },
                    ContextCompat.getMainExecutor(context),
                )
            }
        }.getOrElse {
            cameraError = "Camera unavailable on this device"
            return@LaunchedEffect
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return@setAnalyzer
            }
            detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { faces ->
                    val face = faces.maxByOrNull { it.boundingBox.height() }
                    val next = when {
                        face == null -> FaceQuality.None
                        face.boundingBox.height() >= proxy.height * 0.30f &&
                            kotlin.math.abs(face.headEulerAngleY) < 20f &&
                            kotlin.math.abs(face.headEulerAngleZ) < 20f -> FaceQuality.Good
                        else -> FaceQuality.Weak
                    }
                    hits = if (next == FaceQuality.Good) (hits + 1).coerceAtMost(AUTO_CONSECUTIVE_HITS) else 0
                    quality = next
                    if (autoCapture && !fired && !capturing && hits >= AUTO_CONSECUTIVE_HITS) {
                        fired = true
                        onCapture()
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) },
                analysis,
            )
        }.onFailure { cameraError = "Could not start the camera" }
    }

    CameraFrame(previewView, quality, hits, cameraError, capturing, autoCapture)
}

/** Preview + frame guide + coaching hint + capturing/error overlays. */
@Composable
private fun CameraFrame(
    previewView: PreviewView,
    quality: FaceQuality,
    hits: Int,
    cameraError: String?,
    capturing: Boolean,
    autoCapture: Boolean,
) {
    val colors = LocalWebColors.current
    Box(
        Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(Color.Black),
    ) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        // Frame guide, tinted by coaching quality (frameGuide/Good/Weak).
        Box(
            Modifier.align(Alignment.Center).size(width = 180.dp, height = 220.dp)
                .border(
                    2.dp,
                    when (quality) {
                        FaceQuality.Good -> colors.success
                        FaceQuality.Weak -> colors.warning
                        FaceQuality.None -> colors.border
                    },
                    RoundedCornerShape(90.dp),
                ),
        )
        when {
            cameraError != null -> Column(
                Modifier.fillMaxSize().background(colors.bgSecondary),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(26.dp), tint = colors.danger)
                Text(cameraError, color = colors.textSecondary, fontSize = 0.82.rem, modifier = Modifier.padding(top = 6.dp))
            }
            capturing -> Column(
                Modifier.fillMaxSize().background(Color(0x99000000)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), color = colors.primary)
                Text("Verifying...", color = colors.text, fontSize = 0.85.rem, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (autoCapture && cameraError == null && !capturing) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (quality != FaceQuality.None) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        Modifier.size(13.dp),
                        tint = if (quality == FaceQuality.Good) colors.success else colors.warning,
                    )
                }
                Text(
                    when (quality) {
                        FaceQuality.Good -> "Hold still — verifying"
                        FaceQuality.Weak -> "Move closer & face the light"
                        FaceQuality.None -> "Position your face in the frame"
                    },
                    color = Color.White,
                    fontSize = 0.75.rem,
                    modifier = Modifier.padding(start = 6.dp),
                )
                if (hits > 0) {
                    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(AUTO_CONSECUTIVE_HITS) { index ->
                            Box(
                                Modifier.size(6.dp)
                                    .background(if (index < hits) colors.success else Color(0x66FFFFFF), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}
