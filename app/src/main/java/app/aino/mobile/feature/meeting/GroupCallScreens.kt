package app.aino.mobile.feature.meeting

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.call.PipState
import app.aino.mobile.core.call.hasPermission
import app.aino.mobile.core.call.rememberCallPermissions
import app.aino.mobile.core.call.webrtc.LocalMedia
import app.aino.mobile.core.call.webrtc.VideoRenderer
import app.aino.mobile.core.call.webrtc.WebRtcRuntime
import app.aino.mobile.core.designsystem.component.GroupAvatar
import app.aino.mobile.core.designsystem.component.GroupAvatarMember
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.media.resolveServerMediaUrl
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.VideoTrack

private val CallBg = Color(0xFF121212)
private val CallSurface = Color(0xFF2B2B2D)
private val CallText = Color(0xFFF1F1F1)
private val CallMuted = Color(0xFFB9B9B9)
private val CallEndRed = Color(0xFFE53935)
private val CallJoinGreen = Color(0xFF2E9E5B)
private val SpeakerRing = Color(0xFFFFFFFF)

/** Quick reactions offered in the call (any emoji the server accepts works). */
val CALL_REACTIONS = listOf("❤️", "👍", "👎", "😂", "😮", "😢", "👏", "🎉")

// ── Lobby ────────────────────────────────────────────────────────────────

/**
 * Pre-join screen for a group call: camera preview (or the group's avatar),
 * who is already in the call, mic/camera/ring toggles and Start/Join.
 * Joining an existing call never rings; starting one rings the group unless
 * the Ring toggle is off.
 */
