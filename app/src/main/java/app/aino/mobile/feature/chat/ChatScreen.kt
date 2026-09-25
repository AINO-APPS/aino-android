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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Mic
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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

    Box(Modifier.fillMaxSize().background(colors.bg)) {
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
                unread = ui.conversations.sumOf {
                    if (it.isMuted || it.isArchived || it.isMeetingChat) 0 else it.unreadCount.coerceAtLeast(0)
                },
                meetingUnread = ui.conversations.filter(ChatConversation::isMeetingChat).sumOf { it.unreadCount.coerceAtLeast(0) },
                meetingsEnabled = meetingsEnabled,
                searchOpen = searchOpen,
                query = ui.userSearch,
                onTab = { tab -> activeTab = tab; if (tab == ChatListTab.Calls) viewModel.loadCalls() },
                onQuery = viewModel::updateUserSearch,
                onSearch = { if (ui.userSearch.trim().length >= 2) viewModel.searchUsers() },
                onSearchOpen = { open ->
                    searchOpen = open
                    if (!open) viewModel.updateUserSearch("")
                },
                onNewGroup = { newGroupOpen = true },
            )
            }
            if (newGroupOpen) NewGroupDialog(ui, viewModel) { newGroupOpen = false }

            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = if (activeTab == ChatListTab.Calls) ui.callsLoading else ui.loading,
                onRefresh = { if (activeTab == ChatListTab.Calls) viewModel.loadCalls() else viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
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
                            HonestEmpty(Icons.Outlined.Videocam, if (query.isNotBlank()) "No matching meeting chats" else "No meeting chats yet")
                        } else items(visibleConversations, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], it.id in ui.selectedConversationIds, viewModel, onOpenConversation) }
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
                        items(pinned, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], it.id in ui.selectedConversationIds, viewModel, onOpenConversation) }
                        if (favourites.isNotEmpty()) item(key = "favourites") { SectionHeader("Favourites", Icons.Outlined.StarOutline, true) }
                        items(favourites, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], it.id in ui.selectedConversationIds, viewModel, onOpenConversation) }
                        if ((pinned.isNotEmpty() || favourites.isNotEmpty()) && others.isNotEmpty()) item(key = "all") {
                            SectionHeader("All messages", Icons.Outlined.ChatBubbleOutline)
                        }
                        items(others, key = { it.id }) { ConversationRow(it, ui.presence[it.otherUserId], it.id in ui.selectedConversationIds, viewModel, onOpenConversation) }
                        val archivedCount = ui.conversations.count { it.isArchived && !it.isMeetingChat }
                        if (archivedCount > 0 && query.isBlank()) item(key = "archived") { ArchivedRow(archivedCount) }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ChatHeader(
    activeTab: ChatListTab, unread: Int, meetingUnread: Int, meetingsEnabled: Boolean, searchOpen: Boolean, query: String,
    onTab: (ChatListTab) -> Unit, onQuery: (String) -> Unit, onSearch: () -> Unit, onSearchOpen: (Boolean) -> Unit,
    onNewGroup: () -> Unit,
) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchOpen) {
                Row(
                    Modifier.weight(1f).height(42.dp).background(colors.bgElevated, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(15.dp), tint = colors.textMuted)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query, onValueChange = onQuery, modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search chats and people...", color = colors.textMuted, fontSize = 14.sp)
                            inner()
                        } },
                    )
                    Icon(
                        Icons.Outlined.Close, "Close search",
                        Modifier.size(20.dp).clickable { if (query.isNotEmpty()) onQuery("") else onSearchOpen(false) }.padding(2.dp),
                        tint = colors.textSecondary,
                    )
                }
            } else {
                Segment("Chat", Icons.Outlined.ChatBubbleOutline, activeTab == ChatListTab.Chat, unread, Modifier.weight(1f)) { onTab(ChatListTab.Chat) }
                if (meetingsEnabled) Segment("Meet", Icons.Outlined.Videocam, activeTab == ChatListTab.Meet, meetingUnread, Modifier.weight(1f)) { onTab(ChatListTab.Meet) }
                Segment("Calls", Icons.Outlined.Phone, activeTab == ChatListTab.Calls, 0, Modifier.weight(1f)) { onTab(ChatListTab.Calls) }
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(colors.bgElevated).clickable { onSearchOpen(true) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Search, "Search people", Modifier.size(17.dp), tint = colors.textSecondary) }
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(colors.bgElevated).clickable(onClick = onNewGroup),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.GroupAdd, "New group", Modifier.size(18.dp), tint = colors.textSecondary) }
            }
        }
    }
}

