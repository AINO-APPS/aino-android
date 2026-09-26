package app.aino.mobile.feature.chat

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Signal's rounded (18dp) popup menu on a solid surface. */
@Composable
internal fun SignalDropdownMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = signalColors.surface,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
    ) { content() }
}

@Composable
internal fun SignalMenuItem(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val signal = signalColors
    val tint = if (danger) signal.danger else signal.text
    DropdownMenuItem(
        text = { Text(label, color = tint, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, tint = tint) },
        onClick = onClick,
    )
}

/**
 * Signal confirmation dialog: solid surface (never translucent), "Cancel"
 * plus a coloured primary action. Dismisses before running [onConfirm].
 */
@Composable
internal fun SignalConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    val signal = signalColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = signal.surface,
        titleContentColor = signal.text,
        textContentColor = signal.textSecondary,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = { Text(message, fontSize = 15.sp) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = if (danger) signal.danger else signal.primary, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = signal.primary) } },
    )
}

private fun shortTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * Signal outgoing attachment while uploading: the local image / video frame,
 * dimmed, with the transfer ring (✕ cancels); a failure crossfades to a retry
 * disc. Documents show a file row with the same controls.
 */
@Composable
internal fun PendingMediaBubble(media: PendingMedia, onCancel: () -> Unit, onRetry: () -> Unit) {
    val signal = signalColors
    val shape = messageBubbleShape(isMine = true, startsGroup = true, endsGroup = true)
    val failed = media.state == PendingMediaState.Failed
    val footer: @Composable (onMedia: Boolean) -> Unit = { onMedia ->
        val tint = if (onMedia) Color.White else signal.onOutgoingSecondary
        Row(
            if (onMedia) Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .38f)).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(if (failed) "Not sent" else shortTime(media.createdAtEpochMs), color = tint, fontSize = SignalDimens.footerText)
            if (failed) Icon(Icons.Outlined.ErrorOutline, "Failed", Modifier.size(14.dp), tint = if (onMedia) Color.White else signal.danger)
            else SignalReceiptIcon(DeliveryTick.Sending, tint)
        }
    }
    val aspect = if (media.width != null && media.height != null && media.height > 0) media.width.toFloat() / media.height else null
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, start = SignalDimens.gutter, end = SignalDimens.gutter)) {
        val bubbleMax = maxWidth - SignalDimens.bubbleEdgeMargin
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (media.isImage || media.isVideo) {
                Column(Modifier.widthIn(max = bubbleMax).clip(shape).background(signal.outgoing)) {
                    Box {
                        ChatThumbnail(media.uri, media.fileName, video = media.isVideo, shape = RoundedCornerShape(0.dp), initialAspect = aspect) {}
                        Crossfade(failed, Modifier.matchParentSize(), label = "transfer") { isFailed ->
                            if (isFailed) TransferRetry(onRetry, Modifier.fillMaxSize())
                            else TransferRing(media.progress, onCancel, Modifier.fillMaxSize())
                        }
                        if (media.caption == null) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { footer(true) }
                    }
                    media.caption?.let { caption ->
                        Box(Modifier.padding(horizontal = SignalDimens.bubbleHPad, vertical = SignalDimens.bubbleTopPad)) {
                            BubbleTextWithFooter(AnnotatedString(caption), signal.onOutgoing, { footer(false) })
                        }
                    }
                }
            } else {
                PendingFileRow(media, failed, bubbleMax, shape, onCancel, onRetry) { footer(false) }
            }
            if (failed) media.error?.let { Text(it, Modifier.padding(top = 2.dp, end = 4.dp), color = signal.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PendingFileRow(
    media: PendingMedia,
    failed: Boolean,
    maxWidth: Dp,
    shape: Shape,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    footer: @Composable () -> Unit,
) {
    val signal = signalColors
    Row(
        Modifier.widthIn(max = maxWidth).clip(shape).background(signal.outgoing).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (failed) {
                Icon(Icons.Outlined.Refresh, "Retry", Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRetry).padding(10.dp), tint = signal.onOutgoing)
            } else {
                val progress = media.progress
                if (progress == null) CircularProgressIndicator(Modifier.size(40.dp), color = signal.onOutgoing, strokeWidth = 2.5.dp)
                else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(40.dp), color = signal.onOutgoing, strokeWidth = 2.5.dp, trackColor = signal.onOutgoing.copy(alpha = .25f))
                Icon(Icons.Outlined.Close, "Cancel", Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCancel).padding(10.dp), tint = signal.onOutgoing)
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(media.fileName, color = signal.onOutgoing, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.align(Alignment.End)) { footer() }
        }
    }
}