@Composable
fun GroupCallLobbyScreen(
    conversationId: Long,
    callType: String,
    user: AinoUser,
    onJoined: (code: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { GroupCallRepository(AppContainer.get(context).api) }
    var state by remember { mutableStateOf(GroupCallLobbyState()) }
    var permitted by remember { mutableStateOf(false) }
    val withPermissions = rememberCallPermissions()
    val video = callType == "video"
    LaunchedEffect(Unit) { withPermissions(video) { permitted = true } }
    val preview = remember(permitted) { if (permitted && video) LocalMedia(context, WebRtcRuntime.shared(context), withVideo = true) else null }
    var muted by remember(permitted) { mutableStateOf(!hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var videoOff by remember(permitted) { mutableStateOf(!video) }
    var ring by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    DisposableEffect(preview) {
        if (preview != null) videoOff = !preview.setVideoEnabled(hasPermission(context, Manifest.permission.CAMERA))
        onDispose { preview?.dispose() }
    }
    LaunchedEffect(conversationId) {
        // The roster refreshes while the lobby is open, so "Join" stays accurate.
        while (true) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    Triple(
                        state.group ?: repository.group(conversationId),
                        state.members.ifEmpty { repository.members(conversationId) },
                        repository.activeCall(conversationId),
                    )
                }
            }
            loaded.onSuccess { (group, members, active) -> state = state.copy(group = group, members = members, active = active, loading = false) }
                .onFailure { if (state.loading) state = state.copy(loading = false, error = "Couldn't load the group") }
            delay(5_000)
        }
    }

    fun go() {
        val active = state.active
        if (active != null && active.participants.isNotEmpty()) {
            preview?.dispose()
            joinHuddle(context, active.meetingCode, user, muted, videoOff) { ok ->
                if (ok) onJoined(active.meetingCode) else state = state.copy(error = "Couldn't join the call")
            }
            return
        }
        state = state.copy(starting = true, error = null)
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching { repository.start(conversationId, state.group?.name, callType, ring) }
            }
            code.onSuccess { started ->
                preview?.dispose()
                joinHuddle(context, started, user, muted, videoOff) { ok ->
                    if (ok) onJoined(started) else state = state.copy(starting = false, error = "Couldn't join the call")
                }
            }.onFailure { state = state.copy(starting = false, error = it.message ?: "Couldn't start the call") }
        }
    }

    val members = state.members.map { GroupAvatarMember(it.display(), it.avatar) }
    Box(Modifier.fillMaxSize().background(CallBg)) {
        if (!videoOff && preview?.videoTrack != null) {
            VideoRenderer(preview.videoTrack, Modifier.fillMaxSize(), mirror = true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent, Color.Black.copy(alpha = .7f)))))
        } else {
            state.group?.avatar?.takeIf(String::isNotBlank)?.let { BlurredBackdrop(it) }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Outlined.KeyboardArrowDown, "Back", CallSurface.copy(alpha = .6f), onClick = onBack)
            }
            Spacer(Modifier.height(24.dp))
            if (videoOff || preview == null) GroupAvatar(state.group?.name, state.group?.avatar, members, "conv-$conversationId", 112.dp)
            Text(
                state.group?.name ?: "Group call", Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
                color = CallText, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (state.loading) "Checking who's here…" else lobbyPresenceText(state.active, user.id),
                Modifier.padding(top = 6.dp), color = CallMuted, fontSize = 15.sp,
            )
            state.active?.participants?.takeIf { it.isNotEmpty() }?.let { inCall ->
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    inCall.take(5).forEach { UserAvatar(it.fullName, it.avatar, 32.dp, Modifier.border(2.dp, CallBg, CircleShape)) }
                }
            }
            state.error?.let { Text(it, Modifier.padding(16.dp), color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToggleButton(if (videoOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, "Camera", on = !videoOff) {
                    withPermissions(true) { videoOff = !(preview?.setVideoEnabled(videoOff) ?: false) }
                }
                ToggleButton(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, "Mic", on = !muted) {
                    withPermissions(false) { muted = !muted; preview?.setMuted(muted) }
                }
                if (state.active?.participants.isNullOrEmpty()) {
                    ToggleButton(if (ring) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff, if (ring) "Ring" else "Silent", on = ring) { ring = !ring }
                }
            }
            if (state.active?.participants.isNullOrEmpty()) {
                Text(
                    if (ring) "Members will be rung" else "Members will see a Join banner, no ring",
                    Modifier.padding(top = 10.dp), color = CallMuted, fontSize = 13.sp,
                )
            }
            Text(
                lobbyPrimaryLabel(state.active),
                Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(if (state.starting || state.loading) CallJoinGreen.copy(alpha = .5f) else CallJoinGreen)
                    .clickable(enabled = !state.starting && !state.loading) { withPermissions(!videoOff) { go() } }
                    .padding(vertical = 16.dp),
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Loads the meeting by code and joins its mesh; [done] reports success on the main thread. */
private fun joinHuddle(context: android.content.Context, code: String, user: AinoUser, muted: Boolean, videoOff: Boolean, done: (Boolean) -> Unit) {
    val api = AppContainer.get(context).api
    val session = MeetingRuntime.get(context)
    kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
        val meeting = withContext(Dispatchers.IO) { runCatching { MeetingRepository(api).meeting(code) }.getOrNull() }
        if (meeting == null) return@launch done(false)
        session.joinAs(meeting, user, muted, videoOff)
        done(true)
    }
}

@Composable
private fun BlurredBackdrop(url: String) {
    val context = LocalContext.current
    coil3.compose.AsyncImage(
        model = remember(url) { coil3.request.ImageRequest.Builder(context).data(resolveServerMediaUrl(url)).build() },
        imageLoader = AppContainer.get(context).imageLoader,
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.fillMaxSize().blur(40.dp).alpha(.45f),
    )
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (on) Color.White else CallSurface).clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(26.dp), tint = if (on) Color.Black else Color.White) }
        Text(label, Modifier.padding(top = 6.dp), color = CallText, fontSize = 12.sp)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, background: Color, size: androidx.compose.ui.unit.Dp = 44.dp, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * .5f), tint = tint) }
}

// ── In call ──────────────────────────────────────────────────────────────

/** Layout for [remoteCount] other people: alone, one-on-one with a floating self view, a grid, or speaker view. */
enum class GroupCallLayout { Alone, OneOnOne, Grid, Speaker }

fun groupCallLayout(remoteCount: Int): GroupCallLayout = when {
    remoteCount <= 0 -> GroupCallLayout.Alone
    remoteCount == 1 -> GroupCallLayout.OneOnOne
    remoteCount <= 4 -> GroupCallLayout.Grid
    else -> GroupCallLayout.Speaker
}

/** The remote shown large in speaker view: the active speaker, else whoever has video, else the first. */
fun speakerFocus(peers: List<MeetingPeer>, activeSpeakerId: Long?): MeetingPeer? =
    peers.firstOrNull { it.userId == activeSpeakerId } ?: peers.firstOrNull { it.video != null && !it.videoOff } ?: peers.firstOrNull()

/**
 * The in-call screen for group calls (huddles), laid out like a modern
 * secure-messenger group call: dark full screen, remote video large, your
 * own camera in a draggable corner, an auto-hiding control tray, the
 * participants sheet, raise hand and floating emoji reactions. Scheduled
 * meetings keep [MeetingRoomScreen].
 */
