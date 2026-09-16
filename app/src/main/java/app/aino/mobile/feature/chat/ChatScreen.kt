package app.aino.mobile.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AlertTone
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ChatListTab { Chat, Meet, Calls }

/** The Android contract does not expose thread navigation or call history yet.
 * Those affordances are therefore shown honestly rather than made clickable.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel, onPickDocument: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var activeTab by remember { mutableStateOf(ChatListTab.Chat) }
    var searchOpen by remember { mutableStateOf(false) }
    val query = ui.userSearch.trim()
    val visibleConversations = remember(ui.conversations, activeTab, query) {
        ui.conversations.filter { conversation ->
            val inTab = when (activeTab) {
                ChatListTab.Chat -> !conversation.isMeetingChat && !conversation.isArchived
                ChatListTab.Meet -> conversation.isMeetingChat && !conversation.isArchived
                ChatListTab.Calls -> false
            }
            inTab && (query.isBlank() || listOf(
                conversation.title(), conversation.otherUsername.orEmpty(),
                conversation.lastMessage.orEmpty(), conversation.groupName.orEmpty(),
            ).joinToString(" ").contains(query, ignoreCase = true))
        }
    }
    if (ui.selectedConversation != null) {
        ChatThread(ui, viewModel, onPickDocument)
        return
    }

    AinoAtmosphere {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                activeTab = activeTab,
                unread = ui.conversations.sumOf {
                    if (it.isMuted || it.isArchived || it.isMeetingChat) 0 else it.unreadCount.coerceAtLeast(0)
                },
                meetingUnread = ui.conversations.filter(ChatConversation::isMeetingChat).sumOf { it.unreadCount.coerceAtLeast(0) },
                searchOpen = searchOpen,
                query = ui.userSearch,
                onTab = { tab -> activeTab = tab; if (tab == ChatListTab.Calls) viewModel.loadCalls() },
                onQuery = viewModel::updateUserSearch,
                onSearch = { if (ui.userSearch.trim().length >= 2) viewModel.searchUsers() },
                onSearchOpen = { open ->
                    searchOpen = open
                    if (!open) viewModel.updateUserSearch("")
                },
            )

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 90.dp)) {
                if (ui.error != null || ui.message != null) item(key = "notice") {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ui.error?.let { AinoAlert(it, AlertTone.Error) }
                        ui.message?.let { AinoAlert(it, if (ui.fromCache) AlertTone.Warning else AlertTone.Success) }
                    }
                }
                if (searchOpen && query.length >= 2) {
                    item(key = "people-heading") { SectionHeader("People", Icons.Outlined.Groups) }
                    if (ui.searching) item(key = "searching") { SearchHint("Searching people…", true) }
                    else if (ui.userResults.isEmpty()) item(key = "search-hint") { SearchHint("Press search to find colleagues", false) }
                    items(ui.userResults, key = { "user-${it.id}" }) { user -> UserResultRow(user) { viewModel.startDirect(user) } }
                }
                when (activeTab) {
                    ChatListTab.Calls -> {
                        when {
                            ui.callsLoading -> item(key = "calls-loading") { SearchHint("Loading call history…", true) }
                            ui.calls.isEmpty() -> item(key = "calls-empty") { HonestEmpty(Icons.Outlined.Phone, "No calls yet") }
                            else -> items(ui.calls, key = { "call-${it.id}" }) { CallRow(it, ui.currentUserId) }
                        }
                    }
                    ChatListTab.Meet -> {
                        if (visibleConversations.isEmpty()) item(key = "meet-empty") {
                            HonestEmpty(Icons.Outlined.Videocam, if (query.isNotBlank()) "No matching meeting chats" else "No meeting chats yet")
                        } else items(visibleConversations, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], viewModel) }
                    }
                    ChatListTab.Chat -> {
                        val pinned = visibleConversations.filter(ChatConversation::isPinned)
                        val favourites = visibleConversations.filter { it.isFavourite && !it.isPinned }
                        val others = visibleConversations.filter { !it.isPinned && !it.isFavourite }
                        if (visibleConversations.isEmpty()) item(key = "chat-empty") {
                            HonestEmpty(Icons.Outlined.ChatBubbleOutline, when {
                                ui.loading -> "Loading conversations…"
                                query.isNotBlank() -> "No matching chats"
                                else -> "No conversations yet"
                            })
                        }
                        if (pinned.isNotEmpty()) item(key = "pinned") { SectionHeader("Pinned", Icons.Outlined.PushPin) }
                        items(pinned, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], viewModel) }
                        if (favourites.isNotEmpty()) item(key = "favourites") { SectionHeader("Favourites", Icons.Outlined.StarOutline, true) }
                        items(favourites, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], viewModel) }
                        if ((pinned.isNotEmpty() || favourites.isNotEmpty()) && others.isNotEmpty()) item(key = "all") {
                            SectionHeader("All messages", Icons.Outlined.ChatBubbleOutline)
                        }
                        items(others, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], viewModel) }
                        val archivedCount = ui.conversations.count { it.isArchived && !it.isMeetingChat }
                        if (archivedCount > 0 && query.isBlank()) item(key = "archived") { ArchivedRow(archivedCount) }
                    }
                }
                item(key = "refresh") {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !ui.loading) { viewModel.refresh() }.padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ui.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(7.dp))
                        Text(if (ui.loading) "Refreshing…" else "Refresh conversations", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(
    activeTab: ChatListTab, unread: Int, meetingUnread: Int, searchOpen: Boolean, query: String,
    onTab: (ChatListTab) -> Unit, onQuery: (String) -> Unit, onSearch: () -> Unit, onSearchOpen: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchOpen) {
                Row(
                    Modifier.weight(1f).height(42.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query, onValueChange = onQuery, modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search chats", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            inner()
                        } },
                    )
                    Icon(
                        Icons.Outlined.Close, "Close search",
                        Modifier.size(20.dp).clickable { if (query.isNotEmpty()) onQuery("") else onSearchOpen(false) }.padding(2.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Segment("Chat", Icons.Outlined.ChatBubbleOutline, activeTab == ChatListTab.Chat, unread, Modifier.weight(1f)) { onTab(ChatListTab.Chat) }
                Segment("Meet", Icons.Outlined.Videocam, activeTab == ChatListTab.Meet, meetingUnread, Modifier.weight(1f)) { onTab(ChatListTab.Meet) }
                Segment("Calls", Icons.Outlined.Phone, activeTab == ChatListTab.Calls, 0, Modifier.weight(1f)) { onTab(ChatListTab.Calls) }
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).clickable { onSearchOpen(true) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Search, "Search chats", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Segment(label: String, icon: ImageVector, active: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(42.dp).clip(shape).background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
            .then(if (active) Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .24f)), shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(14.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badge > 0) UnreadBadge(badge, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp))
    }
}

@Composable
private fun ConversationRow(conversation: ChatConversation, presence: ChatPresence?, viewModel: ChatViewModel) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { viewModel.openConversation(conversation) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConversationAvatar(conversation, presence)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.isPinned) { Icon(Icons.Outlined.PushPin, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)) }
                if (conversation.isFavourite) { Icon(Icons.Outlined.StarOutline, null, Modifier.size(12.dp), tint = Color(0xFFCB912F)); Spacer(Modifier.width(4.dp)) }
                Text(conversation.title(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(timeAgo(conversation.lastMessageAt ?: conversation.updatedAt), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.preview(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (conversation.isMuted) { Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.NotificationsOff, "Muted", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f)) }
                if (conversation.unreadCount > 0) { Spacer(Modifier.width(8.dp)); Box(Modifier.clickable { viewModel.markRead(conversation) }) { UnreadBadge(conversation.unreadCount) } }
            }
        }
    }
}

@Composable
private fun ChatThread(ui: ChatUiState, viewModel: ChatViewModel, onPickDocument: () -> Unit) {
    val conversation = ui.selectedConversation ?: return
    if (ui.showInfo) {
        ConversationInfo(ui, viewModel)
        return
    }
    AinoAtmosphere {
        Column(Modifier.fillMaxSize()) {
            // The thread replaces the whole shell, so it owns the status-bar
            // inset rather than inheriting it from the Scaffold.
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .height(58.dp)
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = viewModel::closeConversation), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Box(Modifier.clickable(onClick = viewModel::openInfo)) { ConversationAvatar(conversation, ui.presence[conversation.otherUserId]) }
                Column(Modifier.padding(start = 10.dp).weight(1f).clickable(onClick = viewModel::openInfo)) {
                    Text(conversation.title(), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        if (conversation.isGroup) "${conversation.memberCount ?: 0} members" else presenceLabel(ui.presence[conversation.otherUserId]),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Icon(Icons.Outlined.Info, "Conversation info", Modifier.padding(horizontal = 8.dp).size(20.dp).clickable(onClick = viewModel::openInfo), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = viewModel::refreshThread), contentAlignment = Alignment.Center) {
                    if (ui.threadLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, "Refresh messages", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.threadFromCache) AinoAlert("Offline · showing cached messages", AlertTone.Warning, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            ui.error?.let { AinoAlert(it, AlertTone.Error, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.messages.isEmpty() && ui.queuedMessages.isEmpty() && !ui.threadLoading) {
                    item { HonestEmpty(Icons.Outlined.ChatBubbleOutline, "No messages yet") }
                }
                items(ui.messages, key = { "server-${it.id}" }) { message ->
                    MessageBubble(
                        message,
                        isMine = message.senderId == ui.currentUserId,
                        receipts = ui.receipts,
                        onReact = { viewModel.react(message, it) },
                        onCancel = { viewModel.cancelMedia(message) },
                        onRetry = { viewModel.retryMedia(message) },
                    )
                }
                items(ui.queuedMessages, key = { "queued-${it.clientMessageId}" }) { queued ->
                    QueuedBubble(queued)
                }
            }
            MessageComposer(ui.composer, ui.uploading, viewModel::updateComposer, viewModel::sendMessage, onPickDocument)
        }
    }
}

@Composable
private fun CallRow(call: CallLog, currentUserId: Long?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (call.callType == "video") Icons.Outlined.Videocam else Icons.Outlined.Phone, null, tint = if (call.status == "missed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(call.title(currentUserId), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(call.status.replace('_', ' ').replaceFirstChar(Char::uppercase), call.callType.replaceFirstChar(Char::uppercase), call.duration?.let(::formatCallDuration)).filterNotNull().joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Text(timeAgo(call.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun ConversationInfo(ui: ChatUiState, viewModel: ChatViewModel) {
    val conversation = ui.selectedConversation ?: return
    AinoAtmosphere {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding().height(56.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(38.dp).padding(8.dp).clickable(onClick = viewModel::closeInfo))
                Text("Conversation info", Modifier.padding(start = 6.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(88.dp)) { ConversationAvatar(conversation, ui.presence[conversation.otherUserId]) }
                        Text(conversation.title(), Modifier.padding(top = 12.dp), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(if (conversation.isGroup) "${conversation.memberCount ?: ui.members.size} members" else presenceLabel(ui.presence[conversation.otherUserId]), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoAction(Icons.Outlined.PushPin, if (conversation.isPinned) "Unpin" else "Pin", Modifier.weight(1f), viewModel::togglePin)
                        InfoAction(Icons.Outlined.StarOutline, if (conversation.isFavourite) "Unfavourite" else "Favourite", Modifier.weight(1f), viewModel::toggleFavourite)
                        InfoAction(if (conversation.isMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, if (conversation.isMuted) "Unmute" else "Mute", Modifier.weight(1f), viewModel::toggleMute)
                    }
                }
                item {
                    InfoRow(Icons.Outlined.Archive, if (conversation.isArchived) "Unarchive conversation" else "Archive conversation", viewModel::toggleArchive)
                }
                if (ui.members.isNotEmpty()) {
                    item { SectionHeader("Members", Icons.Outlined.People) }
                    items(ui.members, key = { "member-${it.id}" }) { member ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Text(member.display().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(member.display(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("@${member.username.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            Text(member.role.replaceFirstChar(Char::uppercase), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
                if (ui.conversationCalls.isNotEmpty()) {
                    item { SectionHeader("Call history", Icons.Outlined.Phone) }
                    items(ui.conversationCalls, key = { "info-call-${it.id}" }) { CallRow(it, ui.currentUserId) }
                }
            }
        }
    }
}

@Composable
private fun InfoAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp)); Text(label, Modifier.padding(start = 12.dp).weight(1f), fontSize = 14.sp)
    }
}

private fun formatCallDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    receipts: List<ReadReceipt>,
    onReact: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
        if (!isMine) Text(message.senderName ?: message.senderUsername.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Column(
            Modifier.fillMaxWidth(0.82f).background(
                if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp),
            ).padding(horizontal = 13.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            message.replyContent?.let {
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text(message.replySenderName.orEmpty(), color = if (isMine) Color.White.copy(alpha = .8f) else MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(it, color = if (isMine) Color.White.copy(alpha = .76f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
                }
            }
            Text(message.body(), color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            if (!message.fileName.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(
                        if (message.fileType?.startsWith("image/") == true) Icons.Outlined.Image else Icons.Outlined.Description,
                        null,
                        Modifier.size(18.dp),
                        tint = if (isMine) Color.White else MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(message.fileName, color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        message.fileSize?.let { Text(formatFileSize(it), color = if (isMine) Color.White.copy(alpha = .7f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
                    }
                }
            }
            if (!message.mediaState.isNullOrBlank() && message.mediaState !in setOf("ready", "completed")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(message.mediaStage ?: message.mediaState, message.mediaProgress?.let { "$it%" }).joinToString(" · "),
                        Modifier.weight(1f),
                        color = if (isMine) Color.White.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                    when (message.mediaState) {
                        "failed", "cancelled" -> Icon(Icons.Outlined.Replay, "Retry", Modifier.size(17.dp).clickable(onClick = onRetry), tint = if (isMine) Color.White else MaterialTheme.colorScheme.primary)
                        "queued", "processing" -> Icon(Icons.Outlined.Cancel, "Cancel", Modifier.size(17.dp).clickable(onClick = onCancel), tint = if (isMine) Color.White else MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                message.editedAt?.let { Text("edited · ", color = if (isMine) Color.White.copy(alpha = .65f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
                Text(timeAgo(message.createdAt), color = if (isMine) Color.White.copy(alpha = .65f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        if (message.reactions.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                message.reactions.groupingBy { it.emoji }.eachCount().forEach { (emoji, count) ->
                    Text("$emoji $count", Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable { onReact(emoji) }.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 11.sp)
                }
            }
        } else if (message.deletedAt == null) {
            Text("♡", Modifier.padding(horizontal = 10.dp).clickable { onReact("👍") }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        if (isMine && receipts.isNotEmpty()) {
            Text("Read by ${receipts.joinToString { it.fullName }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun QueuedBubble(message: QueuedMessage) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(
            Modifier.fillMaxWidth(0.82f).background(MaterialTheme.colorScheme.primary.copy(alpha = .72f), RoundedCornerShape(16.dp)).padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(message.content, color = Color.White, fontSize = 14.sp)
            Text("Queued", Modifier.align(Alignment.End), color = Color.White.copy(alpha = .7f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun MessageComposer(value: String, uploading: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, onPickDocument: () -> Unit) {
    // `imePadding()` lifts the composer above the soft keyboard and
    // `navigationBarsPadding()` keeps it clear of the gesture bar; without
    // both, the input sat underneath the keyboard while typing.
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline))
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).clickable(enabled = !uploading, onClick = onPickDocument),
            contentAlignment = Alignment.Center,
        ) {
            if (uploading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.AttachFile, "Attach file", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner -> if (value.isBlank()) Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp); inner() },
        )
        Box(
            Modifier.size(42.dp).background(if (value.isBlank()) MaterialTheme.colorScheme.onSurface.copy(alpha = .08f) else MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(enabled = value.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send", Modifier.size(19.dp), tint = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color.White) }
    }
}

@Composable
private fun ConversationAvatar(conversation: ChatConversation, presence: ChatPresence?) {
    val initials = conversation.title().trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifEmpty { "?" }
    Box(Modifier.size(48.dp)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            if (conversation.isMeetingChat) Icon(Icons.Outlined.Videocam, null, Modifier.size(22.dp), tint = Color.White)
            else if (conversation.isGroup) Icon(Icons.Outlined.Groups, null, Modifier.size(23.dp), tint = Color.White)
            else Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (!conversation.isGroup && !conversation.isMeetingChat) Box(
            Modifier.align(Alignment.BottomEnd).size(15.dp).background(MaterialTheme.colorScheme.background, CircleShape).padding(2.dp).background(statusColor(presence), CircleShape),
        )
    }
}

@Composable
private fun statusColor(presence: ChatPresence?): Color = when {
    presence == null || presence.presence != "online" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
    presence.userStatus in setOf("busy", "dnd", "in_call", "in_meeting") -> Color(0xFFE03E3E)
    presence.userStatus in setOf("away", "brb") -> Color(0xFFCB912F)
    else -> Color(0xFF4DAA57)
}

@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.height(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f), CircleShape).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (count > 99) "99+" else count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SectionHeader(label: String, icon: ImageVector, warning: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = if (warning) Color(0xFFCB912F) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        Text(label.uppercase(Locale.getDefault()), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
    }
}

@Composable
private fun UserResultRow(user: ChatUser, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val name = user.display().ifBlank { "Unknown user" }
        val initials = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) {
            Text(name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            user.email?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1) }
        }
        Text("Message", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchHint(text: String, progress: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (progress) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun ArchivedRow(count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            Text("Archived", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("$count chat${if (count == 1) "" else "s"} · viewing unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HonestEmpty(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(74.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

private fun timeAgo(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return ""
    val minutes = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1_440 -> "${minutes / 60}h"
        else -> DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(instant.atZone(ZoneId.systemDefault()))
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}