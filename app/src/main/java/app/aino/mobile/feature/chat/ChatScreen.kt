@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.aino.mobile.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.designsystem.AinoEmptyState
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.File
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private enum class ChatListTab { Chat, Meet, Calls }

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onPickDocument: () -> Unit,
    conversationId: Long? = null,
    meetingsEnabled: Boolean = false,
    onOpenConversation: ((Long) -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    var activeTab by remember { mutableStateOf(ChatListTab.Chat) }
    var searchOpen by remember { mutableStateOf(false) }
    var newGroupOpen by remember { mutableStateOf(false) }
    val query = ui.userSearch.trim()
    LaunchedEffect(conversationId, ui.conversations) {
        if (conversationId != null && ui.selectedConversation?.id != conversationId) {
            ui.conversations.firstOrNull { it.id == conversationId }?.let(viewModel::openConversation)
        }
    }
    BackHandler(enabled = ui.selectedConversationIds.isNotEmpty()) { viewModel.cancelConversationSelection() }
    // One-shot navigation from the VM: open a just-created chat, or leave a thread we no longer belong to.
    LaunchedEffect(ui.openConversationId, ui.closeThread) {
        val openId = ui.openConversationId
        when {
            openId != null -> {
                viewModel.consumeNavigation()
                if (onOpenConversation != null) onOpenConversation(openId)
            }
            ui.closeThread -> { viewModel.consumeNavigation(); onNavigateBack?.invoke() }
        }
    }
    BackHandler(enabled = ui.selectedCallIds.isNotEmpty()) { viewModel.cancelCallSelection() }
    val visibleConversations = remember(ui.conversations, activeTab, query) {
        ui.conversations.filter { conversation ->
            val inTab = when (activeTab) {
                ChatListTab.Chat -> !conversation.isMeetingChat && !conversation.isArchived
                ChatListTab.Meet -> meetingsEnabled && conversation.isMeetingChat && !conversation.isArchived
                ChatListTab.Calls -> false
            }
            inTab && (query.isBlank() || listOf(
                conversation.title(), conversation.otherUsername.orEmpty(),
                conversation.lastMessage.orEmpty(), conversation.groupName.orEmpty(),
            ).joinToString(" ").contains(query, ignoreCase = true))
        }
    }
    if (ui.selectedConversation != null) {
        ChatThread(ui, viewModel, onPickDocument, onNavigateBack)
        return
    }
    val signal = signalColors
    val searching = searchOpen && query.length >= 2
    // Signal list search: debounce people + message search as you type.
    LaunchedEffect(query, searchOpen) {
        if (!searchOpen || query.length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        viewModel.searchUsers()
        viewModel.searchAllMessages(query)
    }
    BackHandler(enabled = searchOpen) { searchOpen = false; viewModel.updateUserSearch("") }
    val openConversation: (ChatConversation) -> Unit = { conversation ->
        if (onOpenConversation != null) onOpenConversation(conversation.id) else viewModel.openConversation(conversation)
    }

    Box(Modifier.fillMaxSize().background(signal.background)) {
        Column(Modifier.fillMaxSize()) {
            if (ui.selectedCallIds.isNotEmpty()) {
                SelectionHeader(
                    selected = ui.selectedCallIds.size,
                    allSelected = ui.calls.isNotEmpty() && ui.calls.all { it.id in ui.selectedCallIds },
                    deleting = ui.deletingCalls,
                    onCancel = viewModel::cancelCallSelection,
                    onSelectAll = viewModel::selectAllCalls,
                    onDelete = viewModel::deleteSelectedCalls,
                )
            } else if (ui.selectedConversationIds.isNotEmpty()) {
                SelectionHeader(
                    selected = ui.selectedConversationIds.size,
                    allSelected = visibleConversations.isNotEmpty() && visibleConversations.all { it.id in ui.selectedConversationIds },
                    deleting = ui.deletingConversations,
                    onCancel = viewModel::cancelConversationSelection,
                    onSelectAll = { viewModel.selectAll(visibleConversations.map(ChatConversation::id)) },
                    onDelete = viewModel::deleteSelectedConversations,
                )
            } else {
                ChatHeader(
                    activeTab = activeTab,
                    searchOpen = searchOpen,
                    query = ui.userSearch,
                    onQuery = viewModel::updateUserSearch,
                    onSearchOpen = { open ->
                        searchOpen = open
                        if (!open) viewModel.updateUserSearch("")
                    },
                    onNewGroup = { newGroupOpen = true },
                )
                if (!searchOpen) ChatFilterChips(
                    activeTab = activeTab,
                    unread = ui.conversations.sumOf {
                        if (it.isMuted || it.isArchived || it.isMeetingChat) 0 else it.unreadCount.coerceAtLeast(0)
                    },
                    meetingUnread = ui.conversations.filter(ChatConversation::isMeetingChat).sumOf { it.unreadCount.coerceAtLeast(0) },
                    meetingsEnabled = meetingsEnabled,
                    onTab = { tab -> activeTab = tab; if (tab == ChatListTab.Calls) viewModel.loadCalls() },
                )
            }
            if (newGroupOpen) NewGroupDialog(ui, viewModel) { newGroupOpen = false }

            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = if (activeTab == ChatListTab.Calls) ui.callsLoading else ui.loading,
                onRefresh = { if (activeTab == ChatListTab.Calls) viewModel.loadCalls() else viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (ui.error != null || ui.message != null) item(key = "notice") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ui.error?.let { AinoAlert(it, AlertTone.Error) }
                        ui.message?.let { AinoAlert(it, if (ui.fromCache) AlertTone.Warning else AlertTone.Success) }
                    }
                }
                if (searching) {
                    // Signal search results: Chats, Contacts, Messages.
                    if (visibleConversations.isNotEmpty()) {
                        item(key = "s-chats") { SectionHeader("Chats") }
                        items(visibleConversations, key = { "s-chat-${it.id}" }) {
                            ConversationRow(it, ui.presence[it.otherUserId], false, viewModel, onOpenConversation, highlight = query)
                        }
                    }
                    val contacts = ui.userResults.filter { it.id != ui.currentUserId }
                    if (contacts.isNotEmpty() || ui.searching) item(key = "s-contacts") { SectionHeader("Contacts") }
                    if (ui.searching && contacts.isEmpty()) item(key = "s-searching") { SearchHint("Searching…", true) }
                    items(contacts, key = { "s-user-${it.id}" }) { user -> UserResultRow(user, query) { viewModel.startDirect(user) } }
                    if (ui.messageResults.isNotEmpty()) {
                        item(key = "s-messages") { SectionHeader("Messages") }
                        items(ui.messageResults, key = { "s-msg-${it.id}" }) { message ->
                            val conversation = ui.conversations.firstOrNull { it.id == message.conversationId }
                            MessageResultRow(message, conversation, query) {
                                if (conversation != null) { viewModel.rememberJump(message); openConversation(conversation) }
                            }
                        }
                    }
                    if (visibleConversations.isEmpty() && contacts.isEmpty() && ui.messageResults.isEmpty() && !ui.searching) {
                        item(key = "s-empty") { SearchHint("No results for \"$query\"", false) }
                    }
                } else when (activeTab) {
                    ChatListTab.Calls -> {
                        when {
                            ui.callsLoading -> item(key = "calls-loading") { SearchHint("Loading call history…", true) }
                            ui.calls.isEmpty() -> item(key = "calls-empty") { HonestEmpty(Icons.Outlined.Phone, "No calls yet") }
                            else -> items(ui.calls, key = { "call-${it.id}" }) { call ->
                                CallRow(
                                    call = call,
                                    currentUserId = ui.currentUserId,
                                    selected = call.id in ui.selectedCallIds,
                                    selectionEnabled = true,
                                    onToggleSelection = { viewModel.toggleCallSelection(call.id) },
                                )
                            }
                        }
                    }
                    ChatListTab.Meet -> {
                        if (visibleConversations.isEmpty()) item(key = "meet-empty") {
                            HonestEmpty(Icons.Outlined.Videocam, "No meeting chats yet")
                        } else items(visibleConversations, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], it.id in ui.selectedConversationIds, viewModel, onOpenConversation) }
                    }
                    ChatListTab.Chat -> {
                        // Signal: pinned chats float to the top, then everything by recency (no section chrome).
                        val ordered = visibleConversations.filter(ChatConversation::isPinned) +
                            visibleConversations.filter { it.isFavourite && !it.isPinned } +
                            visibleConversations.filter { !it.isPinned && !it.isFavourite }
                        if (visibleConversations.isEmpty()) item(key = "chat-empty") {
                            HonestEmpty(Icons.Outlined.ChatBubbleOutline, if (ui.loading) "Loading conversations…" else "No conversations yet")
                        }
                        items(ordered, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], it.id in ui.selectedConversationIds, viewModel, onOpenConversation) }
                        val archivedCount = ui.conversations.count { it.isArchived && !it.isMeetingChat }
                        if (archivedCount > 0) item(key = "archived") { ArchivedRow(archivedCount) }
                    }
                }
            }
            }
        }
        // Signal compose FAB (new chat → people search).
        if (!searchOpen && activeTab != ChatListTab.Calls && ui.selectedConversationIds.isEmpty()) {
            androidx.compose.material3.FloatingActionButton(
                onClick = { searchOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = if (signal.isDark) Color(0xFF2B3A5A) else Color(0xFFD2DFFB),
                contentColor = signal.text,
            ) { Icon(Icons.Outlined.Edit, "New chat") }
        }
    }
}

