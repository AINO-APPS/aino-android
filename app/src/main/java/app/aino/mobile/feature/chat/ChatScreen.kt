package app.aino.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AinoAtmosphere {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AinoSectionHeader("Chat", "${ui.conversations.size} conversations · ${ui.unread} unread")
            ui.error?.let { AinoAlert(it, AlertTone.Error) }
            ui.message?.let { AinoAlert(it, if (ui.fromCache) AlertTone.Warning else AlertTone.Success) }
            UserSearch(ui, viewModel)
            if (ui.conversations.isEmpty()) {
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (ui.loading) "Loading conversations…" else "No conversations yet. Search for a colleague to start one.",
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.conversations.forEach { conversation ->
                        ConversationRow(conversation, ui.presence[conversation.otherUserId], viewModel)
                    }
                }
            }
            AinoPrimaryButton(
                if (ui.loading) "Refreshing…" else "Refresh conversations",
                viewModel::refresh,
                Modifier.fillMaxWidth(),
                !ui.loading,
                leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.padding(end = 8.dp), tint = Color.White) },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun UserSearch(ui: ChatUiState, viewModel: ChatViewModel) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.userSearch,
                onValueChange = viewModel::updateUserSearch,
                label = { Text("Start a conversation") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchUsers() }),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            )
            AinoPrimaryButton(if (ui.searching) "Searching…" else "Find people", viewModel::searchUsers, Modifier.fillMaxWidth(), !ui.searching)
            ui.userResults.forEach { user ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.startDirect(user) }
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(user.display(), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("Message", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ChatConversation, presence: ChatPresence?, viewModel: ChatViewModel) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(conversation.title(), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (conversation.isPinned) Icon(Icons.Outlined.PushPin, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (conversation.isMuted) Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(conversation.preview(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                if (conversation.unreadCount > 0) {
                    AinoBadge(if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(), AlertTone.Info)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!conversation.isGroup) {
                    val online = presence?.presence == "online"
                    Box(Modifier.size(7.dp).background(if (online) Color(0xFF4DAA57) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                    Text(presenceLabel(presence), Modifier.padding(start = 6.dp).weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${conversation.memberCount ?: 0} members", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (conversation.unreadCount > 0) {
                    Text(
                        "Mark read",
                        Modifier.clickable { viewModel.markRead(conversation) }
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}