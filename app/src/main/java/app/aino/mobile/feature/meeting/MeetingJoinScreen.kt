package app.aino.mobile.feature.meeting

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.call.hasPermission
import app.aino.mobile.core.call.rememberCallPermissions
import app.aino.mobile.core.call.webrtc.LocalMedia
import app.aino.mobile.core.call.webrtc.VideoRenderer
import app.aino.mobile.core.call.webrtc.WebRtcRuntime
import app.aino.mobile.core.designsystem.component.avatarInitials
import app.aino.mobile.core.network.ApiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val JoinBg = Color(0xFF0F0F13)
private val JoinCard = Color(0xFF1A1A22)
private val Sky = Color(0xFF0EA5E9)
private val Muted = Color(0xFFAAAAAA)

fun MeetingSession.joinAs(meeting: Meeting, user: AinoUser, muted: Boolean, videoOff: Boolean) =
    join(meeting, user.id, user.fullName ?: user.username, user.avatar, muted, videoOff)

/** Loads `GET /meetings/:code`; null result + message on failure. */
@Composable
private fun rememberMeeting(code: String): Pair<Meeting?, Boolean> {
    val context = LocalContext.current
    var meeting by remember(code) { mutableStateOf<Meeting?>(null) }
    var failed by remember(code) { mutableStateOf(false) }
    LaunchedEffect(code) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { MeetingRepository(AppContainer.get(context).api).meeting(code) }.getOrNull()
        }
        if (loaded == null) failed = true else meeting = loaded
    }
    return meeting to failed
}

/** Web `MeetingJoin.tsx` (≤640px): preview + mic/camera toggles, meeting info, Join now. */
@Composable
fun MeetingJoinScreen(code: String, user: AinoUser, onBack: () -> Unit, onJoined: (String) -> Unit) {
    val context = LocalContext.current
    val (meeting, failed) = rememberMeeting(code)
    var permitted by remember { mutableStateOf(false) }
    val withPermissions = rememberCallPermissions()
    LaunchedEffect(Unit) { withPermissions(true) { permitted = true } }
    val session = MeetingRuntime.get(context)
    val preview = remember(permitted) {
        if (permitted) LocalMedia(context, WebRtcRuntime.shared(context), withVideo = true) else null
    }
    var muted by remember(permitted) { mutableStateOf(!hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var videoOff by remember(permitted) { mutableStateOf(true) }
    DisposableEffect(preview) {
        if (preview != null) {
            videoOff = !preview.setVideoEnabled(hasPermission(context, Manifest.permission.CAMERA))
            preview.setMuted(muted)
        }
        onDispose { preview?.dispose() }
    }

    when {
        failed -> Box(Modifier.fillMaxSize().background(JoinBg), contentAlignment = Alignment.Center) {
            Text("Meeting not found or you are not invited.", Modifier.padding(24.dp), color = Color(0xFFEF4444), textAlign = TextAlign.Center)
        }
        meeting == null -> Box(Modifier.fillMaxSize().background(JoinBg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Sky, strokeWidth = 3.dp)
                Text("Loading meeting…", color = Muted, fontSize = 16.sp)
            }
        }
        else -> Column(
            Modifier.fillMaxSize().background(JoinBg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(JoinCard)
                    .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(16.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                PreviewArea(user, preview, muted, videoOff,
                    onToggleMic = {
                        muted = !muted
                        preview?.setMuted(muted)
                    },
                    onToggleVideo = { videoOff = !(preview?.setVideoEnabled(videoOff) ?: false) },
                    onFlip = { preview?.switchCamera() },
                )
                MeetingInfo(meeting, code) {
                    preview?.dispose()
                    session.joinAs(meeting, user, muted, videoOff)
                    onJoined(code)
                }
                Text(
                    "← Back",
                    Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Muted,
                    fontSize = 13.3.sp,
                )
            }
        }
    }
}

@Composable
private fun PreviewArea(
    user: AinoUser,
    preview: LocalMedia?,
    muted: Boolean,
    videoOff: Boolean,
    onToggleMic: () -> Unit,
    onToggleVideo: () -> Unit,
    onFlip: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        if (!videoOff && preview?.videoTrack != null) {
            VideoRenderer(preview.videoTrack, Modifier.matchParentSize(), mirror = true)
        } else {
            Box(Modifier.matchParentSize().background(Color(0xFF1A1A2E)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(Sky), contentAlignment = Alignment.Center) {
                    Text(avatarInitials(user.fullName ?: user.username).take(1), color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        NetworkBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LobbyButton(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (muted) "Unmute" else "Mute", muted, onToggleMic)
            LobbyButton(if (videoOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, if (videoOff) "Start video" else "Stop video", videoOff, onToggleVideo)
            if (!videoOff) LobbyButton(Icons.Outlined.Cameraswitch, "Switch camera", false, onFlip)
        }
    }
}

@Composable
private fun LobbyButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, off: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (off) Color(0xCCEF4444) else Color(0x99000000)).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp), tint = Color.White) }
}