/** Signal list toolbar: title + search + overflow; search swaps in a field. */
@Composable
private fun ChatHeader(
    activeTab: ChatListTab, searchOpen: Boolean, query: String,
    onQuery: (String) -> Unit, onSearchOpen: (Boolean) -> Unit, onNewGroup: () -> Unit,
) {
    val signal = signalColors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchOpen) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search", Modifier.size(48.dp).clip(CircleShape).clickable { onSearchOpen(false) }.padding(12.dp), tint = signal.text)
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(signal.searchPill).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query, onValueChange = onQuery, modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = signal.text, fontSize = 17.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(signal.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search", color = signal.textSecondary, fontSize = 17.sp)
                        inner()
                    } },
                )
                if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "Clear search", Modifier.size(24.dp).clip(CircleShape).clickable { onQuery("") }, tint = signal.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Text(
                when (activeTab) { ChatListTab.Chat -> "Chats"; ChatListTab.Meet -> "Meetings"; ChatListTab.Calls -> "Calls" },
                Modifier.weight(1f).padding(start = 12.dp),
                color = signal.text, fontSize = 22.sp, fontWeight = FontWeight.Medium,
            )
            Icon(Icons.Outlined.Search, "Search", Modifier.size(48.dp).clip(CircleShape).clickable { onSearchOpen(true) }.padding(12.dp), tint = signal.text)
            Box {
                Icon(Icons.Outlined.MoreVert, "More options", Modifier.size(48.dp).clip(CircleShape).clickable { menuOpen = true }.padding(12.dp), tint = signal.text)
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = signal.surface) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("New group", color = signal.text) },
                        leadingIcon = { Icon(Icons.Outlined.GroupAdd, null, tint = signal.text) },
                        onClick = { menuOpen = false; onNewGroup() },
                    )
                }
            }
        }
    }
}

/** Signal-style filter chips standing in for the Chats / Meet / Calls tabs. */
@Composable
private fun ChatFilterChips(activeTab: ChatListTab, unread: Int, meetingUnread: Int, meetingsEnabled: Boolean, onTab: (ChatListTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Segment("Chats", Icons.Outlined.ChatBubbleOutline, activeTab == ChatListTab.Chat, unread) { onTab(ChatListTab.Chat) }
        if (meetingsEnabled) Segment("Meet", Icons.Outlined.Videocam, activeTab == ChatListTab.Meet, meetingUnread) { onTab(ChatListTab.Meet) }
        Segment("Calls", Icons.Outlined.Phone, activeTab == ChatListTab.Calls, 0) { onTab(ChatListTab.Calls) }
    }
}

@Composable
private fun Segment(label: String, icon: ImageVector, active: Boolean, badge: Int, onClick: () -> Unit) {
    val signal = signalColors
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) signal.primary.copy(alpha = .16f) else Color.Transparent)
            .border(1.dp, if (active) Color.Transparent else signal.divider, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (active) signal.primary else signal.textSecondary)
        Text(label, color = if (active) signal.primary else signal.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (badge > 0) SignalUnreadBadge(badge, modifier = Modifier.height(18.dp))
    }
}
@Composable
private fun SelectionHeader(
    selected: Int,
    allSelected: Boolean,
    deleting: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().height(SignalDimens.selectionHeader).background(signal.surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Close, "Cancel selection", Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onCancel).padding(12.dp), tint = signal.text)
        Text("$selected", Modifier.weight(1f).padding(start = 8.dp), color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        TextButton(onClick = onSelectAll) { Text(if (allSelected) "Clear all" else "Select all", color = signal.primary) }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !deleting, onClick = onDelete), contentAlignment = Alignment.Center) {
            if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = signal.danger)
            else Icon(Icons.Outlined.DeleteOutline, "Delete", tint = signal.danger)
        }
    }
}

/** Signal conversation-list row: 48dp avatar, name/date line, 2-line preview, unread badge, mute/pin glyphs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ChatConversation,
    presence: ChatPresence?,
    selected: Boolean,
    viewModel: ChatViewModel,
    onOpenConversation: ((Long) -> Unit)?,
    highlight: String? = null,
) {
    val signal = signalColors
    val unread = conversation.unreadCount > 0
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(if (selected) signal.primary.copy(alpha = .14f) else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (viewModel.ui.value.selectedConversationIds.isNotEmpty()) viewModel.toggleConversationSelection(conversation.id)
                    else if (onOpenConversation != null) onOpenConversation(conversation.id)
                    else viewModel.openConversation(conversation)
                },
                onLongClick = { viewModel.toggleConversationSelection(conversation.id) },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ConversationAvatar(conversation, presence, SignalDimens.listAvatar)
            if (selected) Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).background(signal.primary, CircleShape).border(2.dp, signal.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Done, null, Modifier.size(12.dp), tint = Color.White) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlightTerm(conversation.title(), highlight, signal.highlight),
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = signal.text, fontSize = 17.sp, fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                )
                if (conversation.isMuted) { Icon(Icons.Outlined.NotificationsOff, "Muted", Modifier.padding(start = 4.dp).size(14.dp), tint = signal.textSecondary) }
                Text(
                    listTime(conversation.lastMessageAt ?: conversation.updatedAt),
                    Modifier.padding(start = 6.dp),
                    color = if (unread) signal.primary else signal.textSecondary, fontSize = 13.sp,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val sender = conversation.lastSenderName?.takeIf { conversation.isGroup && it.isNotBlank() && conversation.lastDeleted == null }
                Text(
                    buildString { if (sender != null) append(sender.substringBefore(' ')).append(": "); append(conversation.preview()) },
                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (unread) signal.text else signal.textSecondary, fontSize = 15.sp, lineHeight = 20.sp,
                    fontStyle = if (conversation.lastDeleted != null) androidx.compose.ui.text.font.FontStyle.Italic else null,
                )
                if (conversation.isPinned) Icon(Icons.Outlined.PushPin, "Pinned", Modifier.padding(start = 6.dp).size(16.dp), tint = signal.textSecondary)
                if (conversation.isFavourite) Icon(Icons.Outlined.Star, "Favourite", Modifier.padding(start = 6.dp).size(16.dp), tint = Color(0xFFCB912F))
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(CircleShape).clickable { viewModel.markRead(conversation) }) { SignalUnreadBadge(conversation.unreadCount, muted = conversation.isMuted) }
                }
            }
        }
    }
}

/** Signal list timestamps: time today, weekday this week, else "MMM d". */
private fun listTime(value: String?): String {
    val instant = value?.let(::parseChatInstant) ?: return ""
    val zoned = instant.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes in 0..0 -> "Now"
        minutes in 1..59 -> "$minutes min"
        zoned.toLocalDate() == today -> DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(zoned)
        zoned.toLocalDate().isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(zoned)
        zoned.year == today.year -> DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(zoned)
        else -> DateTimeFormatter.ofPattern("M/d/yy", Locale.getDefault()).format(zoned)
    }
}

