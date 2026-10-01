package app.aino.mobile.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.db.AinoDatabase
import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.db.OutboxCoordinator
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import app.aino.mobile.core.realtime.RealtimeEvent
import app.aino.mobile.core.realtime.RealtimeEnvelope
import app.aino.mobile.core.realtime.RoutedRealtimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

sealed interface InfoContent {
    data class Messages(val messages: List<ChatMessage>) : InfoContent
    data class Files(val files: List<SharedChatFile>) : InfoContent
}

/** Drops deleted or locally hidden messages from the shared-media list (All media, info strip). */
internal fun pruneSharedFiles(content: InfoContent?, removed: Set<Long>): InfoContent? =
    if (content is InfoContent.Files && removed.isNotEmpty() && content.files.any { it.id in removed }) {
        InfoContent.Files(content.files.filterNot { it.id in removed })
    } else content

/**
 * Signal-style outgoing attachment bubble shown the moment "Send" is tapped
 * (Signal `TransferControls`): local preview + progress ring until the server
 * row replaces it.
 */
data class PendingMedia(
    val localId: String,
    val conversationId: Long,
    val uri: Uri,
    val mimeType: String,
    val fileName: String,
    val caption: String?,
    val createdAtEpochMs: Long,
    val width: Int? = null,
    val height: Int? = null,
    /** 0..1 bytes-sent fraction; null until bytes start flowing. */
    val progress: Float? = null,
    val state: PendingMediaState = PendingMediaState.Uploading,
    val error: String? = null,
) {
    val isImage get() = mimeType.startsWith("image/")
    val isVideo get() = mimeType.startsWith("video/")
    val isAudio get() = mimeType.startsWith("audio/")
}

enum class PendingMediaState { Uploading, Failed }

internal const val MARK_READ_DEBOUNCE_MS = 300L

/** The row of the thread being read never shows unread (Signal clears it as messages are seen). */
fun zeroUnread(conversations: List<ChatConversation>, conversationId: Long?): List<ChatConversation> =
    if (conversationId == null) conversations
    else conversations.map { if (it.id == conversationId && it.unreadCount != 0) it.copy(unreadCount = 0) else it }

/**
 * The server's realtime echo of an upload can beat the HTTP response. Drop the
 * oldest fully-sent pending bubble of the same type so the row never shows twice.
 */
fun dropEchoedPendingMedia(pending: List<PendingMedia>, incoming: ChatMessage, currentUserId: Long?): List<PendingMedia> =
    echoedPendingMedia(pending, incoming, currentUserId)?.let { pending - it } ?: pending

/** The fully uploaded local bubble that [incoming] (our own echo) replaces, if any. */
fun echoedPendingMedia(pending: List<PendingMedia>, incoming: ChatMessage, currentUserId: Long?): PendingMedia? {
    if (incoming.senderId != currentUserId || incoming.fileUrl.isNullOrBlank()) return null
    return pending.filter {
        it.conversationId == incoming.conversationId && it.state == PendingMediaState.Uploading &&
            it.mimeType.equals(incoming.fileType, ignoreCase = true) && (it.progress ?: 0f) >= 0.99f
    }.minByOrNull { it.createdAtEpochMs }
}

private fun PendingMedia.aspect(): Float? =
    if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else null

data class PendingAttachment(val uri: Uri, val mimeType: String, val fileName: String) {
    val isImage get() = mimeType.startsWith("image/")
    val isVideo get() = mimeType.startsWith("video/")
}

data class ChatUiState(
    val currentUserId: Long? = null,
    val loading: Boolean = false,
    val conversations: List<ChatConversation> = emptyList(),
    val presence: Map<Long, ChatPresence> = emptyMap(),
    val fromCache: Boolean = false,
    val userSearch: String = "",
    val userResults: List<ChatUser> = emptyList(),
    val searching: Boolean = false,
    val selectedConversationIds: Set<Long> = emptySet(),
    val deletingConversations: Boolean = false,
    val selectedConversation: ChatConversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val queuedMessages: List<QueuedMessage> = emptyList(),
    /** Outgoing media bubbles still uploading / failed (Signal transfer controls). */
    val pendingMedia: List<PendingMedia> = emptyList(),
    /** Pinned messages of the open thread, newest pin first (Signal pinned-message bar). */
    val pinnedMessages: List<ChatMessage> = emptyList(),
    val receipts: List<ReadReceipt> = emptyList(),
    val typingUserId: Long? = null,
    val threadLoading: Boolean = false,
    val threadFromCache: Boolean = false,
    val composer: String = "",
    val editingMessage: ChatMessage? = null,
    val replyingTo: ChatMessage? = null,
    val loadingOlder: Boolean = false,
    val hasOlderMessages: Boolean = true,
    val forwardingMessage: ChatMessage? = null,
    val forwardQuery: String = "",
    val forwardTargets: Set<Long> = emptySet(),
    val forwarding: Boolean = false,
    val uploading: Boolean = false,
    /** Picked/captured media awaiting the pre-send preview (web `MediaEditor`/`VideoPreview`). */
    val pendingAttachment: PendingAttachment? = null,
    /** Unread count captured when the thread opened; drives the unread divider. */
    val unreadAtOpen: Int = 0,
    val infoLoading: Boolean = false,
    val infoContent: InfoContent? = null,
    /** One-shot navigation requests consumed by the screen (web opens new chats immediately). */
    val openConversationId: Long? = null,
    val closeThread: Boolean = false,
    val composerLinkPreview: LinkPreview? = null,
    val dismissedPreviewUrl: String? = null,
    /** Web message selection mode (`selectedMessageIds`). */
    val selectedMessageIds: Set<Long> = emptySet(),
    /** "Delete for me": ids hidden on this device only (web `chatLocalDeletes`). */
    val hiddenMessageIds: Set<Long> = emptySet(),
    /** One-shot scroll request (web `handleJumpTo`) for pinned/saved/search results. */
    val jumpToMessageId: Long? = null,
    val calls: List<CallLog> = emptyList(),
    val callsLoading: Boolean = false,
    val selectedCallIds: Set<Long> = emptySet(),
    val deletingCalls: Boolean = false,
    val showInfo: Boolean = false,
    val members: List<ConversationMember> = emptyList(),
    val conversationCalls: List<CallLog> = emptyList(),
    val threadSearchOpen: Boolean = false,
    val threadSearchQuery: String = "",
    /** Match ids newest-first; [threadSearchIndex] is the focused one. */
    val threadSearchMatches: List<Long> = emptyList(),
    val threadSearchIndex: Int = -1,
    val messageResults: List<ChatMessage> = emptyList(),
    /** Just-archived chat offered for Undo (Signal "Chat archived" snackbar). */
    val archiveUndo: ChatConversation? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val unread: Int get() = totalUnread(conversations)
}

/** One item of a Signal media send (camera, tray or gallery). */
data class MediaUploadSpec(val uri: Uri, val mimeType: String? = null, val width: Int? = null, val height: Int? = null)