/** Signal pinned-message banner under the conversation toolbar. */
@Composable
internal fun PinnedMessageBar(
    message: ChatMessage,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    onViewAll: () -> Unit,
) {
    val signal = signalColors
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(signal.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Go to pinned message", onClick = onClick)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Segment indicator: one tick per pin (max 4), current one highlighted.
            val segments = count.coerceIn(1, 4)
            Column(Modifier.height(36.dp).width(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(segments) { i ->
                    Box(
                        Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(2.dp))
                            .background(if (i == index % segments) signal.primary else signal.divider),
                    )
                }
            }
            Icon(Icons.Outlined.PushPin, null, Modifier.padding(start = 12.dp).size(18.dp), tint = signal.primary)
            if (message.isImageAttachment() || message.isVideoAttachment()) {
                ChatThumbnail(
                    resolveChatMediaUrl(message.fileUrl.orEmpty()), message.fileName, video = false,
                    shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(start = 10.dp).size(36.dp),
                ) { onClick() }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    if (count > 1) "Pinned message ${index + 1} of $count" else "Pinned message",
                    color = signal.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                Text(message.body(), color = signal.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                Icon(
                    Icons.Outlined.MoreVert, "Pinned message options",
                    Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }.padding(10.dp), tint = signal.textSecondary,
                )
                SignalDropdownMenu(expanded = menu, onDismiss = { menu = false }) {
                    SignalMenuItem("Go to message", Icons.Outlined.ChatBubbleOutline) { menu = false; onClick() }
                    SignalMenuItem("Unpin", Icons.Outlined.PushPin) { menu = false; onUnpin() }
                    SignalMenuItem("View all pinned", Icons.AutoMirrored.Outlined.FormatListBulleted) { menu = false; onViewAll() }
                }
            }
        }
        HorizontalDivider(color = signal.divider, thickness = 0.5.dp)
    }
}


// ---- Signal confirmation copy ----

@Composable
internal fun ClearChatDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) = SignalConfirmDialog(
    title = "Clear chat?",
    message = "All messages in this chat will be deleted for you. This can't be undone.",
    confirmLabel = "Clear chat",
    onConfirm = onConfirm,
    onDismiss = onDismiss,
)

@Composable
internal fun BlockDialog(conversation: ChatConversation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = conversation.title()
    if (conversation.isBlocked) SignalConfirmDialog(
        title = "Unblock $name?",
        message = "You will be able to message and call each other again.",
        confirmLabel = "Unblock",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        danger = false,
    ) else SignalConfirmDialog(
        title = "Block $name?",
        message = "Blocked people won't be able to call you or send you messages.",
        confirmLabel = "Block",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun LeaveGroupDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) = SignalConfirmDialog(
    title = "Leave group?",
    message = "You will no longer be able to send or receive messages in this group.",
    confirmLabel = "Leave",
    onConfirm = onConfirm,
    onDismiss = onDismiss,
)

@Composable
internal fun DeleteChatDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) = SignalConfirmDialog(
    title = "Delete chat?",
    message = "This chat will be removed from your chat list and its messages deleted for you.",
    confirmLabel = "Delete",
    onConfirm = onConfirm,
    onDismiss = onDismiss,
)
