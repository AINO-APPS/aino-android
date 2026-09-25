package app.aino.mobile.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val SwipeReplyTriggerDp = 56f
const val SwipeReplyMaximumDp = 76f

/** Pure gesture math, public to make integrations and tests deterministic. */
fun replyDragOffset(currentPx: Float, dragPx: Float, endSign: Float, maximumPx: Float): Float {
    val coerced = ((currentPx + dragPx) * endSign).coerceIn(0f, maximumPx)
    // Avoid returning -0.0f (coerced == 0f flipped by endSign), which fails
    // exact float equality checks despite being mathematically zero.
    return if (coerced == 0f) 0f else coerced * endSign
}

fun replyProgress(offsetPx: Float, endSign: Float, triggerPx: Float): Float =
    (offsetPx * endSign / triggerPx).coerceIn(0f, 1f)

/** Adds a direction-aware (logical END) swipe-to-reply gesture and accessibility action. */
@Composable
fun Modifier.swipeToReply(
    enabled: Boolean = true,
    onReply: () -> Unit,
    onProgress: (Float) -> Unit = {},
): Modifier {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val trigger = with(density) { SwipeReplyTriggerDp.dp.toPx() }
    val maximum = with(density) { SwipeReplyMaximumDp.dp.toPx() }
    val endSign = if (direction == LayoutDirection.Ltr) 1f else -1f
    var thresholdHapticSent by remember { mutableStateOf(false) }

    return this
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .pointerInput(enabled, direction, trigger, maximum) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount ->
                    val next = replyDragOffset(offset.value, amount, endSign, maximum)
                    if (next != offset.value) change.consume()
                    scope.launch { offset.snapTo(next) }
                    val progress = replyProgress(next, endSign, trigger)
                    onProgress(progress)
                    if (progress >= 1f && !thresholdHapticSent) {
                        thresholdHapticSent = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (progress < 1f) thresholdHapticSent = false
                },
                onDragEnd = {
                    if (replyProgress(offset.value, endSign, trigger) >= 1f) onReply()
                    thresholdHapticSent = false
                    onProgress(0f)
                    scope.launch { offset.animateTo(0f, spring(dampingRatio = .72f, stiffness = 520f)) }
                },
                onDragCancel = {
                    thresholdHapticSent = false
                    onProgress(0f)
                    scope.launch { offset.animateTo(0f, spring()) }
                },
            )
        }
        .semantics {
            customActions = listOf(CustomAccessibilityAction("Reply to message") { onReply(); true })
        }
}

/** Message host that exposes both long-press reactions and swipe reply. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageGestureBox(
    onReply: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var progress by remember { mutableStateOf(0f) }
    Box(modifier) {
        Icon(
            Icons.AutoMirrored.Outlined.Reply,
            contentDescription = null,
            tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary,
            modifier = Modifier.align(Alignment.CenterStart).alpha(progress)
                .graphicsLayer { scaleX = .65f + progress * .35f; scaleY = scaleX },
        )
        Box(
            Modifier.swipeToReply(enabled, onReply) { progress = it }
                .combinedClickable(
                    enabled = enabled,
                    onClickLabel = "Open message",
                    onLongClickLabel = "Message reactions and actions",
                    onClick = onClick,
                    onLongClick = onLongPress,
                ),
        ) { content() }
    }
}