@Composable
fun GroupCallScreen(code: String, user: AinoUser, online: Boolean, onMinimize: () -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val session = remember { MeetingRuntime.get(context) }
    val state by session.state.collectAsStateWithLifecycle()
    val inPip by PipState.inPip.collectAsStateWithLifecycle()
    val current = state?.takeIf { it.code == code }
    var wasIn by remember { mutableStateOf(false) }
    LaunchedEffect(current != null) {
        if (current != null) wasIn = true else if (wasIn) onLeft()
    }
    BackHandler(onBack = onMinimize)
    if (current == null) {
        Box(Modifier.fillMaxSize().background(CallBg), contentAlignment = Alignment.Center) {
            if (wasIn) Text("Call ended", color = CallMuted, fontSize = 16.sp)
            else CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 2.dp)
        }
        return
    }
    if (inPip) {
        val focus = speakerFocus(current.peers, current.activeSpeakerId)
        if (focus != null) RemoteTile(focus, active = false, Modifier.fillMaxSize()) else SelfTile(current, Modifier.fillMaxSize())
        return
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var participantsOpen by remember { mutableStateOf(false) }
    var reactionsOpen by remember { mutableStateOf(false) }
    // The tray hides after a few seconds of video; audio-only calls keep it up.
    val anyVideo = current.peers.any { it.video != null && !it.videoOff } || !current.videoOff
    LaunchedEffect(lastTouch, anyVideo, participantsOpen, reactionsOpen) {
        controlsVisible = true
        if (anyVideo && !participantsOpen && !reactionsOpen) { delay(5_000); controlsVisible = false }
    }
    val now by produceTicker()
    Box(
        Modifier.fillMaxSize().background(CallBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { lastTouch = System.currentTimeMillis() },
    ) {
        CallStage(current, session)
        ReactionLayer(current.reactions, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 180.dp))
        AnimatedVisibility(controlsVisible, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }, modifier = Modifier.align(Alignment.TopCenter)) {
            CallTopBar(current, now, online, onMinimize = onMinimize, onParticipants = { participantsOpen = true })
        }
        AnimatedVisibility(controlsVisible, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            ControlTray(current, session, onReactions = { reactionsOpen = true })
        }
    }
    if (participantsOpen) ParticipantsSheet(current, session, user) { participantsOpen = false }
    if (reactionsOpen) ReactionPicker(onPick = { session.sendReaction(it); reactionsOpen = false }) { reactionsOpen = false }
}

@Composable
private fun produceTicker() = androidx.compose.runtime.produceState(System.currentTimeMillis()) {
    while (true) { delay(1_000); value = System.currentTimeMillis() }
}

/** "1:05" / "1:02:09". */
fun callElapsed(startMs: Long, nowMs: Long): String {
    val total = ((nowMs - startMs) / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun CallTopBar(state: MeetingState, now: Long, online: Boolean, onMinimize: () -> Unit, onParticipants: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
            .statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(Icons.Outlined.KeyboardArrowDown, "Minimize", Color.Transparent, onClick = onMinimize)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.meeting.title ?: "Group call", color = CallText, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                !online -> "Reconnecting…"
                state.status == MeetingStatus.Joining -> "Connecting…"
                state.status == MeetingStatus.Failed -> "Couldn't connect"
                state.peers.isEmpty() -> "Waiting for others · ${callElapsed(state.joinedAt, now)}"
                else -> callElapsed(state.joinedAt, now)
            }
            Text(status, color = CallMuted, fontSize = 13.sp)
        }
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClickLabel = "Participants", onClick = onParticipants).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Group, null, Modifier.size(20.dp), tint = CallText)
            Text(" ${state.peers.size + 1}", color = CallText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CallStage(state: MeetingState, session: MeetingSession) {
    when (groupCallLayout(state.peers.size)) {
        GroupCallLayout.Alone -> Box(Modifier.fillMaxSize()) {
            SelfTile(state, Modifier.fillMaxSize())
            if (state.videoOff) Text(
                "Waiting for others to join",
                Modifier.align(Alignment.Center).padding(top = 180.dp), color = CallMuted, fontSize = 15.sp,
            )
        }
        GroupCallLayout.OneOnOne -> Box(Modifier.fillMaxSize()) {
            RemoteTile(state.peers.first(), active = false, Modifier.fillMaxSize(), onRetry = { session.retryPeer(state.peers.first().userId) })
            FloatingSelfView(state)
        }
        GroupCallLayout.Grid -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 64.dp, bottom = 112.dp, start = 6.dp, end = 6.dp)) {
            val peers = state.peers
            val rows = peers.chunked(2)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { peer ->
                            RemoteTile(peer, active = peer.userId == state.activeSpeakerId, Modifier.weight(1f).fillMaxHeight(), onRetry = { session.retryPeer(peer.userId) })
                        }
                    }
                }
            }
            FloatingSelfView(state)
        }
        GroupCallLayout.Speaker -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 64.dp, bottom = 112.dp)) {
            val focus = speakerFocus(state.peers, state.activeSpeakerId)!!
            RemoteTile(focus, active = false, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp), onRetry = { session.retryPeer(focus.userId) })
            Row(Modifier.fillMaxWidth().height(112.dp).padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.width(0.dp))
                SelfTile(state, Modifier.width(84.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)))
                state.peers.filter { it.userId != focus.userId }.forEach { peer ->
                    RemoteTile(peer, active = peer.userId == state.activeSpeakerId, Modifier.width(84.dp).fillMaxHeight(), small = true)
                }
            }
        }
    }
}

