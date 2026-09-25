package app.aino.mobile.core.call

import android.app.Activity
import android.app.PictureInPictureParams
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.call.webrtc.VideoRenderer
import app.aino.mobile.core.designsystem.component.UserAvatar
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Signal-style 1:1 call screen (outgoing and connected), with the web's copy:
 * a top bar (minimise → PiP, name, status/duration), the blurred avatar or the
 * remote video, a self preview that drags to any corner (flip + swap), and the
 * bottom controls Speaker · Camera · Mic · End. Video calls hide the chrome on
 * tap once connected.
 */
@Composable
fun ActiveCallScreen(controller: ActiveCallController) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    if (!ui.visible) return
    val inPip by PipState.inPip.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    val connected = ui.connectedAt != null && ui.endMessage == null
    var swapped by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected) {
        while (connected) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val showRemoteVideo = ui.isVideo && connected && ui.remoteVideo != null && !ui.remoteVideoOff
    // Signal hides the controls a few seconds into a video call; a tap brings them back.
    LaunchedEffect(showRemoteVideo, chromeVisible) {
        if (showRemoteVideo && chromeVisible) {
            delay(5_000)
            chromeVisible = false
        }
        if (!showRemoteVideo) chromeVisible = true
    }
    val status = when {
        ui.endMessage != null || !connected || ui.phase == CallPhase.Reconnecting -> ui.statusText
        else -> formatCallDuration((now - (ui.connectedAt ?: now)) / 1000)
    }
    val minimise = {
        activity?.let { runCatching { it.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) } }
        Unit
    }
    BackHandler(enabled = connected, onBack = minimise)
    BackHandler(enabled = !connected) { /* Before connecting, a call is left only via End. */ }

    BoxWithConstraints(
        // Swallows taps (the shell is underneath) and toggles the chrome on video.
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { if (showRemoteVideo) chromeVisible = !chromeVisible },
    ) {
        // Signal: a ringing video call shows your own camera full-screen.
        val localBackdrop = ui.isVideo && !connected && !ui.videoOff && ui.localVideo != null
        if (showRemoteVideo) {
            Box(Modifier.matchParentSize().background(Color.Black))
            VideoRenderer(if (swapped) ui.localVideo else ui.remoteVideo, Modifier.fillMaxSize(), mirror = swapped)
        } else if (localBackdrop) {
            Box(Modifier.matchParentSize().background(Color.Black))
            VideoRenderer(ui.localVideo, Modifier.fillMaxSize(), mirror = true)
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f)))
        } else {
            BlurredAvatarBackdrop(ui.peerAvatar)
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                UserAvatar(ui.peerName.ifBlank { "?" }, ui.peerAvatar, 112.dp, background = Color(0xFF3A3A3A))
            }
        }
        if (inPip) return@BoxWithConstraints

        AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar.
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent)))
                        .statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        "Picture-in-picture",
                        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = connected, onClick = minimise).padding(8.dp),
                        tint = Color.White.copy(alpha = if (connected) 1f else .4f),
                    )
                    Column(Modifier.weight(1f).padding(start = 4.dp, top = 4.dp)) {
                        Text(ui.peerName.ifBlank { "Unknown" }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (ui.remoteMuted && connected) Icon(Icons.Outlined.MicOff, "Muted", Modifier.size(15.dp), tint = Color.White)
                            Text(status, color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
                        }
                    }
                }
                // Bottom controls.
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f))))
                        .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallToggle(Icons.AutoMirrored.Outlined.VolumeUp, "Speaker", ui.speakerOn, controller::toggleSpeaker)
                    if (ui.isVideo) {
                        CallToggle(
                            if (ui.videoOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam,
                            if (ui.videoOff) "Turn on camera (V)" else "Turn off camera (V)",
                            !ui.videoOff,
                            controller::toggleVideo,
                        )
                    }
                    CallToggle(Icons.Outlined.MicOff, if (ui.muted) "Unmute (M)" else "Mute (M)", ui.muted, controller::toggleMute)
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(CallRed).clickable(onClickLabel = "End call (E)", onClick = controller::hangUp),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.CallEnd, "End call (E)", Modifier.size(26.dp), tint = Color.White) }
                }
            }
        }

        // Self preview: a rounded card snapping to the nearest corner (Signal's PiP tile).
        if (ui.isVideo && ui.localVideo != null && !ui.videoOff && !localBackdrop) {
            SelfPreview(ui, swapped, onSwap = { swapped = !swapped }, onFlip = controller::switchCamera, maxWidthPx = constraints.maxWidth, maxHeightPx = constraints.maxHeight)
        }
    }
}

@Composable
private fun SelfPreview(ui: ActiveCallUi, swapped: Boolean, onSwap: () -> Unit, onFlip: () -> Unit, maxWidthPx: Int, maxHeightPx: Int) {
    val density = LocalDensity.current
    val widthPx = with(density) { 96.dp.toPx() }
    val heightPx = with(density) { 160.dp.toPx() }
    val marginX = with(density) { 16.dp.toPx() }
    val top = with(density) { 104.dp.toPx() }
    val bottom = with(density) { 180.dp.toPx() }
    var right by remember { mutableStateOf(true) }
    var atTop by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val baseX = if (right) maxWidthPx - widthPx - marginX else marginX
    val baseY = if (atTop) top else maxHeightPx - heightPx - bottom
    Box(
        Modifier
            .offset { IntOffset((baseX + drag.x).roundToInt(), (baseY + drag.y).roundToInt()) }
            .size(96.dp, 160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2C2C2C))
            .pointerInput(maxWidthPx, maxHeightPx) {
                detectDragGestures(
                    onDragEnd = {
                        right = baseX + drag.x + widthPx / 2 > maxWidthPx / 2
                        atTop = baseY + drag.y + heightPx / 2 < maxHeightPx / 2
                        drag = Offset.Zero
                    },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            }
            .clickable(onClickLabel = "Swap video", onClick = onSwap),
    ) {
        val showRemote = swapped && ui.remoteVideo != null
        VideoRenderer(if (showRemote) ui.remoteVideo else ui.localVideo, Modifier.fillMaxSize(), mirror = !showRemote, overlay = true)
        if (!swapped) {
            Icon(
                Icons.Outlined.Cameraswitch,
                "Switch camera",
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .45f)).clickable(onClick = onFlip).padding(6.dp),
                tint = Color.White,
            )
        }
    }
}

/** Full-screen call surfaces sit over the shell; swallow taps so they never reach it. */
@Composable
internal fun Modifier.blockTouches(): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = {},
)