@Composable
private fun Segment(label: String, icon: ImageVector, active: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(42.dp).clip(shape).background(if (active) colors.bgElevated else Color.Transparent)
            .then(if (active) Modifier.border(BorderStroke(1.dp, colors.primary.copy(alpha = .24f)), shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(14.dp), tint = if (active) colors.primary else colors.textSecondary)
            Text(label, color = if (active) colors.primary else colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (badge > 0) UnreadBadge(badge, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp))
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
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().height(58.dp)
            .background(colors.bgSecondary).border(BorderStroke(0.5.dp, colors.border)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Close, "Cancel selection", Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onCancel).padding(8.dp))
        Text("$selected selected", Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold)
        TextButton(onClick = onSelectAll) { Text(if (allSelected) "Clear all" else "Select all") }
        TextButton(onClick = onDelete, enabled = !deleting) {
            if (deleting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = colors.danger)
            Spacer(Modifier.width(4.dp))
            Text("Delete", color = colors.danger)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(conversation: ChatConversation, presence: ChatPresence?, selected: Boolean, viewModel: ChatViewModel, onOpenConversation: ((Long) -> Unit)?) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().height(66.dp)
            .background(if (selected) colors.primaryGlow else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (viewModel.ui.value.selectedConversationIds.isNotEmpty()) viewModel.toggleConversationSelection(conversation.id)
                    else if (onOpenConversation != null) onOpenConversation(conversation.id)
                    else viewModel.openConversation(conversation)
                },
                onLongClick = { viewModel.toggleConversationSelection(conversation.id) },
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConversationAvatar(conversation, presence)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.isPinned) { Icon(Icons.Outlined.PushPin, null, Modifier.size(12.dp), tint = colors.textMuted); Spacer(Modifier.width(4.dp)) }
                if (conversation.isFavourite) { Icon(Icons.Outlined.StarOutline, null, Modifier.size(12.dp), tint = Color(0xFFCB912F)); Spacer(Modifier.width(4.dp)) }
                Text(conversation.title(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(timeAgo(conversation.lastMessageAt ?: conversation.updatedAt), color = colors.textMuted, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.preview(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.textSecondary, fontSize = 13.sp)
                if (conversation.isMuted) { Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.NotificationsOff, "Muted", Modifier.size(14.dp), tint = colors.textMuted) }
                if (conversation.unreadCount > 0) { Spacer(Modifier.width(8.dp)); Box(Modifier.clickable { viewModel.markRead(conversation) }) { UnreadBadge(conversation.unreadCount) } }
            }
        }
    }
}