/** Signal "Messages" search hit: conversation avatar, title, highlighted snippet, date. */
@Composable
private fun MessageResultRow(message: ChatMessage, conversation: ChatConversation?, query: String, onClick: () -> Unit) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conversation != null) ConversationAvatar(conversation, null, SignalDimens.listAvatar)
        else app.aino.mobile.core.designsystem.component.UserAvatar(message.senderName.orEmpty(), message.senderAvatar, SignalDimens.listAvatar)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation?.title() ?: message.senderName.orEmpty(), Modifier.weight(1f), color = signal.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listTime(message.createdAt), color = signal.textSecondary, fontSize = 13.sp)
            }
            val body = message.body()
            val hit = body.indexOf(query.trim(), ignoreCase = true)
            val snippet = if (hit > 40) "…" + body.substring(hit - 30) else body
            Text(
                highlightTerm(snippet, query, signal.highlight),
                color = signal.textSecondary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
private fun ChatThread(ui: ChatUiState, viewModel: ChatViewModel, onPickDocument: () -> Unit, onNavigateBack: (() -> Unit)?) {
    val conversation = ui.selectedConversation ?: return
    val signal = signalColors
    val context = LocalContext.current
    val threadItems = remember(ui.messages, ui.queuedMessages, ui.currentUserId, ui.hiddenMessageIds) {
        buildThreadItems(ui.messages.filterNot { it.id in ui.hiddenMessageIds }, ui.queuedMessages, ui.currentUserId)
    }
    // Signal-style bottom-anchored thread: `reverseLayout` keeps the newest
    // message pinned above the composer when the keyboard shrinks the viewport.
    val newestFirst = remember(threadItems) { threadItems.asReversed() }
    val dividerKey = remember(conversation.id, ui.unreadAtOpen, newestFirst.isNotEmpty()) {
        unreadDividerKey(newestFirst, ui.unreadAtOpen, ui.currentUserId)
    }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val atBottom by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    var unseen by remember(conversation.id) { mutableStateOf(0) }
    var viewingMediaId by remember { mutableStateOf<Long?>(null) }
    var pollOpen by remember { mutableStateOf(false) }
    var deleteTargets by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var viewOnceLoadingId by remember { mutableStateOf<Long?>(null) }
    var viewOnceOpen by remember { mutableStateOf<ChatMessage?>(null) }
    var cameraOpen by remember { mutableStateOf(false) }
    var sendItems by remember { mutableStateOf<List<app.aino.mobile.feature.chat.media.MediaSendItem>?>(null) }
    var cameraRecent by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(32)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val picked = uris.map { app.aino.mobile.feature.chat.media.MediaSendItem(it, context.contentResolver.getType(it) ?: "image/jpeg") }
        cameraOpen = false
        sendItems = sendItems.orEmpty() + picked
    }
    val openGallery = { gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) cameraOpen = true }
    val openCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraOpen = true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(cameraOpen) { if (cameraOpen) cameraRecent = loadRecentMedia(context, limit = 30).map(RecentMedia::uri) }
    BackHandler(enabled = ui.showInfo) { viewModel.closeInfo() }
    BackHandler(enabled = ui.selectedMessageIds.isNotEmpty()) { viewModel.clearMessageSelection() }
    BackHandler(enabled = ui.threadSearchOpen) { viewModel.closeThreadSearch() }
    BackHandler(enabled = cameraOpen) { cameraOpen = false }
    BackHandler(enabled = sendItems != null) { sendItems = null }
    // Web `handleJumpTo`: scroll to a pinned/saved/search hit and flash it; page older history until it's loaded.
    var highlightedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(ui.jumpToMessageId, newestFirst.size, ui.loadingOlder) {
        val target = ui.jumpToMessageId ?: return@LaunchedEffect
        val index = newestFirst.indexOfFirst { it.key == "server-$target" }
        if (index < 0) {
            if (ui.hasOlderMessages && !ui.loadingOlder && newestFirst.isNotEmpty()) viewModel.loadOlderMessages()
            else if (!ui.hasOlderMessages) viewModel.consumeJump()
            return@LaunchedEffect
        }
        viewModel.consumeJump()
        listState.animateScrollToItem(index)
        highlightedId = target
        kotlinx.coroutines.delay(2_000)
        highlightedId = null
    }
    val newestKey = newestFirst.firstOrNull()?.key
    val newestMine = (newestFirst.firstOrNull() as? ThreadItem.Message)?.message?.senderId == ui.currentUserId ||
        newestFirst.firstOrNull() is ThreadItem.Queued
    LaunchedEffect(newestKey) {
        if (newestKey == null) return@LaunchedEffect
        if (atBottom || newestMine) { listState.animateScrollToItem(0); unseen = 0 } else unseen++
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }
    // Auto load-more when the oldest loaded row scrolls into view (web: scroll-top load-more).
    LaunchedEffect(listState, newestFirst.size) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (newestFirst.isNotEmpty() && last >= newestFirst.size - 3) viewModel.loadOlderMessages() }
    }
    val withCallPermissions = app.aino.mobile.core.call.rememberCallPermissions()
    if (ui.showInfo) {
        ConversationInfo(ui, viewModel)
        return
    }
    if (ui.forwardingMessage != null) {
        ForwardMessageDialog(ui, viewModel)
    }
    val searchTerm = ui.threadSearchQuery.takeIf { ui.threadSearchOpen }
    Box(Modifier.fillMaxSize().background(signal.background)) {
        Column(Modifier.fillMaxSize()) {
            when {
                ui.selectedMessageIds.isNotEmpty() -> MessageSelectionBar(ui, viewModel) { deleteTargets = it }
                ui.threadSearchOpen -> ThreadSearchToolbar(ui.threadSearchQuery, viewModel::updateThreadSearch, viewModel::closeThreadSearch)
                else -> ThreadHeader(conversation, ui, viewModel, onNavigateBack, withCallPermissions)
            }
            if (ui.threadFromCache) AinoAlert("Offline · showing cached messages", AlertTone.Warning, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            ui.error?.let { AinoAlert(it, AlertTone.Error, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (threadItems.isEmpty() && !ui.threadLoading) {
                    item { HonestEmpty(Icons.Outlined.ChatBubbleOutline, "No messages yet") }
                }
                items(newestFirst, key = ThreadItem::key) { item ->
                    Column {
                    if (item.key == dividerKey) UnreadDivider(ui.unreadAtOpen)
                    when (item) {
                        is ThreadItem.DateSeparator -> DateSeparator(item.date)
                        is ThreadItem.Message -> MessageBubble(
                            item.message,
                            isMine = item.message.senderId == ui.currentUserId,
                            showSender = item.startsGroup,
                            startsGroup = item.startsGroup,
                            endsGroup = item.endsGroup,
                            receipts = ui.receipts,
                            participantCount = conversation.memberCount,
                            onReply = { viewModel.beginReply(item.message) },
                            onReact = { viewModel.react(item.message, it) },
                            onEdit = { viewModel.beginEdit(item.message) },
                            onDelete = { deleteTargets = listOf(item.message) },
                            onStar = { viewModel.toggleStar(item.message) },
                            onPin = { viewModel.toggleMessagePin(item.message) },
                            onForward = { viewModel.beginForward(item.message) },
                            onCancel = { viewModel.cancelMedia(item.message) },
                            onRetry = { viewModel.retryMedia(item.message) },
                            onVote = { option -> viewModel.votePoll(item.message, option) },
                            onOpenMedia = { viewingMediaId = it.id },
                            selectionActive = ui.selectedMessageIds.isNotEmpty(),
                            selected = item.message.id in ui.selectedMessageIds,
                            highlighted = item.message.id == highlightedId ||
                                (ui.threadSearchOpen && item.message.id == ui.threadSearchMatches.getOrNull(ui.threadSearchIndex)),
                            onSelect = { viewModel.enterMessageSelection(item.message) },
                            onToggleSelect = { viewModel.toggleMessageSelection(item.message.id) },
                            isGroup = conversation.isGroup,
                            currentUserId = ui.currentUserId,
                            searchTerm = searchTerm,
                            viewOnceLoading = viewOnceLoadingId == item.message.id,
                            onOpenViewOnce = {
                                viewOnceLoadingId = item.message.id
                                viewModel.openViewOnce(item.message) { url ->
                                    viewOnceLoadingId = null
                                    if (url != null) viewOnceOpen = item.message.copy(fileUrl = url)
                                }
                            },
                        )
                        is ThreadItem.Queued -> QueuedBubble(item.message)
                    }
                    }
                }
                if (ui.loadingOlder) item(key = "loading-older") {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = signal.primary)
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            ) {
                ScrollToBottomButton(unseen) {
                    scope.launch { listState.animateScrollToItem(0) }
                }
            }
            }
            if (ui.typingUserId != null) TypingBubble()
            if (ui.threadSearchOpen) {
                ThreadSearchBottomBar(
                    index = ui.threadSearchIndex,
                    count = ui.threadSearchMatches.size,
                    searching = false,
                    onOlder = { viewModel.stepThreadSearch(1) },
                    onNewer = { viewModel.stepThreadSearch(-1) },
                )
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)))
            } else MessageComposer(
                value = ui.composer,
                uploading = ui.uploading,
                uploadProgress = ui.uploadProgress,
                editingMessage = ui.editingMessage,
                replyingTo = ui.replyingTo,
                isGroup = conversation.isGroup,
                members = ui.members,
                currentUserId = ui.currentUserId,
                linkPreview = ui.composerLinkPreview,
                onDismissLinkPreview = viewModel::dismissLinkPreview,
                onMention = viewModel::addMention,
                onChange = viewModel::updateComposer,
                onSend = viewModel::sendMessage,
                onCancelEdit = viewModel::cancelEdit,
                onCancelReply = viewModel::cancelReply,
                onPickDocument = onPickDocument,
                onUploadVoice = viewModel::uploadVoiceNote,
                onOpenPoll = { pollOpen = true },
                onOpenCamera = openCamera,
                onOpenGallery = openGallery,
                onSendMedia = { sendItems = sendItems.orEmpty() + it },
            )
        }
        if (cameraOpen) app.aino.mobile.feature.chat.media.ChatCameraScreen(
            recent = cameraRecent,
            onClose = { cameraOpen = false },
            onCaptured = { captured -> cameraOpen = false; sendItems = sendItems.orEmpty() + captured },
            onOpenGallery = openGallery,
        )
        sendItems?.let { items ->
            app.aino.mobile.feature.chat.media.MediaSendScreen(
                initial = items,
                recipientName = conversation.title(),
                onAddMore = openGallery,
                onClose = { sendItems = null },
                onSend = { finalItems, caption, viewOnce, highQuality ->
                    sendItems = null
                    viewModel.sendMedia(finalItems.map { MediaUploadSpec(it.uri, it.mimeType, it.width, it.height) }, caption, viewOnce, highQuality)
                },
            )
        }
    }
    viewingMediaId?.let { id ->
        val media = remember(ui.messages) { ui.messages.filter { it.deletedAt == null && it.isViewableMedia() && !it.isViewOnce() } }
        ChatMediaViewer(media, id) { viewingMediaId = null }
    }
    viewOnceOpen?.let { message ->
        ChatMediaViewer(listOf(message), message.id, secure = true) { viewOnceOpen = null }
    }
    deleteTargets?.let { targets ->
        SignalDeleteDialog(
            count = targets.size,
            canDeleteForEveryone = targets.all { it.senderId == ui.currentUserId && it.deletedAt == null },
            onDeleteForMe = {
                if (ui.selectedMessageIds.isNotEmpty()) viewModel.deleteSelectedForMe() else targets.forEach(viewModel::deleteForMe)
            },
            onDeleteForEveryone = {
                if (ui.selectedMessageIds.isNotEmpty()) viewModel.deleteSelectedForEveryone() else targets.forEach(viewModel::deleteMessage)
            },
            onDismiss = { deleteTargets = null },
        )
    }
    ui.pendingAttachment?.let { pending ->
        AttachmentPreviewDialog(pending, ui.composer, viewModel::sendAttachment, viewModel::cancelAttachment)
    }
    if (pollOpen) {
        PollCreatorDialog(
            onSubmit = { q, options, multi -> pollOpen = false; viewModel.createPoll(q, options, multi) },
            onClose = { pollOpen = false },
        )
    }
}