/** `checkNetworkSpeed`: `GET /api/health` round trip — <100 good, <300 medium, else poor. */
@Composable
private fun NetworkBadge(modifier: Modifier) {
    val context = LocalContext.current
    var nonce by remember { mutableIntStateOf(0) }
    var rtt by remember { mutableStateOf<Long?>(null) }
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(nonce) {
        rtt = null
        offline = false
        val started = System.nanoTime()
        val ok = withContext(Dispatchers.IO) {
            runCatching { AppContainer.get(context).api.execute(ApiRequest(path = "health")) }.isSuccess
        }
        if (ok) rtt = (System.nanoTime() - started) / 1_000_000 else offline = true
    }
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val value = rtt
        when {
            offline -> Text("⚠ Offline", color = Color(0xFFEF4444), fontSize = 10.9.sp)
            value == null -> Text("Checking…", color = Color.White.copy(alpha = .7f), fontSize = 10.9.sp)
            else -> {
                Box(Modifier.size(7.dp).clip(CircleShape).background(networkColor(value)))
                Text("${value}ms", color = Color.White.copy(alpha = .7f), fontSize = 10.9.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text("↻", Modifier.clickable(onClickLabel = "Recheck network") { nonce++ }, color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
    }
}

fun networkColor(rttMs: Long): Color = when {
    rttMs < 100 -> Color(0xFF10B981)
    rttMs < 300 -> Color(0xFFF59E0B)
    else -> Color(0xFFEF4444)
}

@Composable
private fun MeetingInfo(meeting: Meeting, code: String, onJoin: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.6.dp)) {
        Text(meeting.title ?: "Meeting", color = Color.White, fontSize = 22.4.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Code: $code", color = Color(0xFF888888), fontSize = 12.8.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                "Copy code",
                Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }.padding(3.dp),
                tint = Color(0xFF888888),
            )
        }
        meeting.organizerName?.let { Text("Hosted by $it", color = Muted, fontSize = 13.3.sp) }
        Box(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Sky).clickable(onClick = onJoin).padding(10.4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (meeting.isEnded()) "Rejoin meeting" else "Join now", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Web `HuddleAutoJoin.tsx`: no lobby; voice calls join with the camera off. */
@Composable
fun HuddleAutoJoinScreen(code: String, user: AinoUser, onJoined: (String) -> Unit, onBackToChat: () -> Unit) {
    val context = LocalContext.current
    val (meeting, failed) = rememberMeeting(code)
    val withPermissions = rememberCallPermissions()
    LaunchedEffect(meeting) {
        val m = meeting ?: return@LaunchedEffect
        withPermissions(m.isVideoCall()) {
            val videoOff = !m.isVideoCall() || !hasPermission(context, Manifest.permission.CAMERA)
            MeetingRuntime.get(context).joinAs(m, user, muted = !hasPermission(context, Manifest.permission.RECORD_AUDIO), videoOff = videoOff)
            onJoined(code)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F17)), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Couldn't join the call. It may have ended or you may not be invited.", Modifier.padding(horizontal = 24.dp), color = Color(0xFFFCA5A5), fontSize = 15.sp, textAlign = TextAlign.Center)
                Text(
                    "Back to chat",
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2563EB)).clickable(onClick = onBackToChat).padding(horizontal = 18.dp, vertical = 8.dp),
                    color = Color.White,
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(38.dp), color = Color.White, trackColor = Color.White.copy(alpha = .2f), strokeWidth = 3.dp)
                Text("Connecting to call…", color = Color(0xFFE5E7EB).copy(alpha = .8f), fontSize = 14.sp)
            }
        }
    }
}