@Composable
private fun ChatThread(ui: ChatUiState, viewModel: ChatViewModel, onPickDocument: () -> Unit, onNavigateBack: (() -> Unit)?) {
    val conversation = ui.selectedConversation ?: return
    val colors = LocalWebColors.current
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
    BackHandler(enabled = ui.showInfo) { viewModel.closeInfo() }
    BackHandler(enabled = ui.selectedMessageIds.isNotEmpty()) { viewModel.clearMessageSelection() }
    // Web `handleJumpTo`: scroll to a pinned/saved/search hit and flash it for 2s.
    var highlightedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(ui.jumpToMessageId, newestFirst.size) {
        val target = ui.jumpToMessageId ?: return@LaunchedEffect
        val index = newestFirst.indexOfFirst { it.key == "server-$target" }
        if (index < 0) return@LaunchedEffect
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
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // The thread replaces the whole shell, so it owns the status-bar
            // inset rather than inheriting it from the Scaffold.
            if (ui.selectedMessageIds.isNotEmpty()) MessageSelectionBar(ui, viewModel) else
            Row(
                Modifier.fillMaxWidth().background(colors.bgSecondary)
                    .statusBarsPadding()
                    .height(72.dp)
                    .border(BorderStroke(0.5.dp, colors.border)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { viewModel.closeConversation(); onNavigateBack?.invoke() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                }
                Box(Modifier.size(40.dp).clickable(onClick = viewModel::openInfo)) { ConversationAvatar(conversation, ui.presence[conversation.otherUserId], 40.dp) }
                Column(Modifier.padding(start = 10.dp).weight(1f).clickable(onClick = viewModel::openInfo)) {
                    Text(conversation.title(), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        when {
                            ui.typingUserId != null -> "Typing…"
                            conversation.isGroup -> "${conversation.memberCount ?: 0} members"
                            else -> presenceLabel(ui.presence[conversation.otherUserId])
                        },
                        color = if (ui.typingUserId != null) colors.primary else colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
                Icon(Icons.Outlined.Phone, "Voice call", Modifier.padding(horizontal = 5.dp).size(20.dp).clickable { withCallPermissions(false) { viewModel.startCall("voice") } }, tint = colors.textSecondary)
                Icon(Icons.Outlined.Videocam, "Video call", Modifier.padding(horizontal = 5.dp).size(20.dp).clickable { withCallPermissions(true) { viewModel.startCall("video") } }, tint = colors.textSecondary)
                Icon(Icons.Outlined.Info, "Conversation info", Modifier.padding(horizontal = 8.dp).size(20.dp).clickable(onClick = viewModel::openInfo), tint = colors.textSecondary)
            }
            if (ui.threadFromCache) AinoAlert("Offline · showing cached messages", AlertTone.Warning, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            ui.error?.let { AinoAlert(it, AlertTone.Error, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
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
                            onDelete = { viewModel.deleteMessage(item.message) },
                            onStar = { viewModel.toggleStar(item.message) },
                            onPin = { viewModel.toggleMessagePin(item.message) },
                            onForward = { viewModel.beginForward(item.message) },
                            onCancel = { viewModel.cancelMedia(item.message) },
                            onRetry = { viewModel.retryMedia(item.message) },
                            onVote = { option -> viewModel.votePoll(item.message, option) },
                            onOpenMedia = { viewingMediaId = it.id },
                            selectionActive = ui.selectedMessageIds.isNotEmpty(),
                            selected = item.message.id in ui.selectedMessageIds,
                            highlighted = item.message.id == highlightedId,
                            onSelect = { viewModel.enterMessageSelection(item.message) },
                            onToggleSelect = { viewModel.toggleMessageSelection(item.message.id) },
                        )
                        is ThreadItem.Queued -> QueuedBubble(item.message)
                    }
                    }
                }
                if (ui.loadingOlder) item(key = "loading-older") {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            ) {
                ScrollToBottomButton(unseen) {
                    scope.launch { listState.animateScrollToItem(0) }
                }
            }
            }
            if (ui.typingUserId != null) TypingBubble()
            MessageComposer(
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
                onStageAttachment = viewModel::stageAttachment,
                onUploadVoice = viewModel::uploadVoiceNote,
                onOpenPoll = { pollOpen = true },
            )
        }
    }
    viewingMediaId?.let { id ->
        val media = remember(ui.messages) { ui.messages.filter { it.deletedAt == null && it.isViewableMedia() } }
        ChatMediaViewer(media, id) { viewingMediaId = null }
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

/** Web `Chat.tsx` `messageSelectionBar`: copy, forward (single), pin, save, delete for me / everyone. */
@Composable
private fun MessageSelectionBar(ui: ChatUiState, viewModel: ChatViewModel) {
    val colors = LocalWebColors.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val selected = ui.messages.filter { it.id in ui.selectedMessageIds }
    val allMine = selected.isNotEmpty() && selected.all { it.senderId == ui.currentUserId }
    Row(
        Modifier.fillMaxWidth().background(colors.bgSecondary).statusBarsPadding().height(64.dp)
            .border(BorderStroke(0.5.dp, colors.border)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Close, "Cancel message selection", Modifier.size(38.dp).clip(CircleShape).clickable(onClick = viewModel::clearMessageSelection).padding(8.dp), tint = colors.text)
        Text("${ui.selectedMessageIds.size} selected", Modifier.weight(1f).padding(start = 4.dp), color = colors.text, fontWeight = FontWeight.Bold)
        @Composable
        fun action(icon: ImageVector, label: String, tint: Color = colors.textSecondary, onClick: () -> Unit) =
            Icon(icon, label, Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick).padding(9.dp), tint = tint)
        action(Icons.Outlined.ContentCopy, "Copy selected text") {
            viewModel.selectedText().takeIf(String::isNotEmpty)?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
            viewModel.clearMessageSelection()
        }
        if (selected.size == 1) action(Icons.AutoMirrored.Outlined.Forward, "Forward") { viewModel.beginForward(selected.first()); viewModel.clearMessageSelection() }
        action(Icons.Outlined.PushPin, "Pin or unpin selected", onClick = viewModel::pinSelected)
        action(Icons.Outlined.StarOutline, "Save or unsave selected", onClick = viewModel::starSelected)
        TextButton(onClick = viewModel::deleteSelectedForMe) { Text("For me", color = colors.danger, fontSize = 12.sp) }
        if (allMine) TextButton(onClick = viewModel::deleteSelectedForEveryone) { Text("Everyone", color = colors.danger, fontSize = 12.sp) }
    }
}

@Composable
private fun UnreadDivider(count: Int) {
    val colors = LocalWebColors.current
    Text(
        if (count == 1) "1 unread message" else "$count unread messages",
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(colors.surface, RoundedCornerShape(8.dp)).padding(vertical = 6.dp),
        color = colors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
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
    val colors = LocalWebColors.current
    Box {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(colors.bgElevated)
                .border(1.dp, colors.border, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.KeyboardArrowDown, "Scroll to latest", tint = colors.text) }
        if (unseen > 0) {
            Text(
                if (unseen > 99) "99+" else unseen.toString(),
                Modifier.align(Alignment.TopEnd).background(colors.primary, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                color = colors.onAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
) {
    var actionsOpen by remember { mutableStateOf(false) }
    val colors = LocalWebColors.current
    // Web ChatMessages: `format_type 'meeting'` rows render a MeetingCard, not a bubble.
    val meetingMeta = message.metadata?.takeIf { message.formatType == "meeting" } as? kotlinx.serialization.json.JsonObject
    val meetingCode = (meetingMeta?.get("meetingCode") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    if (meetingMeta != null && meetingCode != null) {
        ChatMeetingCard(meetingMeta, meetingCode)
        return
    }
    val shape = messageBubbleShape(isMine, startsGroup, endsGroup)
    val bubbleContent = colors.text
    val bubbleSecondary = colors.textMuted
    val rowTint by androidx.compose.animation.animateColorAsState(
        when {
            selected -> colors.primary.copy(alpha = .18f)
            highlighted -> colors.warning.copy(alpha = .18f)
            else -> Color.Transparent
        },
        label = "rowTint",
    )
    BoxWithConstraints(Modifier.fillMaxWidth().background(rowTint).padding(top = if (startsGroup) 14.dp else 0.dp)) {
    // Signal/WhatsApp parity: the bubble HUGS its content and only caps at a
    // fraction of the row. The previous `fillMaxWidth(0.82f)` forced even a
    // one-word message into a full-width slab, which is what made the thread
    // look broken.
    val bubbleMax = maxWidth * 0.80f
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        if (!isMine && showSender) Text(
            message.senderName ?: message.senderUsername.orEmpty(),
            color = colors.primaryLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        // Signal-style swipe-to-reply + long-press reaction bar (see
        // ChatMessageGestures.kt / ChatReactionOverlay.kt).
        MessageGestureBox(
            onReply = onReply,
            // In selection mode a tap/long-press toggles selection (web selectionActive).
            onLongPress = { if (selectionActive) onToggleSelect() else if (message.deletedAt == null) actionsOpen = true },
            onClick = { if (selectionActive) onToggleSelect() },
            enabled = message.deletedAt == null,
        ) {
        Column(
            Modifier.widthIn(max = bubbleMax)
                .background(
                    if (isMine) colors.primary.copy(alpha = .14f).compositeOver(colors.surfaceHover) else colors.surface,
                    shape,
                )
                .then(if (isMine) Modifier.border(1.dp, colors.primary.copy(alpha = .22f), shape) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (message.forwardedFromId != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Forward, null, Modifier.size(13.dp), tint = bubbleSecondary)
                    Text("Forwarded", color = bubbleSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            message.replyContent?.let {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = if (isMine) 0.14f else 0.08f))
                        .padding(vertical = 6.dp),
                ) {
                    // Web `ReplyPreview`: a thin primary accent bar carries the
                    // quote instead of a heavy filled box.
                    Box(Modifier.width(3.dp).height(32.dp).background(colors.primary, RoundedCornerShape(2.dp)))
                    Column(Modifier.padding(start = 8.dp, end = 8.dp)) {
                        Text(
                            message.replySenderName.orEmpty(),
                            color = colors.primaryLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(it, color = bubbleSecondary, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
            when (message.formatType) {
                "system" -> {
                    val text = message.metadata?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: message.body()
                    Text(
                        text,
                        color = bubbleSecondary,
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
                "poll" -> {
                    val options = message.metadata?.jsonObject?.get("options")?.jsonArray
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Poll, null, Modifier.size(18.dp), tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
                        Text(message.body(), Modifier.padding(start = 7.dp), color = bubbleContent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    options?.forEachIndexed { index, element ->
                        val label = element.jsonPrimitive.contentOrNull ?: return@forEachIndexed
                        TextButton(onClick = { onVote(index) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
                else -> if (message.fileUrl.isNullOrBlank() || message.deletedAt != null) {
                    Text(message.body(), color = bubbleContent, fontSize = 16.sp, lineHeight = 22.sp)
                }
            }
            if (message.formatType != "poll" && message.deletedAt == null) message.linkPreview?.let { LinkPreviewCard(it) }
            if (!message.fileUrl.isNullOrBlank() && message.deletedAt == null) {
                ChatMediaPreview(
                    message = message,
                    onOpenMedia = onOpenMedia,
                    onCancelProcessing = { onCancel() },
                    onRetryProcessing = { onRetry() },
                )
                // Caption below the media (the filename is already on the card/viewer).
                message.content?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = bubbleContent, fontSize = 16.sp, lineHeight = 22.sp)
                }
            } else if (!message.fileName.isNullOrBlank() && message.deletedAt == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(
                        when {
                            message.fileType?.startsWith("image/") == true -> Icons.Outlined.Image
                            message.fileType?.startsWith("audio/") == true -> Icons.Outlined.AudioFile
                            message.fileType?.startsWith("video/") == true -> Icons.Outlined.Movie
                            else -> Icons.Outlined.Description
                        },
                        null,
                        Modifier.size(18.dp),
                        tint = if (isMine) bubbleContent else app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(message.fileName, color = bubbleContent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        message.fileSize?.let { Text(formatFileSize(it), color = bubbleSecondary, fontSize = 10.sp) }
                    }
                }
                if (!message.mediaState.isNullOrBlank() && message.mediaState !in setOf("ready", "completed")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(message.mediaStage ?: message.mediaState, message.mediaProgress?.let { "$it%" }).joinToString(" · "),
                            Modifier.weight(1f),
                            color = bubbleSecondary,
                            fontSize = 10.sp,
                        )
                        when (message.mediaState) {
                            "failed", "cancelled" -> Icon(Icons.Outlined.Replay, "Retry", Modifier.size(17.dp).clickable(onClick = onRetry), tint = if (isMine) bubbleContent else app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
                            "queued", "processing" -> Icon(Icons.Outlined.Cancel, "Cancel", Modifier.size(17.dp).clickable(onClick = onCancel), tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.danger)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                message.editedAt?.let { Text("edited · ", color = bubbleSecondary, fontSize = 10.sp) }
                Text(timeAgo(message.createdAt), color = bubbleSecondary, fontSize = 10.sp)
                if (isMine) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryTickIcon(
                        tick = deliveryTick(message, receipts, participantCount),
                        readTint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary,
                        mutedTint = bubbleSecondary,
                    )
                }
                if (message.starred) {
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Outlined.Star, "Saved message", Modifier.size(13.dp), tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
                }
                if (message.pinnedAt != null) {
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Outlined.PushPin, "Pinned message", Modifier.size(13.dp), tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
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
                actions = ReactionActions(
                    onSelect = onSelect,
                    onReply = onReply,
                    onForward = onForward,
                    onEdit = if (isMine && message.fileUrl.isNullOrBlank() && !isPoll) onEdit else null,
                    onStar = onStar,
                    onPin = onPin,
                    onDelete = if (isMine) onDelete else null,
                    onCopy = message.content?.takeIf(String::isNotBlank)?.let { text ->
                        { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }
                    },
                    pinned = message.pinnedAt != null,
                    starred = message.starred,
                ),
            )
        }
        // Reaction chips render ONLY when someone actually reacted (legacy +
        // web parity). The previous always-visible "React" pill put a control
        // under every single message, which is what made the thread look
        // cluttered and unfinished. Tapping a chip toggles that reaction, and
        // the long-press overlay exposes the full action list.
        if (message.reactions.isNotEmpty()) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                message.reactions.groupingBy { it.emoji }.eachCount().forEach { (emoji, count) ->
                    Text(
                        "$emoji $count",
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.surfaceHover)
                            .border(1.dp, colors.glassBorder, CircleShape)
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
    }
}

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
    val colors = LocalWebColors.current
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            Modifier.background(colors.bgElevated, CircleShape)
                .border(1.dp, colors.glassBorder, CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Rounded on the outside of a group and tight toward consecutive bubbles. */
private fun messageBubbleShape(isMine: Boolean, startsGroup: Boolean, endsGroup: Boolean): RoundedCornerShape {
    val large = 16.dp
    val tight = 4.dp
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
internal enum class DeliveryTick { Sent, Delivered, Read }

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
    when (tick) {
        DeliveryTick.Sent -> Icon(Icons.Outlined.Done, "Sent", Modifier.size(14.dp), tint = mutedTint)
        DeliveryTick.Delivered -> Icon(Icons.Outlined.DoneAll, "Delivered", Modifier.size(14.dp), tint = mutedTint)
        DeliveryTick.Read -> Icon(Icons.Outlined.DoneAll, "Read", Modifier.size(14.dp), tint = readTint)
    }
}

private fun parseChatInstant(value: String): Instant? = runCatching {
    Instant.parse(value.replace(" ", "T").let {
        if (it.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(it)) it else "${it}Z"
    })
}.getOrNull()

@Composable
private fun QueuedBubble(message: QueuedMessage) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .background(colors.primary.copy(alpha = .14f), RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .border(1.dp, colors.primary.copy(alpha = .16f), RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(message.content, color = colors.text, fontSize = 16.sp, lineHeight = 22.sp)
            Text("Sending…", Modifier.align(Alignment.End), color = colors.textMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun TypingBubble() {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(28.dp).background(colors.primary, CircleShape))
        Row(
            Modifier.background(colors.surface, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(3) { Box(Modifier.size(6.dp).background(colors.textMuted, CircleShape)) }
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
    onStageAttachment: (Uri) -> Unit,
    onUploadVoice: (Uri) -> Unit,
    onOpenPoll: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalWebColors.current
    val player = app.aino.mobile.core.AppContainer.get(context).audio
    var emojiOpen by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        if (captured) cameraUri?.let(onStageAttachment)
        cameraUri = null
    }
    val launchCamera = {
        val directory = File(context.cacheDir, "chat-media").apply { mkdirs() }
        val file = File.createTempFile("camera-", ".jpg", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
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
        Modifier.fillMaxWidth().background(colors.bg)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        // Determinate once bytes start flowing (OkHttp ProgressRequestBody).
        if (uploading) {
            if (uploadProgress != null && uploadProgress > 0f) {
                androidx.compose.material3.LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.primary)
            } else {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.primary)
            }
        }
        voiceHint?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = colors.textSecondary, fontSize = 12.sp)
        }
        val suggestions = remember(value, members) {
            activeMentionQuery(value)?.let { mentionSuggestions(members, it, currentUserId) }.orEmpty()
        }
        if (suggestions.isNotEmpty() && phase == VoicePhase.Idle) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(colors.bgElevated, RoundedCornerShape(10.dp)).border(1.dp, colors.border, RoundedCornerShape(10.dp))) {
                suggestions.forEach { member ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onMention(member) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(member.display(), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        member.username?.let { Text("@$it", color = colors.textMuted, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (editingMessage != null) {
            Row(
                Modifier.fillMaxWidth().background(colors.bgSecondary)
                    .border(BorderStroke(0.5.dp, colors.border))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = colors.primary)
                Text(
                    "Editing message",
                    Modifier.padding(start = 8.dp).weight(1f),
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(Icons.Outlined.Close, "Cancel edit", Modifier.size(20.dp).clickable(onClick = onCancelEdit), tint = colors.textSecondary)
            }
        }
        if (replyingTo != null) {
            Row(
                Modifier.fillMaxWidth().background(colors.bgSecondary)
                    .border(BorderStroke(0.5.dp, colors.border))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(30.dp).background(colors.primary, RoundedCornerShape(2.dp)))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(
                        "Replying to ${replyingTo.senderName ?: replyingTo.senderUsername.orEmpty()}",
                        color = colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(replyingTo.body(), color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.Close, "Cancel reply", Modifier.size(20.dp).clickable(onClick = onCancelReply), tint = colors.textSecondary)
            }
        }
        if (linkPreview != null && phase == VoicePhase.Idle && editingMessage == null) {
            LinkPreviewCard(linkPreview, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), onRemove = onDismissLinkPreview)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Web `ChatInputBar`: pill = emoji · input · camera · mic; the
            // right-outside slot is Send while typing, otherwise "+" (Poll/Attach).
            Row(
                Modifier.weight(1f).heightIn(min = 46.dp, max = 124.dp).background(colors.inputBg, CircleShape)
                    .border(1.dp, colors.inputBorder, CircleShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (phase) {
                    VoicePhase.Idle -> {
                        Box(Modifier.size(width = 42.dp, height = 46.dp).clickable { emojiOpen = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.EmojiEmotions, "Show emoji", Modifier.size(21.dp), tint = colors.textSecondary)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = { onChange(it.take(5000)) },
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 11.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 16.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSend() }),
                            decorationBox = { inner ->
                                if (value.isEmpty()) Text(if (editingMessage == null) "Type a message..." else "Edit message...", color = colors.textMuted, fontSize = 16.sp)
                                inner()
                            },
                        )
                        if (canRecord) {
                            Box(Modifier.size(width = 38.dp, height = 46.dp).clickable(enabled = !uploading) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                                else cameraPermission.launch(Manifest.permission.CAMERA)
                            }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CameraAlt, "Open camera", Modifier.size(20.dp), tint = colors.textSecondary) }
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
            Box {
                Box(
                    Modifier.size(46.dp).background(if (sendMode) colors.primary else colors.surface, CircleShape)
                        .border(1.dp, if (sendMode) colors.primary else colors.glassBorder, CircleShape)
                        .clickable(enabled = !uploading && phase != VoicePhase.Holding) {
                            when {
                                recordingActive -> dispatch(VoiceEvent.Send)
                                sendMode -> onSend()
                                else -> plusOpen = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (sendMode) Icons.AutoMirrored.Outlined.Send else Icons.Outlined.Add,
                        if (sendMode) "Send" else "More options",
                        Modifier.size(21.dp),
                        tint = if (sendMode) colors.onAccent else colors.textSecondary,
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = plusOpen, onDismissRequest = { plusOpen = false }) {
                    if (isGroup) androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Poll") },
                        leadingIcon = { Icon(Icons.Outlined.Poll, null) },
                        onClick = { plusOpen = false; onOpenPoll() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Attach file") },
                        leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                        onClick = { plusOpen = false; onPickDocument() },
                    )
                }
            }
        }
    }
    if (emojiOpen) {
        Dialog(onDismissRequest = { emojiOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            EmojiPicker(onEmojiSelected = { onChange(value + it) }, onDismiss = { emojiOpen = false })
        }
    }
}

@Composable
private fun ConversationAvatar(conversation: ChatConversation, presence: ChatPresence?, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val colors = LocalWebColors.current
    Box(Modifier.size(size)) {
        when {
            conversation.isMeetingChat -> Box(Modifier.fillMaxSize().background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Videocam, null, Modifier.size(size * .46f), tint = Color.White)
            }
            conversation.isGroup && conversation.groupAvatar.isNullOrBlank() -> Box(Modifier.fillMaxSize().background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, null, Modifier.size(size * .48f), tint = Color.White)
            }
            else -> app.aino.mobile.core.designsystem.component.UserAvatar(conversation.title(), conversation.avatar(), size)
        }
        if (!conversation.isGroup && !conversation.isMeetingChat) Box(
            Modifier.align(Alignment.BottomEnd).size(size * .32f).background(colors.bg, CircleShape).padding(2.dp).background(statusColor(presence), CircleShape),
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
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    Box(
        modifier.height(20.dp).background(colors.primary, CircleShape)
            .border(1.dp, colors.primaryLight, CircleShape).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (count > 99) "99+" else count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SectionHeader(label: String, icon: ImageVector, warning: Boolean = false) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = if (warning) colors.warning else colors.textMuted)
        Text(label.uppercase(Locale.getDefault()), color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
    }
}

@Composable
private fun UserResultRow(user: ChatUser, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val name = user.display().ifBlank { "Unknown user" }
        app.aino.mobile.core.designsystem.component.UserAvatar(name, user.avatar, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(name, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            user.email?.takeIf(String::isNotBlank)?.let { Text(it, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary, fontSize = 13.sp, maxLines = 1) }
        }
        Text("Message", color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchHint(text: String, progress: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (progress) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun ArchivedRow(count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).background(app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp), tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary)
        }
        Column {
            Text("Archived", color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("$count chat${if (count == 1) "" else "s"} · viewing unavailable", color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary, fontSize = 13.sp)
        }
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