/** Signal multi-select toolbar: count, copy, forward (single), pin, save, delete (dialog). */
@Composable
private fun MessageSelectionBar(ui: ChatUiState, viewModel: ChatViewModel, onDelete: (List<ChatMessage>) -> Unit) {
    val signal = signalColors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val selected = ui.messages.filter { it.id in ui.selectedMessageIds }
    Row(
        Modifier.fillMaxWidth().background(signal.surface).statusBarsPadding().height(SignalDimens.selectionHeader).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Close, "Cancel message selection", Modifier.size(48.dp).clip(CircleShape).clickable(onClick = viewModel::clearMessageSelection).padding(12.dp), tint = signal.text)
        Text("${ui.selectedMessageIds.size}", Modifier.weight(1f).padding(start = 8.dp), color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        @Composable
        fun action(icon: ImageVector, label: String, tint: Color = signal.text, onClick: () -> Unit) =
            Icon(icon, label, Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).padding(12.dp), tint = tint)
        action(Icons.Outlined.ContentCopy, "Copy selected text") {
            viewModel.selectedText().takeIf(String::isNotEmpty)?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
            viewModel.clearMessageSelection()
        }
        if (selected.size == 1) action(Icons.AutoMirrored.Outlined.Forward, "Forward") { viewModel.beginForward(selected.first()); viewModel.clearMessageSelection() }
        action(Icons.Outlined.PushPin, "Pin or unpin selected", onClick = viewModel::pinSelected)
        action(Icons.Outlined.StarOutline, "Save or unsave selected", onClick = viewModel::starSelected)
        action(Icons.Outlined.DeleteOutline, "Delete selected", tint = signal.danger) { onDelete(selected) }
    }
}
@Composable
private fun UnreadDivider(count: Int) {
    val signal = signalColors
    Text(
        if (count == 1) "1 Unread Message" else "$count Unread Messages",
        Modifier.fillMaxWidth().padding(vertical = 10.dp).background(signal.surface).padding(vertical = 6.dp),
        color = signal.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
/** Web sidebar "New group": name + people search + multi-select, then open the new chat. */
@Composable
private fun NewGroupDialog(ui: ChatUiState, viewModel: ChatViewModel, onClose: () -> Unit) {
    val colors = LocalWebColors.current
    var name by remember { mutableStateOf("") }
    val picked = remember { androidx.compose.runtime.mutableStateMapOf<Long, ChatUser>() }
    DisposableEffect(Unit) { onDispose { viewModel.updateUserSearch("") } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    name, { name = it.take(100) },
                    Modifier.fillMaxWidth().background(colors.inputBg, RoundedCornerShape(8.dp)).padding(12.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                    decorationBox = { inner -> if (name.isEmpty()) Text("Group name", color = colors.textMuted); inner() },
                )
                BasicTextField(
                    ui.userSearch, viewModel::updateUserSearch,
                    Modifier.fillMaxWidth().background(colors.inputBg, RoundedCornerShape(8.dp)).padding(12.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchUsers() }),
                    decorationBox = { inner -> if (ui.userSearch.isEmpty()) Text("Search people...", color = colors.textMuted); inner() },
                )
                if (picked.isNotEmpty()) Text(picked.values.joinToString { it.display() }, color = colors.primary, fontSize = 12.sp)
                if (ui.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(ui.userResults.filter { it.id != ui.currentUserId }, key = { "pick-${it.id}" }) { user ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (user.id in picked) picked.remove(user.id) else picked[user.id] = user },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(user.id in picked, { if (it) picked[user.id] = user else picked.remove(user.id) })
                            Column {
                                Text(user.display(), color = colors.text, fontSize = 14.sp)
                                user.username?.let { Text("@$it", color = colors.textMuted, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.createGroup(name, picked.keys.toList()); onClose() }, enabled = name.isNotBlank() && picked.isNotEmpty()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ScrollToBottomButton(unseen: Int, onClick: () -> Unit) {
    Box {
        val signal = signalColors
        androidx.compose.material3.Surface(
            onClick = onClick, shape = CircleShape, color = signal.surface, shadowElevation = 3.dp,
            modifier = Modifier.padding(top = 10.dp).size(44.dp),
        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.KeyboardArrowDown, "Scroll to latest", tint = signal.text) } }
        if (unseen > 0) SignalUnreadBadge(unseen, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CallRow(
    call: CallLog,
    currentUserId: Long?,
    selected: Boolean = false,
    selectionEnabled: Boolean = false,
    onToggleSelection: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primaryGlow else Color.Transparent)
            .combinedClickable(
                enabled = selectionEnabled,
                onClick = { if (selected) onToggleSelection() },
                onLongClick = onToggleSelection,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionEnabled && selected) {
            Checkbox(checked = true, onCheckedChange = { onToggleSelection() })
        }
        Box(Modifier.size(46.dp).background(app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (call.callType == "video") Icons.Outlined.Videocam else Icons.Outlined.Phone, null, tint = if (call.status == "missed") app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.danger else app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(call.title(currentUserId), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(call.status.replace('_', ' ').replaceFirstChar(Char::uppercase), call.callType.replaceFirstChar(Char::uppercase), call.duration?.let(::formatCallDuration)).filterNotNull().joinToString(" · "),
                color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary,
                fontSize = 12.sp,
            )
        }
        Text(timeAgo(call.createdAt), color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary, fontSize = 11.sp)
    }
}

private enum class InfoPage(val title: String) {
    Main("Conversation info"), Search("Search"), Shared("Shared media, files & links"),
    Pinned("Pinned messages"), Saved("Saved messages"), Group("Group settings & members"),
}

/** Port of web `ConversationInfoPanel.tsx` (quick Call/Video/Search, shared/pinned/saved, block, clear). */
@Composable
private fun ConversationInfo(ui: ChatUiState, viewModel: ChatViewModel) {
    val conversation = ui.selectedConversation ?: return
    val colors = LocalWebColors.current
    var page by remember { mutableStateOf(InfoPage.Main) }
    var confirmClear by remember { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf("") }
    BackHandler(enabled = page != InfoPage.Main) { page = InfoPage.Main }
    fun open(next: InfoPage) {
        page = next
        when (next) {
            InfoPage.Pinned -> viewModel.loadPinnedMessages()
            InfoPage.Saved -> viewModel.loadSavedMessages()
            InfoPage.Shared -> viewModel.loadSharedFiles()
            InfoPage.Search -> viewModel.searchInConversation("")
            else -> Unit
        }
    }
    val withCallPermissions = app.aino.mobile.core.call.rememberCallPermissions()
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(colors.bgSecondary)
                    .border(BorderStroke(0.5.dp, colors.border))
                    .statusBarsPadding().height(56.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack, "Back",
                    Modifier.size(38.dp).padding(8.dp).clickable { if (page == InfoPage.Main) viewModel.closeInfo() else page = InfoPage.Main },
                    tint = colors.text,
                )
                Text(page.title, Modifier.padding(start = 6.dp), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            ui.error?.let { AinoAlert(it, AlertTone.Error, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            when (page) {
                InfoPage.Main -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(88.dp)) { ConversationAvatar(conversation, ui.presence[conversation.otherUserId], 88.dp) }
                            Text(conversation.title(), Modifier.padding(top = 12.dp), color = colors.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (conversation.isGroup) conversation.memberCount?.let { "$it members" } ?: "Group" else presenceLabel(ui.presence[conversation.otherUserId]),
                                color = colors.textSecondary, fontSize = 13.sp,
                            )
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!conversation.isSelfChat) {
                                InfoAction(Icons.Outlined.Phone, "Call", Modifier.weight(1f)) { withCallPermissions(false) { viewModel.closeInfo(); viewModel.startCall("voice") } }
                                InfoAction(Icons.Outlined.Videocam, "Video", Modifier.weight(1f)) { withCallPermissions(true) { viewModel.closeInfo(); viewModel.startCall("video") } }
                            }
                            InfoAction(Icons.Outlined.Search, "Search", Modifier.weight(1f)) { open(InfoPage.Search) }
                        }
                    }
                    if (conversation.isGroup) item { InfoRow(Icons.Outlined.Settings, "Group settings & members") { open(InfoPage.Group) } }
                    item { InfoRow(Icons.Outlined.FolderOpen, "Shared media, files & links") { open(InfoPage.Shared) } }
                    item { InfoRow(Icons.Outlined.PushPin, "Pinned messages") { open(InfoPage.Pinned) } }
                    item { InfoRow(Icons.Outlined.StarOutline, "Saved messages") { open(InfoPage.Saved) } }
                    // The web exposes these through the conversation row's hover menu;
                    // mobile has no hover, so they live here.
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoAction(Icons.Outlined.PushPin, if (conversation.isPinned) "Unpin" else "Pin chat", Modifier.weight(1f), viewModel::togglePin)
                            InfoAction(Icons.Outlined.StarOutline, if (conversation.isFavourite) "Unfavourite" else "Favourite", Modifier.weight(1f), viewModel::toggleFavourite)
                            InfoAction(if (conversation.isMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, if (conversation.isMuted) "Unmute" else "Mute", Modifier.weight(1f), viewModel::toggleMute)
                            InfoAction(Icons.Outlined.Archive, if (conversation.isArchived) "Unarchive" else "Archive", Modifier.weight(1f), viewModel::toggleArchive)
                        }
                    }
                    if (!conversation.isGroup && !conversation.isSelfChat && conversation.otherUserId != null) item {
                        InfoRow(Icons.Outlined.Block, if (conversation.isBlocked) "Unblock user" else "Block user", danger = !conversation.isBlocked, onClick = viewModel::toggleBlock)
                    }
                    item { InfoRow(Icons.Outlined.DeleteOutline, "Clear chat", danger = true) { confirmClear = true } }
                    if (ui.conversationCalls.isNotEmpty()) {
                        item { SectionHeader("Call history", Icons.Outlined.Phone) }
                        items(ui.conversationCalls, key = { "info-call-${it.id}" }) { CallRow(it, ui.currentUserId) }
                    }
                }
                InfoPage.Search -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp).height(44.dp).background(colors.inputBg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = colors.textMuted)
                        BasicTextField(
                            searchTerm, { searchTerm = it }, Modifier.weight(1f).padding(start = 8.dp), singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.searchInConversation(searchTerm) }),
                            decorationBox = { inner -> if (searchTerm.isEmpty()) Text("Search in conversation...", color = colors.textMuted, fontSize = 14.sp); inner() },
                        )
                    }
                    InfoMessageList(ui, emptyText = if (searchTerm.length < 2) "Type at least 2 characters and press search" else "No messages found", onOpen = viewModel::jumpToMessage)
                }
                InfoPage.Pinned -> InfoMessageList(ui, "No pinned messages", onOpen = viewModel::jumpToMessage)
                InfoPage.Saved -> InfoMessageList(ui, "No saved messages", onOpen = viewModel::jumpToMessage, onUnstar = viewModel::unstarFromList)
                InfoPage.Shared -> {
                    val files = (ui.infoContent as? InfoContent.Files)?.files.orEmpty()
                    val context = LocalContext.current
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ui.infoLoading) item { SearchHint("Loading…", true) }
                        else if (files.isEmpty()) item { HonestEmpty(Icons.Outlined.FolderOpen, "No shared files yet") }
                        items(files, key = { "file-${it.id}" }) { file ->
                            InfoRow(
                                when {
                                    file.fileType?.startsWith("image/") == true -> Icons.Outlined.Image
                                    file.fileType?.startsWith("video/") == true -> Icons.Outlined.Movie
                                    file.fileType?.startsWith("audio/") == true -> Icons.Outlined.AudioFile
                                    else -> Icons.Outlined.Description
                                },
                                listOfNotNull(file.fileName ?: "File", formatChatFileSize(file.fileSize)).joinToString(" · "),
                            ) { scope.launch { openChatFile(context, resolveChatMediaUrl(file.fileUrl), file.fileName, file.fileType) } }
                        }
                    }
                }
                InfoPage.Group -> GroupSettings(ui, viewModel)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear chat") },
            text = { Text("Delete all messages in this conversation for you?") },
            confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearChat() }) { Text("Clear", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoMessageList(ui: ChatUiState, emptyText: String, onOpen: (ChatMessage) -> Unit, onUnstar: ((ChatMessage) -> Unit)? = null) {
    val colors = LocalWebColors.current
    val messages = (ui.infoContent as? InfoContent.Messages)?.messages.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.infoLoading) item { SearchHint("Loading…", true) }
        else if (messages.isEmpty()) item { HonestEmpty(Icons.Outlined.ChatBubbleOutline, emptyText) }
        items(messages, key = { "info-msg-${it.id}" }) { message ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).clickable { onOpen(message) }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.senderName ?: message.senderUsername.orEmpty(), Modifier.weight(1f), color = colors.primaryLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    message.conversationName?.let { Text("in $it ", color = colors.textMuted, fontSize = 11.sp, maxLines = 1) }
                    Text(timeAgo(message.createdAt), color = colors.textMuted, fontSize = 11.sp)
                    onUnstar?.let { unstar -> Icon(Icons.Outlined.Star, "Unsave", Modifier.padding(start = 6.dp).size(18.dp).clickable { unstar(message) }, tint = colors.warning) }
                }
                Text(message.body(), color = colors.text, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GroupSettings(ui: ChatUiState, viewModel: ChatViewModel) {
    val conversation = ui.selectedConversation ?: return
    val colors = LocalWebColors.current
    var name by remember(conversation.id) { mutableStateOf(conversation.groupName.orEmpty()) }
    var confirmLeave by remember { mutableStateOf(false) }
    val myRole = ui.members.firstOrNull { it.id == ui.currentUserId }?.role
    val canManage = myRole == "owner" || myRole == "admin"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canManage) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    name, { name = it.take(100) },
                    Modifier.weight(1f).background(colors.inputBg, RoundedCornerShape(10.dp)).padding(12.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                    decorationBox = { inner -> if (name.isEmpty()) Text("Group name", color = colors.textMuted); inner() },
                )
                TextButton(onClick = { viewModel.updateGroup(GroupUpdateRequest(name = name.trim())) }, enabled = name.isNotBlank() && name != conversation.groupName) { Text("Save") }
            }
        }
        item { SectionHeader("Members", Icons.Outlined.People) }
        items(ui.members, key = { "member-${it.id}" }) { member ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                app.aino.mobile.core.designsystem.component.UserAvatar(member.display(), member.avatar, 38.dp)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(member.display() + if (member.id == ui.currentUserId) " (You)" else "", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("@${member.username.orEmpty()}", color = colors.textSecondary, fontSize = 11.sp)
                }
                Text(member.role.replaceFirstChar(Char::uppercase), color = colors.textSecondary, fontSize = 11.sp)
                if (canManage && member.id != ui.currentUserId && member.role != "owner") {
                    Icon(Icons.Outlined.Close, "Remove ${member.display()}", Modifier.padding(start = 8.dp).size(20.dp).clickable {
                        viewModel.updateGroup(GroupUpdateRequest(removeUserIds = listOf(member.id)))
                    }, tint = colors.danger)
                }
            }
        }
        item { InfoRow(Icons.AutoMirrored.Outlined.ExitToApp, "Leave group", danger = true) { confirmLeave = true } }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave group") },
            text = { Text("You will stop receiving messages from this group.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; viewModel.leaveGroup() }) { Text("Leave", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(colors.surface).border(1.dp, colors.glassBorder, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.primary)
        Text(label, color = colors.text, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    val tint = if (danger) colors.danger else colors.textSecondary
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).border(1.dp, colors.glassBorder, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, Modifier.padding(start = 12.dp).weight(1f), color = if (danger) colors.danger else colors.text, fontSize = 14.sp)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = colors.textMuted)
    }
}

