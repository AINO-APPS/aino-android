package app.aino.mobile.feature.meeting

import android.Manifest
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.call.PipState
import app.aino.mobile.core.call.hasPermission
import app.aino.mobile.core.call.rememberCallPermissions
import app.aino.mobile.core.call.webrtc.VideoRenderer
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.media.openAuthenticatedFile
import app.aino.mobile.core.media.resolveServerMediaUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.VideoTrack

// MeetingRoom.css theme variables.
internal val MrBg = Color(0xFF1F2937)
internal val MrBgDark = Color(0xFF111827)
internal val MrTile = Color(0xFF374151)
internal val MrText = Color(0xFFF9FAFB)
internal val MrMuted = Color(0xFF9CA3AF)
internal val MrAccent = Color(0xFF3B82F6)
internal val MrDanger = Color(0xFFEF4444)
internal val MrSuccess = Color(0xFF10B981)
internal val MrBorder = Color(0xFF4B5563)
private val Warn = Color(0xFFF59E0B)

private enum class Panel { Chat, Participants }

/**
 * Web `MeetingRoom.tsx` at ≤480px: header (title, code, timer, minimise),
 * connection banner, participant grid / presenter layout, the 60px bottom
 * bar, and full-body Chat / Participants panels. Opened from a link without
 * a session it joins first ("Joining meeting…").
 */
@Composable
fun MeetingRoomScreen(code: String, user: AinoUser, online: Boolean, onMinimize: () -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val session = remember { MeetingRuntime.get(context) }
    val state by session.state.collectAsStateWithLifecycle()
    val inPip by PipState.inPip.collectAsStateWithLifecycle()
    var wasInMeeting by remember { mutableStateOf(false) }
    var joinFailed by remember { mutableStateOf(false) }
    val withPermissions = rememberCallPermissions()
    val current = state?.takeIf { it.code == code }

    LaunchedEffect(current != null) {
        if (current != null) {
            wasInMeeting = true
        } else if (wasInMeeting) {
            onLeft() // ended, left, or ended by the host
        } else {
            val meeting = withContext(Dispatchers.IO) {
                runCatching { MeetingRepository(AppContainer.get(context).api).meeting(code) }.getOrNull()
            }
            if (meeting == null) {
                joinFailed = true
            } else {
                withPermissions(true) {
                    session.joinAs(
                        meeting,
                        user,
                        muted = !hasPermission(context, Manifest.permission.RECORD_AUDIO),
                        videoOff = !hasPermission(context, Manifest.permission.CAMERA),
                    )
                }
            }
        }
    }
    BackHandler(onBack = onMinimize)

    if (current == null) {
        Box(Modifier.fillMaxSize().background(MrBgDark), contentAlignment = Alignment.Center) {
            if (joinFailed) {
                Text("Meeting not found or you are not invited.", Modifier.padding(24.dp), color = MrText, fontSize = 15.sp, textAlign = TextAlign.Center)
            } else {
                StatusCard("Joining meeting…", "Setting up your camera and microphone.", spinner = true)
            }
        }
        return
    }
    if (inPip) {
        PipTile(current)
        return
    }

    var panel by remember { mutableStateOf<Panel?>(null) }
    LaunchedEffect(panel) { session.setChatOpen(panel == Panel.Chat) }
    Column(Modifier.fillMaxSize().background(MrBgDark)) {
        RoomHeader(current, onMinimize)
        val banner = when {
            !online -> "You appear to be offline — trying to reconnect…"
            current.peers.any { it.link == PeerLink.Reconnecting } -> "Reconnecting to a participant…"
            else -> null
        }
        banner?.let { ConnectionBanner(it) }
        Box(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
            val presenter = current.presenter
            if (presenter != null) PresenterLayout(current, presenter, session) else ParticipantGrid(current, session)
            when (panel) {
                Panel.Chat -> SidePanel("Chat", onClose = { panel = null }) { ChatPanel(current, session) }
                Panel.Participants -> SidePanel("Participants", onClose = { panel = null }) { ParticipantsPanel(current, session) }
                null -> Unit
            }
            when (current.status) {
                MeetingStatus.Joining -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)), contentAlignment = Alignment.Center) {
                    StatusCard("Joining meeting…", "Connecting you to other participants.", spinner = true)
                }
                MeetingStatus.Failed -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)), contentAlignment = Alignment.Center) {
                    StatusCard("Unable to join meeting", "Connection failed. Please check your network and try again.", spinner = false) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("Go back", MrTile) { session.leave() }
                            SmallButton("Retry", MrAccent) {
                                val meeting = current.meeting
                                val muted = current.muted
                                val videoOff = current.videoOff
                                session.leave()
                                wasInMeeting = false
                                session.joinAs(meeting, user, muted, videoOff)
                            }
                        }
                    }
                }
                MeetingStatus.Joined -> Unit
            }
        }
        BottomBar(
            current,
            session,
            chatOpen = panel == Panel.Chat,
            onChat = { panel = if (panel == Panel.Chat) null else Panel.Chat },
            onParticipants = { panel = Panel.Participants },
        )
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String, spinner: Boolean, actions: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(MrBg).border(1.dp, MrBorder, RoundedCornerShape(14.dp)).padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (spinner) CircularProgressIndicator(Modifier.size(36.dp), color = MrAccent, trackColor = MrBorder, strokeWidth = 3.dp)
        Text(title, color = MrText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, Modifier.widthIn(max = 280.dp), color = MrMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        actions?.invoke()
    }
}

