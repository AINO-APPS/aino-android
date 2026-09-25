package app.aino.mobile.feature.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.call.formatCallDuration
import app.aino.mobile.core.call.webrtc.VideoRenderer
import app.aino.mobile.core.designsystem.component.avatarInitials
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Sky = Color(0xFF0EA5E9)

/**
 * Web `MeetingPiP.tsx` (≤480px): the floating card shown over the app while
 * a meeting is minimised — self view, title + timer, mic / camera / leave.
 * Drag to move; tap the video to return to the room.
 */
@Composable
fun MeetingPipWidget(state: MeetingState, session: MeetingSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(
        modifier
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); drag += amount } }
            .width(200.dp)
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1A2E))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(14.dp)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF111118)).clickable(onClick = onOpen)) {
            if (!state.videoOff && state.localVideo != null) {
                VideoRenderer(state.localVideo, Modifier.fillMaxSize(), overlay = true)
            } else {
                Box(
                    Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Sky, Color(0xFF6366F1)))),
                    contentAlignment = Alignment.Center,
                ) { Text(avatarInitials(state.selfName).take(1), color = Color.White, fontSize = 25.6.sp, fontWeight = FontWeight.Bold) }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(state.meeting.title ?: "Meeting", Modifier.fillMaxWidth(.65f), color = Color.White, fontSize = 10.4.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatCallDuration((now - state.joinedAt) / 1000),
                    Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .4f)).padding(horizontal = 4.dp, vertical = 1.dp),
                    color = Color.White,
                    fontSize = 10.9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF16161E)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            PipButton(if (state.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (state.muted) "Unmute" else "Mute", state.muted, onClick = session::toggleMute)
            PipButton(if (state.videoOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, if (state.videoOff) "Start video" else "Stop video", state.videoOff, onClick = session::toggleVideo)
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(MrDanger).clickable(onClickLabel = "Leave meeting", onClick = session::leave),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CallEnd, "Leave meeting", Modifier.size(15.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun PipButton(icon: ImageVector, label: String, off: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(if (off) MrDanger.copy(alpha = .2f) else Color.White.copy(alpha = .08f)).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(15.dp), tint = if (off) Color(0xFFFCA5A5) else Color.White) }
}

/** A `meeting_started` event worth announcing (web `GlobalMeetingNotification`). */
data class MeetingAnnouncement(val meetingId: Long, val code: String, val title: String?, val organizerName: String?, val restarted: Boolean)

fun meetingAnnouncement(data: kotlinx.serialization.json.JsonObject): MeetingAnnouncement? {
    val id = data.long("meetingId") ?: return null
    val code = data.string("meetingCode") ?: return null
    return MeetingAnnouncement(id, code, data.string("title"), data.string("organizerName"), data.flag("restarted") == true)
}

/** Web `GlobalMeetingNotification` card (≤480px: 280 wide); auto-dismisses after 60 s. */
@Composable
fun MeetingStartedCard(announcement: MeetingAnnouncement, onJoin: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(announcement) {
        delay(60_000)
        onDismiss()
    }
    val verb = if (announcement.restarted) "restarted" else "started"
    Column(
        modifier.width(280.dp).shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1A2E)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Sky.copy(alpha = .18f), Sky.copy(alpha = .06f)))).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Sky))
            Text(if (announcement.restarted) "MEETING RESTARTED" else "MEETING STARTED", Modifier.weight(1f), color = Color.White.copy(alpha = .55f), fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text("✕", Modifier.clickable(onClickLabel = "Dismiss", onClick = onDismiss), color = Color.White.copy(alpha = .55f), fontSize = 14.sp)
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
            Text(announcement.title ?: "Meeting", color = Color.White, fontSize = 15.2.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${announcement.organizerName.orEmpty()} $verb this meeting", color = Color.White.copy(alpha = .45f), fontSize = 12.5.sp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Sky).clickable(onClick = onJoin).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Join now", color = Color.White, fontSize = 13.6.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .06f)).border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Dismiss", color = Color.White.copy(alpha = .7f), fontSize = 13.6.sp) }
        }
    }
}
