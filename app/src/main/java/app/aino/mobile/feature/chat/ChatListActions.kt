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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.icons.HeroIcons

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
            SignalMenuItem(label, HeroIcons.BellSlash) { onDismiss(); onPick(duration) }
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
        item(if (conversation.isPinned) "Unpin chat" else "Pin chat", HeroIcons.PushPin) { viewModel.togglePin(conversation) }
        if (conversation.unreadCount > 0) item("Mark as read", HeroIcons.ChatBubbleLeft) { viewModel.markRead(conversation) }
        else item("Mark as unread", HeroIcons.ChatBubbleLeftEllipsis) { viewModel.markUnread(conversation) }
        if (conversation.isMuted) item("Unmute notifications", HeroIcons.Bell) { viewModel.muteFor(conversation, null) }
        else item("Mute notifications", HeroIcons.BellSlash, action = onChooseMute)
        item("Select", HeroIcons.CheckCircle) { viewModel.toggleConversationSelection(conversation.id) }
        if (conversation.isArchived) item("Unarchive", HeroIcons.ArchiveBoxArrowDown) { viewModel.toggleArchive(conversation) }
        else item("Archive", HeroIcons.ArchiveBox) { viewModel.toggleArchive(conversation, undoable = true) }
        item("Delete", HeroIcons.Trash, danger = true, action = onConfirmDelete)
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
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val canCall = ui.callsEnabled && !conversation.isSelfChat && !conversation.isMeetingChat && !conversation.isBlocked
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
                SheetButton(HeroIcons.ChatBubbleOvalLeft, "Message", Modifier.weight(1f)) { onDismiss(); onOpen() }
                if (canCall) {
                    SheetButton(HeroIcons.Phone, "Voice", Modifier.weight(1f)) {
                        withCallPermissions(false) { onDismiss(); viewModel.startCall(conversation, "voice") }
                    }
                    SheetButton(HeroIcons.VideoCamera, "Video", Modifier.weight(1f)) {
                        withCallPermissions(true) { onDismiss(); viewModel.startCall(conversation, "video") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    SheetButton(
                        if (conversation.isMuted) HeroIcons.Bell else HeroIcons.BellSlash,
                        if (conversation.isMuted) "Unmute" else "Mute",
                        Modifier.fillMaxWidth(),
                    ) { if (conversation.isMuted) viewModel.muteFor(conversation, null) else muteOpen = true }
                    MuteDurationMenu(muteOpen, { muteOpen = false }) { viewModel.muteFor(conversation, it) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetRow(HeroIcons.InformationCircle, if (conversation.isGroup) "Group settings" else "Chat settings") {
            viewModel.openSettingsOnOpen(conversation.id)
            onDismiss()
            onOpen()
        }
        if (oneToOne) SheetRow(HeroIcons.NoSymbol, if (conversation.isBlocked) "Unblock" else "Block", danger = !conversation.isBlocked) {
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
