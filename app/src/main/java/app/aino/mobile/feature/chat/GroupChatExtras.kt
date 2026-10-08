package app.aino.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.component.GroupAvatar
import app.aino.mobile.core.designsystem.component.GroupAvatarMember
import app.aino.mobile.core.designsystem.component.UserAvatar

/** "Group call · 3 people · Join" strip under the thread header while the group's call is running. */
@Composable
internal fun GroupCallBanner(call: ActiveGroupCall, callsEnabled: Boolean, onJoin: () -> Unit) {
    val signal = signalColors
    val count = call.participants.size
    Row(
        Modifier.fillMaxWidth().background(signal.primary.copy(alpha = .12f)).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (call.callType == "video") Icons.Outlined.Videocam else Icons.Outlined.Call, null, Modifier.size(20.dp), tint = signal.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (call.callType == "video") "Group video call" else "Group call", color = signal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                when (count) {
                    0 -> "Starting…"
                    1 -> "${call.participants.first().fullName ?: "1 person"} is in the call"
                    else -> "$count people in the call"
                },
                color = signal.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // Stacked faces of the first three participants.
        Box(Modifier.padding(end = 8.dp)) {
            call.participants.take(3).forEachIndexed { i, p ->
                UserAvatar(p.fullName, p.avatar, 26.dp, Modifier.offset(x = (i * 16).dp).clip(CircleShape))
            }
            Spacer(Modifier.size(width = (26 + (call.participants.take(3).size - 1).coerceAtLeast(0) * 16).dp, height = 26.dp))
        }
        if (callsEnabled) {
            Text(
                "Join",
                Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF4CAF50)).clickable(onClick = onJoin).padding(horizontal = 16.dp, vertical = 6.dp),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Replaces the composer when the group only lets admins post (`post_policy = admins`). */
@Composable
internal fun AdminsOnlyComposerNotice() {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().background(signal.surface).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Campaign, null, Modifier.size(20.dp), tint = signal.textSecondary)
        Text("  Only admins can send messages", color = signal.textSecondary, fontSize = 15.sp)
    }
}

/** Preview of a group from its invite link, then Join / Request to join. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JoinGroupSheet(state: InviteSheetState, chat: ChatViewModel) {
    val signal = signalColors
    ModalBottomSheet(onDismissRequest = chat::dismissInvite, containerColor = signal.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val preview = state.preview
            when {
                state.loading -> Box(Modifier.height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = signal.primary, strokeWidth = 2.dp)
                }
                preview == null -> {
                    Text("Can't open group link", color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(state.error ?: "This group link is no longer valid.", Modifier.padding(top = 8.dp), color = signal.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    TextButton(onClick = chat::dismissInvite, Modifier.padding(top = 12.dp)) { Text("OK") }
                }
                else -> {
                    GroupAvatar(
                        preview.name, preview.avatar, preview.memberAvatars.map { GroupAvatarMember(null, it) },
                        "conv-${preview.conversationId}", 88.dp,
                    )
                    Text(preview.name ?: "Group", Modifier.padding(top = 12.dp), color = signal.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Text("Group · ${preview.memberCount} ${if (preview.memberCount == 1) "member" else "members"}", color = signal.textSecondary, fontSize = 15.sp)
                    preview.description?.takeIf(String::isNotBlank)?.let {
                        Text(it, Modifier.padding(top = 8.dp), color = signal.text, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                    state.error?.let { Text(it, Modifier.padding(top = 8.dp), color = signal.danger, fontSize = 14.sp, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(20.dp))
                    when {
                        preview.alreadyMember -> SheetPrimary("Open chat", enabled = true, onClick = chat::openInviteConversation)
                        preview.pending -> {
                            Text("Your request to join was sent. An admin needs to approve it.", color = signal.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                            TextButton(onClick = chat::cancelJoinRequest, Modifier.padding(top = 8.dp)) { Text("Cancel request", color = signal.danger) }
                        }
                        else -> {
                            if (preview.requiresApproval) {
                                Text("An admin of this group must approve your request before you can join.", Modifier.padding(bottom = 12.dp), color = signal.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                            }
                            SheetPrimary(if (preview.requiresApproval) "Request to join" else "Join group", enabled = !state.joining, onClick = chat::joinInvite)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetPrimary(label: String, enabled: Boolean, onClick: () -> Unit) {
    val signal = signalColors
    Text(
        label,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (enabled) signal.primary else signal.primary.copy(alpha = .5f))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
    )
}
