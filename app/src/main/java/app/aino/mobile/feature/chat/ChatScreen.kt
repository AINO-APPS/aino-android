@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
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
import app.aino.mobile.core.designsystem.icons.HeroIcons

internal enum class ChatListTab { Chat, Meet, Calls }

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onPickDocument: () -> Unit,
    conversationId: Long? = null,
    meetingsEnabled: Boolean = false,
    onOpenConversation: ((Long) -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    onNewGroup: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    var activeTab by remember { mutableStateOf(ChatListTab.Chat) }
    var searchOpen by remember { mutableStateOf(false) }
    var archivedOpen by remember { mutableStateOf(false) }
    val query = ui.userSearch.trim()
    // Deep links and notification taps open by id at once (placeholder header, stored rows)
    // instead of waiting for the conversation list.
    LaunchedEffect(conversationId) {
        if (conversationId != null && viewModel.ui.value.selectedConversation?.id != conversationId) {
            viewModel.openConversationById(conversationId)
        }
    }
    BackHandler(enabled = ui.selectingConversations) { viewModel.cancelConversationSelection() }
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
    BackHandler(enabled = ui.selectingCalls) { viewModel.cancelCallSelection() }
    // A group invite link (aino://chat/join/<token> or the web link) previews the group over any chat screen.
    ui.invite?.let { JoinGroupSheet(it, viewModel) }
    val visibleConversations = remember(ui.conversations, activeTab, query, archivedOpen) {
        ui.conversations.filter { conversation ->
            val inTab = when (activeTab) {
                ChatListTab.Chat -> !conversation.isMeetingChat && conversation.isArchived == archivedOpen
                ChatListTab.Meet -> meetingsEnabled && conversation.isMeetingChat && !conversation.isArchived
                ChatListTab.Calls -> false
            }
            inTab && (query.isBlank() || listOf(
                conversation.title(), conversation.otherUsername.orEmpty(),
                conversation.lastMessage.orEmpty(), conversation.groupName.orEmpty(),
            ).joinToString(" ").contains(query, ignoreCase = true))
        }
    }
    if (conversationId != null) {
        // Thread route: while transitions overlap, the VM may already hold another
        // thread (or none). Keep painting this one instead of flashing the list.
        val retained = remember(conversationId) { RetainedThread() }
        if (ui.selectedConversation?.id == conversationId) retained.ui = ui
        retained.ui?.let { ChatThread(it, viewModel, onPickDocument, onNavigateBack) }
            ?: Box(Modifier.fillMaxSize().background(signalColors.background))
        return
    }
    if (ui.selectedConversation != null && onOpenConversation == null) {
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
    BackHandler(enabled = archivedOpen && !searchOpen && !ui.selectingConversations) { archivedOpen = false }
    val openConversation: (ChatConversation) -> Unit = { conversation ->
        if (onOpenConversation != null) onOpenConversation(conversation.id) else viewModel.openConversation(conversation)
    }

    val listState = rememberLazyListState()
    val listScrolled by remember {
        androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val tabs = buildList {
        add(
            ChatListTabItem(
                ChatListTab.Chat, "Chats", HeroIcons.ChatBubbleOvalLeft,
                ui.conversations.sumOf { if (it.isMuted || it.isArchived || it.isMeetingChat) 0 else it.unreadCount.coerceAtLeast(0) },
            ),
        )
        if (meetingsEnabled) add(
            ChatListTabItem(
                ChatListTab.Meet, "Meet", HeroIcons.VideoCamera,
                ui.conversations.filter(ChatConversation::isMeetingChat).sumOf { it.unreadCount.coerceAtLeast(0) },
            ),
        )
        add(ChatListTabItem(ChatListTab.Calls, "Calls", HeroIcons.Phone, 0))
    }
    val selecting = ui.selectingConversations
    val rowHaptics = app.aino.mobile.core.designsystem.rememberAinoHaptics()

    Box(Modifier.fillMaxSize().background(signal.background)) {
        // The shell consumes the status bar while its scaffold pads the list; when a pushed
        // full-screen route drops that padding mid-transition, this keeps the toolbar below it.
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (ui.selectingCalls) {
                SelectionHeader(
                    selected = ui.selectedCallIds.size,
                    allSelected = ui.calls.isNotEmpty() && ui.calls.all { it.id in ui.selectedCallIds },
                    deleting = ui.deletingCalls,
                    onCancel = viewModel::cancelCallSelection,
                    onSelectAll = viewModel::selectAllCalls,
                    onDelete = viewModel::deleteSelectedCalls,
                )
            } else if (ui.selectingConversations) {
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
                    tabs = if (archivedOpen) emptyList() else tabs,
                    activeTab = activeTab,
                    onTab = { tab -> activeTab = tab; if (tab == ChatListTab.Calls) viewModel.loadCalls() },
                    elevated = listScrolled,
                    searchOpen = searchOpen,
                    query = ui.userSearch,
                    onQuery = viewModel::updateUserSearch,
                    onSearchOpen = { open ->
                        searchOpen = open
                        if (!open) viewModel.updateUserSearch("")
                    },
                    onNewGroup = onNewGroup,
                    menu = when (activeTab) {
                        ChatListTab.Chat -> buildList {
                            if (!archivedOpen) {
                                add(ChatMenuItem("New chat", HeroIcons.PencilSquare) { searchOpen = true })
                                add(ChatMenuItem("New group", HeroIcons.UserGroup, onClick = onNewGroup))
                                add(null)
                            }
                            add(ChatMenuItem("Mark all as read", receipt = true, enabled = visibleConversations.any { it.unreadCount > 0 }) { viewModel.markAllRead(visibleConversations) })
                            add(ChatMenuItem("Select chats", HeroIcons.CheckCircle, enabled = visibleConversations.isNotEmpty(), onClick = viewModel::startConversationSelection))
                            val archivedCount = ui.conversations.count { it.isArchived && !it.isMeetingChat }
                            if (archivedOpen) add(ChatMenuItem("Back to chats", HeroIcons.ChatBubbleOvalLeft) { archivedOpen = false })
                            else add(ChatMenuItem("Archived chats", HeroIcons.ArchiveBox, count = archivedCount) { archivedOpen = true })
                            add(null)
                            add(ChatMenuItem("Refresh", HeroIcons.ArrowPath, onClick = viewModel::refresh))
                        }
                        ChatListTab.Meet -> listOf(
                            ChatMenuItem("Mark all as read", receipt = true, enabled = visibleConversations.any { it.unreadCount > 0 }) { viewModel.markAllRead(visibleConversations) },
                            ChatMenuItem("Select chats", HeroIcons.CheckCircle, enabled = visibleConversations.isNotEmpty(), onClick = viewModel::startConversationSelection),
                            null,
                            ChatMenuItem("Refresh", HeroIcons.ArrowPath, onClick = viewModel::refresh),
                        )
                        ChatListTab.Calls -> listOf(
                            ChatMenuItem("Select calls", HeroIcons.CheckCircle, enabled = ui.calls.isNotEmpty(), onClick = viewModel::startCallSelection),
                            null,
                            ChatMenuItem("Refresh", HeroIcons.ArrowPath, onClick = viewModel::loadCalls),
                        )
                    },
                )
            }

            app.aino.mobile.core.designsystem.component.AinoPullToRefreshBox(
                loading = if (activeTab == ChatListTab.Calls) ui.callsLoading else ui.loading,
                onRefresh = { if (activeTab == ChatListTab.Calls) viewModel.loadCalls() else viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                if (searchOpen && !searching) item(key = "new-group") {
                    // Signal's compose screen leads with "New group".
                    Row(
                        Modifier.fillMaxWidth().clickable { rowHaptics.tap(); onNewGroup() }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(SignalDimens.listAvatar).background(signal.primary.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(HeroIcons.UserGroup, null, Modifier.size(24.dp), tint = signal.primary)
                        }
                        Text("New group", Modifier.padding(start = 16.dp), color = signal.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
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
                            ConversationRow(it, ui.presence[it.otherUserId], false, viewModel, onOpenConversation, highlight = query, currentUserId = ui.currentUserId)
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
                            ui.callsLoading && ui.calls.isEmpty() -> item(key = "calls-loading") { app.aino.mobile.core.designsystem.component.FirstLoadSpinner(Modifier.height(160.dp)) }
                            ui.calls.isEmpty() -> item(key = "calls-empty") {
                                ChatListEmpty(HeroIcons.Phone, "No calls yet", "Voice and video calls you make or receive show up here.")
                            }
                            else -> items(ui.calls, key = { "call-${it.id}" }) { call ->
                                CallRow(
                                    call = call,
                                    conversation = ui.conversations.firstOrNull { it.id == call.conversationId },
                                    currentUserId = ui.currentUserId,
                                    selected = call.id in ui.selectedCallIds,
                                    selectionEnabled = true,
                                    selectionMode = ui.selectingCalls,
                                    onToggleSelection = { viewModel.toggleCallSelection(call.id) },
                                )
                            }
                        }
                    }
                    ChatListTab.Meet -> {
                        if (visibleConversations.isEmpty()) item(key = "meet-empty") {
                            ChatListEmpty(HeroIcons.VideoCamera, "No meeting chats yet", "Chats from meetings you join appear here.")
                        } else items(visibleConversations, key = { it.id }) {
                            ConversationRow(it, ui.presence[it.otherUserId], it.id in ui.selectedConversationIds, viewModel, onOpenConversation, currentUserId = ui.currentUserId)
                        }
                    }
                    ChatListTab.Chat -> {
                        // Signal: pinned chats float to the top, then everything by recency (no section chrome).
                        val ordered = visibleConversations.filter(ChatConversation::isPinned) +
                            visibleConversations.filter { it.isFavourite && !it.isPinned } +
                            visibleConversations.filter { !it.isPinned && !it.isFavourite }
                        if (archivedOpen) item(key = "archived-header") { ArchivedHeader { archivedOpen = false } }
                        if (visibleConversations.isEmpty()) item(key = "chat-empty") {
                            if (ui.loading) app.aino.mobile.core.designsystem.component.FirstLoadSpinner(Modifier.height(160.dp))
                            else if (archivedOpen) ChatListEmpty(HeroIcons.ArchiveBox, "No archived chats", "Swipe a chat left to archive it.")
                            else ChatListEmpty(
                                HeroIcons.ChatBubbleOvalLeft, "No conversations yet", "Start a chat with a colleague or create a group.",
                                actionLabel = "Start a chat", onAction = { searchOpen = true },
                            )
                        }
                        items(ordered, key = { it.id }) { conversation ->
                            SwipeableConversation(
                                conversation = conversation,
                                enabled = !selecting,
                                onPin = { viewModel.togglePin(conversation) },
                                onArchive = { viewModel.toggleArchive(conversation, undoable = true) },
                            ) {
                                ConversationRow(conversation, ui.presence[conversation.otherUserId], conversation.id in ui.selectedConversationIds, viewModel, onOpenConversation, currentUserId = ui.currentUserId)
                            }
                        }
                        val archivedCount = ui.conversations.count { it.isArchived && !it.isMeetingChat }
                        if (archivedCount > 0 && !archivedOpen) item(key = "archived") { ArchivedChatsRow(archivedCount) { archivedOpen = true } }
                    }
                }
            }
            }
        }
        // Signal compose FAB (new chat → people search, with "New group" on top).
        NewChatFab(
            visible = !searchOpen && activeTab != ChatListTab.Calls && !selecting,
            onClick = { searchOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
        // Signal "Chat archived" snackbar with Undo.
        ui.archiveUndo?.let { archived ->
            LaunchedEffect(archived.id) { kotlinx.coroutines.delay(4_000); viewModel.dismissArchiveUndo() }
            androidx.compose.material3.Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
                action = { TextButton(onClick = viewModel::undoArchive) { Text("Undo", color = signal.primary) } },
            ) { Text("Chat archived") }
        }
    }
}

/** List toolbar: borderless Chats/Meet/Calls tabs + search + overflow in one row; search swaps in a field. */
@Composable
private fun ChatHeader(
    tabs: List<ChatListTabItem>, activeTab: ChatListTab, onTab: (ChatListTab) -> Unit,
    elevated: Boolean, searchOpen: Boolean, query: String,
    onQuery: (String) -> Unit, onSearchOpen: (Boolean) -> Unit, onNewGroup: () -> Unit,
    menu: List<ChatMenuItem?> = listOf(ChatMenuItem("New group", HeroIcons.UserGroup, onClick = onNewGroup)),
) {
    val signal = signalColors
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = app.aino.mobile.core.designsystem.rememberAinoHaptics()
    val divider by androidx.compose.animation.animateColorAsState(if (elevated) signal.divider else Color.Transparent, label = "toolbarDivider")
    Column {
    Row(
        Modifier.fillMaxWidth().height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchOpen) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Icon(HeroIcons.ArrowLeft, "Close search", Modifier.size(48.dp).clip(CircleShape).clickable { onSearchOpen(false) }.padding(12.dp), tint = signal.text)
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(signal.searchPill).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HeroIcons.MagnifyingGlass, null, Modifier.padding(end = 8.dp).size(18.dp), tint = signal.textSecondary)
                BasicTextField(
                    value = query, onValueChange = onQuery, modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = signal.text, fontSize = 17.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(signal.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search people, chats and messages", color = signal.textSecondary, fontSize = 16.sp, maxLines = 1)
                        inner()
                    } },
                )
                if (query.isNotEmpty()) Icon(HeroIcons.XMark, "Clear search", Modifier.size(24.dp).clip(CircleShape).clickable { onQuery("") }, tint = signal.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (tabs.size > 1) ChatListTabs(tabs, activeTab, onTab)
            }
            Icon(HeroIcons.MagnifyingGlass, "Search", Modifier.size(44.dp).clip(CircleShape).clickable { onSearchOpen(true) }.padding(11.dp), tint = signal.text)
            Box {
                val menuBg by androidx.compose.animation.animateColorAsState(if (menuOpen) signal.searchPill else Color.Transparent, label = "menuButton")
                Icon(
                    HeroIcons.EllipsisVertical, "More options",
                    Modifier.size(44.dp).clip(CircleShape).background(menuBg).clickable { menuOpen = true }.padding(11.dp),
                    tint = signal.text,
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = signal.surface,
                    modifier = Modifier.widthIn(min = 220.dp),
                ) {
                    menu.forEach { item ->
                        if (item == null) {
                            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(signal.divider))
                            return@forEach
                        }
                        val tint = if (item.enabled) signal.text else signal.textSecondary.copy(alpha = .5f)
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(item.label, color = tint, fontSize = 15.sp) },
                            leadingIcon = {
                                if (item.receipt) SignalReceiptIcon(DeliveryTick.Read, tint, Modifier.padding(end = 2.dp), punchThrough = signal.surface)
                                else item.icon?.let { Icon(it, null, Modifier.size(22.dp), tint = tint) }
                            },
                            trailingIcon = if (item.count > 0) { { Text("${item.count}", color = signal.textSecondary, fontSize = 13.sp) } } else null,
                            enabled = item.enabled,
                            onClick = { haptics.tap(); menuOpen = false; item.onClick() },
                        )
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
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
        Icon(HeroIcons.XMark, "Cancel selection", Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onCancel).padding(12.dp), tint = signal.text)
        Text("$selected", Modifier.weight(1f).padding(start = 8.dp), color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        TextButton(onClick = onSelectAll) { Text(if (allSelected) "Clear all" else "Select all", color = signal.primary) }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !deleting, onClick = onDelete), contentAlignment = Alignment.Center) {
            if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = signal.danger)
            else Icon(HeroIcons.Trash, "Delete", tint = signal.danger)
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
    currentUserId: Long? = null,
) {
    val signal = signalColors
    val unread = conversation.unreadCount > 0
    var menuOpen by remember { mutableStateOf(false) }
    var muteOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    val open = {
        if (onOpenConversation != null) onOpenConversation(conversation.id) else viewModel.openConversation(conversation)
    }
    val haptics = app.aino.mobile.core.designsystem.rememberAinoHaptics()
    val highlightBg by androidx.compose.animation.animateColorAsState(
        if (selected || menuOpen) signal.primary.copy(alpha = .14f) else Color.Transparent, label = "rowHighlight",
    )
    // Opaque base so swipe actions only show where the row has slid away.
    Box(Modifier.background(signal.background)) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(highlightBg)
            .combinedClickable(
                onClick = {
                    if (viewModel.ui.value.selectingConversations) { haptics.toggle(); viewModel.toggleConversationSelection(conversation.id) }
                    else open()
                },
                // Signal: long-press opens the context menu; while selecting it keeps toggling.
                onLongClick = {
                    haptics.longPress()
                    if (viewModel.ui.value.selectingConversations) viewModel.toggleConversationSelection(conversation.id)
                    else menuOpen = true
                },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clickable(onClickLabel = "Open contact details") {
                if (viewModel.ui.value.selectingConversations) viewModel.toggleConversationSelection(conversation.id)
                else sheetOpen = true
            },
        ) {
            ConversationAvatar(conversation, presence, 52.dp)
            androidx.compose.animation.AnimatedVisibility(
                selected,
                Modifier.align(Alignment.BottomEnd),
                enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
            ) {
                Box(
                    Modifier.size(22.dp).background(signal.primary, CircleShape).border(2.dp, signal.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(HeroIcons.Check, null, Modifier.size(12.dp), tint = Color.White) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlightTerm(conversation.title(), highlight, signal.highlight),
                    Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = signal.text, fontSize = 16.sp, fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                )
                if (conversation.isMuted) Icon(HeroIcons.BellSlash, "Muted", Modifier.padding(start = 4.dp).size(14.dp), tint = signal.textSecondary)
                if (conversation.isPinned) Icon(HeroIcons.PushPin, "Pinned", Modifier.padding(start = 4.dp).size(14.dp), tint = signal.textSecondary)
                if (conversation.isFavourite) Icon(HeroIcons.Star, "Favourite", Modifier.padding(start = 4.dp).size(14.dp), tint = Color(0xFFCB912F))
            }
            ConversationSnippet(
                conversation, currentUserId, unread,
                textColor = signal.text, secondaryColor = signal.textSecondary,
                background = signal.background,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        // Right rail: time on top, unread badge or own-message receipt underneath, flush to the end edge.
        Column(
            Modifier.padding(start = 10.dp).widthIn(min = 40.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                listTime(conversation.lastMessageAt ?: conversation.updatedAt),
                color = if (unread) signal.primary else signal.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            )
            Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterEnd) {
                if (unread) {
                    Box(Modifier.clip(CircleShape).clickable { haptics.tap(); viewModel.markRead(conversation) }) {
                        SignalUnreadBadge(conversation.unreadCount, muted = conversation.isMuted)
                    }
                } else if (conversation.lastIsMine(currentUserId) && conversation.lastDeleted == null) {
                    SignalReceiptIcon(
                        listDeliveryTick(conversation), signal.textSecondary,
                        punchThrough = highlightBg.compositeOver(signal.background),
                    )
                }
            }
        }
    }
    ConversationContextMenu(
        expanded = menuOpen,
        conversation = conversation,
        viewModel = viewModel,
        onDismiss = { menuOpen = false },
        onChooseMute = { muteOpen = true },
        onConfirmDelete = { confirmDelete = true },
    )
    MuteDurationMenu(muteOpen, { muteOpen = false }) { viewModel.muteFor(conversation, it) }
    }
    if (confirmDelete) DeleteChatDialog(onConfirm = { viewModel.deleteConversation(conversation) }) { confirmDelete = false }
    if (sheetOpen) RecipientSheet(conversation, presence, viewModel, onOpen = open) { sheetOpen = false }
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
private class RetainedThread { var ui: ChatUiState? = null }

/**
 * Reversed thread: [index] first lands on the bottom edge, then the list moves
 * toward the newest rows until that row (the first unread) sits at the top.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToUnreadTop(index: Int) {
    scrollToItem(index)
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val lift = info.viewportSize.height - item.size - info.beforeContentPadding - info.afterContentPadding
    if (lift > 0) scrollBy(-lift.toFloat())
}

private enum class ThreadBar { Header, Search, Selection }

private fun threadItemsOf(ui: ChatUiState, pending: List<PendingMedia>): List<ThreadItem> = buildThreadItems(
    ui.messages.filterNot { it.id in ui.hiddenMessageIds },
    ui.queuedMessages.filter { it.conversationId == ui.selectedConversation?.id }, ui.currentUserId,
    uploads = pending.map { OutgoingMediaItem(it.localId, it.createdAtEpochMs, it.sequence) },
)

/** Signal's item animator: a live message rises a few dp into place as it fades in. */
@Composable
private fun Modifier.arrivalSlide(): Modifier {
    val rise = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) {
        rise.animateTo(0f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    val distance = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    return graphicsLayer { translationY = rise.value * distance }
}

@Composable
private fun ThreadLoadWarning(text: String, retryLabel: String, loading: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AinoAlert(text, AlertTone.Warning, Modifier.weight(1f))
        TextButton(onClick = onRetry, enabled = !loading) { Text(retryLabel) }
    }
}

@Composable
private fun ChatThread(ui: ChatUiState, viewModel: ChatViewModel, onPickDocument: () -> Unit, onNavigateBack: (() -> Unit)?) {
    val conversation = ui.selectedConversation ?: return
    val signal = signalColors
    val context = LocalContext.current
    // Signal ConversationFragment onResume/onPause → MessageNotifier visible thread.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(conversation.id, lifecycleOwner) {
        val id = conversation.id
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.onThreadVisible(id)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> app.aino.mobile.core.push.VisibleThread.clear(id)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.aino.mobile.core.push.VisibleThread.clear(id)
        }
    }
    val threadPending =  remember(ui.pendingMedia, conversation.id) { ui.pendingMedia.filter { it.conversationId == conversation.id } }
    // Upload progress changes the bubble only; the item list is rebuilt when the set/order of items changes.
    val pendingLayout = remember(threadPending) { threadPending.map { Triple(it.localId, it.sequence, it.createdAtEpochMs) } }
    // The first frame builds synchronously so a cached thread paints at once; later rebuilds run off the main thread.
    var threadItems by remember(conversation.id) { mutableStateOf(threadItemsOf(ui, threadPending)) }
    // Whether [threadItems] already reflect the server page, not only stored rows (second open positioning).
    var itemsSettled by remember(conversation.id) { mutableStateOf(!ui.threadLoading) }
    LaunchedEffect(ui.messages, ui.queuedMessages, ui.currentUserId, ui.hiddenMessageIds, pendingLayout, ui.threadLoading) {
        val current = threadItems
        val source = ui
        val pending = threadPending
        withContext(Dispatchers.Default) { threadItemsOf(source, pending).takeIf { it != current } }?.let { threadItems = it }
        itemsSettled = !source.threadLoading
    }
    val pendingById = remember(threadPending) { threadPending.associateBy(PendingMedia::localId) }
    val latestUi by androidx.compose.runtime.rememberUpdatedState(ui)
    val reactionAvatars = remember { { reactionAvatarLookup(latestUi.selectedConversation, latestUi.members, latestUi.messages) } }
    var allMediaOpen by remember(conversation.id) { mutableStateOf(false) }
    var pinnedIndex by remember(conversation.id) { mutableStateOf(0) }
    // Signal-style bottom-anchored thread: `reverseLayout` keeps the newest
    // message pinned above the composer when the keyboard shrinks the viewport.
    val newestFirst = remember(threadItems) { threadItems.asReversed() }
    val arrivals = remember(conversation.id) { ThreadArrivals() }
    val arrivedKeys = remember(arrivals, newestFirst) { arrivals.update(newestFirst.map(ThreadItem::key)) }
    val slidKeys = remember(conversation.id) { HashSet<String>() }
    // Follows the rows until the server page lands, then stays put (Signal's "N unread messages").
    var dividerKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val atBottom by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    // Stick-to-bottom as of the last scroll: rows inserted at the newest end shift the
    // index without scrolling, so they never flip it before the follow decision below.
    var stickToBottom by remember(conversation.id) { mutableStateOf(true) }
    var userScrolled by remember(conversation.id) { mutableStateOf(false) }
    val stickSlopPx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(listState, conversation.id) {
        var wasScrolling = false
        androidx.compose.runtime.snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (scrolling, index, offset) ->
            if (scrolling || wasScrolling) stickToBottom = isAtThreadBottom(index, offset, stickSlopPx)
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(listState, conversation.id) {
        listState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) userScrolled = true
        }
    }
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
        newestFirst.firstOrNull() is ThreadItem.Queued || newestFirst.firstOrNull() is ThreadItem.PendingUpload
    // 0: not positioned, 1: on stored rows, 2: on the server page (see [initialThreadScroll]).
    var positioned by remember(conversation.id) { mutableStateOf(0) }
    var positionedUnread by remember(conversation.id) { mutableStateOf(-1) }
    var lastNewestKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(newestKey) {
        val previous = lastNewestKey
        lastNewestKey = newestKey
        // The open sequence below positions the thread until the server page has landed.
        if (newestKey == null || previous == null || positioned < 2) return@LaunchedEffect
        val added = addedAtNewest(newestFirst.map(ThreadItem::key), previous)
        when (val action = scrollOnNewItems(stickToBottom, newestMine, added)) {
            is ThreadScroll.ToNewest -> {
                if (action.animate) listState.animateScrollToItem(0) else listState.scrollToItem(0)
                stickToBottom = true
                unseen = 0
            }
            else -> unseen += added
        }
    }
    LaunchedEffect(conversation.id, newestFirst, itemsSettled, ui.unreadAtOpen) {
        if (newestFirst.isEmpty()) return@LaunchedEffect
        val phase = if (itemsSettled) 2 else 1
        val unreadAtOpen = latestUi.unreadAtOpen
        if (phase <= positioned && unreadAtOpen == positionedUnread) return@LaunchedEffect
        positioned = maxOf(positioned, phase)
        positionedUnread = unreadAtOpen
        dividerKey = unreadDividerKey(newestFirst, unreadAtOpen, latestUi.currentUserId)
        val target = initialThreadScroll(
            newestFirst.map(ThreadItem::key), dividerKey,
            jumpPending = latestUi.jumpToMessageId != null, userScrolled = userScrolled,
        )
        // Not tied to this effect: a later row change must not cancel the positioning half way.
        scope.launch {
            when (target) {
                is ThreadScroll.ToUnread -> { listState.scrollToUnreadTop(target.index); stickToBottom = false }
                is ThreadScroll.ToNewest -> { listState.scrollToItem(0); stickToBottom = true }
                ThreadScroll.None -> Unit
            }
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }
    // Prefetch older history about a screen before the top so scrolling never waits on it.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - OLDER_PREFETCH_ITEMS) info.totalItemsCount else -1
        }.collect { if (it > 0) viewModel.loadOlderMessages() }
    }
    val withCallPermissions = app.aino.mobile.core.call.rememberCallPermissions()
    // Missed-call notification "Call back": place the call once this thread is open and the socket is up.
    val callBack by app.aino.mobile.core.call.PendingCallBack.request.collectAsStateWithLifecycle()
    LaunchedEffect(callBack, conversation.id) {
        val request = app.aino.mobile.core.call.PendingCallBack.consume(conversation.id) ?: return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(10_000) {
            while (!app.aino.mobile.core.call.CallRealtimeLink.connected.value) kotlinx.coroutines.delay(250)
        } ?: return@LaunchedEffect
        withCallPermissions(request.callType == "video") { viewModel.startCall(request.callType) }
    }
    BackHandler(enabled = allMediaOpen) { allMediaOpen = false }
    if (ui.showInfo) {
        ConversationInfo(ui, viewModel, onOpenAllMedia = { allMediaOpen = true })
        if (allMediaOpen) AllMediaScreen(ui, conversation, viewModel, onClose = { allMediaOpen = false })
        return
    }
    if (ui.forwardingMessage != null) {
        ForwardMessageDialog(ui, viewModel)
    }
    val searchTerm = ui.threadSearchQuery.takeIf { ui.threadSearchOpen }
    Box(Modifier.fillMaxSize().background(signal.background)) {
        Column(Modifier.fillMaxSize()) {
            // Signal action mode fades over the toolbar instead of popping in.
            androidx.compose.animation.AnimatedContent(
                targetState = when {
                    ui.selectedMessageIds.isNotEmpty() -> ThreadBar.Selection
                    ui.threadSearchOpen -> ThreadBar.Search
                    else -> ThreadBar.Header
                },
                transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                },
                label = "threadBar",
            ) { bar ->
                when (bar) {
                    ThreadBar.Selection -> MessageSelectionBar(ui, viewModel) { deleteTargets = it }
                    ThreadBar.Search -> ThreadSearchToolbar(ui.threadSearchQuery, viewModel::updateThreadSearch, viewModel::closeThreadSearch)
                    ThreadBar.Header -> ThreadHeader(conversation, ui, viewModel, onNavigateBack, withCallPermissions, onOpenAllMedia = { allMediaOpen = true })
                }
            }
            // Signal pinned-message bar: newest pin shown; tap jumps and cycles to the next.
            androidx.compose.animation.AnimatedVisibility(
                visible = ui.pinnedMessages.isNotEmpty() && ui.selectedMessageIds.isEmpty() && !ui.threadSearchOpen,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                val index = pinnedIndex.coerceIn(0, (ui.pinnedMessages.size - 1).coerceAtLeast(0))
                ui.pinnedMessages.getOrNull(index)?.let { pinned ->
                    PinnedMessageBar(
                        message = pinned,
                        index = index,
                        count = ui.pinnedMessages.size,
                        onClick = {
                            viewModel.jumpToMessage(pinned)
                            pinnedIndex = (index + 1) % ui.pinnedMessages.size
                        },
                        onUnpin = { viewModel.toggleMessagePin(pinned) },
                        onViewAll = { viewModel.openInfo(); viewModel.loadPinnedMessages() },
                    )
                }
            }
            if (conversation.isGroup && ui.selectedMessageIds.isEmpty() && !ui.threadSearchOpen) {
                ui.activeGroupCall?.let { call -> GroupCallBanner(call, ui.callsEnabled) { withCallPermissions(call.callType == "video") { viewModel.joinActiveGroupCall() } } }
            }
            ui.threadRefreshError?.let { failure ->
                ThreadLoadWarning(
                    text = if (ui.threadFromCache) "$failure\nShowing saved messages." else failure,
                    retryLabel = "Retry",
                    loading = ui.threadLoading,
                    onRetry = viewModel::refreshThread,
                )
            }
            ui.olderMessagesError?.let { failure ->
                ThreadLoadWarning(
                    text = "Couldn't refresh older messages.\n${failure.message}",
                    retryLabel = "Retry older messages",
                    loading = ui.loadingOlder,
                    onRetry = viewModel::loadOlderMessages,
                )
            }
            ui.error?.let { AinoAlert(it, AlertTone.Error, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (threadItems.isEmpty() && !ui.threadLoading) {
                    item(contentType = "empty") { HonestEmpty(HeroIcons.ChatBubbleOvalLeft, "No messages yet") }
                }

                items(newestFirst, key = ThreadItem::key, contentType = { it.contentType(ui.currentUserId) }) { item ->
                    // Signal item animator: only rows that arrive live slide up + fade in (and fade out
                    // on the pending → sent swap); the first page and older pages appear in place.
                    val arrived = item.key in arrivedKeys
                    val slide = remember { arrived && slidKeys.add(item.key) }
                    Column(
                        (if (arrived) Modifier.animateItem(
                            fadeInSpec = androidx.compose.animation.core.tween(220),
                            placementSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1),
                            ),
                            fadeOutSpec = androidx.compose.animation.core.tween(150),
                        ) else Modifier).then(if (slide) Modifier.arrivalSlide() else Modifier),
                    ) {
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
                            poll = item.message.pollId()?.let(ui.polls::get),
                            onLoadPoll = viewModel::loadPoll,
                            onOpenMedia = { viewingMediaId = it.id },
                            selectionActive = ui.selectedMessageIds.isNotEmpty(),
                            selected = item.message.id in ui.selectedMessageIds,
                            highlighted = item.message.id == highlightedId ||
                                (ui.threadSearchOpen && item.message.id == ui.threadSearchMatches.getOrNull(ui.threadSearchIndex)),
                            onSelect = { viewModel.enterMessageSelection(item.message) },
                            onToggleSelect = { viewModel.toggleMessageSelection(item.message.id) },
                            isGroup = conversation.isGroup,
                            currentUserId = ui.currentUserId,
                            reactionAvatars = reactionAvatars,
                            searchTerm = searchTerm,
                            viewOnceLoading = viewOnceLoadingId == item.message.id,
                            onOpenViewOnce = {
                                viewOnceLoadingId = item.message.id
                                viewModel.openViewOnce(item.message) { url ->
                                    viewOnceLoadingId = null
                                    if (url != null) viewOnceOpen = item.message.copy(fileUrl = url)
                                }
                            },
                            onStartCall = { type -> withCallPermissions(type == "video") { viewModel.startCall(type) } },
                        )
                        is ThreadItem.Queued -> QueuedBubble(item.message)
                        is ThreadItem.PendingUpload -> pendingById[item.localId]?.let { media ->
                            PendingMediaBubble(
                                media,
                                onCancel = { viewModel.cancelPendingMedia(media.localId) },
                                onRetry = { viewModel.retryPendingMedia(media.localId) },
                            )
                        }
                    }
                    }
                }
            }
            // Older-page spinner floats over the top edge so the list never shifts when it appears.
            androidx.compose.animation.AnimatedVisibility(
                visible = ui.loadingOlder,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
            ) {
                Box(
                    Modifier.size(32.dp).shadow(2.dp, CircleShape).background(signal.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = signal.primary)
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
            } else if (conversation.isGroup && !GroupPermissions.of(conversation, ui.members, ui.currentUserId).canSend) {
                AdminsOnlyComposerNotice()
            } else MessageComposer(
                value = ui.composer,
                uploading = ui.uploading,
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
                onSendGif = { uri, mime -> viewModel.upload(uri, mimeOverride = mime, keepComposer = true) },
            )
        }
        if (cameraOpen) app.aino.mobile.feature.chat.media.ChatCameraScreen(
            recent = cameraRecent,
            onClose = { cameraOpen = false },
            onCaptured = { captured -> cameraOpen = false; sendItems = sendItems.orEmpty() + captured },
            onOpenGallery = openGallery,
        )
        sendItems?.let { items ->
            val mediaHaptics = app.aino.mobile.core.designsystem.rememberAinoHaptics()
            DisposableEffect(Unit) { onDispose { viewModel.discardPrewarmedMedia() } }
            app.aino.mobile.feature.chat.media.MediaSendScreen(
                initial = items,
                recipientName = conversation.title(),
                onAddMore = openGallery,
                onClose = { sendItems = null },
                onSend = { finalItems, caption, viewOnce, highQuality ->
                    mediaHaptics.confirm()
                    sendItems = null
                    viewModel.sendMedia(finalItems.map { MediaUploadSpec(it.uri, it.mimeType, it.width, it.height) }, caption, viewOnce, highQuality)
                },
                onPrepare = { prepared, highQuality ->
                    viewModel.prewarmMedia(prepared.map { MediaUploadSpec(it.uri, it.mimeType, it.width, it.height) }, highQuality)
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
        Icon(HeroIcons.XMark, "Cancel message selection", Modifier.size(48.dp).clip(CircleShape).clickable(onClick = viewModel::clearMessageSelection).padding(12.dp), tint = signal.text)
        Text("${ui.selectedMessageIds.size}", Modifier.weight(1f).padding(start = 8.dp), color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        @Composable
        fun action(icon: ImageVector, label: String, tint: Color = signal.text, onClick: () -> Unit) =
            Icon(icon, label, Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).padding(12.dp), tint = tint)
        action(HeroIcons.DocumentDuplicate, "Copy selected text") {
            viewModel.selectedText().takeIf(String::isNotEmpty)?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
            viewModel.clearMessageSelection()
        }
        if (selected.size == 1) action(HeroIcons.ArrowUturnRight, "Forward") { viewModel.beginForward(selected.first()); viewModel.clearMessageSelection() }
        action(HeroIcons.PushPin, "Pin or unpin selected", onClick = viewModel::pinSelected)
        action(HeroIcons.Star, "Save or unsave selected", onClick = viewModel::starSelected)
        action(HeroIcons.Trash, "Delete selected", tint = signal.danger) { onDelete(selected) }
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
@Composable
private fun ScrollToBottomButton(unseen: Int, onClick: () -> Unit) {
    Box {
        val signal = signalColors
        androidx.compose.material3.Surface(
            onClick = onClick, shape = CircleShape, color = signal.surface, shadowElevation = 3.dp,
            modifier = Modifier.padding(top = 10.dp).size(44.dp),
        ) { Box(contentAlignment = Alignment.Center) { Icon(HeroIcons.ChevronDown, "Scroll to latest", tint = signal.text) } }
        if (unseen > 0) SignalUnreadBadge(unseen, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CallRow(
    call: CallLog,
    conversation: ChatConversation?,
    currentUserId: Long?,
    selected: Boolean = false,
    selectionEnabled: Boolean = false,
    selectionMode: Boolean = selected,
    onToggleSelection: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primaryGlow else Color.Transparent)
            .combinedClickable(
                enabled = selectionEnabled,
                onClick = { if (selected || selectionMode) onToggleSelection() },
                onLongClick = onToggleSelection,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionEnabled && (selected || selectionMode)) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
        }
        val webColors = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current
        val label = call.historyLabel(currentUserId)
        val labelTint = if (label.danger) webColors.danger else webColors.textSecondary
        val title = call.title(currentUserId)
        when {
            conversation != null && (conversation.isGroup || conversation.isMeetingChat) -> ConversationAvatar(conversation, null, 46.dp)
            call.isGroup -> app.aino.mobile.core.designsystem.component.GroupAvatar(title, null, emptyList(), "group-${call.conversationId}", 46.dp)
            else -> app.aino.mobile.core.designsystem.component.UserAvatar(title, call.peerAvatar(currentUserId) ?: conversation?.otherAvatar, 46.dp)
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (label.danger) webColors.danger else Color.Unspecified)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (label.outgoing) HeroIcons.ArrowUpRight else HeroIcons.ArrowDownLeft,
                    if (label.outgoing) "Outgoing" else "Incoming",
                    Modifier.size(13.dp),
                    tint = labelTint,
                )
                Text(
                    listOfNotNull(label.title, label.detail).joinToString(" · "),
                    Modifier.padding(start = 4.dp),
                    color = labelTint,
                    fontSize = 12.sp,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(timeAgo(call.createdAt), color = webColors.textSecondary, fontSize = 11.sp)
            Icon(
                if (label.video) HeroIcons.VideoCamera else HeroIcons.Phone,
                if (label.video) "Video call" else "Voice call",
                Modifier.padding(top = 4.dp).size(18.dp),
                tint = if (label.danger) webColors.danger else webColors.primary,
            )
        }
    }
}

internal enum class InfoPage(val title: String) {
    Main("Conversation info"), Search("Search"), Shared("Shared media, files & links"),
    Pinned("Pinned messages"), Saved("Saved messages"), Group("Group settings & members"),
}

/** Port of web `ConversationInfoPanel.tsx` (quick Call/Video/Search, shared/pinned/saved, block, clear). */
@Composable
private fun ConversationInfo(ui: ChatUiState, viewModel: ChatViewModel, onOpenAllMedia: () -> Unit = {}) {
    val conversation = ui.selectedConversation ?: return
    val colors = LocalWebColors.current
    var page by remember { mutableStateOf(InfoPage.Main) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    // Signal settings shows a strip of recent media above "All media".
    LaunchedEffect(conversation.id) { viewModel.loadSharedFiles() }
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
    // Groups get the Signal-style settings screen; its media/pinned/saved/search rows reuse the pages below.
    if (conversation.isGroup && !conversation.isMeetingChat && (page == InfoPage.Main || page == InfoPage.Group)) {
        GroupSettingsScreen(ui, viewModel, onOpenAllMedia = onOpenAllMedia, onOpenInfoPage = ::open)
        return
    }
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(colors.bgSecondary)
                    .border(BorderStroke(0.5.dp, colors.border))
                    .statusBarsPadding().height(56.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HeroIcons.ArrowLeft, "Back",
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
                            if (!conversation.isSelfChat && ui.callsEnabled) {
                                InfoAction(HeroIcons.Phone, "Call", Modifier.weight(1f)) { withCallPermissions(false) { viewModel.closeInfo(); viewModel.startCall("voice") } }
                                InfoAction(HeroIcons.VideoCamera, "Video", Modifier.weight(1f)) { withCallPermissions(true) { viewModel.closeInfo(); viewModel.startCall("video") } }
                            }
                            InfoAction(HeroIcons.MagnifyingGlass, "Search", Modifier.weight(1f)) { open(InfoPage.Search) }
                        }
                    }
                    if (conversation.isGroup) item { InfoRow(HeroIcons.Cog6Tooth, "Group settings & members") { open(InfoPage.Group) } }
                    item {
                        val recent = (ui.infoContent as? InfoContent.Files)?.files.orEmpty().filter { it.isVisual() }.take(4)
                        Column {
                            InfoRow(HeroIcons.FolderOpen, "All media, files & links", onClick = onOpenAllMedia)
                            if (recent.isNotEmpty()) Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                recent.forEach { file ->
                                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                                        ChatThumbnail(
                                            resolveChatMediaUrl(file.fileUrl), file.fileName, video = false,
                                            shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxSize(),
                                        ) { onOpenAllMedia() }
                                    }
                                }
                                repeat(4 - recent.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    item { InfoRow(HeroIcons.PushPin, "Pinned messages") { open(InfoPage.Pinned) } }
                    item { InfoRow(HeroIcons.Star, "Saved messages") { open(InfoPage.Saved) } }
                    // The web exposes these through the conversation row's hover menu;
                    // mobile has no hover, so they live here.
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoAction(HeroIcons.PushPin, if (conversation.isPinned) "Unpin" else "Pin chat", Modifier.weight(1f), viewModel::togglePin)
                            InfoAction(HeroIcons.Star, if (conversation.isFavourite) "Unfavourite" else "Favourite", Modifier.weight(1f), viewModel::toggleFavourite)
                            InfoAction(if (conversation.isMuted) HeroIcons.Bell else HeroIcons.BellSlash, if (conversation.isMuted) "Unmute" else "Mute", Modifier.weight(1f), viewModel::toggleMute)
                            InfoAction(HeroIcons.ArchiveBox, if (conversation.isArchived) "Unarchive" else "Archive", Modifier.weight(1f), viewModel::toggleArchive)
                        }
                    }
                    if (!conversation.isGroup && !conversation.isSelfChat && conversation.otherUserId != null) item {
                        InfoRow(HeroIcons.NoSymbol, if (conversation.isBlocked) "Unblock user" else "Block user", danger = !conversation.isBlocked) { confirmBlock = true }
                    }
                    item { InfoRow(HeroIcons.Trash, "Clear chat", danger = true) { confirmClear = true } }
                    if (ui.conversationCalls.isNotEmpty()) {
                        item { SectionHeader("Call history", HeroIcons.Phone) }
                        items(ui.conversationCalls, key = { "info-call-${it.id}" }) { CallRow(it, conversation, ui.currentUserId) }
                    }
                }
                InfoPage.Search -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp).height(44.dp).background(colors.inputBg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HeroIcons.MagnifyingGlass, null, Modifier.size(16.dp), tint = colors.textMuted)
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
                        else if (files.isEmpty()) item { HonestEmpty(HeroIcons.FolderOpen, "No shared files yet") }
                        items(files, key = { "file-${it.id}" }) { file ->
                            InfoRow(
                                when {
                                    file.fileType?.startsWith("image/") == true -> HeroIcons.Photo
                                    file.fileType?.startsWith("video/") == true -> HeroIcons.Film
                                    file.fileType?.startsWith("audio/") == true -> HeroIcons.MusicalNote
                                    else -> HeroIcons.DocumentText
                                },
                                listOfNotNull(file.fileName ?: "File", formatChatFileSize(file.fileSize)).joinToString(" · "),
                            ) { scope.launch { openChatFile(context, resolveChatMediaUrl(file.fileUrl), file.fileName, file.fileType) } }
                        }
                    }
                }
                InfoPage.Group -> Unit
            }
        }
    }
    if (confirmClear) ClearChatDialog(onConfirm = viewModel::clearChat) { confirmClear = false }
    if (confirmBlock) BlockDialog(conversation, onConfirm = viewModel::toggleBlock) { confirmBlock = false }
}

