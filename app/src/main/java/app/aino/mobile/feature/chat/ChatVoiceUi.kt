package app.aino.mobile.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.icons.HeroIcons

fun formatVoiceTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private suspend fun android.content.Context.localAudioDurationMs(url: String): Long =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(this@localAudioDurationMs, android.net.Uri.parse(url))
                    extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { release() }
            }
        }.getOrDefault(0L)
    }

/** Deterministic pseudo-waveform (the server stores no peaks): 0.2..1 bar heights seeded by the url. */
fun voiceWaveform(seed: String, bars: Int = 46): List<Float> {
    var x = seed.hashCode().toLong() and 0xffffffffL
    return List(bars) {
        x = (x * 1103515245L + 12345L) and 0x7fffffffL
        0.2f + (x % 1000) / 1250f
    }
}

/**
 * Signal voice-note bubble: round play button, waveform scrubber, elapsed/total time, speed chip.
 * While [uploading], a thin transfer ring circles the play button (indeterminate until
 * [uploadProgress] is known); the local file stays playable. [waveSeed] keys the
 * pseudo-waveform so the pending bubble and the delivered message draw the same bars.
 */
@Composable
fun ChatVoicePlayer(
    url: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalWebColors.current.primary,
    outgoing: Boolean = false,
    waveSeed: String = url,
    uploading: Boolean = false,
    uploadProgress: Float? = null,
) {
    val signal = signalColors
    val context = LocalContext.current
    val player = AppContainer.get(context).audio
    val state by player.state.collectAsStateWithLifecycle()
    val current = state.url == url
    val playing = current && state.playing
    // Local files (drafts, pending sends) know their length before first play.
    val localDuration by produceState(0L, url) {
        if (url.startsWith("content:") || url.startsWith("file:")) value = context.localAudioDurationMs(url)
    }
    val duration = (if (current) state.durationMs else player.durationOf(url)).takeIf { it > 0 } ?: localDuration
    val position = if (current) state.positionMs else 0
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fg = if (outgoing) signal.onOutgoing else signal.onIncoming
    val fgMuted = if (outgoing) signal.onOutgoingSecondary else signal.onIncomingSecondary
    // Web org theme: bubbles are a light accent wash, so the play button is the accent on both sides.
    val buttonBg = if (outgoing) signal.primary else tint
    val buttonFg = Color.White
    val bars = remember(waveSeed) { voiceWaveform(waveSeed) }
    Row(modifier.widthIn(min = 220.dp, max = 260.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp).background(buttonBg, CircleShape).clickable { player.toggle(url) },
                contentAlignment = Alignment.Center,
            ) {
                if (current && state.buffering && !playing) CircularProgressIndicator(Modifier.size(18.dp), color = buttonFg, strokeWidth = 2.dp)
                else Icon(if (playing) HeroIcons.Pause else HeroIcons.Play, if (playing) "Pause" else "Play", Modifier.size(24.dp), tint = buttonFg)
            }
            androidx.compose.animation.AnimatedVisibility(uploading, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                val ringColor = buttonBg
                if (uploadProgress == null || uploadProgress <= 0f) {
                    CircularProgressIndicator(Modifier.size(44.dp), color = ringColor, strokeWidth = 2.dp)
                } else {
                    val animated by androidx.compose.animation.core.animateFloatAsState(uploadProgress, tween(250), label = "voiceUpload")
                    CircularProgressIndicator(progress = { animated }, modifier = Modifier.size(44.dp), color = ringColor, strokeWidth = 2.dp, trackColor = ringColor.copy(alpha = .2f))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
            var width by remember { mutableIntStateOf(1) }
            Canvas(
                Modifier.fillMaxWidth().height(26.dp)
                    .onSizeChanged { width = it.width.coerceAtLeast(1) }
                    .pointerInput(url) { detectTapGestures { player.seekTo(url, it.x / width) } }
                    .pointerInput(url) { detectHorizontalDragGestures { change, _ -> player.seekTo(url, change.position.x / width) } },
            ) {
                val step = size.width / bars.size
                val barWidth = (step * 0.55f).coerceAtLeast(2f)
                bars.forEachIndexed { index, level ->
                    val h = size.height * level
                    val left = index * step
                    drawRoundRect(
                        color = if ((index + 0.5f) / bars.size <= progress) fg else fgMuted.copy(alpha = .45f),
                        topLeft = Offset(left, (size.height - h) / 2),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                }
            }
            Text(
                when {
                    current && state.error != null -> state.error!!
                    current && position > 0 -> formatVoiceTime(position)
                    duration > 0 -> formatVoiceTime(duration)
                    else -> "--:--"
                },
                color = if (current && state.error != null) LocalWebColors.current.danger else fgMuted,
                fontSize = 12.sp,
            )
        }
        Text(
            "${state.speed.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}x",
            Modifier.clip(RoundedCornerShape(10.dp)).background(fg.copy(alpha = .14f)).clickable { player.cycleSpeed() }
                .padding(horizontal = 7.dp, vertical = 3.dp),
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
/** Scrolling live waveform of the most recent amplitude samples (newest on the right). */
@Composable
fun LiveWaveform(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val barWidth = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val slots = ((size.width + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
        val visible = levels.takeLast(slots)
        val start = size.width - visible.size * (barWidth + gap) + gap
        visible.forEachIndexed { i, level ->
            val h = (size.height * (0.12f + 0.88f * level)).coerceAtLeast(barWidth)
            drawRoundRect(
                color,
                topLeft = Offset(start + i * (barWidth + gap), (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/** Holding / Locked / Paused recording strip that replaces the text pill. */
@Composable
fun VoiceRecordingPanel(
    phase: VoicePhase,
    elapsedMs: Long,
    levels: List<Float>,
    slideOffsetPx: Float,
    onDelete: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Row(
        modifier.heightIn(min = 46.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (phase == VoicePhase.Holding) {
            // Signal: pulsing red mic + timer, and a "‹ Slide to cancel" hint that
            // slides in from the mic, follows the finger and fades toward the cancel point.
            Icon(HeroIcons.Microphone, null, Modifier.size(20.dp).alpha(blink), tint = colors.danger)
            Text(formatVoiceTime(elapsedMs), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val cancelPx = with(LocalDensity.current) { CancelTravel.toPx() }
            val enter = remember { Animatable(0f) }
            LaunchedEffect(Unit) { enter.animateTo(1f, tween(220, easing = LinearOutSlowInEasing)) }
            val nudge by rememberInfiniteTransition(label = "slideHint").animateFloat(
                0f, -6f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "nudge",
            )
            Row(
                Modifier.graphicsLayer {
                    val slid = slideOffsetPx.coerceAtMost(0f)
                    translationX = slid + (1f - enter.value) * 48.dp.toPx()
                    alpha = enter.value * (1f - (-slid / cancelPx)).coerceIn(0f, 1f)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HeroIcons.ChevronLeft, null,
                    Modifier.size(18.dp).graphicsLayer { translationX = nudge.dp.toPx() },
                    tint = colors.textMuted,
                )
                Text("Slide to cancel", color = colors.textMuted, fontSize = 13.sp)
            }
            // Leave room for the floating record button that covers the mic.
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(HeroIcons.Trash, "Delete recording", Modifier.size(22.dp).clickable(onClick = onDelete), tint = colors.danger)
            Icon(
                HeroIcons.Microphone, null,
                Modifier.size(18.dp).alpha(if (phase == VoicePhase.Paused) 1f else blink),
                tint = if (phase == VoicePhase.Paused) colors.textMuted else colors.danger,
            )
            Text(formatVoiceTime(elapsedMs), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            LiveWaveform(levels, if (phase == VoicePhase.Paused) colors.textMuted else colors.primary, Modifier.weight(1f).height(26.dp))
            Icon(
                if (phase == VoicePhase.Paused) HeroIcons.Microphone else HeroIcons.Pause,
                if (phase == VoicePhase.Paused) "Resume recording" else "Pause recording",
                Modifier.size(24.dp).clickable(onClick = onPauseResume),
                tint = if (phase == VoicePhase.Paused) colors.danger else colors.textSecondary,
            )
            Icon(HeroIcons.Stop, "Stop and review", Modifier.size(24.dp).clickable(onClick = onStop), tint = colors.textSecondary)
        }
    }
}

/** Recorded draft: listen (play/pause/seek on the shared player), delete, or send via the action button. */
@Composable
fun VoiceDraftPanel(draftUrl: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    Row(
        modifier.heightIn(min = 46.dp).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HeroIcons.Trash, "Delete recording", Modifier.size(22.dp).clickable(onClick = onDelete), tint = colors.danger)
        ChatVoicePlayer(draftUrl, Modifier.weight(1f).padding(start = 6.dp))
    }
}


private const val RECORD_HIDE_MS = 160
private const val LOCK_HINT_DELAY_MS = 300L
private val RecordFabSize = 72.dp
private val LockTravel = 88.dp
private val CancelTravel = 110.dp
private val LockPillHeight = 72.dp
private val RecordPopSpring = spring<Float>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)

/**
 * Hold-to-record mic inside the composer pill, with Signal-style behaviour:
 * press starts, release sends, slide left cancels, slide up onto the lock
 * target locks. While held, a large red record button
 * pops out under the finger (following it on one axis) and a lock pill rises
 * above it. Both draw in an unclipped popup so the composer pill can't hide them.
 * The composable must stay in composition for the whole gesture, so callers
 * keep it mounted while [holding].
 */
@Composable
fun MicHoldButton(
    holding: Boolean,
    enabled: Boolean,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
    onLock: () -> Unit,
    onSlideCancel: () -> Unit,
    onSlide: (Float) -> Unit,
) {
    val colors = LocalWebColors.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val lockPx = with(density) { LockTravel.toPx() }
    val cancelPx = with(density) { CancelTravel.toPx() }
    val latestEnabled by rememberUpdatedState(enabled)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val lock by rememberUpdatedState(onLock)
    val slideCancel by rememberUpdatedState(onSlideCancel)
    val slide by rememberUpdatedState(onSlide)
    var drag by remember { mutableStateOf(Offset.Zero) }
    var locked by remember { mutableStateOf(false) }
    Box(Modifier.size(width = 42.dp, height = 46.dp), contentAlignment = Alignment.Center) {
        RecordOverlay(holding = holding, drag = drag, locked = locked, lockPx = lockPx)
        Box(
            Modifier.size(42.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!latestEnabled || !press()) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag = Offset.Zero
                        locked = false
                        var settled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!settled) release()
                                break
                            }
                            change.consume()
                            if (settled) continue
                            // Signal: only left or up, whichever the finger favours.
                            val dx = (change.position.x - down.position.x).coerceIn(-cancelPx, 0f)
                            val dy = (change.position.y - down.position.y).coerceIn(-lockPx, 0f)
                            drag = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) Offset(dx, 0f) else Offset(0f, dy)
                            slide(drag.x)
                            when {
                                drag.y <= -lockPx -> {
                                    settled = true
                                    locked = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    lock()
                                }
                                drag.x <= -cancelPx -> { settled = true; slideCancel() }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                HeroIcons.Microphone,
                "Hold to record voice message",
                Modifier.size(20.dp).alpha(if (holding) 0f else 1f),
                tint = colors.textSecondary,
            )
        }
    }
}

/**
 * Floating record button + lock target. The popup's bottom-end corner holds the
 * record button centred on the mic, leaving room above for the lock travel and
 * to the left for the cancel slide.
 */
@Composable
private fun RecordOverlay(holding: Boolean, drag: Offset, locked: Boolean, lockPx: Float) {
    val colors = LocalWebColors.current
    val density = LocalDensity.current
    val fab = remember { Animatable(0f) }
    val lockRise = remember { Animatable(0f) }
    val lockScale = remember { Animatable(1f) }
    LaunchedEffect(holding) {
        if (holding) {
            lockScale.snapTo(1f)
            lockRise.snapTo(0f)
            launch { fab.animateTo(1f, RecordPopSpring) }
            // The lock hint rises once the button has popped, so it reads as "slide up here".
            kotlinx.coroutines.delay(LOCK_HINT_DELAY_MS)
            lockRise.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
        } else {
            launch { lockScale.animateTo(0f, tween(RECORD_HIDE_MS, easing = FastOutLinearInEasing)) }
            fab.animateTo(0f, tween(RECORD_HIDE_MS, easing = FastOutLinearInEasing))
        }
    }
    if (!holding && fab.value <= 0f && lockScale.value <= 0f) return
    val halfFabPx = with(density) { (RecordFabSize / 2).roundToPx() }
    val positioner = remember(halfFabPx) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ) = androidx.compose.ui.unit.IntOffset(
                anchorBounds.center.x - (popupContentSize.width - halfFabPx),
                anchorBounds.center.y - (popupContentSize.height - halfFabPx),
            )
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = positioner,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(Modifier.size(width = RecordFabSize + CancelTravel, height = RecordFabSize + LockTravel + LockPillHeight + 24.dp)) {
            // Lock target: starts just above the button and rises by the lock travel.
            val nearLock = (-drag.y / lockPx).coerceIn(0f, 1f)
            val bob by rememberInfiniteTransition(label = "lockHint").animateFloat(
                0f, -4f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "chevron",
            )
            Column(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = (RecordFabSize - 40.dp) / 2, bottom = RecordFabSize + 12.dp)
                    .graphicsLayer {
                        translationY = -lockPx * lockRise.value
                        alpha = lockRise.value
                        scaleX = lockScale.value
                        scaleY = lockScale.value
                    }
                    .size(width = 40.dp, height = LockPillHeight)
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .background(colors.bgElevated, RoundedCornerShape(20.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(20.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Icon(
                    if (locked || nearLock > .85f) HeroIcons.LockClosed else HeroIcons.LockOpen,
                    "Slide up to lock",
                    Modifier.size(20.dp),
                    tint = if (locked || nearLock > .85f) colors.danger else colors.textSecondary,
                )
                Icon(
                    HeroIcons.ChevronUp, null,
                    Modifier.size(20.dp).graphicsLayer { translationY = bob.dp.toPx() * (1f - nearLock) },
                    tint = colors.textMuted,
                )
            }
            Box(
                Modifier.align(Alignment.BottomEnd).size(RecordFabSize)
                    .graphicsLayer {
                        translationX = drag.x
                        translationY = drag.y
                        val s = 0.6f + 0.4f * fab.value
                        scaleX = s
                        scaleY = s
                        alpha = fab.value.coerceIn(0f, 1f)
                    }
                    .shadow(6.dp, CircleShape)
                    .background(colors.danger, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HeroIcons.Microphone, null, Modifier.size(30.dp), tint = Color.White)
            }
        }
    }
}
