package app.aino.mobile.feature.chat

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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

fun formatVoiceTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Deterministic pseudo-waveform (the server stores no peaks): 0.2..1 bar heights seeded by the url. */
fun voiceWaveform(seed: String, bars: Int = 46): List<Float> {
    var x = seed.hashCode().toLong() and 0xffffffffL
    return List(bars) {
        x = (x * 1103515245L + 12345L) and 0x7fffffffL
        0.2f + (x % 1000) / 1250f
    }
}

/** Signal voice-note bubble: round play button, waveform scrubber, elapsed/total time, speed chip. */
@Composable
fun ChatVoicePlayer(url: String, modifier: Modifier = Modifier, tint: Color = LocalWebColors.current.primary, outgoing: Boolean = false) {
    val signal = signalColors
    val player = AppContainer.get(LocalContext.current).audio
    val state by player.state.collectAsStateWithLifecycle()
    val current = state.url == url
    val playing = current && state.playing
    val duration = if (current) state.durationMs else player.durationOf(url)
    val position = if (current) state.positionMs else 0
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fg = if (outgoing) signal.onOutgoing else signal.onIncoming
    val fgMuted = if (outgoing) signal.onOutgoingSecondary else signal.onIncomingSecondary
    val buttonBg = if (outgoing) signal.onOutgoing else tint
    val buttonFg = if (outgoing) signal.outgoing else Color.White
    val bars = remember(url) { voiceWaveform(url) }
    Row(modifier.widthIn(min = 220.dp, max = 260.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(buttonBg, CircleShape).clickable { player.toggle(url) },
            contentAlignment = Alignment.Center,
        ) {
            if (current && state.buffering && !playing) CircularProgressIndicator(Modifier.size(18.dp), color = buttonFg, strokeWidth = 2.dp)
            else Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(24.dp), tint = buttonFg)
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
            Box(Modifier.size(9.dp).alpha(blink).background(colors.danger, CircleShape))
            Text(formatVoiceTime(elapsedMs), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // Slide-to-cancel hint follows the finger (Signal pattern).
            Row(
                Modifier.offset { androidx.compose.ui.unit.IntOffset(slideOffsetPx.coerceAtMost(0f).toInt(), 0) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, Modifier.size(18.dp), tint = colors.textMuted)
                Text("Slide to cancel", color = colors.textMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Icon(Icons.Outlined.DeleteOutline, "Delete recording", Modifier.size(22.dp).clickable(onClick = onDelete), tint = colors.danger)
            Box(Modifier.size(9.dp).alpha(if (phase == VoicePhase.Paused) 1f else blink).background(if (phase == VoicePhase.Paused) colors.textMuted else colors.danger, CircleShape))
            Text(formatVoiceTime(elapsedMs), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            LiveWaveform(levels, if (phase == VoicePhase.Paused) colors.textMuted else colors.primary, Modifier.weight(1f).height(26.dp))
            Icon(
                if (phase == VoicePhase.Paused) Icons.Outlined.Mic else Icons.Outlined.Pause,
                if (phase == VoicePhase.Paused) "Resume recording" else "Pause recording",
                Modifier.size(24.dp).clickable(onClick = onPauseResume),
                tint = if (phase == VoicePhase.Paused) colors.danger else colors.textSecondary,
            )
            Icon(Icons.Outlined.Stop, "Stop and review", Modifier.size(24.dp).clickable(onClick = onStop), tint = colors.textSecondary)
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
        Icon(Icons.Outlined.DeleteOutline, "Delete recording", Modifier.size(22.dp).clickable(onClick = onDelete), tint = colors.danger)
        ChatVoicePlayer(draftUrl, Modifier.weight(1f).padding(start = 6.dp))
    }
}

/**
 * Hold-to-record mic inside the composer pill (web places camera + mic in the
 * pill). Press starts, release sends, slide left cancels, slide up locks.
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
    val lockPx = with(density) { 80.dp.toPx() }
    val cancelPx = with(density) { 120.dp.toPx() }
    val latestEnabled by rememberUpdatedState(enabled)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val lock by rememberUpdatedState(onLock)
    val slideCancel by rememberUpdatedState(onSlideCancel)
    val slide by rememberUpdatedState(onSlide)
    Box(Modifier.size(width = 42.dp, height = 46.dp), contentAlignment = Alignment.Center) {
        if (holding) {
            // Lock target floating above the finger (Signal pattern).
            Column(
                Modifier.offset(y = (-70).dp).background(colors.bgElevated, RoundedCornerShape(20.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Lock, "Slide up to lock", Modifier.size(18.dp), tint = colors.textSecondary)
                Icon(Icons.Outlined.KeyboardArrowUp, null, Modifier.size(18.dp), tint = colors.textMuted)
            }
        }
        Box(
            Modifier.size(if (holding) 52.dp else 42.dp)
                .background(if (holding) colors.danger else Color.Transparent, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!latestEnabled || !press()) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            slide(dx)
                            when {
                                dy < -lockPx -> { settled = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress); lock() }
                                dx < -cancelPx -> { settled = true; slideCancel() }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Mic,
                "Hold to record voice message",
                Modifier.size(if (holding) 24.dp else 20.dp),
                tint = if (holding) Color.White else colors.textSecondary,
            )
        }
    }
}
