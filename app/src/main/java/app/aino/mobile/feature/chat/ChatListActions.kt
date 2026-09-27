package app.aino.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Signal mute durations (server accepts 1h | 8h | 1d | 1w | always). */
internal val MuteDurations = listOf(
    "1h" to "Mute for 1 hour",
    "8h" to "Mute for 8 hours",
    "1d" to "Mute for 1 day",
    "1w" to "Mute for 7 days",
    "always" to "Mute always",
)

@Composable
internal fun MuteDurationMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    SignalDropdownMenu(expanded = expanded, onDismiss = onDismiss) {
        MuteDurations.forEach { (duration, label) ->
            SignalMenuItem(label, Icons.Outlined.NotificationsOff) { onDismiss(); onPick(duration) }
        }
    }
}

/**
 * Signal conversation-list long-press menu (`ConversationListFragment`
 * context menu): Pin, Read/Unread, Mute, Select, Archive, Delete.
 */
@Composable
internal fun ConversationContextMenu(
    expanded: Boolean,
    conversation: ChatConversation,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onChooseMute: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    SignalDropdownMenu(expanded = expanded, onDismiss = onDismiss) {
        @Composable
        fun item(label: String, icon: ImageVector, danger: Boolean = false, action: () -> Unit) =
            SignalMenuItem(label, icon, danger) { onDismiss(); action() }
        item(if (conversation.isPinned) "Unpin chat" else "Pin chat", Icons.Outlined.PushPin) { viewModel.togglePin(conversation) }
        if (conversation.unreadCount > 0) item("Mark as read", Icons.Outlined.MarkChatRead) { viewModel.markRead(conversation) }
        else item("Mark as unread", Icons.Outlined.MarkChatUnread) { viewModel.markUnread(conversation) }
        if (conversation.isMuted) item("Unmute notifications", Icons.Outlined.Notifications) { viewModel.muteFor(conversation, null) }
        else item("Mute notifications", Icons.Outlined.NotificationsOff, action = onChooseMute)
        item("Select", Icons.Outlined.CheckCircleOutline) { viewModel.toggleConversationSelection(conversation.id) }
        if (conversation.isArchived) item("Unarchive", Icons.Outlined.Unarchive) { viewModel.toggleArchive(conversation) }
        else item("Archive", Icons.Outlined.Archive) { viewModel.toggleArchive(conversation, undoable = true) }
        item("Delete", Icons.Outlined.DeleteOutline, danger = true, action = onConfirmDelete)
    }
}

/**
 * Signal `RecipientBottomSheetDialogFragment`, opened from a chat-list avatar:
 * large avatar, name, quick Message / Voice / Video / Mute buttons, settings and block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipientSheet(
    conversation: ChatConversation,
    presence: ChatPresence?,
    viewModel: ChatViewModel,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val signal = signalColors
    val withCallPermissions = app.aino.mobile.core.call.rememberCallPermissions()
    var muteOpen by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val oneToOne = !conversation.isGroup && !conversation.isMeetingChat && !conversation.isSelfChat && conversation.otherUserId != null
    val canCall = !conversation.isSelfChat && !conversation.isMeetingChat && !conversation.isBlocked
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = signal.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ConversationAvatar(conversation, presence, 80.dp)
            Text(
                conversation.title(), Modifier.padding(top = 12.dp),
                color = signal.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            recipientSubtitle(conversation, presence)?.let {
                Text(it, Modifier.padding(top = 2.dp), color = signal.textSecondary, fontSize = 15.sp, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetButton(Icons.Outlined.ChatBubbleOutline, "Message", Modifier.weight(1f)) { onDismiss(); onOpen() }
                if (canCall) {
                    SheetButton(Icons.Outlined.Phone, "Voice", Modifier.weight(1f)) {
                        withCallPermissions(false) { onDismiss(); viewModel.startCall(conversation, "voice") }
                    }
                    SheetButton(Icons.Outlined.Videocam, "Video", Modifier.weight(1f)) {
                        withCallPermissions(true) { onDismiss(); viewModel.startCall(conversation, "video") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    SheetButton(
                        if (conversation.isMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                        if (conversation.isMuted) "Unmute" else "Mute",
                        Modifier.fillMaxWidth(),
                    ) { if (conversation.isMuted) viewModel.muteFor(conversation, null) else muteOpen = true }
                    MuteDurationMenu(muteOpen, { muteOpen = false }) { viewModel.muteFor(conversation, it) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetRow(Icons.Outlined.Info, if (conversation.isGroup) "Group settings" else "Chat settings") {
            viewModel.openSettingsOnOpen(conversation.id)
            onDismiss()
            onOpen()
        }
        if (oneToOne) SheetRow(Icons.Outlined.Block, if (conversation.isBlocked) "Unblock" else "Block", danger = !conversation.isBlocked) {
            confirmBlock = true
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmBlock) BlockDialog(conversation, onConfirm = { viewModel.toggleBlock(conversation); onDismiss() }) { confirmBlock = false }
}

internal fun recipientSubtitle(conversation: ChatConversation, presence: ChatPresence?): String? = when {
    conversation.isSelfChat -> null
    conversation.isGroup -> conversation.memberCount?.takeIf { it > 0 }?.let { "$it member${if (it == 1) "" else "s"}" }
    presence?.presence == "online" -> "Online"
    else -> conversation.otherUsername?.takeIf(String::isNotBlank)?.let { "@$it" }
}

@Composable
private fun SheetButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val signal = signalColors
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(signal.background).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = signal.text)
        Text(label, Modifier.padding(top = 4.dp), color = signal.text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val signal = signalColors
    val tint = if (danger) signal.danger else signal.text
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(label, Modifier.padding(start = 20.dp), color = tint, fontSize = 16.sp)
    }
}