@Composable
private fun RemoteTile(peer: MeetingPeer, active: Boolean, modifier: Modifier, small: Boolean = false, onRetry: () -> Unit = {}) {
    val ring by animateFloatAsState(if (active) 1f else 0f, tween(200), label = "speakerRing")
    Box(
        modifier.clip(RoundedCornerShape(if (small) 12.dp else 16.dp)).background(CallSurface)
            .border((3 * ring).dp, SpeakerRing.copy(alpha = ring), RoundedCornerShape(if (small) 12.dp else 16.dp)),
    ) {
        val video: VideoTrack? = peer.video.takeIf { !peer.videoOff && peer.link != PeerLink.Failed }
        if (video != null) VideoRenderer(video, Modifier.fillMaxSize())
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            UserAvatar(peer.name, peer.avatar, if (small) 40.dp else 88.dp)
            if (!small && peer.link != PeerLink.Connected) {
                Text(
                    when (peer.link) {
                        PeerLink.Connecting -> "Connecting…"
                        PeerLink.Reconnecting -> "Reconnecting…"
                        PeerLink.Failed -> "Couldn't connect · Tap to retry"
                        PeerLink.Connected -> ""
                    },
                    Modifier.padding(top = 10.dp).let { if (peer.link == PeerLink.Failed) it.clickable(onClick = onRetry) else it },
                    color = CallMuted, fontSize = 13.sp,
                )
            }
        }
        if (peer.handRaised) Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(28.dp).clip(CircleShape).background(Color(0xFFFFC107)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PanTool, "Hand raised", Modifier.size(16.dp), tint = Color.Black)
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .45f)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (peer.muted) Icon(Icons.Outlined.MicOff, "Muted", Modifier.size(14.dp), tint = Color.White)
            Text((if (peer.muted) " " else "") + peer.name.substringBefore(' '), color = Color.White, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SelfTile(state: MeetingState, modifier: Modifier) {
    Box(modifier.background(CallSurface)) {
        val video = state.localVideo.takeIf { !state.videoOff }
        if (video != null) VideoRenderer(video, Modifier.fillMaxSize(), mirror = true)
        else UserAvatar(state.selfName, state.selfAvatar, 72.dp, Modifier.align(Alignment.Center))
        if (state.muted) Icon(Icons.Outlined.MicOff, "You're muted", Modifier.align(Alignment.BottomStart).padding(8.dp).size(16.dp), tint = Color.White)
    }
}

/** Your own view in a corner; drag it anywhere. */
@Composable
private fun FloatingSelfView(state: MeetingState) {
    val density = LocalDensity.current
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 72.dp, bottom = 124.dp, end = 12.dp, start = 12.dp)) {
        val w = 96.dp
        val h = 144.dp
        val maxX = with(density) { (maxWidth - w).toPx() }
        val maxY = with(density) { (maxHeight - h).toPx() }
        SelfTile(
            state,
            Modifier.offset { IntOffset((maxX + offset.x).roundToInt(), (maxY + offset.y).roundToInt()) }
                .size(w, h).clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset = Offset((offset.x + drag.x).coerceIn(-maxX, 0f), (offset.y + drag.y).coerceIn(-maxY, 0f))
                    }
                },
        )
    }
}

@Composable
private fun ControlTray(state: MeetingState, session: MeetingSession, onReactions: () -> Unit) {
    val withPermissions = rememberCallPermissions()
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f))))
            .navigationBarsPadding().padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (!state.videoOff) TrayButton(Icons.Outlined.Cameraswitch, "Flip", on = false, onClick = session::switchCamera)
            TrayButton(if (state.speakerOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, "Speaker", on = state.speakerOn, onClick = session::toggleSpeaker)
            TrayButton(if (state.videoOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, "Camera", on = !state.videoOff) {
                withPermissions(true) { session.toggleVideo() }
            }
            TrayButton(if (state.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, "Mic", on = !state.muted) {
                withPermissions(false) { session.toggleMute() }
            }
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(CallEndRed).clickable(onClickLabel = "Leave call", onClick = session::leave),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CallEnd, "Leave call", Modifier.size(28.dp), tint = Color.White) }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(Icons.Outlined.PanTool, if (state.handRaised) "Lower hand" else "Raise hand", active = state.handRaised, onClick = session::toggleHand)
            PillButton(Icons.Outlined.EmojiEmotions, "React", active = false, onClick = onReactions)
        }
    }
}