@Composable
private fun InfoMessageList(ui: ChatUiState, emptyText: String, onOpen: (ChatMessage) -> Unit, onUnstar: ((ChatMessage) -> Unit)? = null) {
    val colors = LocalWebColors.current
    val messages = (ui.infoContent as? InfoContent.Messages)?.messages.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.infoLoading) item { SearchHint("Loading…", true) }
        else if (messages.isEmpty()) item { HonestEmpty(HeroIcons.ChatBubbleOvalLeft, emptyText) }
        items(messages, key = { "info-msg-${it.id}" }) { message ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).clickable { onOpen(message) }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.senderName ?: message.senderUsername.orEmpty(), Modifier.weight(1f), color = colors.primaryLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    message.conversationName?.let { Text("in $it ", color = colors.textMuted, fontSize = 11.sp, maxLines = 1) }
                    Text(timeAgo(message.createdAt), color = colors.textMuted, fontSize = 11.sp)
                    onUnstar?.let { unstar -> Icon(HeroIcons.Star, "Unsave", Modifier.padding(start = 6.dp).size(18.dp).clickable { unstar(message) }, tint = colors.warning) }
                }
                Text(message.body(), color = colors.text, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
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
        Icon(HeroIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.textMuted)
    }
}

/** Signal call-history chip: centred pill with the call icon, label and time; tap calls back. */
@Composable
private fun CallHistoryChip(label: CallHistoryLabel, time: String, onClick: () -> Unit) {
    val signal = signalColors
    val accent = if (label.danger) signal.danger else signal.text
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(signal.datePill).clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (label.video) HeroIcons.VideoCamera else HeroIcons.Phone, null, Modifier.size(18.dp), tint = accent)
            Column(Modifier.padding(start = 10.dp)) {
                Text(label.title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(label.detail, time.takeIf(String::isNotBlank)).joinToString(" · "),
                    color = signal.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

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
    reactionAvatars: () -> Map<Long, String> = { emptyMap() },
    /** Call-history chip tap: place the same call type again ("voice" / "video"). */
    onStartCall: (String) -> Unit = {},
    poll: ChatPoll? = null,
    onLoadPoll: (Long) -> Unit = {},
) {
    var actionsOpen by remember { mutableStateOf(false) }
    var reactionsOpen by remember { mutableStateOf(false) }
    val signal = signalColors
    val saveMedia = rememberChatMediaSaver()
    // Web ChatMessages: `format_type 'meeting'` rows render a MeetingCard, not a bubble.
    val meetingMeta = message.metadata?.takeIf { message.formatType == "meeting" } as? kotlinx.serialization.json.JsonObject
    val meetingCode = (meetingMeta?.get("meetingCode") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    if (meetingMeta != null && meetingCode != null) {
        ChatMeetingCard(meetingMeta, meetingCode)
        return
    }
    message.callHistoryLabel(currentUserId)?.let { label ->
        CallHistoryChip(label, bubbleTime(message.createdAt)) { onStartCall(if (label.video) "video" else "voice") }
        return
    }
    if (message.formatType == "system") {
        val meta = message.metadata?.jsonObject
        val text = meta?.get("text")?.jsonPrimitive?.contentOrNull ?: message.body()
        val callCode = meta?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "group_call_started" }?.get("meetingCode")?.jsonPrimitive?.contentOrNull
        Text(
            if (callCode != null) "$text · Join" else text,
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                .let { if (callCode != null) it.clickable { app.aino.mobile.core.navigation.RouteRequests.open(app.aino.mobile.core.navigation.huddleRoute(callCode)) } else it },
            color = if (callCode != null) signal.primary else signal.textSecondary, fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }
    val deleted = message.deletedAt != null
    val viewOnce = !deleted && message.isViewOnce() && (message.fileType?.startsWith("image/") == true || message.fileType?.startsWith("video/") == true)
    val mediaOnly = !deleted && !viewOnce && message.isViewableMedia() && message.content.isNullOrBlank() &&
        !message.hasQuotedReply() && message.forwardedFromId == null && !(showSender && isGroup && !isMine)
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
            if (message.starred) Icon(HeroIcons.Star, "Saved message", Modifier.size(12.dp), tint = tint)
            if (message.pinnedAt != null) Icon(HeroIcons.PushPin, "Pinned message", Modifier.size(12.dp), tint = tint)
            if (message.editedAt != null && !deleted) Text("Edited", color = tint, fontSize = SignalDimens.footerText)
            Text(remember(message.createdAt) { bubbleTime(message.createdAt) }, color = tint, fontSize = SignalDimens.footerText)
            if (isMine && !deleted) DeliveryTickIcon(remember(message, receipts, participantCount) { deliveryTick(message, receipts, participantCount) }, onMedia)
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
                            .then(
                                when {
                                    deleted -> Modifier.border(1.dp, signal.divider, shape)
                                    // Web `.myBubble { border: 1px solid color-mix(--primary 16%) }`.
                                    isMine && !mediaOnly -> Modifier.border(1.dp, signal.outgoingBorder, shape)
                                    else -> Modifier
                                },
                            )
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
                            // Web `.senderName { color: var(--primary); opacity: .85 }`.
                            color = signal.primary.copy(alpha = .85f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (message.forwardedFromId != null && !deleted) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(HeroIcons.ArrowUturnRight, null, Modifier.size(14.dp), tint = fgMuted)
                                Text("Forwarded", color = fgMuted, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            }
                        }
                        if (!deleted && message.hasQuotedReply()) SignalQuote(message, isMine, fg)
                        when {
                            deleted -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(HeroIcons.NoSymbol, null, Modifier.size(16.dp), tint = signal.textSecondary)
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
                                val pollId = message.pollId()
                                LaunchedEffect(pollId) { if (pollId != null && poll == null) onLoadPoll(pollId) }
                                val labels = poll?.options
                                    ?: message.metadata?.jsonObject?.get("options")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                                    .orEmpty()
                                val tally = pollTally(poll, labels.size, currentUserId)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(HeroIcons.ChartBar, null, Modifier.size(18.dp), tint = fg)
                                    Text(poll?.question ?: message.body(), Modifier.padding(start = 7.dp), color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                                labels.forEachIndexed { index, label ->
                                    val option = tally.options[index]
                                    val shape = RoundedCornerShape(10.dp)
                                    Box(
                                        Modifier.fillMaxWidth().clip(shape)
                                            .border(if (option.mine) 2.dp else 1.dp, fg.copy(alpha = if (option.mine) .9f else .35f), shape)
                                            .clickable(enabled = poll?.closedAt == null) { onVote(index) },
                                    ) {
                                        Box(Modifier.matchParentSize().fillMaxWidth(option.percent / 100f).background(fg.copy(alpha = .14f)))
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(label, Modifier.weight(1f), color = fg, fontSize = 15.sp)
                                            if (option.count > 0) Text("${option.count} (${option.percent}%)", color = fg.copy(alpha = .8f), fontSize = 13.sp)
                                            if (option.mine) Text(" ✓", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${tally.total} vote${if (tally.total == 1) "" else "s"}" + if (poll?.multiSelect == true) " · Multiple choice" else "",
                                        Modifier.weight(1f), color = fg.copy(alpha = .7f), fontSize = 12.sp,
                                    )
                                    footer(false)
                                }
                            }
                            mediaOnly -> Box {
                                ChatMediaPreview(
                                    message = message, onOpenMedia = { if (selectionActive) onToggleSelect() else onOpenMedia(it) },
                                    onCancelProcessing = { onCancel() }, onRetryProcessing = { onRetry() }, shape = shape, outgoing = isMine,
                                    onLongPress = { if (selectionActive) onToggleSelect() else actionsOpen = true },
                                )
                                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { footer(true) }
                            }
                            !message.fileUrl.isNullOrBlank() -> {
                                if (message.formatType != "poll") message.linkPreview?.let { LinkPreviewCard(it) }
                                ChatMediaPreview(
                                    message = message, onOpenMedia = { if (selectionActive) onToggleSelect() else onOpenMedia(it) },
                                    onCancelProcessing = { onCancel() }, onRetryProcessing = { onRetry() },
                                    shape = RoundedCornerShape(SignalDimens.bubbleCornerCollapsed * 3), outgoing = isMine,
                                    onLongPress = { if (selectionActive) onToggleSelect() else actionsOpen = true },
                                    withContent = !message.content.isNullOrBlank(),
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
                                                message.fileType?.startsWith("image/") == true -> HeroIcons.Photo
                                                message.fileType?.startsWith("audio/") == true -> HeroIcons.MusicalNote
                                                message.fileType?.startsWith("video/") == true -> HeroIcons.Film
                                                else -> HeroIcons.DocumentText
                                            },
                                            null, Modifier.size(22.dp), tint = fg,
                                        )
                                    }
                                    Column(Modifier.weight(1f, fill = false)) {
                                        Text(message.fileName, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        message.fileSize?.let { Text(formatFileSize(it), color = fgMuted, fontSize = 13.sp) }
                                    }
                                    message.fileUrl?.takeIf { it.isNotBlank() && !selectionActive }?.let { url ->
                                        Icon(
                                            HeroIcons.ArrowDownTray, "Save to device",
                                            Modifier.size(36.dp).clip(CircleShape)
                                                .clickable { saveMedia(resolveChatMediaUrl(url), message.fileName, message.fileType) }
                                                .padding(7.dp),
                                            tint = fg,
                                        )
                                    }
                                }
                                if (!message.mediaState.isNullOrBlank() && message.mediaState !in setOf("ready", "completed")) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(listOfNotNull(message.mediaStage ?: message.mediaState, message.mediaProgress?.let { "$it%" }).joinToString(" · "), color = fgMuted, fontSize = 12.sp)
                                        when (message.mediaState) {
                                            "failed", "cancelled" -> Icon(HeroIcons.ArrowUturnLeft, "Retry", Modifier.padding(start = 6.dp).size(18.dp).clickable(onClick = onRetry), tint = fg)
                                            "queued", "processing" -> Icon(HeroIcons.XCircle, "Cancel", Modifier.padding(start = 6.dp).size(18.dp).clickable(onClick = onCancel), tint = fg)
                                        }
                                    }
                                }
                                Box(Modifier.align(Alignment.End)) { footer(false) }
                            }
                            else -> {
                                message.linkPreview?.let { LinkPreviewCard(it) }
                                BubbleTextWithFooter(
                                    highlightTerm(message.body(), searchTerm, signal.highlight), fg, { footer(false) },
                                    fontSize = emojiMessageSize(message.body()),
                                )
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
                // View-once media is never saveable (Signal).
                onSaveToDevice = message.fileUrl?.takeIf { it.isNotBlank() && !deleted && !message.isViewOnce() }?.let { url ->
                    { saveMedia(resolveChatMediaUrl(url), message.fileName, message.fileType) }
                },
            ),
            messagePreview = {
                val preview = message.body().take(400)
                // Signal lifts the actual media into the overlay, not its file name.
                if (!deleted && message.isViewableMedia() && !message.isViewOnce()) Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                    ChatThumbnail(
                        ChatMediaMemory.localFor(message.fileUrl) ?: resolveChatMediaUrl(message.fileUrl.orEmpty()), message.fileName,
                        video = message.isVideoAttachment(), shape = RoundedCornerShape(18.dp),
                        naturalSize = message.mediaDims() ?: ChatMediaMemory.dims(message.fileUrl),
                        withContent = !message.content.isNullOrBlank(),
                    ) {}
                    message.content?.takeIf(String::isNotBlank)?.let { caption ->
                        Text(
                            caption, Modifier.padding(top = 4.dp).widthIn(max = 240.dp).clip(shape).background(bubbleColor)
                                .padding(horizontal = SignalDimens.bubbleHPad, vertical = SignalDimens.bubbleTopPad),
                            color = fg, fontSize = SignalDimens.bodyText, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (preview.isNotBlank()) Text(
                    preview,
                    Modifier.widthIn(max = 300.dp).clip(shape).background(bubbleColor.takeIf { it != Color.Transparent } ?: signal.incoming)
                        .padding(horizontal = SignalDimens.bubbleHPad, vertical = SignalDimens.bubbleTopPad),
                    color = fg, fontSize = SignalDimens.bodyText, lineHeight = SignalDimens.bodyLine, maxLines = 8, overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
    if (reactionsOpen) {
        val avatars = remember { reactionAvatars() }
        ReactionsSheet(message.reactions, currentUserId, onRemoveMine = onReact, avatars = avatars) { reactionsOpen = false }
    }
}

/** Signal quote block inside a bubble: accent bar, author, 2-line text, optional 60dp thumbnail. */
@Composable
private fun SignalQuote(message: ChatMessage, isMine: Boolean, fg: Color) {
    val signal = signalColors
    val quoteShape = RoundedCornerShape(SignalDimens.quoteCorner)
    Row(
        // Web ReplyPreview: neutral wash + org-accent rule on both sides (bubbles are no longer solid blue).
        Modifier.clip(quoteShape).background(if (signal.isDark) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .05f))
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(signal.primary))
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(message.replySenderName.orEmpty(), color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                message.replyContent?.takeIf(String::isNotBlank)
                    ?: if (message.replyFileType != null || message.replyFileName != null)
                        attachmentSnippet(message.replyFileType, message.replyFileName, null)
                    else "Original message unavailable",
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
        containerColor = signalColors.surface,
        titleContentColor = signalColors.text,
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
                    if (choices.isEmpty()) item { AinoEmptyState(HeroIcons.ChatBubbleOvalLeft, "No matching conversations") }
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
    val label = remember(date) {
        val today = LocalDate.now()
        when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date.isAfter(today.minusDays(7)) -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            date.year == today.year -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
            else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            Modifier.clip(RoundedCornerShape(14.dp)).background(signal.datePill).padding(horizontal = 12.dp, vertical = 4.dp),
            color = signal.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
    }
}
/**
 * Web `MessageBubble.module.css`: 16px corners, with the first bubble of a
 * sender block squared on the sender's top corner (`.myBubble 16 4 16 16`,
 * `.theirBubble 4 16 16 16`); grouped continuations are fully rounded.
 * [endsGroup] is kept for call-site compatibility.
 */
@Suppress("UNUSED_PARAMETER")
internal fun messageBubbleShape(isMine: Boolean, startsGroup: Boolean, endsGroup: Boolean): RoundedCornerShape {
    val large = SignalDimens.bubbleCorner
    val notch = if (startsGroup) SignalDimens.bubbleCornerCollapsed else large
    return if (isMine) {
        RoundedCornerShape(topStart = large, topEnd = notch, bottomStart = large, bottomEnd = large)
    } else {
        RoundedCornerShape(topStart = notch, topEnd = large, bottomStart = large, bottomEnd = large)
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

/**
 * Tick colours: sending/sent muted; delivered and read in the org accent
 * (`--primary`). Over media everything is white on the dark pill ([onMedia]).
 */
@Composable
private fun DeliveryTickIcon(tick: DeliveryTick, onMedia: Boolean) {
    val signal = signalColors
    val tint = when {
        onMedia -> Color.White
        // Delivered and read both use the org accent; the glyph shape tells them apart.
        tick == DeliveryTick.Read || tick == DeliveryTick.Delivered -> signal.tickRead
        else -> signal.tickMuted
    }
    SignalReceiptIcon(tick, tint, punchThrough = if (onMedia) Color.Black.copy(alpha = .55f) else signal.outgoing)
}

private val explicitOffset = Regex("[+-]\\d{2}:?\\d{2}$")

private fun parseChatInstant(value: String): Instant? = runCatching {
    Instant.parse(value.replace(" ", "T").let {
        if (it.endsWith("Z") || explicitOffset.containsMatchIn(it)) it else "${it}Z"
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
                }, fontSize = emojiMessageSize(message.content))
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageComposer(
    value: String,
    uploading: Boolean,
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
    onSendGif: (Uri, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val signal = signalColors
    val player = app.aino.mobile.core.AppContainer.get(context).audio
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Signal swaps the IME for its own emoji / attachment keyboards at the same height.
    var panel by remember { mutableStateOf(ComposerPanel.None) }
    var keyboardHeight by remember { mutableStateOf(KeyboardHeightStore.get(context).dp) }
    // Holds the drawer's space while the IME slides back in, so the composer never dips.
    var awaitingIme by remember { mutableStateOf(false) }
    // Cursor-aware composer text so emoji insert at the caret, not always at the end.
    var fieldValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    if (fieldValue.text != value) {
        fieldValue = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    }
    fun insertAtCursor(emoji: String) {
        val next = insertAtSelection(fieldValue.text, fieldValue.selection.start, fieldValue.selection.end, emoji)
        if (next.first.length > 5000) return
        fieldValue = androidx.compose.ui.text.input.TextFieldValue(next.first, androidx.compose.ui.text.TextRange(next.second))
        onChange(next.first)
    }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    // Signal records the IME height only from settled insets (DISPATCH_MODE_STOP);
    // mid-animation frames while the keyboard hides are what made the drawer short.
    LaunchedEffect(imeBottom, imeTarget) {
        if (imeTarget > navBottom && imeBottom == imeTarget) {
            val h = with(density) { (imeBottom - navBottom).toDp() }
            KeyboardHeightStore.save(context, h.value)
            keyboardHeight = KeyboardHeightStore.get(context).dp
            awaitingIme = false
        }
    }
    // The IME starting to open replaces the emoji/attachment drawer (never its hide frames).
    val imeOpening = imeTarget > navBottom
    LaunchedEffect(imeOpening) {
        if (imeOpening && (panel == ComposerPanel.Emoji || panel == ComposerPanel.Attach)) {
            awaitingIme = true
            panel = ComposerPanel.None
        }
    }
    LaunchedEffect(awaitingIme) {
        // Hardware keyboards never raise the IME; don't hold the space forever.
        if (awaitingIme) { kotlinx.coroutines.delay(800); awaitingIme = false }
    }
    BackHandler(enabled = panel != ComposerPanel.None) { panel = ComposerPanel.None }
    fun toggle(target: ComposerPanel) {
        if (panel == target) { awaitingIme = true; panel = ComposerPanel.None; keyboard?.show() }
        else { keyboard?.hide(); panel = target }
    }
    // A drawer opening with the keyboard down, or closing without the keyboard taking
    // its place, slides the composer like the IME would instead of jumping. Decided
    // during composition so the very first frame is already offset.
    val panelMotion = remember { PanelMotion(panel) }
    if (panelMotion.shown != panel) {
        val opening = panelMotion.shown == ComposerPanel.None
        panelMotion.shown = panel
        val panelPx = with(density) { keyboardHeight.toPx() }
        when {
            opening && imeBottom <= navBottom -> { panelMotion.collapse.floatValue = 0f; panelMotion.reveal.floatValue = panelPx }
            panel == ComposerPanel.None && !awaitingIme && !imeOpening -> { panelMotion.reveal.floatValue = 0f; panelMotion.collapse.floatValue = panelPx + navBottom }
        }
    }
    LaunchedEffect(panel) {
        launch { panelMotion.reveal.settle() }
        launch { panelMotion.collapse.settle() }
    }
    val insetPx = if (panel == ComposerPanel.None && !awaitingIme) maxOf(navBottom, imeBottom).toFloat() else 0f
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
                if (event == VoiceEvent.Release) voiceHint = "Tap and hold to record a voice message, release to send"
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
        voiceHint = if (granted) "Tap and hold to record a voice message, release to send" else "Microphone permission is required for voice messages"
    }
    DisposableEffect(Unit) { onDispose { draftUrl?.let(player::stop); voice.discard() } }

    val recordingActive = phase != VoicePhase.Idle
    val canRecord = value.isBlank() && editingMessage == null
    // `union()` takes the max of the nav-bar and IME insets (chaining the two
    // paddings would sum them); with adjustResize in the manifest the window
    // itself never pans, so this is the only place the keyboard is accounted for.
    Column(
        Modifier.drawWithContent {
                if (panelMotion.reveal.floatValue > 0f) clipRect { this@drawWithContent.drawContent() } else drawContent()
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val extra = (panelMotion.collapse.floatValue - insetPx).coerceAtLeast(0f)
                val height = (placeable.height - panelMotion.reveal.floatValue + extra).roundToInt().coerceIn(0, constraints.maxHeight)
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .fillMaxWidth().background(signal.background)
            .then(
                when {
                    panel != ComposerPanel.None -> Modifier
                    awaitingIme -> Modifier.padding(bottom = maxOf(keyboardHeight + with(density) { navBottom.toDp() }, with(density) { imeBottom.toDp() }))
                    else -> Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                },
            ),
    ) {
        // Signal tooltip above the mic (short tap / permission result).
        androidx.compose.animation.AnimatedVisibility(
            visible = voiceHint != null,
            modifier = Modifier.align(Alignment.End),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = .9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            var shown by remember { mutableStateOf("") }
            voiceHint?.let { shown = it }
            Text(
                shown,
                Modifier.padding(start = 48.dp, end = 12.dp, top = 4.dp)
                    .shadow(3.dp, RoundedCornerShape(12.dp))
                    .background(signal.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = signal.text,
                fontSize = 14.sp,
            )
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
                        app.aino.mobile.core.designsystem.component.UserAvatar(member.display(), member.avatar, 28.dp)
                        Text(member.display(), color = signal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        member.username?.let { Text("@$it", color = signal.textSecondary, fontSize = 13.sp) }
                    }
                }
            }
        }
        // Signal quote-style banner above the input for edit / reply; expands in and collapses out.
        androidx.compose.animation.AnimatedVisibility(
            visible = editingMessage != null || replyingTo != null,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
        ) {
            var quoted by remember { mutableStateOf<Pair<Boolean, ChatMessage>?>(null) }
            (editingMessage?.let { true to it } ?: replyingTo?.let { false to it })?.let { quoted = it }
            val (editing, target) = quoted ?: return@AnimatedVisibility
            val banner = (if (editing) "Edit message" else target.senderName ?: target.senderUsername.orEmpty()) to target.body()
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
                target.fileUrl?.takeIf { !editing && target.fileType?.startsWith("image/") == true }?.let { url ->
                    coil3.compose.AsyncImage(
                        model = resolveChatMediaUrl(url),
                        imageLoader = app.aino.mobile.core.AppContainer.get(context).imageLoader,
                        contentDescription = "Quoted image",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Icon(
                    HeroIcons.XMark, if (editing) "Cancel edit" else "Cancel reply",
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClick = if (editing) onCancelEdit else onCancelReply).padding(10.dp),
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
                        Box(Modifier.size(SignalDimens.composeHeight).clickable { toggle(if (KeyboardPageStore.get(context) == KeyboardPage.Emoji) ComposerPanel.Emoji else ComposerPanel.Giphy) }, contentAlignment = Alignment.Center) {
                            val mediaKeyboardOpen = panel == ComposerPanel.Emoji || panel == ComposerPanel.Giphy
                            Icon(
                                if (mediaKeyboardOpen) HeroIcons.Keyboard else HeroIcons.FaceSmile,
                                if (mediaKeyboardOpen) "Show keyboard" else "Show emoji",
                                Modifier.size(24.dp), tint = signal.textSecondary,
                            )
                        }
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = { next ->
                                val capped = if (next.text.length > 5000) next.copy(text = next.text.take(5000)) else next
                                fieldValue = capped
                                if (capped.text != value) onChange(capped.text)
                            },
                            modifier = Modifier.weight(1f).padding(vertical = 11.dp)
                                .onFocusChanged { if (it.isFocused && panel != ComposerPanel.None && panel != ComposerPanel.EmojiSearch) panel = ComposerPanel.None },
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
                                Icon(HeroIcons.Camera, "Open camera", Modifier.size(24.dp), tint = signal.textSecondary)
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
            val sendHaptics = app.aino.mobile.core.designsystem.rememberAinoHaptics()
            Box(
                Modifier.size(SignalDimens.composeHeight).clip(CircleShape)
                    .background(if (sendMode) signal.primary else signal.searchPill)
                    .clickable(enabled = !uploading && phase != VoicePhase.Holding) {
                        when {
                            recordingActive -> { sendHaptics.confirm(); dispatch(VoiceEvent.Send) }
                            sendMode -> { sendHaptics.confirm(); onSend() }
                            else -> { sendHaptics.tap(); toggle(ComposerPanel.Attach) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.Crossfade(sendMode, label = "sendMode") { send ->
                    Icon(
                        if (send) HeroIcons.PaperAirplane else HeroIcons.Plus,
                        if (send) "Send" else "Attachments",
                        Modifier.size(24.dp).graphicsLayer { rotationZ = if (send) 0f else rotation },
                        tint = if (send) Color.White else signal.text,
                    )
                }
            }
        }
        when (panel) {
            // Signal media keyboard (Emoji | Sticker | GIF). The GIPHY search field raises the IME, so those pages shrink above it.
            ComposerPanel.Emoji, ComposerPanel.Giphy -> MediaKeyboard(
                giphy = panel == ComposerPanel.Giphy,
                height = if (panel == ComposerPanel.Giphy && imeOpening) 220.dp else keyboardHeight,
                onShowGiphy = { giphy -> panel = if (giphy) ComposerPanel.Giphy else ComposerPanel.Emoji },
                onEmoji = ::insertAtCursor,
                onBackspace = { if (value.isNotEmpty()) onChange(dropLastGrapheme(value)) },
                onOpenEmojiSearch = { panel = ComposerPanel.EmojiSearch },
                onGiphyPicked = { uri, mime -> panel = ComposerPanel.None; onSendGif(uri, mime) },
                showTabs = !(panel == ComposerPanel.Giphy && imeOpening),
                modifier = Modifier.windowInsetsPadding(
                    if (panel == ComposerPanel.Giphy) WindowInsets.navigationBars.union(WindowInsets.ime) else WindowInsets.navigationBars,
                ),
            )
            // Signal: search bar docks above the system keyboard; Back returns to the grid.
            ComposerPanel.EmojiSearch -> EmojiSearchBar(
                onEmoji = ::insertAtCursor,
                onClose = { keyboard?.hide(); panel = ComposerPanel.Emoji },
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
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

/** [Emoji] and [Giphy] are the same media keyboard on its Emoji vs Sticker/GIF pages (only the latter keeps the IME). */
private enum class ComposerPanel { None, Emoji, EmojiSearch, Giphy, Attach }

/** Composer height offsets (px) that ease a drawer in/out when the IME isn't there to do it. */
private class PanelMotion(var shown: ComposerPanel) {
    val reveal = androidx.compose.runtime.mutableFloatStateOf(0f)
    val collapse = androidx.compose.runtime.mutableFloatStateOf(0f)
}

private suspend fun androidx.compose.runtime.MutableFloatState.settle() {
    if (floatValue == 0f) return
    androidx.compose.animation.core.animate(
        floatValue, 0f,
        animationSpec = androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
    ) { value, _ -> floatValue = value }
}

/** Replaces [start, end) of [text] with [insert]; returns the new text and caret position. */
internal fun insertAtSelection(text: String, start: Int, end: Int, insert: String): Pair<String, Int> {
    val from = minOf(start, end).coerceIn(0, text.length)
    val to = maxOf(start, end).coerceIn(0, text.length)
    return (text.substring(0, from) + insert + text.substring(to)) to (from + insert.length)
}

/** Backspace for the emoji keyboard: removes one user-perceived character (Android's ICU BreakIterator keeps emoji sequences whole). */
internal fun dropLastGrapheme(text: String): String {
    if (text.isEmpty()) return text
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(text)
    it.last()
    return text.substring(0, it.previous().coerceAtLeast(0))
}
@Composable
internal fun ConversationAvatar(
    conversation: ChatConversation,
    presence: ChatPresence?,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    members: List<ConversationMember> = emptyList(),
) {
    val signal = signalColors
    Box(Modifier.size(size)) {
        when {
            conversation.isMeetingChat -> Box(Modifier.fillMaxSize().background(signal.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(HeroIcons.VideoCamera, null, Modifier.size(size * .46f), tint = Color.White)
            }
            conversation.isGroup -> app.aino.mobile.core.designsystem.component.GroupAvatar(
                conversation.title(), conversation.groupAvatar, conversation.avatarMembers(members), "conv-${conversation.id}", size,
            )
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
private fun ArchivedHeader(onBack: () -> Unit) {
    val signal = signalColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            HeroIcons.ArrowLeft, "Back to chats",
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack).padding(12.dp), tint = signal.text,
        )
        Text("Archived chats", Modifier.padding(start = 8.dp), color = signal.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
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
            Icon(HeroIcons.VideoCamera, null, Modifier.size(18.dp), tint = Color(0xFF0EA5E9))
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
    onOpenAllMedia: () -> Unit = {},
) {
    val signal = signalColors
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var muteMenuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().background(signal.background)
            .statusBarsPadding()
            .height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable { viewModel.closeConversation(); onNavigateBack?.invoke() }, contentAlignment = Alignment.Center) {
            Icon(HeroIcons.ArrowLeft, "Back", tint = signal.text)
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
        if (ui.callsEnabled) {
            Icon(HeroIcons.VideoCamera, "Video call", Modifier.size(48.dp).clip(CircleShape).clickable { withCallPermissions(true) { viewModel.startCall("video") } }.padding(12.dp), tint = signal.text)
            Icon(HeroIcons.Phone, "Voice call", Modifier.size(48.dp).clip(CircleShape).clickable { withCallPermissions(false) { viewModel.startCall("voice") } }.padding(12.dp), tint = signal.text)
        }
        Box {
            Icon(HeroIcons.EllipsisVertical, "More options", Modifier.size(48.dp).clip(CircleShape).clickable { menuOpen = true }.padding(12.dp), tint = signal.text)
            // Signal ConversationOptionsMenu order, limited to what the server supports.
            SignalDropdownMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                SignalMenuItem("View all media", HeroIcons.Photo) { menuOpen = false; viewModel.openInfo(); onOpenAllMedia() }
                SignalMenuItem("Search", HeroIcons.MagnifyingGlass) { menuOpen = false; viewModel.openThreadSearch() }
                if (ui.pinnedMessages.isNotEmpty()) SignalMenuItem("Pinned messages", HeroIcons.PushPin) {
                    menuOpen = false; viewModel.jumpToMessage(ui.pinnedMessages.first())
                }
                if (conversation.isMuted) SignalMenuItem("Unmute notifications", HeroIcons.Bell) { menuOpen = false; viewModel.muteFor(null) }
                else SignalMenuItem("Mute notifications", HeroIcons.BellSlash) { menuOpen = false; muteMenuOpen = true }
                SignalMenuItem(if (conversation.isGroup) "Group settings" else "Chat settings", HeroIcons.InformationCircle) { menuOpen = false; viewModel.openInfo() }
                SignalMenuItem("Add to home screen", HeroIcons.DevicePhoneMobile) { menuOpen = false; pinChatShortcut(context, conversation) }
                SignalMenuItem(if (conversation.isArchived) "Unarchive" else "Archive", HeroIcons.ArchiveBox) { menuOpen = false; viewModel.toggleArchive() }
                androidx.compose.material3.HorizontalDivider(color = signal.divider)
                if (conversation.isGroup) SignalMenuItem("Leave group", HeroIcons.ArrowRightStartOnRectangle, danger = true) { menuOpen = false; confirm = "leave" }
                else if (!conversation.isSelfChat && conversation.otherUserId != null) SignalMenuItem(
                    if (conversation.isBlocked) "Unblock" else "Block", HeroIcons.NoSymbol, danger = !conversation.isBlocked,
                ) { menuOpen = false; confirm = "block" }
                SignalMenuItem("Clear chat", HeroIcons.ArchiveBoxXMark, danger = true) { menuOpen = false; confirm = "clear" }
                SignalMenuItem("Delete chat", HeroIcons.Trash, danger = true) { menuOpen = false; confirm = "delete" }
            }
            MuteDurationMenu(muteMenuOpen, { muteMenuOpen = false }) { viewModel.muteFor(it) }
        }
    }
    when (confirm) {
        "clear" -> ClearChatDialog(onConfirm = viewModel::clearChat) { confirm = null }
        "block" -> BlockDialog(conversation, onConfirm = viewModel::toggleBlock) { confirm = null }
        "leave" -> LeaveGroupDialog(onConfirm = viewModel::leaveGroup) { confirm = null }
        "delete" -> DeleteChatDialog(onConfirm = viewModel::deleteCurrentConversation) { confirm = null }
    }
}

/** Signal "Add to home screen": pinned launcher shortcut into this thread via the `aino://chat/{id}` deep link. */
private fun pinChatShortcut(context: android.content.Context, conversation: ChatConversation) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("aino://chat/${conversation.id}"))
        .setPackage(context.packageName)
    val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, "chat-${conversation.id}")
        .setShortLabel(conversation.title().take(24))
        .setLongLabel(conversation.title())
        .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(context, context.applicationInfo.icon))
        .setIntent(intent)
        .build()
    if (androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    } else {
        android.widget.Toast.makeText(context, "Your launcher doesn't support shortcuts", android.widget.Toast.LENGTH_SHORT).show()
    }
}