private fun formatCallDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    showSender: Boolean,
    startsGroup: Boolean,
    endsGroup: Boolean,
    receipts: List<ReadReceipt>,
    participantCount: Int?,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStar: () -> Unit,
    onPin: () -> Unit,
    onForward: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onVote: (Int) -> Unit,
    onOpenMedia: (ChatMessage) -> Unit,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    highlighted: Boolean = false,
    onSelect: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    isGroup: Boolean = false,
    currentUserId: Long? = null,
    searchTerm: String? = null,
    viewOnceLoading: Boolean = false,
    onOpenViewOnce: () -> Unit = {},
) {
    var actionsOpen by remember { mutableStateOf(false) }
    var reactionsOpen by remember { mutableStateOf(false) }
    val signal = signalColors
    // Web ChatMessages: `format_type 'meeting'` rows render a MeetingCard, not a bubble.
    val meetingMeta = message.metadata?.takeIf { message.formatType == "meeting" } as? kotlinx.serialization.json.JsonObject
    val meetingCode = (meetingMeta?.get("meetingCode") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    if (meetingMeta != null && meetingCode != null) {
        ChatMeetingCard(meetingMeta, meetingCode)
        return
    }
    if (message.formatType == "system") {
        val text = message.metadata?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: message.body()
        Text(
            text,
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
            color = signal.textSecondary, fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }
    val deleted = message.deletedAt != null
    val viewOnce = !deleted && message.isViewOnce() && (message.fileType?.startsWith("image/") == true || message.fileType?.startsWith("video/") == true)
    val mediaOnly = !deleted && !viewOnce && message.isViewableMedia() && message.content.isNullOrBlank() &&
        message.replyContent == null && message.forwardedFromId == null && !(showSender && isGroup && !isMine)
    val shape = messageBubbleShape(isMine, startsGroup, endsGroup)
    val bubbleColor = when {
        deleted -> Color.Transparent
        isMine -> signal.outgoing
        else -> signal.incoming
    }
    val fg = if (isMine && !deleted) signal.onOutgoing else signal.onIncoming
    val fgMuted = if (isMine && !deleted) signal.onOutgoingSecondary else signal.onIncomingSecondary
    val rowTint by androidx.compose.animation.animateColorAsState(
        when {
            selected -> signal.primary.copy(alpha = .16f)
            highlighted -> signal.highlight
            else -> Color.Transparent
        },
        label = "rowTint",
    )
    val footer: @Composable (onMedia: Boolean) -> Unit = { onMedia ->
        val tint = if (onMedia) Color.White else fgMuted
        Row(
            (if (onMedia) Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .38f)).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (message.starred) Icon(Icons.Outlined.Star, "Saved message", Modifier.size(12.dp), tint = tint)
            if (message.pinnedAt != null) Icon(Icons.Outlined.PushPin, "Pinned message", Modifier.size(12.dp), tint = tint)
            if (message.editedAt != null && !deleted) Text("Edited", color = tint, fontSize = SignalDimens.footerText)
            Text(bubbleTime(message.createdAt), color = tint, fontSize = SignalDimens.footerText)
            if (isMine && !deleted) DeliveryTickIcon(deliveryTick(message, receipts, participantCount), readTint = tint, mutedTint = tint)
        }
    }
    val showAvatarColumn = isGroup && !isMine
    val startGutter = if (showAvatarColumn) SignalDimens.receivedGutter else SignalDimens.gutter
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(rowTint)
            .padding(top = if (startsGroup) 8.dp else 2.dp, start = startGutter, end = SignalDimens.gutter),
    ) {
        val avatarSpace = if (showAvatarColumn) SignalDimens.groupAvatar + 8.dp else 0.dp
        val bubbleMax = maxWidth - SignalDimens.bubbleEdgeMargin - avatarSpace
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
            if (showAvatarColumn) {
                Box(Modifier.size(SignalDimens.groupAvatar)) {
                    if (endsGroup) app.aino.mobile.core.designsystem.component.UserAvatar(
                        message.senderName ?: message.senderUsername.orEmpty(), message.senderAvatar, SignalDimens.groupAvatar,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                MessageGestureBox(
                    onReply = onReply,
                    onLongPress = { if (selectionActive) onToggleSelect() else if (!deleted) actionsOpen = true },
                    onClick = { if (selectionActive) onToggleSelect() },
                    enabled = !deleted,
                ) {
                    Column(
                        Modifier.widthIn(max = bubbleMax)
                            .clip(shape)
                            .background(bubbleColor)
                            .then(if (deleted) Modifier.border(1.dp, signal.divider, shape) else Modifier)
                            .then(
                                if (mediaOnly) Modifier
                                else Modifier.padding(
                                    start = SignalDimens.bubbleHPad, end = SignalDimens.bubbleHPad,
                                    top = SignalDimens.bubbleTopPad, bottom = SignalDimens.bubbleFooterBottomPad,
                                ),
                            ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (!isMine && isGroup && showSender && !deleted) Text(
                            message.senderName ?: message.senderUsername.orEmpty(),
                            color = senderColor(message.senderId, signal.isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (message.forwardedFromId != null && !deleted) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.Forward, null, Modifier.size(14.dp), tint = fgMuted)
                                Text("Forwarded", color = fgMuted, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            }
                        }
                        if (!deleted) message.replyContent?.let { SignalQuote(message, isMine, fg) }
                        when {
                            deleted -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Block, null, Modifier.size(16.dp), tint = signal.textSecondary)
                                Text(
                                    if (isMine) "You deleted this message." else "This message was deleted.",
                                    color = signal.textSecondary, fontSize = 15.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                )
                                footer(false)
                            }
                            viewOnce -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ViewOnceContent(message, viewOnceState(message, currentUserId), isMine, viewOnceLoading, onOpenViewOnce)
                                footer(false)
                            }
                            message.formatType == "poll" -> {
                                val options = message.metadata?.jsonObject?.get("options")?.jsonArray
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Poll, null, Modifier.size(18.dp), tint = fg)
                                    Text(message.body(), Modifier.padding(start = 7.dp), color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                                options?.forEachIndexed { index, element ->
                                    val label = element.jsonPrimitive.contentOrNull ?: return@forEachIndexed
                                    Text(
                                        label,
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, fg.copy(alpha = .35f), RoundedCornerShape(10.dp))
                                            .clickable { onVote(index) }.padding(horizontal = 12.dp, vertical = 9.dp),
                                        color = fg, fontSize = 15.sp,
                                    )
                                }
                                Box(Modifier.align(Alignment.End)) { footer(false) }
                            }
                            mediaOnly -> Box {
                                ChatMediaPreview(message = message, onOpenMedia = onOpenMedia, onCancelProcessing = { onCancel() }, onRetryProcessing = { onRetry() }, shape = shape, outgoing = isMine)
                                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { footer(true) }
                            }
                            !message.fileUrl.isNullOrBlank() -> {
                                if (message.formatType != "poll") message.linkPreview?.let { LinkPreviewCard(it) }
                                ChatMediaPreview(
                                    message = message, onOpenMedia = onOpenMedia,
                                    onCancelProcessing = { onCancel() }, onRetryProcessing = { onRetry() },
                                    shape = RoundedCornerShape(SignalDimens.bubbleCornerCollapsed * 3), outgoing = isMine,
                                )
                                val caption = message.content?.takeIf(String::isNotBlank)
                                if (caption != null) BubbleTextWithFooter(highlightTerm(caption, searchTerm, signal.highlight), fg, { footer(false) })
                                else Box(Modifier.align(Alignment.End)) { footer(false) }
                            }
                            !message.fileName.isNullOrBlank() -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(40.dp).background(fg.copy(alpha = .15f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                        Icon(
                                            when {
                                                message.fileType?.startsWith("image/") == true -> Icons.Outlined.Image
                                                message.fileType?.startsWith("audio/") == true -> Icons.Outlined.AudioFile
                                                message.fileType?.startsWith("video/") == true -> Icons.Outlined.Movie
                                                else -> Icons.Outlined.Description
                                            },
                                            null, Modifier.size(22.dp), tint = fg,
                                        )
                                    }
                                    Column(Modifier.weight(1f, fill = false)) {
                                        Text(message.fileName, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        message.fileSize?.let { Text(formatFileSize(it), color = fgMuted, fontSize = 13.sp) }
                                    }
                                }
                                if (!message.mediaState.isNullOrBlank() && message.mediaState !in setOf("ready", "completed")) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(listOfNotNull(message.mediaStage ?: message.mediaState, message.mediaProgress?.let { "$it%" }).joinToString(" · "), color = fgMuted, fontSize = 12.sp)
                                        when (message.mediaState) {
                                            "failed", "cancelled" -> Icon(Icons.Outlined.Replay, "Retry", Modifier.padding(start = 6.dp).size(18.dp).clickable(onClick = onRetry), tint = fg)
                                            "queued", "processing" -> Icon(Icons.Outlined.Cancel, "Cancel", Modifier.padding(start = 6.dp).size(18.dp).clickable(onClick = onCancel), tint = fg)
                                        }
                                    }
                                }
                                Box(Modifier.align(Alignment.End)) { footer(false) }
                            }
                            else -> {
                                message.linkPreview?.let { LinkPreviewCard(it) }
                                BubbleTextWithFooter(highlightTerm(message.body(), searchTerm, signal.highlight), fg, { footer(false) })
                            }
                        }
                    }
                }
                // Signal reaction pill overlaps the bubble's bottom edge.
                if (message.reactions.isNotEmpty() && !deleted) {
                    val counts = message.reactions.groupingBy { it.emoji }.eachCount().entries.sortedByDescending { it.value }
                    Row(
                        Modifier.padding(horizontal = 8.dp).offset(y = (-4).dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(signal.surface)
                            .border(2.dp, signal.background, RoundedCornerShape(14.dp))
                            .clickable { reactionsOpen = true }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        counts.take(3).forEach { (emoji, _) -> Text(emoji, fontSize = 14.sp) }
                        if (message.reactions.size > 1) Text("${message.reactions.size}", Modifier.padding(start = 2.dp), color = signal.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    if (actionsOpen) {
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val isPoll = message.formatType == "poll"
        ChatReactionOverlay(
            onDismiss = { actionsOpen = false },
            onReaction = onReact,
            myReactions = message.reactions.filter { it.userId == currentUserId }.map { it.emoji }.toSet(),
            alignEnd = isMine,
            actions = ReactionActions(
                onSelect = onSelect,
                onReply = onReply,
                onForward = onForward,
                onEdit = if (isMine && message.fileUrl.isNullOrBlank() && !isPoll) onEdit else null,
                onStar = onStar,
                onPin = onPin,
                onDelete = onDelete,
                onCopy = message.content?.takeIf(String::isNotBlank)?.let { text ->
                    { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }
                },
                pinned = message.pinnedAt != null,
                starred = message.starred,
            ),
            messagePreview = {
                val preview = message.body().take(400)
                if (preview.isNotBlank()) Text(
                    preview,
                    Modifier.widthIn(max = 300.dp).clip(shape).background(bubbleColor.takeIf { it != Color.Transparent } ?: signal.incoming)
                        .padding(horizontal = SignalDimens.bubbleHPad, vertical = SignalDimens.bubbleTopPad),
                    color = fg, fontSize = SignalDimens.bodyText, lineHeight = SignalDimens.bodyLine, maxLines = 8, overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
    if (reactionsOpen) ReactionsSheet(message.reactions, currentUserId, onRemoveMine = onReact) { reactionsOpen = false }
}

/** Signal quote block inside a bubble: accent bar, author, 2-line text, optional 60dp thumbnail. */
@Composable
private fun SignalQuote(message: ChatMessage, isMine: Boolean, fg: Color) {
    val signal = signalColors
    val quoteShape = RoundedCornerShape(SignalDimens.quoteCorner)
    Row(
        Modifier.clip(quoteShape).background(if (isMine) Color.White.copy(alpha = .22f) else if (signal.isDark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .6f))
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (isMine) Color.White else signal.primary))
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(message.replySenderName.orEmpty(), color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                message.replyContent?.takeIf(String::isNotBlank) ?: message.replyFileName ?: "Attachment",
                color = fg.copy(alpha = .85f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val thumb = message.replyFileUrl?.takeIf { message.replyFileType?.startsWith("image/") == true }
        if (thumb != null) {
            coil3.compose.AsyncImage(
                model = resolveChatMediaUrl(thumb),
                imageLoader = app.aino.mobile.core.AppContainer.get(LocalContext.current).imageLoader,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(SignalDimens.quoteThumb),
            )
        }
    }
}

/** Signal colours group sender names from a fixed palette keyed by the sender. */
private fun senderColor(senderId: Long, dark: Boolean): Color {
    val light = listOf(0xFFD00B0B, 0xFFC72A0A, 0xFFB34209, 0xFF9C5711, 0xFF866118, 0xFF76681E, 0xFF6C6C13, 0xFF5E6E0C, 0xFF507406, 0xFF3D7406, 0xFF2D7906, 0xFF1A7906, 0xFF067906, 0xFF067919, 0xFF06792D, 0xFF067940, 0xFF067953, 0xFF067462, 0xFF067474, 0xFF077288, 0xFF086DA0, 0xFF0A69C7, 0xFF0D59F2, 0xFF3454F4, 0xFF5151F6, 0xFF6447F5, 0xFF7A3DF5, 0xFF8F2AF4, 0xFFA20CED, 0xFFAF0BD0, 0xFFB80AB8, 0xFFC20AA3)
    val base = Color(light[(senderId.mod(light.size.toLong())).toInt()])
    return if (dark) androidx.compose.ui.graphics.lerp(base, Color.White, .45f) else base
}

/** Signal bubbles show the local clock time ("10:42 AM"), not a relative age. */
private fun bubbleTime(value: String): String = parseChatInstant(value)?.let {
    DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault()))
}.orEmpty()
@Composable
private fun ForwardMessageDialog(ui: ChatUiState, viewModel: ChatViewModel) {
    val message = ui.forwardingMessage ?: return
    val choices = remember(ui.conversations, ui.forwardQuery) {
        forwardDestinations(ui.conversations, ui.forwardQuery)
    }
    AlertDialog(
        onDismissRequest = viewModel::cancelForward,
        title = { Text("Forward message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    message.body().take(120),
                    color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicTextField(
                    value = ui.forwardQuery,
                    onValueChange = viewModel::updateForwardQuery,
                    modifier = Modifier.fillMaxWidth().background(app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.bgElevated, MaterialTheme.shapes.large)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (ui.forwardQuery.isBlank()) Text("Search conversations", color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary)
                            inner()
                        }
                    },
                )
                Text(
                    "${ui.forwardTargets.size} of 20 selected",
                    color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    if (choices.isEmpty()) item { AinoEmptyState(Icons.Outlined.ChatBubbleOutline, "No matching conversations") }
                    items(choices, key = ChatConversation::id) { conversation ->
                        val selected = conversation.id in ui.forwardTargets
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .clickable(enabled = selected || ui.forwardTargets.size < 20) { viewModel.toggleForwardTarget(conversation.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { viewModel.toggleForwardTarget(conversation.id) },
                                enabled = selected || ui.forwardTargets.size < 20,
                            )
                            ConversationAvatar(conversation, ui.presence[conversation.otherUserId])
                            Text(
                                conversation.title(),
                                Modifier.padding(start = 10.dp).weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::submitForward, enabled = ui.forwardTargets.isNotEmpty() && !ui.forwarding) {
                if (ui.forwarding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Forward${if (ui.forwardTargets.isEmpty()) "" else " (${ui.forwardTargets.size})"}")
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancelForward, enabled = !ui.forwarding) { Text("Cancel") } },
    )
}

@Composable
private fun DateSeparator(date: LocalDate) {
    val signal = signalColors
    val today = LocalDate.now()
    val label = when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
        else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            Modifier.clip(RoundedCornerShape(14.dp)).background(signal.datePill).padding(horizontal = 12.dp, vertical = 4.dp),
            color = signal.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
    }
}
/** Rounded on the outside of a group and tight toward consecutive bubbles. */
internal fun messageBubbleShape(isMine: Boolean, startsGroup: Boolean, endsGroup: Boolean): RoundedCornerShape {
    val large = SignalDimens.bubbleCorner
    val tight = SignalDimens.bubbleCornerCollapsed
    return if (isMine) {
        RoundedCornerShape(
            topStart = large,
            topEnd = if (startsGroup) large else tight,
            bottomStart = large,
            bottomEnd = if (endsGroup) large else tight,
        )
    } else {
        RoundedCornerShape(
            topStart = if (startsGroup) large else tight,
            topEnd = large,
            bottomStart = if (endsGroup) large else tight,
            bottomEnd = large,
        )
    }
}

/** Authoritative receipts that include this exact message in their read range. */
private fun readByForMessage(message: ChatMessage, receipts: List<ReadReceipt>): List<ReadReceipt> {
    val sentAt = parseChatInstant(message.createdAt) ?: return emptyList()
    return receipts.filter { receipt ->
        val readAt = parseChatInstant(receipt.lastReadAt)
        readAt != null && !readAt.isBefore(sentAt)
    }
}

/** Signal-style tick states, ported from `DeliveryStatus.tsx`'s thresholds. */
internal enum class DeliveryTick { Sending, Sent, Delivered, Read }

internal fun deliveryTick(message: ChatMessage, receipts: List<ReadReceipt>, participantCount: Int?): DeliveryTick {
    val others = (participantCount ?: 2) - 1
    if (others <= 0) return DeliveryTick.Sent
    val delivered = message.deliveredTo.size
    // Exclude the sender's own receipt (trivially "read" their own message).
    val read = readByForMessage(message, receipts).count { it.userId != message.senderId }
    return when {
        read >= others -> DeliveryTick.Read
        read > 0 && delivered >= others -> DeliveryTick.Read
        delivered >= others -> DeliveryTick.Delivered
        delivered > 0 -> DeliveryTick.Delivered
        else -> DeliveryTick.Sent
    }
}

@Composable
private fun DeliveryTickIcon(tick: DeliveryTick, readTint: Color, mutedTint: Color) {
    SignalReceiptIcon(tick, if (tick == DeliveryTick.Read) readTint else mutedTint)
}

private fun parseChatInstant(value: String): Instant? = runCatching {
    Instant.parse(value.replace(" ", "T").let {
        if (it.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(it)) it else "${it}Z"
    })
}.getOrNull()

@Composable
private fun QueuedBubble(message: QueuedMessage) {
    val signal = signalColors
    val shape = messageBubbleShape(isMine = true, startsGroup = true, endsGroup = true)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp, start = SignalDimens.gutter, end = SignalDimens.gutter)) {
        val bubbleMax = maxWidth - SignalDimens.bubbleEdgeMargin
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.widthIn(max = bubbleMax).clip(shape).background(signal.outgoing.copy(alpha = .85f))
                    .padding(start = SignalDimens.bubbleHPad, end = SignalDimens.bubbleHPad, top = SignalDimens.bubbleTopPad, bottom = SignalDimens.bubbleFooterBottomPad),
            ) {
                BubbleTextWithFooter(AnnotatedString(message.content), signal.onOutgoing, {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
                                .format(Instant.ofEpochMilli(message.createdAtEpochMs).atZone(ZoneId.systemDefault())),
                            color = signal.onOutgoingSecondary, fontSize = SignalDimens.footerText,
                        )
                        SignalReceiptIcon(DeliveryTick.Sending, signal.onOutgoingSecondary)
                    }
                })
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    val signal = signalColors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.fillMaxWidth().padding(start = SignalDimens.gutter, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            Modifier.background(signal.incoming, RoundedCornerShape(SignalDimens.bubbleCorner)).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = .3f, targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(500, delayMillis = index * 160),
                        androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                Box(Modifier.size(8.dp).background(signal.onIncomingSecondary.copy(alpha = alpha), CircleShape))
            }
        }
    }
}
@Composable
private fun MessageComposer(
    value: String,
    uploading: Boolean,
    uploadProgress: Float?,
    editingMessage: ChatMessage?,
    replyingTo: ChatMessage?,
    isGroup: Boolean,
    members: List<ConversationMember>,
    currentUserId: Long?,
    linkPreview: LinkPreview?,
    onDismissLinkPreview: () -> Unit,
    onMention: (ConversationMember) -> Unit,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelEdit: () -> Unit,
    onCancelReply: () -> Unit,
    onPickDocument: () -> Unit,
    onUploadVoice: (Uri) -> Unit,
    onOpenPoll: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    onSendMedia: (List<app.aino.mobile.feature.chat.media.MediaSendItem>) -> Unit,
) {
    val context = LocalContext.current
    val signal = signalColors
    val player = app.aino.mobile.core.AppContainer.get(context).audio
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Signal swaps the IME for its own emoji / attachment keyboards at the same height.
    var panel by remember { mutableStateOf(ComposerPanel.None) }
    var keyboardHeight by remember { mutableStateOf(300.dp) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    LaunchedEffect(imeBottom) {
        val h = with(density) { (imeBottom - navBottom).toDp() }
        if (h > 200.dp) { keyboardHeight = h; panel = ComposerPanel.None }
    }
    BackHandler(enabled = panel != ComposerPanel.None) { panel = ComposerPanel.None }
    fun toggle(target: ComposerPanel) {
        if (panel == target) { panel = ComposerPanel.None; keyboard?.show() } else { keyboard?.hide(); panel = target }
    }
    // ---- Voice note state machine (see VoiceNoteRecorder.kt) ----
    val voice = remember { VoiceNoteRecorder(context) }
    var phase by remember { mutableStateOf(VoicePhase.Idle) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val levels = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    var slideOffset by remember { mutableStateOf(0f) }
    var draftUrl by remember { mutableStateOf<String?>(null) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    fun dispatch(event: VoiceEvent) {
        val (next, effect) = phase.reduce(event, voice.elapsedMs())
        when (effect) {
            VoiceEffect.Start -> if (runCatching { voice.start() }.isFailure) {
                voiceHint = "Could not access microphone"
                return
            }
            VoiceEffect.Pause -> runCatching { voice.pause() }
            VoiceEffect.Resume -> runCatching { voice.resume() }
            VoiceEffect.StopToDraft -> {
                val file = voice.stop()
                if (file == null) { voiceHint = "Recording failed"; phase = VoicePhase.Idle; return }
                draftUrl = Uri.fromFile(file).toString()
            }
            VoiceEffect.Send -> {
                draftUrl?.let(player::stop)
                val file = voice.stop()
                voice.detach()
                draftUrl = null
                if (file != null) onUploadVoice(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                else voiceHint = "Recording failed"
            }
            VoiceEffect.Discard -> {
                draftUrl?.let(player::stop)
                voice.discard()
                draftUrl = null
                if (event == VoiceEvent.Release) voiceHint = "Hold to record, release to send"
            }
            VoiceEffect.None -> Unit
        }
        phase = next
        if (next == VoicePhase.Idle) { levels.clear(); slideOffset = 0f; elapsedMs = 0 }
    }
    LaunchedEffect(phase) {
        while (phase == VoicePhase.Holding || phase == VoicePhase.Locked || phase == VoicePhase.Paused) {
            elapsedMs = voice.elapsedMs()
            if (phase != VoicePhase.Paused) {
                levels.add(voice.amplitude())
                if (levels.size > 160) levels.removeAt(0)
            }
            // Signal caps voice notes at one hour.
            if (elapsedMs >= 60 * 60_000L) dispatch(if (phase == VoicePhase.Holding) VoiceEvent.Release else VoiceEvent.Stop)
            kotlinx.coroutines.delay(80)
        }
    }
    LaunchedEffect(voiceHint) { if (voiceHint != null) { kotlinx.coroutines.delay(2_500); voiceHint = null } }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceHint = if (granted) "Hold to record, release to send" else "Microphone permission is required for voice messages"
    }
    DisposableEffect(Unit) { onDispose { draftUrl?.let(player::stop); voice.discard() } }

    val recordingActive = phase != VoicePhase.Idle
    val canRecord = value.isBlank() && editingMessage == null
    // `union()` takes the max of the nav-bar and IME insets (chaining the two
    // paddings would sum them); with adjustResize in the manifest the window
    // itself never pans, so this is the only place the keyboard is accounted for.
    Column(
        Modifier.fillMaxWidth().background(signal.background)
            .then(if (panel == ComposerPanel.None) Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)) else Modifier),
    ) {
        // Determinate once bytes start flowing (OkHttp ProgressRequestBody).
        if (uploading) {
            if (uploadProgress != null && uploadProgress > 0f) {
                androidx.compose.material3.LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = signal.primary)
            } else {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = signal.primary)
            }
        }
        voiceHint?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = signal.textSecondary, fontSize = 13.sp)
        }
        val suggestions = remember(value, members) {
            activeMentionQuery(value)?.let { mentionSuggestions(members, it, currentUserId) }.orEmpty()
        }
        if (suggestions.isNotEmpty() && phase == VoicePhase.Idle) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(signal.surface)) {
                suggestions.forEach { member ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onMention(member) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        app.aino.mobile.core.designsystem.component.UserAvatar(member.display(), null, 28.dp)
                        Text(member.display(), color = signal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        member.username?.let { Text("@$it", color = signal.textSecondary, fontSize = 13.sp) }
                    }
                }
            }
        }
        // Signal quote-style banner above the input for edit / reply.
        val banner: Pair<String, String>? = when {
            editingMessage != null -> "Edit message" to editingMessage.body()
            replyingTo != null -> (replyingTo.senderName ?: replyingTo.senderUsername.orEmpty()) to replyingTo.body()
            else -> null
        }
        if (banner != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp)
                    .clip(RoundedCornerShape(SignalDimens.quoteCorner)).background(signal.surface)
                    .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(signal.primary))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).weight(1f)) {
                    Text(banner.first, color = signal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(banner.second, color = signal.textSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    Icons.Outlined.Close, if (editingMessage != null) "Cancel edit" else "Cancel reply",
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClick = if (editingMessage != null) onCancelEdit else onCancelReply).padding(10.dp),
                    tint = signal.textSecondary,
                )
            }
        }
        if (linkPreview != null && phase == VoicePhase.Idle && editingMessage == null) {
            LinkPreviewCard(linkPreview, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), onRemove = onDismissLinkPreview)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Signal compose pill: emoji toggle · text · quick camera · quick mic; outside is attach (+) / send.
            Row(
                Modifier.weight(1f).heightIn(min = SignalDimens.composeHeight, max = 140.dp)
                    .clip(RoundedCornerShape(22.dp)).background(signal.searchPill),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (phase) {
                    VoicePhase.Idle -> {
                        Box(Modifier.size(SignalDimens.composeHeight).clickable { toggle(ComposerPanel.Emoji) }, contentAlignment = Alignment.Center) {
                            Icon(
                                if (panel == ComposerPanel.Emoji) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                                if (panel == ComposerPanel.Emoji) "Show keyboard" else "Show emoji",
                                Modifier.size(24.dp), tint = signal.textSecondary,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = { onChange(it.take(5000)) },
                            modifier = Modifier.weight(1f).padding(vertical = 11.dp)
                                .onFocusChanged { if (it.isFocused && panel != ComposerPanel.None) panel = ComposerPanel.None },
                            textStyle = androidx.compose.ui.text.TextStyle(color = signal.text, fontSize = 17.sp, lineHeight = 22.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(signal.primary),
                            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                            decorationBox = { inner ->
                                if (value.isEmpty()) Text(if (editingMessage == null) "Message" else "Edit message", color = signal.textSecondary, fontSize = 17.sp)
                                inner()
                            },
                        )
                        if (canRecord) {
                            Box(Modifier.size(width = 40.dp, height = SignalDimens.composeHeight).clickable(enabled = !uploading) { panel = ComposerPanel.None; onOpenCamera() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.CameraAlt, "Open camera", Modifier.size(24.dp), tint = signal.textSecondary)
                            }
                        }
                    }
                    VoicePhase.Draft -> VoiceDraftPanel(draftUrl.orEmpty(), onDelete = { dispatch(VoiceEvent.Delete) }, Modifier.weight(1f))
                    else -> VoiceRecordingPanel(
                        phase = phase,
                        elapsedMs = elapsedMs,
                        levels = levels,
                        slideOffsetPx = slideOffset,
                        onDelete = { dispatch(VoiceEvent.Delete) },
                        onPauseResume = { dispatch(if (phase == VoicePhase.Paused) VoiceEvent.Resume else VoiceEvent.Pause) },
                        onStop = { dispatch(VoiceEvent.Stop) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Kept mounted from Idle through Holding so the press gesture survives the recomposition.
                if (canRecord && (phase == VoicePhase.Idle || phase == VoicePhase.Holding)) {
                    MicHoldButton(
                        holding = phase == VoicePhase.Holding,
                        enabled = !uploading,
                        onPress = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                player.stop()
                                dispatch(VoiceEvent.Press)
                                phase == VoicePhase.Holding
                            } else {
                                recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                                false
                            }
                        },
                        onRelease = { dispatch(VoiceEvent.Release) },
                        onLock = { dispatch(VoiceEvent.Lock) },
                        onSlideCancel = { dispatch(VoiceEvent.SlideCancel) },
                        onSlide = { slideOffset = it },
                    )
                }
            }
            val sendMode = phase == VoicePhase.Locked || phase == VoicePhase.Paused || phase == VoicePhase.Draft ||
                value.isNotBlank() || editingMessage != null
            val rotation by androidx.compose.animation.core.animateFloatAsState(if (panel == ComposerPanel.Attach && !sendMode) 45f else 0f, label = "plus")
            Box(
                Modifier.size(SignalDimens.composeHeight).clip(CircleShape)
                    .background(if (sendMode) signal.primary else signal.searchPill)
                    .clickable(enabled = !uploading && phase != VoicePhase.Holding) {
                        when {
                            recordingActive -> dispatch(VoiceEvent.Send)
                            sendMode -> onSend()
                            else -> toggle(ComposerPanel.Attach)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.Crossfade(sendMode, label = "sendMode") { send ->
                    Icon(
                        if (send) Icons.AutoMirrored.Outlined.Send else Icons.Outlined.Add,
                        if (send) "Send" else "Attachments",
                        Modifier.size(24.dp).graphicsLayer { rotationZ = if (send) 0f else rotation },
                        tint = if (send) Color.White else signal.text,
                    )
                }
            }
        }
        when (panel) {
            ComposerPanel.Emoji -> SignalEmojiKeyboard(
                onEmoji = { onChange(value + it) },
                onBackspace = { if (value.isNotEmpty()) onChange(dropLastGrapheme(value)) },
                height = keyboardHeight,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
            ComposerPanel.Attach -> Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                AttachmentKeyboard(
                    height = keyboardHeight,
                    showPoll = isGroup,
                    onSendMedia = { panel = ComposerPanel.None; onSendMedia(it) },
                    onGallery = { panel = ComposerPanel.None; onOpenGallery() },
                    onFile = { panel = ComposerPanel.None; onPickDocument() },
                    onPoll = { panel = ComposerPanel.None; onOpenPoll() },
                    onCamera = { panel = ComposerPanel.None; onOpenCamera() },
                )
            }
            ComposerPanel.None -> Unit
        }
    }
}

private enum class ComposerPanel { None, Emoji, Attach }

/** Backspace for the emoji keyboard: removes one user-perceived character (Android's ICU BreakIterator keeps emoji sequences whole). */
internal fun dropLastGrapheme(text: String): String {
    if (text.isEmpty()) return text
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(text)
    it.last()
    return text.substring(0, it.previous().coerceAtLeast(0))
}
@Composable
private fun ConversationAvatar(conversation: ChatConversation, presence: ChatPresence?, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val signal = signalColors
    Box(Modifier.size(size)) {
        when {
            conversation.isMeetingChat -> Box(Modifier.fillMaxSize().background(signal.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Videocam, null, Modifier.size(size * .46f), tint = Color.White)
            }
            conversation.isGroup && conversation.groupAvatar.isNullOrBlank() -> Box(Modifier.fillMaxSize().background(signal.primary.copy(alpha = .2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, null, Modifier.size(size * .5f), tint = signal.primary)
            }
            else -> app.aino.mobile.core.designsystem.component.UserAvatar(conversation.title(), conversation.avatar(), size)
        }
        if (!conversation.isGroup && !conversation.isMeetingChat && presence?.presence == "online") Box(
            Modifier.align(Alignment.BottomEnd).size(size * .3f).background(signal.background, CircleShape).padding(2.dp).background(statusColor(presence), CircleShape),
        )
    }
}

@Composable
private fun statusColor(presence: ChatPresence?): Color = when {
    presence == null || presence.presence != "online" -> LocalWebColors.current.textMuted
    presence.userStatus in setOf("busy", "dnd", "in_call") -> Color(0xFFEF4444)
    presence.userStatus in setOf("away", "brb", "in_meeting") -> Color(0xFFF59E0B)
    else -> Color(0xFF4DAA57)
}

@Composable
private fun SectionHeader(label: String, @Suppress("UNUSED_PARAMETER") icon: ImageVector? = null) {
    val signal = signalColors
    Text(
        label,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        color = signal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun UserResultRow(user: ChatUser, query: String? = null, onClick: () -> Unit) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val name = user.display().ifBlank { "Unknown user" }
        app.aino.mobile.core.designsystem.component.UserAvatar(name, user.avatar, SignalDimens.listAvatar)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(highlightTerm(name, query, signal.highlight), color = signal.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            (user.username?.let { "@$it" } ?: user.email)?.takeIf(String::isNotBlank)?.let {
                Text(it, color = signal.textSecondary, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SearchHint(text: String, progress: Boolean) {
    val signal = signalColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (progress) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = signal.primary); Spacer(Modifier.width(8.dp)) }
        Text(text, color = signal.textSecondary, fontSize = 15.sp)
    }
}

@Composable
private fun ArchivedRow(count: Int) {
    val signal = signalColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(SignalDimens.listAvatar), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Archive, null, Modifier.size(24.dp), tint = signal.textSecondary)
        }
        Text("Archived chats ($count)", Modifier.padding(start = 16.dp), color = signal.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable
private fun HonestEmpty(icon: ImageVector, text: String) {
    AinoEmptyState(icon = icon, title = text, modifier = Modifier.padding(top = 42.dp))
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
/** Web `MeetingCard.tsx`: in-chat meeting invite with Join / "Meeting ended". */
@Composable
private fun ChatMeetingCard(meta: kotlinx.serialization.json.JsonObject, code: String) {
    fun field(key: String) = (meta[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    val ended = field("status") in setOf("ended", "cancelled")
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp).widthIn(max = 320.dp).clip(shape)
            .background(Color(0xFF1A1A22)).border(1.dp, Color.White.copy(alpha = .1f), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Color(0x1A0EA5E9)).padding(horizontal = 14.4.dp, vertical = 10.4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Videocam, null, Modifier.size(18.dp), tint = Color(0xFF0EA5E9))
            Column {
                Text(field("meetingTitle") ?: "Meeting", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                field("hostName")?.let { Text("Hosted by $it", color = Color(0xFFAAAAAA), fontSize = 11.5.sp) }
            }
        }
        Text(
            code,
            Modifier.padding(horizontal = 14.4.dp, vertical = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0F0F13)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color(0xFF888888),
            fontSize = 12.5.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Box(Modifier.padding(start = 14.4.dp, end = 14.4.dp, bottom = 10.dp)) {
            if (ended) {
                Text("Meeting ended", color = Color(0xFF888888), fontSize = 12.5.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            } else {
                Text(
                    "Join meeting",
                    Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0EA5E9))
                        .clickable { app.aino.mobile.core.navigation.RouteRequests.open(app.aino.mobile.core.navigation.meetingRoute(code)) }
                        .padding(horizontal = 16.dp, vertical = 6.4.dp),
                    color = Color.White,
                    fontSize = 13.1.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Signal conversation toolbar: back, avatar, name + subtitle, video, voice, overflow. */
@Composable
private fun ThreadHeader(
    conversation: ChatConversation,
    ui: ChatUiState,
    viewModel: ChatViewModel,
    onNavigateBack: (() -> Unit)?,
    withCallPermissions: (Boolean, () -> Unit) -> Unit,
) {
    val signal = signalColors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(signal.background)
            .statusBarsPadding()
            .height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable { viewModel.closeConversation(); onNavigateBack?.invoke() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = signal.text)
        }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable(onClick = viewModel::openInfo).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationAvatar(conversation, ui.presence[conversation.otherUserId], 40.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(conversation.title(), color = signal.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    ui.typingUserId != null -> "typing…"
                    conversation.isGroup -> "${conversation.memberCount ?: 0} members"
                    else -> presenceLabel(ui.presence[conversation.otherUserId])
                }
                if (subtitle.isNotBlank()) Text(subtitle, color = signal.textSecondary, fontSize = 13.sp, maxLines = 1)
            }
        }
        Icon(Icons.Outlined.Videocam, "Video call", Modifier.size(48.dp).clip(CircleShape).clickable { withCallPermissions(true) { viewModel.startCall("video") } }.padding(12.dp), tint = signal.text)
        Icon(Icons.Outlined.Phone, "Voice call", Modifier.size(48.dp).clip(CircleShape).clickable { withCallPermissions(false) { viewModel.startCall("voice") } }.padding(12.dp), tint = signal.text)
        Box {
            Icon(Icons.Outlined.MoreVert, "More options", Modifier.size(48.dp).clip(CircleShape).clickable { menuOpen = true }.padding(12.dp), tint = signal.text)
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = signal.surface) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Search", color = signal.text) }, leadingIcon = { Icon(Icons.Outlined.Search, null, tint = signal.text) }, onClick = { menuOpen = false; viewModel.openThreadSearch() })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Chat settings", color = signal.text) }, leadingIcon = { Icon(Icons.Outlined.Info, null, tint = signal.text) }, onClick = { menuOpen = false; viewModel.openInfo() })
            }
        }
    }
}