@Composable
private fun SmallButton(label: String, background: Color, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(8.dp)).background(background).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        color = MrText,
        fontSize = 13.sp,
    )
}

@Composable
private fun RoomHeader(state: MeetingState, onMinimize: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(MrBgDark).statusBarsPadding().heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.meeting.title ?: "Meeting", Modifier.weight(1f, fill = false), color = MrText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).background(MrTile.copy(alpha = .6f))
                    .clickable(onClickLabel = "Click to copy code") {
                        clipboard.setText(AnnotatedString(state.code))
                        copied = true
                    }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(state.code, color = MrMuted, fontSize = 12.sp)
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(12.dp), tint = MrMuted)
            }
        }
        Text(meetingTimer((now - state.joinedAt) / 1000), Modifier.padding(horizontal = 8.dp), color = MrMuted, fontSize = 13.sp)
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MrTile).clickable(onClickLabel = "Minimize meeting", onClick = onMinimize),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.CloseFullscreen, "Minimize meeting", Modifier.size(15.dp), tint = MrText) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MrBorder))
}

@Composable
private fun ConnectionBanner(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(Warn.copy(alpha = .18f)).border(1.dp, Warn.copy(alpha = .45f), RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFDE68A)))
            Text(text, color = Color(0xFFFDE68A), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private data class TileSpec(
    val key: Long,
    val name: String,
    val avatar: String?,
    val video: VideoTrack?,
    val muted: Boolean,
    val handRaised: Boolean,
    val speaking: Boolean,
    val activeSpeaker: Boolean,
    val link: PeerLink?,
)

private fun MeetingState.tiles(now: Long): List<TileSpec> = buildList {
    add(TileSpec(selfId, "$selfName (You)", selfAvatar, localVideo.takeIf { !videoOff }, muted, handRaised, selfLevel > 0.05f, false, null))
    peers.forEach { p ->
        add(
            TileSpec(
                p.userId, p.name, p.avatar, p.video.takeIf { !p.videoOff }, p.muted, p.handRaised,
                speaking = now - p.levelAt <= 1_000 && p.level > 0.05f,
                activeSpeaker = activeSpeakerId == p.userId,
                link = p.link,
            ),
        )
    }
}

/** ≤768px grid (`data-count`): 1 · 1×2 · 2×2 (3rd spans) · 2×2 · 2×3 (scroll) · 3×3 · 4 cols. */
@Composable
private fun ParticipantGrid(state: MeetingState, session: MeetingSession) {
    val tiles = state.tiles(System.currentTimeMillis())
    val columns = meetingGridColumns(tiles.size)
    val rows = meetingGridRows(tiles.size)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 8.dp
        val minHeight = if (tiles.size in 5..6) 140.dp else 0.dp
        val tileHeight = ((maxHeight - gap * (rows - 1)) / rows).coerceAtLeast(minHeight)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(tileHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { tile ->
                        ParticipantTile(tile, Modifier.weight(1f).fillMaxHeight(), onRetry = { session.retryPeer(tile.key) })
                    }
                    // Only a count of 3 spans the last tile; other short rows keep empty cells.
                    if (tiles.size != 3) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PresenterLayout(state: MeetingState, presenter: MeetingPeer, session: MeetingSession) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            VideoRenderer(presenter.screen, Modifier.fillMaxSize(), fit = true)
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.DesktopWindows, null, Modifier.size(14.dp), tint = MrText)
                Text("${presenter.name} is presenting", color = MrText, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(110.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tiles(System.currentTimeMillis()).forEach { tile ->
                ParticipantTile(tile, Modifier.width(160.dp).fillMaxHeight(), mini = true, onRetry = { session.retryPeer(tile.key) })
            }
        }
    }
}

@Composable
private fun ParticipantTile(tile: TileSpec, modifier: Modifier, mini: Boolean = false, onRetry: () -> Unit) {
    val ring = when {
        tile.activeSpeaker -> MrAccent
        tile.speaking -> MrSuccess
        else -> null
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MrTile)
            .then(if (ring != null) Modifier.border(3.dp, ring, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        if (tile.video != null && tile.link != PeerLink.Failed) {
            VideoRenderer(tile.video, Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UserAvatar(tile.name.removeSuffix(" (You)"), tile.avatar, if (mini) 32.dp else 48.dp, background = MrAccent)
                if (!mini) tile.link?.let { StatusPill(it, onRetry) }
            }
        }
        if (tile.handRaised) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).clip(CircleShape).background(Warn), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PanTool, "Hand raised", Modifier.size(15.dp), tint = Color.White)
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f))))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tile.name, Modifier.weight(1f), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tile.muted) Box(Modifier.size(18.dp).clip(CircleShape).background(MrDanger), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MicOff, "Muted", Modifier.size(12.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun StatusPill(link: PeerLink, onRetry: () -> Unit) {
    val (text, background, color) = when (link) {
        PeerLink.Connecting -> Triple("Connecting…", Color.Black.copy(alpha = .55f), MrText)
        PeerLink.Reconnecting -> Triple("Reconnecting…", Warn.copy(alpha = .25f), Color(0xFFFDE68A))
        PeerLink.Failed -> Triple("Couldn't connect", MrDanger.copy(alpha = .25f), MrText)
        PeerLink.Connected -> return
    }
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (link) {
            PeerLink.Connecting -> CircularProgressIndicator(Modifier.size(10.dp), color = MrText, strokeWidth = 1.5.dp)
            PeerLink.Reconnecting -> Icon(Icons.Outlined.WifiOff, null, Modifier.size(12.dp), tint = color)
            else -> Unit
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        if (link == PeerLink.Failed) {
            Text(
                "Retry",
                Modifier.clip(RoundedCornerShape(6.dp)).background(MrAccent).clickable(onClickLabel = "Retry connecting to this participant", onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** System PiP: the active speaker (or first remote) fills the window. */
@Composable
private fun PipTile(state: MeetingState) {
    val focus = state.peers.firstOrNull { it.userId == state.activeSpeakerId } ?: state.peers.firstOrNull()
    val tile = state.tiles(System.currentTimeMillis()).firstOrNull { it.key == (focus?.userId ?: state.selfId) } ?: return
    ParticipantTile(tile, Modifier.fillMaxSize(), mini = true, onRetry = {})
}

@Composable
private fun BottomBar(state: MeetingState, session: MeetingSession, chatOpen: Boolean, onChat: () -> Unit, onParticipants: () -> Unit) {
    val withPermissions = rememberCallPermissions()
    var moreOpen by remember { mutableStateOf(false) }
    val hands = state.peers.count { it.handRaised } + if (state.handRaised) 1 else 0
    Column(Modifier.fillMaxWidth().background(MrBgDark)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MrBorder))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                BarButton(if (state.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (state.muted) "Unmute (Alt+A)" else "Mute (Alt+A)", if (state.muted) MrDanger else MrTile) {
                    withPermissions(false) { session.toggleMute() }
                }
                BarButton(if (state.videoOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, if (state.videoOff) "Start Video (Alt+V)" else "Stop Video (Alt+V)", if (state.videoOff) MrDanger else MrTile) {
                    withPermissions(true) { session.toggleVideo() }
                }
                BarButton(Icons.Outlined.PanTool, if (state.handRaised) "Lower Hand" else "Raise Hand", if (state.handRaised) MrAccent else MrTile, badge = hands.takeIf { it > 0 }, badgeColor = Warn, onClick = session::toggleHand)
                BarButton(Icons.Outlined.ChatBubbleOutline, "Chat", if (chatOpen) MrAccent else MrTile, badge = state.unread.takeIf { it > 0 && !chatOpen }, onClick = onChat)
                Box {
                    BarButton(Icons.Outlined.MoreHoriz, "More actions", if (moreOpen) MrAccent else MrTile) { moreOpen = true }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, containerColor = MrBg) {
                        MoreItem(Icons.Outlined.People, "Participants (${state.peers.size + 1})") {
                            moreOpen = false
                            onParticipants()
                        }
                        if (state.isHost) MoreItem(Icons.Outlined.MicOff, "Mute All") {
                            moreOpen = false
                            session.muteAll()
                        }
                        if (!state.videoOff) MoreItem(Icons.Outlined.Cameraswitch, "Switch camera") {
                            moreOpen = false
                            session.switchCamera()
                        }
                        MoreItem(Icons.AutoMirrored.Outlined.VolumeUp, if (state.speakerOn) "Speaker off" else "Speaker on") {
                            moreOpen = false
                            session.toggleSpeaker()
                        }
                    }
                }
            }
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(MrDanger).clickable(onClickLabel = "Leave meeting", onClick = session::leave).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CallEnd, "Leave meeting", Modifier.size(18.dp), tint = Color.White) }
            if (state.isHost) {
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(MrDanger).clickable(onClickLabel = "End meeting for all", onClick = session::endForAll).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("End All", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = MrText, fontSize = 13.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = MrText) },
        onClick = onClick,
    )
}

@Composable
private fun BarButton(icon: ImageVector, label: String, background: Color, badge: Int? = null, badgeColor: Color = MrAccent, size: Dp = 36.dp, onClick: () -> Unit) {
    Box {
        Box(
            Modifier.size(size).clip(CircleShape).background(background).clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(18.dp), tint = Color.White) }
        badge?.let {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 0.dp).heightIn(min = 16.dp).widthIn(min = 16.dp).clip(RoundedCornerShape(999.dp)).background(badgeColor).padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (it > 99) "99+" else it.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** ≤768px: panels cover the body (`inset: 0`), header and bottom bar stay visible. */
@Composable
private fun SidePanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(MrBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = MrText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("✕", Modifier.clickable(onClickLabel = "Close", onClick = onClose).padding(4.dp), color = MrMuted, fontSize = 18.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MrBorder))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

private val TimeFormat = DateTimeFormatter.ofPattern("hh:mm")

private fun chatTime(iso: String?): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(TimeFormat)
}.getOrElse { "" }

@Composable
private fun ChatPanel(state: MeetingState, session: MeetingSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        if (size > 10L * 1024 * 1024) {
            Toast.makeText(context, "File must be under 10MB", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@rememberLauncherForActivityResult
        if (bytes.size > 10 * 1024 * 1024) {
            Toast.makeText(context, "File must be under 10MB", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        session.sendFile(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (state.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.TopCenter) {
                Text("No messages yet", color = MrMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages, key = { it.key }) { m ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(m.senderName ?: "Participant", color = MrAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        val alpha = if (m.delivery == ChatDelivery.Sending) .65f else 1f
                        m.text?.takeIf { m.fileUrl == null || !it.startsWith("📎") }?.let {
                            Text(it, color = MrText.copy(alpha = alpha), fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        if (m.fileName != null || m.fileUrl != null) {
                            Row(
                                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(MrTile)
                                    .clickable(enabled = m.fileUrl != null) {
                                        m.fileUrl?.let { url -> scope.launch { openAuthenticatedFile(context, resolveServerMediaUrl(url), m.fileName, null) } }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Outlined.Description, null, Modifier.size(16.dp), tint = MrMuted)
                                Column {
                                    Text(m.fileName ?: "File", color = MrAccent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    meetingFileSize(m.fileSize).takeIf(String::isNotEmpty)?.let { Text(it, color = MrMuted, fontSize = 10.sp) }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(chatTime(m.createdAt), color = MrMuted, fontSize = 10.sp)
                            when (m.delivery) {
                                ChatDelivery.Uploading -> Text("Uploading…", color = MrMuted, fontSize = 10.sp)
                                ChatDelivery.Sending -> Text("Sending…", color = MrMuted, fontSize = 10.sp)
                                ChatDelivery.Failed -> Row(
                                    Modifier.clip(RoundedCornerShape(999.dp)).background(MrDanger)
                                        .clickable(onClickLabel = m.failureReason?.let { "Failed: $it. Tap to retry." } ?: "Failed — tap to retry") { m.clientMsgId?.let(session::retryMessage) }
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(10.dp), tint = Color.White)
                                    Text("Failed", color = Color.White, fontSize = 10.sp)
                                }
                                ChatDelivery.Sent -> Unit
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MrBorder))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.AttachFile, "Attach file", Modifier.size(28.dp).clip(CircleShape).clickable { picker.launch("*/*") }.padding(5.dp), tint = MrMuted)
            BasicTextField(
                draft,
                { draft = it },
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(MrTile).border(1.dp, MrBorder, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                textStyle = TextStyle(color = MrText, fontSize = 13.sp),
                cursorBrush = SolidColor(MrAccent),
                maxLines = 4,
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("Send a message…", color = MrMuted, fontSize = 13.sp)
                    inner()
                },
            )
            val canSend = draft.isNotBlank()
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (canSend) MrAccent else MrAccent.copy(alpha = .5f))
                    .clickable(enabled = canSend, onClickLabel = "Send") {
                        session.sendChat(draft)
                        draft = ""
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send", Modifier.size(16.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun ParticipantsPanel(state: MeetingState, session: MeetingSession) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MeetingUserHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, adding) {
        if (!adding || query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        val existing = state.peers.map { it.userId }.toSet() + state.selfId
        results = withContext(Dispatchers.IO) {
            runCatching { MeetingRepository(AppContainer.get(context).api).searchUsers(query) }.getOrDefault(emptyList())
        }.filterNot { it.id in existing }
        searching = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Participants (${state.peers.size})", Modifier.weight(1f), color = MrText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (state.isHost) {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(MrTile).clickable(onClickLabel = "Mute all participants", onClick = session::muteAll).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Outlined.MicOff, null, Modifier.size(14.dp), tint = MrText)
                    Text("Mute All", color = MrText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("＋ Add", Modifier.clickable { adding = !adding }.padding(4.dp), color = MrAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (adding) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MrTile).border(1.dp, MrBorder, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                    textStyle = TextStyle(color = MrText, fontSize = 12.sp),
                    cursorBrush = SolidColor(MrAccent),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Search to add…", color = MrMuted, fontSize = 12.sp)
                        inner()
                    },
                )
                if (searching) Text("…", color = MrMuted, fontSize = 12.sp)
                results.forEach { hit ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(hit.display(), Modifier.weight(1f), color = MrText, fontSize = 12.sp)
                        Text(
                            "Invite",
                            Modifier.clip(RoundedCornerShape(4.dp)).background(MrAccent).clickable {
                                session.addParticipant(hit.id)
                                adding = false
                                query = ""
                            }.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = Color.White,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        ParticipantRow(state.selfName, state.selfAvatar, isSelf = true, handRaised = state.handRaised, muted = state.muted, canMute = false, onToggleMute = {})
        state.peers.forEach { p ->
            ParticipantRow(p.name, p.avatar, isSelf = false, handRaised = p.handRaised, muted = p.muted, canMute = state.isHost) {
                session.muteParticipant(p.userId, !p.muted)
            }
        }
    }
}

@Composable
private fun ParticipantRow(name: String, avatar: String?, isSelf: Boolean, handRaised: Boolean, muted: Boolean, canMute: Boolean, onToggleMute: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UserAvatar(name, avatar, 32.dp, background = MrAccent)
        Text(name + if (isSelf) " (you)" else "", Modifier.weight(1f), color = MrText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (handRaised) Icon(Icons.Outlined.PanTool, "Hand raised", Modifier.size(15.dp), tint = Color(0xFFFACC15))
        if (muted) Icon(Icons.Outlined.MicOff, "Muted", Modifier.size(15.dp), tint = MrDanger)
        if (canMute) {
            Icon(
                if (muted) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                if (muted) "Unmute participant" else "Mute participant",
                Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onToggleMute).padding(5.dp),
                tint = MrMuted,
            )
        }
    }
}