@Composable
private fun TrayButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (on) Color.White else CallSurface).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(24.dp), tint = if (on) Color.Black else Color.White) }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (active) Color(0xFFFFC107) else CallSurface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (active) Color.Black else Color.White)
        Text(" $label", color = if (active) Color.Black else Color.White, fontSize = 14.sp)
    }
}

/** Reactions float up from the bottom-left with the sender's name, then fade. */
@Composable
private fun ReactionLayer(reactions: List<CallReactionBurst>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reactions.forEach { burst ->
            androidx.compose.runtime.key(burst.id) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it }) {
                    Row(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(burst.emoji, fontSize = 22.sp)
                        Text("  ${burst.name}", color = Color.White, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CallSurface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CALL_REACTIONS.forEach { emoji ->
                Text(emoji, Modifier.clip(CircleShape).clickable { onPick(emoji) }.padding(8.dp), fontSize = 28.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsSheet(state: MeetingState, session: MeetingSession, user: AinoUser, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CallSurface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("In this call · ${state.peers.size + 1}", Modifier.weight(1f), color = CallText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp), tint = CallText)
                    Text(" Add", color = CallText)
                }
            }
            val hands = state.peers.filter { it.handRaised }
            if (hands.isNotEmpty() || state.handRaised) {
                Text(
                    "Raised hands: " + (listOfNotNull("You".takeIf { state.handRaised }) + hands.map { it.name.substringBefore(' ') }).joinToString(),
                    Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = Color(0xFFFFC107), fontSize = 14.sp,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                item { ParticipantLine(state.selfName + " (You)", state.selfAvatar, state.muted, state.handRaised, canMute = false) {} }
                items(state.peers, key = { it.userId }) { peer ->
                    ParticipantLine(peer.name, peer.avatar, peer.muted, peer.handRaised, canMute = state.isHost && !peer.muted) {
                        session.muteParticipant(peer.userId, true)
                    }
                }
            }
        }
    }
    if (adding) AddToCallSheet(state, session) { adding = false }
}

@Composable
private fun ParticipantLine(name: String, avatar: String?, muted: Boolean, hand: Boolean, canMute: Boolean, onMute: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(name, avatar, 40.dp)
        Text(name, Modifier.weight(1f).padding(start = 16.dp), color = CallText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hand) Icon(Icons.Outlined.PanTool, "Hand raised", Modifier.padding(end = 12.dp).size(18.dp), tint = Color(0xFFFFC107))
        if (canMute) TextButton(onClick = onMute) { Text("Mute", color = CallText) }
        else Icon(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (muted) "Muted" else "Unmuted", Modifier.size(20.dp), tint = CallMuted)
    }
}

/** Invite someone from the organization into the running call (they get a ring). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToCallSheet(state: MeetingState, session: MeetingSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MeetingUserHit>>(emptyList()) }
    var invited by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        results = withContext(Dispatchers.IO) {
            runCatching { MeetingRepository(AppContainer.get(context).api).searchUsers(query) }.getOrDefault(emptyList())
        }
    }
    val inCall = state.peers.map { it.userId }.toSet() + state.selfId
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CallSurface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Add to call", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = CallText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            androidx.compose.foundation.text.BasicTextField(
                query, { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(22.dp)).background(CallBg).padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = CallText, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CallText),
                decorationBox = { inner -> if (query.isEmpty()) Text("Search people", color = CallMuted); inner() },
            )
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(results.filter { it.id !in inCall }, key = { it.id }) { hit ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(hit.fullName ?: hit.username, hit.avatar, 36.dp)
                        Text(hit.fullName ?: hit.username.orEmpty(), Modifier.weight(1f).padding(start = 12.dp), color = CallText, fontSize = 15.sp, maxLines = 1)
                        if (hit.id in invited) Text("Ringing…", color = CallMuted, fontSize = 13.sp)
                        else TextButton(onClick = { session.addParticipant(hit.id); invited = invited + hit.id }) { Text("Add", color = CallText) }
                    }
                }
            }
        }
    }
}