class ChatViewModel(
    private val repository: ChatRepository,
    private val cacheFactory: (CacheScope) -> ChatCache,
    private val enqueueText: suspend (CacheScope, Long, String, Long?, Long, String) -> String,
    /** Same reads served from the last responses: lists and threads paint instantly. */
    private val warm: ChatRepository? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()
    private var scope: CacheScope? = null
    private var cache: ChatCache? = null
    private var realtimeRefresh: Job? = null
    private var typingExpiry: Job? = null
    private var typingDispatch: Job? = null
    private var linkPreviewJob: Job? = null
    private var globalSearchJob: Job? = null
    private var threadSearchJob: Job? = null
    private var pendingJump: Pair<Long, Long>? = null
    private var pendingSettings: Long? = null
    private val mentionedIds = mutableSetOf<Long>()
    private var realtimeSend: (RealtimeEnvelope) -> Boolean = { false }
    private val markReadJobs = mutableMapOf<Long, Job>()

    init {
        // A push swallowed for the on-screen thread: mark read and pull the message
        // in case the realtime socket missed it (Signal marks the visible thread read).
        viewModelScope.launch {
            app.aino.mobile.core.push.VisibleThread.suppressed.collect { id ->
                if (_ui.value.selectedConversation?.id == id) {
                    scheduleMarkRead(id)
                    scheduleRefresh()
                }
            }
        }
    }

    /** The thread screen resumed (Signal `setVisibleThread`): suppress its notifications and mark it read. */
    fun onThreadVisible(conversationId: Long) {
        if (_ui.value.selectedConversation?.id != conversationId) return
        app.aino.mobile.core.push.VisibleThread.set(conversationId)
        context?.let { app.aino.mobile.core.push.ChatNotifications.cancel(it, conversationId) }
        scheduleMarkRead(conversationId)
    }

    /**
     * Signal `MarkReadHelper`: zero the row now, send one debounced read mark
     * per burst of messages instead of one request per message.
     */
    private fun scheduleMarkRead(conversationId: Long) {
        _ui.value = _ui.value.copy(conversations = zeroUnread(_ui.value.conversations, conversationId))
        synchronized(markReadJobs) {
            markReadJobs.remove(conversationId)?.cancel()
            markReadJobs[conversationId] = viewModelScope.launch(Dispatchers.IO) {
                delay(MARK_READ_DEBOUNCE_MS)
                runCatching { repository.markRead(conversationId) }
                synchronized(markReadJobs) { markReadJobs.remove(conversationId) }
            }
        }
    }
    /** Injected from the process-owned realtime ViewModel after composition. */
    fun setRealtimeSender(sender: (RealtimeEnvelope) -> Boolean) {
        realtimeSend = sender
    }

    fun startCall(callType: String) {
        startCall(_ui.value.selectedConversation ?: return, callType)
    }

    fun startCall(conversation: ChatConversation, callType: String) {
        val appContext = context ?: return
        if (conversation.isGroup) {
            startGroupCall(conversation, callType)
            return
        }
        app.aino.mobile.core.call.ActiveCallRuntime.get(appContext).startOutgoing(
            conversationId = conversation.id,
            callType = callType,
            peerName = conversation.title(),
            peerAvatar = conversation.avatar(),
            peerUserId = conversation.otherUserId,
        )?.let { _ui.value = _ui.value.copy(error = it) }
    }

    /** Web `startGroupCall`: a huddle meeting, then `/huddle/:code` (members are rung by the server). */
    private fun startGroupCall(conversation: ChatConversation, callType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.startGroupCall(conversation.id, conversation.groupName, callType) }.fold(
                onSuccess = { app.aino.mobile.core.navigation.RouteRequests.open(app.aino.mobile.core.navigation.huddleRoute(it.meetingCode)) },
                onFailure = { _ui.value = _ui.value.copy(error = "Could not start the group call. Please try again.") },
            )
        }
    }

    fun setScope(tenantId: Long?, userId: Long?) {
        val next = if (tenantId != null && tenantId > 0 && userId != null && userId > 0) {
            CacheScope(tenantId, userId)
        } else null
        if (next == scope) return
        scope = next
        cache = next?.let(cacheFactory)
        _ui.value = ChatUiState(currentUserId = next?.userId)
        if (next != null) refresh()
    }

    fun refresh() {
        val scopedCache = cache ?: return
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            if (_ui.value.conversations.isEmpty()) {
                warm?.let { runCatching(it::loadConversations).getOrNull() }?.takeIf { it.isNotEmpty() }?.let { cached ->
                    if (_ui.value.conversations.isEmpty()) _ui.value = _ui.value.copy(conversations = zeroUnread(cached, visibleConversationId()))
                }
            }
            runCatching(repository::loadConversations).fold(
                onSuccess = { conversations ->
                    scopedCache.replace(conversations)
                    val userIds = conversations.mapNotNull { it.otherUserId }.distinct()
                    val presence = runCatching { repository.loadPresence(userIds) }.getOrDefault(emptyMap())
                    _ui.value = _ui.value.copy(
                        loading = false,
                        conversations = zeroUnread(conversations, visibleConversationId()),
                        presence = presence,
                        fromCache = false,
                    )
                },
                onFailure = { error ->
                    // Offline first: keep the exact scoped cache visible, while
                    // reporting that freshness/presence are unavailable.
                    val cached = runCatching { scopedCache.snapshot() }.getOrDefault(emptyList())
                    _ui.value = _ui.value.copy(
                        loading = false,
                        conversations = zeroUnread(cached, visibleConversationId()),
                        presence = emptyMap(),
                        fromCache = cached.isNotEmpty(),
                        error = if (cached.isEmpty()) error.message ?: "Could not load conversations" else null,
                        message = if (cached.isNotEmpty()) "Offline · showing cached conversations" else null,
                    )
                },
            )
        }
    }

    fun onRealtimeEvent(event: RoutedRealtimeEvent) {
        when (event.event) {
            RealtimeEvent.ChatTyping -> {
                val typing = decodeChatRealtime<ChatTypingEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id != typing.conversationId || typing.userId == scope?.userId) return
                _ui.value = _ui.value.copy(typingUserId = typing.userId)
                typingExpiry?.cancel()
                typingExpiry = viewModelScope.launch {
                    delay(3_000)
                    if (_ui.value.typingUserId == typing.userId) _ui.value = _ui.value.copy(typingUserId = null)
                }
                return
            }
            RealtimeEvent.ChatReadReceipt -> {
                val receipt = decodeChatRealtime<ChatReadReceiptEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == receipt.conversationId) {
                    _ui.value = _ui.value.copy(receipts = applyRealtimeReceipt(_ui.value.receipts, receipt))
                }
                return
            }
            RealtimeEvent.ChatReaction -> {
                val reaction = decodeChatRealtime<ChatReactionEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == reaction.conversationId) {
                    _ui.value = _ui.value.copy(messages = applyRealtimeReaction(_ui.value.messages, reaction))
                }
                // Reconcile after the immediate patch, matching web behavior.
            }
            RealtimeEvent.ChatEdit -> {
                val edit = decodeChatRealtime<ChatEditEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == edit.conversationId) {
                    _ui.value = _ui.value.copy(messages = applyRealtimeEdit(_ui.value.messages, edit))
                }
                return
            }
            RealtimeEvent.ChatDelete -> {
                val delete = decodeChatRealtime<ChatDeleteEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == delete.conversationId) {
                    _ui.value = _ui.value.copy(
                        messages = applyRealtimeDelete(_ui.value.messages, delete),
                        editingMessage = _ui.value.editingMessage?.takeUnless { it.id == delete.messageId },
                        infoContent = pruneSharedFiles(_ui.value.infoContent, setOf(delete.messageId)),
                    )
                }
                return
            }
            RealtimeEvent.ChatMessageDelivered -> {
                val delivered = decodeChatRealtime<ChatDeliveredEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == delivered.conversationId) {
                    _ui.value = _ui.value.copy(messages = applyDelivered(_ui.value.messages, delivered))
                }
                return
            }
            RealtimeEvent.ChatViewOnce -> {
                val viewed = decodeChatRealtime<ChatViewOnceEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == viewed.conversationId) {
                    _ui.value = _ui.value.copy(messages = applyViewOnce(_ui.value.messages, viewed))
                }
                return
            }
            RealtimeEvent.ChatPin -> {
                val pin = decodeChatRealtime<ChatPinEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == pin.conversationId) {
                    val messages = applyRealtimePin(_ui.value.messages, pin)
                    _ui.value = _ui.value.copy(messages = messages, pinnedMessages = updatePinnedList(_ui.value.pinnedMessages, messages, pin.messageId, pin.pinned))
                }
                return
            }
            RealtimeEvent.ChatMessage -> {
                // Patch immediately (web `reconcileOwnMessage`): the echo carries
                // clientMsgId, so the optimistic bubble is swapped in place instead
                // of sitting next to the persisted row until the thread reloads.
                val incoming = decodeChatRealtime<ChatRealtimeMessage>(event.data)
                incoming?.toChatMessage()?.let { received -> acknowledgeDelivery(listOf(received)) }
                if (incoming != null && _ui.value.selectedConversation?.id == incoming.conversationId) {
                    val message = incoming.toChatMessage()
                    val state = _ui.value
                    echoedPendingMedia(state.pendingMedia, message, scope?.userId)?.let {
                        ChatMediaMemory.rememberSent(message.fileUrl, it.uri, it.aspect())
                    }
                    val messages = mergeIncomingMessage(state.messages, message)
                    _ui.value = state.copy(
                        messages = messages,
                        queuedMessages = reconcileQueuedMessages(state.queuedMessages, messages, scope?.userId),
                        pendingMedia = dropEchoedPendingMedia(state.pendingMedia, message, scope?.userId),
                        typingUserId = state.typingUserId.takeUnless { it == message.senderId },
                    )
                    // Read live, as Signal does for the thread on screen.
                    if (message.senderId != scope?.userId && visibleConversationId() == incoming.conversationId) {
                        scheduleMarkRead(incoming.conversationId)
                    }
                }
            }
            else -> Unit
        }
        if (!shouldRefreshConversationList(event.type)) return
        scheduleRefresh()
    }

    /**
     * Reconnect replay and multi-device fan-out can deliver a burst of
     * equivalent invalidations. One authoritative refresh after a short
     * coalescing window is enough and avoids overlapping REST/cache writes.
     */
    private fun scheduleRefresh() {
        realtimeRefresh?.cancel()
        realtimeRefresh = viewModelScope.launch {
            delay(150)
            refresh()
            if (_ui.value.selectedConversation != null) refreshThread()
        }
    }

    /** Delivery receipts for messages that reached this device (see [DeliveryReceipts]). */
    private fun acknowledgeDelivery(messages: List<ChatMessage>) {
        val ids = DeliveryReceipts.pending(messages, scope?.userId).takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch(Dispatchers.IO) { DeliveryReceipts.send(repository, ids) }
    }

    /** The open thread, but only while its screen is resumed. */
    private fun visibleConversationId(): Long? =
        _ui.value.selectedConversation?.id?.takeIf(app.aino.mobile.core.push.VisibleThread::isVisible)

    fun updateUserSearch(value: String) {
        _ui.value = _ui.value.copy(userSearch = value, error = null)
        if (value.trim().length < 2) _ui.value = _ui.value.copy(userResults = emptyList())
    }

    fun searchUsers() {
        val query = _ui.value.userSearch.trim()
        if (query.length < 2) {
            _ui.value = _ui.value.copy(error = "Enter at least 2 characters")
            return
        }
        _ui.value = _ui.value.copy(searching = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.searchUsers(query) }.fold(
                onSuccess = { _ui.value = _ui.value.copy(searching = false, userResults = it) },
                onFailure = { _ui.value = _ui.value.copy(searching = false, error = it.message ?: "User search failed") },
            )
        }
    }

    fun startDirect(user: ChatUser) {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createDirect(user.id) }.fold(
                onSuccess = { created ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        userSearch = "",
                        userResults = emptyList(),
                        // Web opens the conversation straight away.
                        openConversationId = created.conversationId,
                    )
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not start conversation") },
            )
        }
    }

    fun markRead(conversation: ChatConversation) {
        if (conversation.unreadCount == 0) return
        val original = _ui.value.conversations
        _ui.value = _ui.value.copy(
            conversations = original.map { if (it.id == conversation.id) it.copy(unreadCount = 0) else it },
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.markRead(conversation.id) }.fold(
                onSuccess = { refresh() },
                onFailure = {
                    _ui.value = _ui.value.copy(conversations = original, error = it.message ?: "Could not mark conversation read")
                },
            )
        }
    }

    fun toggleConversationSelection(conversationId: Long) {
        val selected = _ui.value.selectedConversationIds
        _ui.value = _ui.value.copy(
            selectedConversationIds = if (conversationId in selected) selected - conversationId else selected + conversationId,
        )
    }

    fun selectAll(conversationIds: Collection<Long>) {
        val ids = conversationIds.toSet()
        _ui.value = _ui.value.copy(
            selectedConversationIds = if (ids.isNotEmpty() && ids.all(_ui.value.selectedConversationIds::contains)) emptySet() else ids,
        )
    }

    fun cancelConversationSelection() {
        _ui.value = _ui.value.copy(selectedConversationIds = emptySet())
    }

    fun deleteSelectedConversations() {
        val ids = _ui.value.selectedConversationIds
        if (ids.isEmpty() || _ui.value.deletingConversations) return
        _ui.value = _ui.value.copy(deletingConversations = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val failed = ids.filter { id -> runCatching { repository.deleteConversation(id) }.isFailure }
            _ui.value = _ui.value.copy(
                deletingConversations = false,
                selectedConversationIds = failed.toSet(),
                conversations = _ui.value.conversations.filterNot { it.id in ids && it.id !in failed },
                message = if (failed.isEmpty()) "${ids.size} conversation${if (ids.size == 1) "" else "s"} deleted" else null,
                error = if (failed.isNotEmpty()) "Could not delete ${failed.size} selected conversation${if (failed.size == 1) "" else "s"}" else null,
            )
        }
    }

    fun openConversation(conversation: ChatConversation) {
        _ui.value = _ui.value.copy(
            selectedConversation = conversation,
            messages = emptyList(),
            queuedMessages = emptyList(),
            receipts = emptyList(),
            composer = loadDraft(conversation.id),
            composerLinkPreview = null,
            dismissedPreviewUrl = null,
            selectedMessageIds = emptySet(),
            hiddenMessageIds = loadHidden(conversation.id),
            jumpToMessageId = pendingJump?.takeIf { it.first == conversation.id }?.second,
            threadSearchOpen = false,
            threadSearchQuery = "",
            threadSearchMatches = emptyList(),
            threadSearchIndex = -1,
            unreadAtOpen = conversation.unreadCount.coerceAtLeast(0),
            editingMessage = null,
            replyingTo = null,
            hasOlderMessages = true,
            forwardingMessage = null,
            forwardQuery = "",
            forwardTargets = emptySet(),
            forwarding = false,
            error = null,
        )
        pendingJump = null
        // Opening a thread clears its system notification (Signal behaviour);
        // the screen's ON_RESUME (onThreadVisible) coalesces into the same read mark.
        context?.let { app.aino.mobile.core.push.ChatNotifications.cancel(it, conversation.id) }
        scheduleMarkRead(conversation.id)
        refreshThread()
        refreshPinned(conversation.id)
        // Members feed @mention suggestions (web MentionInput uses convMembers).
        viewModelScope.launch(Dispatchers.IO) {
            val members = runCatching { repository.loadMembers(conversation.id) }.getOrNull() ?: return@launch
            if (_ui.value.selectedConversation?.id == conversation.id) _ui.value = _ui.value.copy(members = members)
        }
        if (pendingSettings == conversation.id) { pendingSettings = null; openInfo() }
    }

    /** Recipient sheet "Chat settings": open the thread straight onto its info page. */
    fun openSettingsOnOpen(conversationId: Long) { pendingSettings = conversationId }

    fun closeConversation() {
        typingExpiry?.cancel()
        typingDispatch?.cancel()
        _ui.value.selectedConversation?.let { app.aino.mobile.core.push.VisibleThread.clear(it.id) }
        _ui.value = _ui.value.copy(
            selectedConversation = null,
            messages = emptyList(),
            queuedMessages = emptyList(),
            pinnedMessages = emptyList(),
            receipts = emptyList(),
            typingUserId = null,
            composer = "",
            editingMessage = null,
            replyingTo = null,
            forwardingMessage = null,
            forwardQuery = "",
            forwardTargets = emptySet(),
            forwarding = false,
            threadFromCache = false,
        )
    }

    fun updateComposer(value: String) {
        _ui.value = _ui.value.copy(composer = value.take(5_000), error = null)
        val conversationId = _ui.value.selectedConversation?.id ?: return
        if (_ui.value.editingMessage == null) saveDraft(conversationId, _ui.value.composer)
        scheduleLinkPreview(_ui.value.composer)
        // Match web exactly: reset on every change, then emit after 200 ms of
        // inactivity. Typing is fire-and-forget and never causes a refetch.
        typingDispatch?.cancel()
        typingDispatch = viewModelScope.launch {
            delay(200)
            realtimeSend(typingEnvelope(conversationId))
        }
    }

    fun refreshThread() {
        val conversation = _ui.value.selectedConversation ?: return
        val scopedCache = cache ?: return
        _ui.value = _ui.value.copy(threadLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            if (_ui.value.messages.isEmpty()) {
                warm?.let { runCatching { it.loadMessages(conversation.id) }.getOrNull() }?.takeIf { it.isNotEmpty() }?.let { cached ->
                    if (_ui.value.selectedConversation?.id == conversation.id && _ui.value.messages.isEmpty()) {
                        _ui.value = _ui.value.copy(messages = cached, hasOlderMessages = cached.size >= 50)
                    }
                }
            }
            runCatching { repository.loadMessages(conversation.id) }.fold(
                onSuccess = { messages ->
                    scopedCache.replaceMessages(conversation.id, messages)
                    val receipts = runCatching { repository.loadReadReceipts(conversation.id) }.getOrDefault(emptyList())
                    _ui.value = _ui.value.copy(
                        threadLoading = false,
                        messages = messages,
                        hasOlderMessages = messages.size >= 50,
                        // REST rows omit clientMsgId. Reconcile one-to-one by
                        // own-message chronology rather than deleting every
                        // queued bubble when a single echo appears.
                        queuedMessages = reconcileQueuedMessages(_ui.value.queuedMessages, messages, scope?.userId),
                        receipts = receipts,
                        threadFromCache = false,
                    )
                    restoreDraftTargets(conversation.id, messages)
                    acknowledgeDelivery(messages.takeLast(50))
                },
                onFailure = { error ->
                    val cached = runCatching { scopedCache.messageSnapshot(conversation.id) }.getOrDefault(emptyList())
                    _ui.value = _ui.value.copy(
                        threadLoading = false,
                        messages = cached,
                        receipts = emptyList(),
                        threadFromCache = cached.isNotEmpty(),
                        error = if (cached.isEmpty()) error.message ?: "Could not load messages" else null,
                    )
                },
            )
        }
    }

    fun loadOlderMessages() {
        val conversation = _ui.value.selectedConversation ?: return
        val oldestId = _ui.value.messages.minOfOrNull(ChatMessage::id) ?: return
        if (_ui.value.loadingOlder || !_ui.value.hasOlderMessages) return
        _ui.value = _ui.value.copy(loadingOlder = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadMessages(conversation.id, oldestId) }.fold(
                onSuccess = { older ->
                    val merged = (older + _ui.value.messages).distinctBy(ChatMessage::id)
                    _ui.value = _ui.value.copy(
                        loadingOlder = false,
                        messages = merged,
                        hasOlderMessages = older.size >= 50 && merged.size > _ui.value.messages.size,
                    )
                    cache?.replaceMessages(conversation.id, merged)
                },
                onFailure = { _ui.value = _ui.value.copy(loadingOlder = false, error = it.message ?: "Could not load older messages") },
            )
        }
    }

    fun sendMessage() {
        val editing = _ui.value.editingMessage
        if (editing != null) {
            submitEdit(editing)
            return
        }
        val currentScope = scope ?: return
        val conversation = _ui.value.selectedConversation ?: return
        val content = _ui.value.composer.trim()
        if (content.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Message content is required")
            return
        }
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val replyToId = _ui.value.replyingTo?.id
        val preview = _ui.value.composerLinkPreview?.takeIf { it.url == firstLinkIn(content) }
        val mentions = _ui.value.members.filter { it.id in mentionedIds && content.contains("@" + it.display()) }.map { it.id }
        val queued = QueuedMessage(clientId, conversation.id, currentScope.userId, content, now)
        _ui.value = _ui.value.copy(
            composer = "", replyingTo = null, composerLinkPreview = null, dismissedPreviewUrl = null,
            queuedMessages = _ui.value.queuedMessages + queued, error = null,
        )
        saveDraft(conversation.id, "")
        saveDraftTarget("reply", null)
        mentionedIds.clear()
        linkPreviewJob?.cancel()
        context?.let(app.aino.mobile.core.notifications.NotificationSoundPrefs::playSendConfirmation)
        // Mentions and link previews only travel on the web's WS `chat_message`
        // frame (the REST send route drops them). Use it when connected; plain
        // text keeps the durable offline outbox.
        if ((preview != null || mentions.isNotEmpty()) &&
            realtimeSend(chatMessageEnvelope(conversation.id, content, clientId, replyToId, mentions, preview))
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { enqueueText(currentScope, conversation.id, content, replyToId, now, clientId) }.onFailure {
                _ui.value = _ui.value.copy(
                    queuedMessages = _ui.value.queuedMessages.filterNot { it.clientMessageId == clientId },
                    composer = content,
                    error = it.message ?: "Could not queue message",
                )
            }
        }
    }

    /** Web `ChatInputBar`: debounce 600 ms, fetch OpenGraph for the first URL unless dismissed. */
    private fun scheduleLinkPreview(text: String) {
        val url = firstLinkIn(text)
        if (url == null) {
            linkPreviewJob?.cancel()
            _ui.value = _ui.value.copy(composerLinkPreview = null, dismissedPreviewUrl = null)
            return
        }
        if (url == _ui.value.dismissedPreviewUrl || _ui.value.composerLinkPreview?.url == url) return
        linkPreviewJob?.cancel()
        linkPreviewJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            val preview = runCatching { repository.loadLinkPreview(url) }.getOrNull()
            if (firstLinkIn(_ui.value.composer) == url) _ui.value = _ui.value.copy(composerLinkPreview = preview)
        }
    }

    fun dismissLinkPreview() {
        _ui.value = _ui.value.copy(dismissedPreviewUrl = _ui.value.composerLinkPreview?.url, composerLinkPreview = null)
    }

    /** Records a picked @mention so its user id is sent with the message (web `getMentionedIds`). */
    fun addMention(member: ConversationMember) {
        mentionedIds += member.id
        updateComposer(insertMention(_ui.value.composer, member))
    }

    fun beginReply(message: ChatMessage) {
        if (message.deletedAt != null) return
        _ui.value = _ui.value.copy(replyingTo = message, editingMessage = null, error = null)
        saveDraftTarget("reply", message.id)
        saveDraftTarget("edit", null)
    }

    fun cancelReply() {
        _ui.value = _ui.value.copy(replyingTo = null)
        saveDraftTarget("reply", null)
    }

    fun beginEdit(message: ChatMessage) {
        if (message.senderId != scope?.userId || message.deletedAt != null) return
        _ui.value = _ui.value.copy(editingMessage = message, replyingTo = null, composer = message.content.orEmpty(), error = null)
        saveDraftTarget("edit", message.id)
        saveDraftTarget("reply", null)
    }

    fun cancelEdit() {
        _ui.value = _ui.value.copy(editingMessage = null, composer = "", error = null)
        saveDraftTarget("edit", null)
    }

    private fun submitEdit(message: ChatMessage) {
        val content = _ui.value.composer.trim()
        if (content.isEmpty() || content.length > 5_000) {
            _ui.value = _ui.value.copy(error = "Edited message must be 1–5000 characters")
            return
        }
        saveDraftTarget("edit", null)
        val original = _ui.value.messages
        val optimistic = message.copy(content = content, editedAt = java.time.Instant.now().toString())
        _ui.value = _ui.value.copy(
            messages = original.map { if (it.id == message.id) optimistic else it },
            editingMessage = null,
            composer = "",
            error = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.editMessage(message.id, content) }.onFailure {
                _ui.value = _ui.value.copy(messages = original, editingMessage = message, composer = content, error = it.message ?: "Could not edit message")
            }
        }
    }

    fun deleteMessage(message: ChatMessage) {
        if (message.senderId != scope?.userId || message.deletedAt != null) return
        val original = _ui.value.messages
        _ui.value = _ui.value.copy(
            messages = applyRealtimeDelete(original, ChatDeleteEvent(message.id, message.conversationId ?: _ui.value.selectedConversation?.id ?: return)),
            editingMessage = _ui.value.editingMessage?.takeUnless { it.id == message.id },
            infoContent = pruneSharedFiles(_ui.value.infoContent, setOf(message.id)),
            error = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteMessage(message.id) }.onFailure {
                _ui.value = _ui.value.copy(messages = original, error = it.message ?: "Could not delete message")
            }
        }
    }

    fun toggleStar(message: ChatMessage) {
        if (message.deletedAt != null) return
        val original = _ui.value.messages
        _ui.value = _ui.value.copy(messages = original.map { if (it.id == message.id) it.copy(starred = !it.starred) else it })
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleStar(message.id) }.fold(
                onSuccess = { result ->
                    _ui.value = _ui.value.copy(messages = _ui.value.messages.map { if (it.id == message.id) it.copy(starred = result.starred) else it })
                },
                onFailure = { _ui.value = _ui.value.copy(messages = original, error = it.message ?: "Could not update saved message") },
            )
        }
    }

    fun toggleMessagePin(message: ChatMessage) {
        if (message.deletedAt != null) return
        val conversationId = message.conversationId ?: _ui.value.selectedConversation?.id ?: return
        val original = _ui.value.messages
        val optimisticPinned = message.pinnedAt == null
        _ui.value = _ui.value.copy(
            messages = applyRealtimePin(
                original,
                ChatPinEvent(message.id, conversationId, optimisticPinned, scope?.userId),
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleMessagePin(message.id) }.fold(
                onSuccess = { response ->
                    val messages = applyRealtimePin(
                        _ui.value.messages,
                        ChatPinEvent(message.id, conversationId, response.pinned, scope?.userId),
                    )
                    _ui.value = _ui.value.copy(
                        messages = messages,
                        pinnedMessages = updatePinnedList(_ui.value.pinnedMessages, messages, message.id, response.pinned, message),
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(messages = original, error = it.message ?: "Could not update pinned message") },
            )
        }
    }

    fun beginForward(message: ChatMessage) {
        if (message.deletedAt != null) return
        _ui.value = _ui.value.copy(
            forwardingMessage = message,
            forwardQuery = "",
            forwardTargets = emptySet(),
            error = null,
        )
    }

    fun cancelForward() {
        if (_ui.value.forwarding) return
        _ui.value = _ui.value.copy(forwardingMessage = null, forwardQuery = "", forwardTargets = emptySet())
    }

    fun updateForwardQuery(value: String) {
        _ui.value = _ui.value.copy(forwardQuery = value.take(100), error = null)
    }

    fun toggleForwardTarget(conversationId: Long) {
        val current = _ui.value.forwardTargets
        if (conversationId !in current && current.size >= 20) {
            _ui.value = _ui.value.copy(error = "Choose no more than 20 conversations")
            return
        }
        _ui.value = _ui.value.copy(
            forwardTargets = if (conversationId in current) current - conversationId else current + conversationId,
            error = null,
        )
    }

    fun submitForward() {
        val message = _ui.value.forwardingMessage ?: return
        val targets = _ui.value.forwardTargets.toList()
        if (targets.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Choose at least one conversation")
            return
        }
        _ui.value = _ui.value.copy(forwarding = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.forwardMessage(message.id, targets) }.fold(
                onSuccess = {
                    val refreshCurrent = _ui.value.selectedConversation?.id in targets
                    _ui.value = _ui.value.copy(
                        forwarding = false,
                        forwardingMessage = null,
                        forwardQuery = "",
                        forwardTargets = emptySet(),
                        message = "Forwarded to ${targets.size} conversation${if (targets.size == 1) "" else "s"}",
                    )
                    refresh()
                    if (refreshCurrent) refreshThread()
                },
                onFailure = { _ui.value = _ui.value.copy(forwarding = false, error = it.message ?: "Could not forward message") },
            )
        }
    }

    fun react(message: ChatMessage, emoji: String) {
        if (message.deletedAt != null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleReaction(message.id, emoji) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not update reaction") },
            )
        }
    }

    fun createPoll(question: String, options: List<String>, multiSelect: Boolean) {
        val conversation = _ui.value.selectedConversation ?: return
        val cleaned = options.map(String::trim).filter(String::isNotEmpty)
        if (question.isBlank() || cleaned.size < 2) {
            _ui.value = _ui.value.copy(error = "A poll needs a question and at least two options")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createPoll(conversation.id, question.trim(), cleaned, multiSelect) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not create poll") },
            )
        }
    }

    fun votePoll(message: ChatMessage, optionIndex: Int) {
        val pollId = message.metadata?.jsonObject?.get("pollId")?.jsonPrimitive?.longOrNull ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.votePoll(pollId, optionIndex) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not update poll") },
            )
        }
    }

    // ---- Message selection (web Chat.tsx messageSelectionBar) ----

    fun enterMessageSelection(message: ChatMessage) {
        if (message.deletedAt != null) return
        _ui.value = _ui.value.copy(selectedMessageIds = setOf(message.id))
    }

    fun toggleMessageSelection(messageId: Long) {
        val current = _ui.value.selectedMessageIds
        _ui.value = _ui.value.copy(selectedMessageIds = if (messageId in current) current - messageId else current + messageId)
    }

    fun clearMessageSelection() { _ui.value = _ui.value.copy(selectedMessageIds = emptySet()) }

    private fun selectedMessages(): List<ChatMessage> =
        _ui.value.messages.filter { it.id in _ui.value.selectedMessageIds }

    /** Web `copySelectedMessages`: non-empty contents joined by newlines. */
    fun selectedText(): String = selectedMessages().mapNotNull { it.content?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n")

    fun pinSelected() { selectedMessages().forEach(::toggleMessagePin); clearMessageSelection() }
    fun starSelected() { selectedMessages().forEach(::toggleStar); clearMessageSelection() }
    fun deleteSelectedForEveryone() {
        val mine = selectedMessages().filter { it.senderId == scope?.userId }
        mine.forEach(::deleteMessage)
        clearMessageSelection()
    }

    fun deleteSelectedForMe() {
        val conversationId = _ui.value.selectedConversation?.id ?: return
        val hidden = _ui.value.hiddenMessageIds + _ui.value.selectedMessageIds
        saveHidden(conversationId, hidden)
        _ui.value = _ui.value.copy(hiddenMessageIds = hidden, selectedMessageIds = emptySet(), infoContent = pruneSharedFiles(_ui.value.infoContent, hidden))
    }

    private fun hiddenKey(conversationId: Long): String? = draftKey(conversationId)?.replace(":draft:", ":hidden:")

    private fun loadHidden(conversationId: Long): Set<Long> {
        val key = hiddenKey(conversationId) ?: return emptySet()
        return context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)?.getStringSet(key, emptySet())
            .orEmpty().mapNotNull(String::toLongOrNull).toSet()
    }

    private fun saveHidden(conversationId: Long, ids: Set<Long>) {
        val key = hiddenKey(conversationId) ?: return
        context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putStringSet(key, ids.map(Long::toString).toSet())?.apply()
    }

    /** Pinned/saved/search result tapped: scroll to it here, or open its conversation. */
    fun jumpToMessage(message: ChatMessage) {
        val currentId = _ui.value.selectedConversation?.id
        if (message.conversationId == null || message.conversationId == currentId) {
            _ui.value = _ui.value.copy(showInfo = false, jumpToMessageId = message.id)
        } else {
            _ui.value = _ui.value.copy(showInfo = false, openConversationId = message.conversationId)
        }
    }

    fun consumeJump() { _ui.value = _ui.value.copy(jumpToMessageId = null) }

    fun unstarFromList(message: ChatMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleStar(message.id) }.onSuccess {
                val content = _ui.value.infoContent as? InfoContent.Messages ?: return@onSuccess
                _ui.value = _ui.value.copy(infoContent = InfoContent.Messages(content.messages.filterNot { it.id == message.id }))
            }
        }
    }

    /** Web `useConversationDraft`: text drafts keyed `chat:v2:draft:{tenant}:{user}:{conversation}`. */
    private fun draftKey(conversationId: Long): String? =
        scope?.let { "chat:v2:draft:${it.tenantId}:${it.userId}:$conversationId" }

    private fun loadDraft(conversationId: Long): String {
        val key = draftKey(conversationId) ?: return ""
        return context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)?.getString(key, "").orEmpty()
    }

    private fun saveDraft(conversationId: Long, text: String) {
        val key = draftKey(conversationId) ?: return
        context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)?.edit()?.apply {
            if (text.isBlank()) remove(key) else putString(key, text)
        }?.apply()
    }

    /** Web `StoredDraft.replyTo` / `editing`: which message the draft replies to or edits (0 = none). */
    private fun saveDraftTarget(suffix: String, messageId: Long?) {
        val conversationId = _ui.value.selectedConversation?.id ?: return
        val key = (draftKey(conversationId) ?: return) + ":$suffix"
        context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)?.edit()?.apply {
            if (messageId == null) remove(key) else putLong(key, messageId)
        }?.apply()
    }

    private fun loadDraftTarget(conversationId: Long, suffix: String): Long? {
        val key = (draftKey(conversationId) ?: return null) + ":$suffix"
        return context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)?.getLong(key, 0L)?.takeIf { it > 0 }
    }

    /** After the thread loads, re-attach a saved reply/edit target if that message still exists. */
    private fun restoreDraftTargets(conversationId: Long, messages: List<ChatMessage>) {
        if (_ui.value.replyingTo != null || _ui.value.editingMessage != null) return
        val edit = loadDraftTarget(conversationId, "edit")?.let { id -> messages.firstOrNull { it.id == id && it.deletedAt == null } }
        val reply = loadDraftTarget(conversationId, "reply")?.let { id -> messages.firstOrNull { it.id == id && it.deletedAt == null } }
        when {
            edit != null -> _ui.value = _ui.value.copy(editingMessage = edit, composer = _ui.value.composer.ifBlank { edit.content.orEmpty() })
            reply != null -> _ui.value = _ui.value.copy(replyingTo = reply)
        }
    }

    fun stageAttachment(uri: Uri) {
        val appContext = context ?: return
        val resolver = appContext.contentResolver
        var name = uri.lastPathSegment ?: "file"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        _ui.value = _ui.value.copy(pendingAttachment = PendingAttachment(uri, mime, name), error = null)
    }

    fun cancelAttachment() { _ui.value = _ui.value.copy(pendingAttachment = null) }

    fun sendAttachment(caption: String) {
        val pending = _ui.value.pendingAttachment ?: return
        _ui.value = _ui.value.copy(pendingAttachment = null, composer = caption)
        upload(pending.uri, mimeOverride = pending.mimeType)
    }

    /** Voice notes: FileProvider's `.m4a` MIME varies by OS version, so pin the server-allowed type. */
    fun uploadVoiceNote(uri: Uri) = upload(uri, mimeOverride = "audio/mp4", keepComposer = true)

    fun upload(uri: Uri, mimeOverride: String? = null, keepComposer: Boolean = false) =
        uploadAll(listOf(MediaUploadSpec(uri, mimeOverride)), caption = if (keepComposer) null else _ui.value.composer.trim().ifBlank { null }, clearComposer = !keepComposer)

    /** Signal media send: items upload in order, caption rides on the first (server has no albums). */
    fun sendMedia(items: List<MediaUploadSpec>, caption: String, viewOnce: Boolean, highQuality: Boolean) {
        if (items.isEmpty()) return
        uploadAll(items, caption.trim().ifBlank { null }?.takeUnless { viewOnce }, clearComposer = false, viewOnce = viewOnce, quality = if (highQuality) "hd" else "standard")
    }

    private fun uploadAll(
        items: List<MediaUploadSpec>,
        caption: String?,
        clearComposer: Boolean,
        viewOnce: Boolean = false,
        quality: String? = null,
    ) {
        val conversation = _ui.value.selectedConversation ?: return
        val appContext = context ?: return
        val resolver = appContext.contentResolver
        val now = System.currentTimeMillis()
        // Signal: every item gets its own outgoing bubble immediately, in send order.
        val pending = items.mapIndexed { index, item ->
            var name = item.uri.lastPathSegment ?: "file"
            runCatching {
                resolver.query(item.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
            }
            val mime = item.mimeType ?: resolver.getType(item.uri) ?: "application/octet-stream"
            PendingMedia(
                localId = "media-${UUID.randomUUID()}", conversationId = conversation.id,
                uri = item.uri, mimeType = mime, fileName = name,
                caption = caption.takeIf { index == 0 }, createdAtEpochMs = now + index,
                width = item.width, height = item.height,
            )
        }
        pending.forEach { pendingOptions[it.localId] = UploadOptions(viewOnce, quality) }
        _ui.value = _ui.value.copy(
            pendingMedia = _ui.value.pendingMedia + pending,
            composer = if (clearComposer) "" else _ui.value.composer,
            error = null,
        )
        if (clearComposer) saveDraft(conversation.id, "")
        context?.let(app.aino.mobile.core.notifications.NotificationSoundPrefs::playSendConfirmation)
        startUploads(pending)
    }

    private data class UploadOptions(val viewOnce: Boolean, val quality: String?)
    private val pendingOptions = java.util.concurrent.ConcurrentHashMap<String, UploadOptions>()

    /** Uploads run sequentially (server has no albums), one bubble at a time. */
    private fun startUploads(queue: List<PendingMedia>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (media in queue) {
                if (_ui.value.pendingMedia.none { it.localId == media.localId }) continue // cancelled
                uploadOne(media)
            }
        }
    }

    private fun updatePending(localId: String, transform: (PendingMedia) -> PendingMedia) {
        _ui.value = _ui.value.copy(pendingMedia = _ui.value.pendingMedia.map { if (it.localId == localId) transform(it) else it })
    }

    /** Signal ✕ on the progress ring: removes the bubble (an in-flight request may still land server-side). */
    fun cancelPendingMedia(localId: String) {
        pendingOptions.remove(localId)
        _ui.value = _ui.value.copy(pendingMedia = _ui.value.pendingMedia.filterNot { it.localId == localId })
    }

    fun retryPendingMedia(localId: String) {
        val media = _ui.value.pendingMedia.firstOrNull { it.localId == localId && it.state == PendingMediaState.Failed } ?: return
        startUploads(listOf(media))
    }

    private fun uploadOne(media: PendingMedia) {
        val appContext = context ?: return
        val options = pendingOptions[media.localId] ?: UploadOptions(false, null)
        updatePending(media.localId) { it.copy(state = PendingMediaState.Uploading, progress = null, error = null) }
        runCatching {
            val resolver = appContext.contentResolver
            var size = -1L
            resolver.query(media.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0)
            }
            if (size > MAX_CHAT_FILE_BYTES) throw IllegalArgumentException("Files must be 25 MB or smaller")
            val bytes = resolver.openInputStream(media.uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_CHAT_FILE_BYTES) throw IllegalArgumentException("Files must be 25 MB or smaller")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: throw IllegalArgumentException("Could not read the selected file")
            validateChatUpload(media.fileName, media.mimeType, bytes.size.toLong())?.let { throw IllegalArgumentException(it) }
            val visual = media.isImage || media.isVideo
            repository.uploadFile(
                media.conversationId,
                ChatUpload(
                    media.fileName, media.mimeType, bytes, media.caption,
                    viewOnce = options.viewOnce && visual,
                    quality = options.quality.takeIf { visual },
                    width = media.width, height = media.height,
                ),
            ) { sent, total ->
                if (total > 0) updatePending(media.localId) { it.copy(progress = (sent.toFloat() / total).coerceIn(0f, 1f)) }
            }
        }.fold(
            onSuccess = { uploaded ->
                if (_ui.value.pendingMedia.none { it.localId == media.localId }) return // cancelled mid-flight
                pendingOptions.remove(media.localId)
                val message = uploaded.copy(conversationId = uploaded.conversationId ?: media.conversationId)
                ChatMediaMemory.rememberSent(message.fileUrl, media.uri, media.aspect())
                val state = _ui.value
                val inThread = state.selectedConversation?.id == media.conversationId
                _ui.value = state.copy(
                    // Swap the local bubble for the persisted row in one state update.
                    messages = if (inThread) mergeIncomingMessage(state.messages, message) else state.messages,
                    pendingMedia = state.pendingMedia.filterNot { it.localId == media.localId },
                )
                refresh()
            },
            onFailure = { error ->
                updatePending(media.localId) { it.copy(state = PendingMediaState.Failed, progress = null, error = error.message ?: "Upload failed") }
            },
        )
    }

    /** View-once open: the server claims the single allowed view and returns the URL (null once consumed). */
    fun openViewOnce(message: ChatMessage, onUrl: (String?) -> Unit) {
        val me = scope?.userId ?: return
        if (message.senderId == me) return // Senders can't re-open their own view-once media.
        viewModelScope.launch(Dispatchers.IO) {
            val url = runCatching { repository.viewMessage(message.id) }.getOrNull()?.fileUrl
            val event = ChatViewOnceEvent(message.id, message.conversationId ?: 0, me)
            _ui.value = _ui.value.copy(messages = applyViewOnce(_ui.value.messages, event))
            kotlinx.coroutines.withContext(Dispatchers.Main) { onUrl(url) }
        }
    }

    /** Signal "Delete for me" on a single message (local hide, web `chatLocalDeletes`). */
    fun deleteForMe(message: ChatMessage) {
        val conversationId = _ui.value.selectedConversation?.id ?: return
        val hidden = _ui.value.hiddenMessageIds + message.id
        saveHidden(conversationId, hidden)
        _ui.value = _ui.value.copy(hiddenMessageIds = hidden, infoContent = pruneSharedFiles(_ui.value.infoContent, hidden))
    }

    // ---- Signal in-chat search: toolbar field + "x of y" stepping ----
    fun openThreadSearch() { _ui.value = _ui.value.copy(threadSearchOpen = true) }

    fun closeThreadSearch() {
        threadSearchJob?.cancel()
        _ui.value = _ui.value.copy(threadSearchOpen = false, threadSearchQuery = "", threadSearchMatches = emptyList(), threadSearchIndex = -1)
    }

    fun updateThreadSearch(term: String) {
        _ui.value = _ui.value.copy(threadSearchQuery = term)
        threadSearchJob?.cancel()
        val conversation = _ui.value.selectedConversation ?: return
        if (term.trim().length < 2) {
            _ui.value = _ui.value.copy(threadSearchMatches = emptyList(), threadSearchIndex = -1)
            return
        }
        threadSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            runCatching { repository.searchMessages(term.trim(), conversation.id) }.onSuccess { found ->
                val newestFirst = found.filter { it.id !in _ui.value.hiddenMessageIds }.sortedByDescending { it.createdAt }.map { it.id }
                _ui.value = _ui.value.copy(
                    threadSearchMatches = newestFirst,
                    threadSearchIndex = if (newestFirst.isEmpty()) -1 else 0,
                    jumpToMessageId = newestFirst.firstOrNull(),
                )
            }
        }
    }

    /** delta = +1 walks to older matches (Signal "up"), -1 to newer. */
    fun stepThreadSearch(delta: Int) {
        val state = _ui.value
        val next = stepSearchMatch(state.threadSearchIndex, state.threadSearchMatches.size, delta)
        if (next < 0) return
        _ui.value = state.copy(threadSearchIndex = next, jumpToMessageId = state.threadSearchMatches[next])
    }

    // ---- Signal list search: chats + contacts + messages sections ----
    fun searchAllMessages(term: String) {
        globalSearchJob?.cancel()
        if (term.trim().length < 2) { _ui.value = _ui.value.copy(messageResults = emptyList()); return }
        globalSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            runCatching { repository.searchMessages(term.trim()) }.onSuccess { _ui.value = _ui.value.copy(messageResults = it) }
        }
    }

    /** Remember a list-search hit so the thread scrolls to it once opened (by id route or directly). */
    fun rememberJump(message: ChatMessage) {
        pendingJump = message.conversationId?.let { it to message.id }
    }
    fun cancelMedia(message: ChatMessage) {
        val id = message.mediaJobId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.cancelMediaJob(id) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not cancel media") },
            )
        }
    }

    fun retryMedia(message: ChatMessage) {
        val id = message.mediaJobId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.retryMediaJob(id) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not retry media") },
            )
        }
    }

    fun loadCalls() {
        if (_ui.value.callsLoading) return
        _ui.value = _ui.value.copy(callsLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(repository::loadCalls).fold(
                onSuccess = { _ui.value = _ui.value.copy(callsLoading = false, calls = it) },
                onFailure = { _ui.value = _ui.value.copy(callsLoading = false, error = it.message ?: "Could not load calls") },
            )
        }
    }

    fun toggleCallSelection(callId: Long) {
        val selected = _ui.value.selectedCallIds
        _ui.value = _ui.value.copy(
            selectedCallIds = if (callId in selected) selected - callId else selected + callId,
        )
    }

    fun selectAllCalls() {
        val ids = _ui.value.calls.map(CallLog::id).toSet()
        _ui.value = _ui.value.copy(
            selectedCallIds = if (ids.isNotEmpty() && ids.all(_ui.value.selectedCallIds::contains)) emptySet() else ids,
        )
    }

    fun cancelCallSelection() {
        _ui.value = _ui.value.copy(selectedCallIds = emptySet())
    }

    fun deleteSelectedCalls() {
        val ids = _ui.value.selectedCallIds
        if (ids.isEmpty() || _ui.value.deletingCalls) return
        _ui.value = _ui.value.copy(deletingCalls = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (ids.size == _ui.value.calls.size) repository.deleteAllCalls()
                else repository.deleteCalls(ids.toList())
            }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        deletingCalls = false,
                        selectedCallIds = emptySet(),
                        calls = _ui.value.calls.filterNot { it.id in ids },
                        message = "${it.deleted} call${if (it.deleted == 1) "" else "s"} deleted",
                    )
                },
                onFailure = {
                    _ui.value = _ui.value.copy(
                        deletingCalls = false,
                        error = it.message ?: "Could not delete call history",
                    )
                },
            )
        }
    }

    fun openInfo() {
        val conversation = _ui.value.selectedConversation ?: return
        _ui.value = _ui.value.copy(showInfo = true, conversationCalls = emptyList(), infoContent = null, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val members = runCatching { repository.loadMembers(conversation.id) }.getOrDefault(emptyList())
            val calls = runCatching { repository.loadConversationCalls(conversation.id) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(members = members, conversationCalls = calls)
        }
    }

    fun closeInfo() { _ui.value = _ui.value.copy(showInfo = false) }

    fun togglePin(conversation: ChatConversation? = _ui.value.selectedConversation) {
        val current = conversation ?: return
        updateConversation(
            current, current.copy(isPinned = !current.isPinned), "Could not update pin",
            request = { repository.togglePinConversation(current.id) },
            reconcile = { c, r -> c.copy(isPinned = r.pinned) },
            after = { refresh() },
        )
    }

    fun toggleFavourite() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleFavouriteConversation(current.id) }.fold(
                onSuccess = { updateSelected(current.copy(isFavourite = it.favourite)); refresh() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not update favourite") },
            )
        }
    }
    fun toggleMute() {
        val current = _ui.value.selectedConversation ?: return
        muteFor(current, if (current.isMuted) null else "always")
    }

    fun toggleArchive(conversation: ChatConversation? = _ui.value.selectedConversation, undoable: Boolean = false) {
        val current = conversation ?: return
        val archiving = !current.isArchived
        updateConversation(
            current, current.copy(isArchived = archiving), "Could not archive conversation",
            request = { repository.toggleArchive(current.id) },
            reconcile = { c, r -> c.copy(isArchived = r.archived) },
            after = {
                if (_ui.value.selectedConversation?.id == current.id) closeConversation()
                // Signal: "Chat archived" snackbar with Undo.
                if (undoable && archiving) _ui.value = _ui.value.copy(archiveUndo = current.copy(isArchived = true))
                refresh()
            },
        )
    }

    /** Signal snackbar Undo: puts the just-archived chat back. */
    fun undoArchive() {
        val archived = _ui.value.archiveUndo ?: return
        _ui.value = _ui.value.copy(archiveUndo = null)
        toggleArchive(archived)
    }

    fun dismissArchiveUndo() { _ui.value = _ui.value.copy(archiveUndo = null) }

    /** Signal list "Mark as unread". */
    fun markUnread(conversation: ChatConversation) {
        if (conversation.unreadCount > 0) return
        updateConversation(
            conversation, conversation.copy(unreadCount = 1), "Could not mark conversation unread",
            request = { repository.markUnread(conversation.id) },
        )
    }

    private fun updateSelected(conversation: ChatConversation) {
        _ui.value = _ui.value.copy(
            selectedConversation = conversation,
            conversations = _ui.value.conversations.map { if (it.id == conversation.id) conversation else it },
        )
    }

    /** Patches a conversation in the list and, when it is the open one, in the thread. */
    private fun patchConversation(conversation: ChatConversation) {
        val state = _ui.value
        _ui.value = state.copy(
            selectedConversation = state.selectedConversation?.let { if (it.id == conversation.id) conversation else it },
            conversations = state.conversations.map { if (it.id == conversation.id) conversation else it },
        )
    }

    /** Optimistic list action: show [optimistic] now, reconcile with the server, or roll back to [original]. */
    private fun <R> updateConversation(
        original: ChatConversation,
        optimistic: ChatConversation,
        failure: String,
        request: () -> R,
        reconcile: (ChatConversation, R) -> ChatConversation = { c, _ -> c },
        after: () -> Unit = {},
    ) {
        patchConversation(optimistic)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(request).fold(
                onSuccess = { patchConversation(reconcile(optimistic, it)); after() },
                onFailure = {
                    patchConversation(original)
                    _ui.value = _ui.value.copy(error = it.message ?: failure)
                },
            )
        }
    }

    // ---- Conversation info sub-pages (web ConversationInfoPanel entry points) ----

    private fun loadInfo(block: suspend (ChatConversation) -> InfoContent) {
        val conversation = _ui.value.selectedConversation ?: return
        _ui.value = _ui.value.copy(infoLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block(conversation) }.fold(
                onSuccess = { _ui.value = _ui.value.copy(infoLoading = false, infoContent = it) },
                onFailure = { _ui.value = _ui.value.copy(infoLoading = false, error = it.message ?: "Could not load") },
            )
        }
    }

    fun loadPinnedMessages() = loadInfo { InfoContent.Messages(repository.loadPinnedMessages(it.id)) }

    /** Signal pinned-message bar source; failures leave the bar hidden rather than erroring the thread. */
    private fun refreshPinned(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val pinned = runCatching { repository.loadPinnedMessages(conversationId) }.getOrNull() ?: return@launch
            if (_ui.value.selectedConversation?.id == conversationId) {
                _ui.value = _ui.value.copy(pinnedMessages = pinned.filter { it.deletedAt == null })
            }
        }
    }

    // ---- Signal overflow-menu actions ----
    fun muteFor(duration: String?) {
        muteFor(_ui.value.selectedConversation ?: return, duration)
    }

    /** Signal mute durations: 1h | 8h | 1d | 1w | always; null unmutes. */
    fun muteFor(conversation: ChatConversation, duration: String?) {
        updateConversation(
            conversation, conversation.copy(isMuted = duration != null), "Could not update mute",
            request = { repository.setMute(conversation.id, duration) },
            reconcile = { c, r -> c.copy(isMuted = r.muted) },
        )
    }

    fun deleteCurrentConversation() {
        deleteConversation(_ui.value.selectedConversation ?: return)
    }

    fun deleteConversation(conversation: ChatConversation) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteConversation(conversation.id) }.fold(
                onSuccess = {
                    val state = _ui.value
                    val wasOpen = state.selectedConversation?.id == conversation.id
                    _ui.value = state.copy(
                        conversations = state.conversations.filterNot { it.id == conversation.id },
                        closeThread = wasOpen || state.closeThread,
                        showInfo = if (wasOpen) false else state.showInfo,
                    )
                    context?.let { app.aino.mobile.core.push.ChatNotifications.cancel(it, conversation.id) }
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not delete chat") },
            )
        }
    }
    fun loadSavedMessages() = loadInfo { InfoContent.Messages(repository.loadStarredMessages()) }
    fun loadSharedFiles() = loadInfo { conversation ->
        pruneSharedFiles(InfoContent.Files(repository.loadSharedFiles(conversation.id)), _ui.value.hiddenMessageIds)!!
    }
    fun searchInConversation(term: String) {
        if (term.trim().length < 2) { _ui.value = _ui.value.copy(infoContent = InfoContent.Messages(emptyList())); return }
        loadInfo { InfoContent.Messages(repository.searchMessages(term.trim(), it.id)) }
    }

    fun toggleBlock() {
        toggleBlock(_ui.value.selectedConversation ?: return)
    }

    fun toggleBlock(conversation: ChatConversation) {
        val current = conversation
        val userId = current.otherUserId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { if (current.isBlocked) repository.unblockUser(userId) else repository.blockUser(userId) }.fold(
                onSuccess = { patchConversation(current.copy(isBlocked = it.blocked)); refresh() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not update block") },
            )
        }
    }

    fun clearChat() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.clearMessages(current.id) }.fold(
                onSuccess = { _ui.value = _ui.value.copy(messages = emptyList(), showInfo = false); refreshThread(); refresh() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not clear chat") },
            )
        }
    }

    fun leaveGroup() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.leaveGroup(current.id) }.fold(
                onSuccess = { closeConversation(); refresh(); _ui.value = _ui.value.copy(closeThread = true) },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not leave group") },
            )
        }
    }

    fun updateGroup(request: GroupUpdateRequest) {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.updateGroup(current.id, request) }.fold(
                onSuccess = {
                    request.name?.let { updateSelected(current.copy(groupName = it)) }
                    val members = runCatching { repository.loadMembers(current.id) }.getOrDefault(_ui.value.members)
                    _ui.value = _ui.value.copy(members = members)
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not update group") },
            )
        }
    }

    fun createGroup(name: String, userIds: List<Long>) {
        if (name.isBlank() || userIds.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Add a group name and at least one member")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createGroup(name.trim(), userIds) }.fold(
                onSuccess = { created -> refresh(); _ui.value = _ui.value.copy(openConversationId = created.conversationId) },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not create group") },
            )
        }
    }

    fun consumeNavigation() { _ui.value = _ui.value.copy(openConversationId = null, closeThread = false) }

    companion object {
        private const val DRAFT_PREFS = "aino_chat_drafts"

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                val tokens = container.tokens
                val api = container.api
                val dao = AinoDatabase.get(context).dao()
                val outbox = OutboxCoordinator(context.applicationContext)
                return ChatViewModel(
                    ChatRepository(api),
                    { scope -> ChatCache(scope, ScopedCache(scope, dao)) },
                    { scope, conversationId, content, replyToId, now, clientId ->
                        outbox.enqueueText(scope, conversationId, content, replyToId, nowEpochMs = now, clientMessageId = clientId)
                    },
                    warm = ChatRepository(container.cachedApi),
                ).also { it.context = context.applicationContext } as T
            }
        }
    }

    private var context: Context? = null
}