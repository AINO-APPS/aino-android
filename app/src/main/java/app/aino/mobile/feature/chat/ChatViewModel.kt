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
import app.aino.mobile.core.db.ChatOutbox
import app.aino.mobile.core.db.OutboxCoordinator
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import app.aino.mobile.core.network.userFacingMessage
import app.aino.mobile.core.realtime.RealtimeEvent
import app.aino.mobile.core.realtime.RealtimeEnvelope
import app.aino.mobile.core.realtime.RoutedRealtimeEvent
import app.aino.mobile.feature.chat.media.ContentMediaPreparer
import app.aino.mobile.feature.chat.media.MediaPrepRequest
import app.aino.mobile.feature.chat.media.MediaPreparer
import app.aino.mobile.feature.chat.media.PreparedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
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
    /** Local send order shared with queued text (see [ChatSendQueue]). */
    val sequence: Long = 0,
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

data class GroupCandidates(
    val query: String = "",
    val results: List<ChatUser> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
)

data class InviteSheetState(
    val token: String,
    val loading: Boolean = false,
    val preview: InvitePreview? = null,
    val joining: Boolean = false,
    val error: String? = null,
)

data class OlderMessagesFailure(val before: Long, val message: String)

private fun messageLoadFailure(error: Throwable, fallback: String): String = when (error) {
    is CancellationException -> throw error
    is ApiError -> userFacingMessage(error, fallback)
    is SerializationException -> "The server returned an unexpected message format. Try again."
    else -> fallback
}

data class ChatUiState(
    val currentUserId: Long? = null,
    /** Tenant plan `calls` (1:1 and group calls); web hides the call buttons when off. */
    val callsEnabled: Boolean = false,
    /** Loaded poll tallies by poll id, refreshed on `chat_poll_vote`. */
    val polls: Map<Long, ChatPoll> = emptyMap(),
    val loading: Boolean = false,
    val conversations: List<ChatConversation> = emptyList(),
    val presence: Map<Long, ChatPresence> = emptyMap(),
    val fromCache: Boolean = false,
    /** Wall-clock start of the last successful server list fetch; null while only cache is shown. */
    val syncedAtMs: Long? = null,
    val userSearch: String = "",
    val userResults: List<ChatUser> = emptyList(),
    val searching: Boolean = false,
    val selectedConversationIds: Set<Long> = emptySet(),
    /** List multi-select entered from the overflow menu, so it stays open with nothing picked yet. */
    val conversationSelectionMode: Boolean = false,
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
    val threadRefreshError: String? = null,
    val olderMessagesError: OlderMessagesFailure? = null,
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
    val callSelectionMode: Boolean = false,
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
    /** New-group flow: the create request (and optional photo upload) is in flight. */
    val creatingGroup: Boolean = false,
    /** The open group's running call (Join banner), or null. */
    val activeGroupCall: ActiveGroupCall? = null,
    /** A group invite link being previewed / joined. */
    val invite: InviteSheetState? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val unread: Int get() = totalUnread(conversations)
    val selectingConversations: Boolean get() = conversationSelectionMode || selectedConversationIds.isNotEmpty()
    val selectingCalls: Boolean get() = callSelectionMode || selectedCallIds.isNotEmpty()
}

/** One item of a Signal media send (camera, tray or gallery). */
data class MediaUploadSpec(val uri: Uri, val mimeType: String? = null, val width: Int? = null, val height: Int? = null)

class ChatViewModel(
    private val repository: ChatRepository,
    private val cacheFactory: (CacheScope) -> ChatCache,
    /** Durable backing for text sends (Room outbox + WorkManager fallback). */
    private val outbox: ChatOutbox = ChatOutbox.None,
    /** Same reads served from the last responses: lists and threads paint instantly. */
    private val warm: ChatRepository? = null,
    /** Compression / transcoding before upload; defaults to the content-resolver implementation. */
    private val mediaPreparer: MediaPreparer? = null,
    /** Drops a thread's warm (response-cache) page so a cleared/deleted chat never repaints from it. */
    private val forgetWarmThread: (Long) -> Unit = {},
    /** Conversations whose stored rows a push wake wrote (see [app.aino.mobile.core.push.PushSync]). */
    private val storedThreadUpdates: kotlinx.coroutines.flow.Flow<Long> = app.aino.mobile.core.push.PushSync.threadUpdates,
    /** Square-crops a picked group photo for upload; null when the image can't be used. */
    private val prepareGroupAvatar: (Uri) -> app.aino.mobile.core.media.PreparedAvatar? = { null },
    /** Pause before the single retry of a list load that failed at the transport (stale socket after resume). */
    private val listRetryDelayMs: Long = 750,
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
    /** A thread opened by id before the account scope was known (cold-start notification tap). */
    private var pendingOpen: OpenRequest? = null
    /** Open thread painted from a hint while its real list row is unknown. */
    private var placeholderId: Long? = null
    /** Chats deleted for this user while open: their closing thread is not kept in memory. */
    private val deletedThreads: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val mentionedIds = mutableSetOf<Long>()
    private var realtimeSend: (RealtimeEnvelope) -> Boolean = { false }
    private val markReadJobs = mutableMapOf<Long, Job>()
    private val threadCache = ThreadMessageCache()
    private val sendQueue = ChatSendQueue(viewModelScope)
    private val sendSequence = java.util.concurrent.atomic.AtomicLong(0)
    /** Signal compresses a couple of attachments at a time; more only fights over CPU. */
    private val prepSlots = kotlinx.coroutines.sync.Semaphore(2)
    private val prepJobs = java.util.concurrent.ConcurrentHashMap<String, Deferred<PreparedMedia>>()
    private val prewarmed = HashMap<PrepKey, Deferred<PreparedMedia>>()
    private val preparer: MediaPreparer? by lazy { mediaPreparer ?: context?.let { ContentMediaPreparer(it, MAX_CHAT_FILE_BYTES) } }

    private data class PrepKey(val uri: Uri, val quality: String?)

    private data class OpenRequest(val conversationId: Long, val hint: ConversationHint?, val jumpTo: Long?)

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
        // A push wake stored new rows: fold them into the open thread / its memory copy.
        viewModelScope.launch {
            storedThreadUpdates.collect { id -> mergeStoredThread(id) }
        }
    }

    private fun mergeStoredThread(conversationId: Long) {
        val scopedCache = cache ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val stored = runCatching { scopedCache.fullMessages(conversationId, limit = CACHED_THREAD_MESSAGES) }.getOrNull().orEmpty()
            if (stored.isEmpty()) return@launch
            _ui.update { st ->
                if (st.selectedConversation?.id != conversationId || st.messages.isEmpty()) st
                else st.copy(messages = mergeStoredRows(st.messages, stored))
            }
            if (!isOpen(conversationId)) {
                threadCache.put(conversationId, mergeStoredRows(threadCache.get(conversationId)?.messages.orEmpty(), stored))
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
        _ui.update { st -> st.copy(conversations = zeroUnread(st.conversations, conversationId)) }
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

    fun setCallsEnabled(enabled: Boolean) {
        if (_ui.value.callsEnabled != enabled) _ui.update { it.copy(callsEnabled = enabled) }
    }

    fun startCall(conversation: ChatConversation, callType: String) {
        if (!_ui.value.callsEnabled) return
        if (conversation.isGroup) {
            startGroupCall(conversation, callType)
            return
        }
        val appContext = context ?: return
        app.aino.mobile.core.call.ActiveCallRuntime.get(appContext).startOutgoing(
            conversationId = conversation.id,
            callType = callType,
            peerName = conversation.title(),
            peerAvatar = conversation.avatar(),
            peerUserId = conversation.otherUserId,
        )?.let { _ui.update { st -> st.copy(error = it) } }
    }

    /** Group calls open the lobby (preview, who's in, ring toggle); it starts the huddle or joins the running one. */
    private fun startGroupCall(conversation: ChatConversation, callType: String) {
        app.aino.mobile.core.navigation.RouteRequests.open(app.aino.mobile.core.navigation.groupCallLobbyRoute(conversation.id, callType))
    }

    fun setScope(tenantId: Long?, userId: Long?) {
        val next = if (tenantId != null && tenantId > 0 && userId != null && userId > 0) {
            CacheScope(tenantId, userId)
        } else null
        if (next == scope) return
        // In-flight texts of the previous account stop at their next attempt; its durable worker takes them over.
        scope?.let { previous -> viewModelScope.launch(Dispatchers.IO) { runCatching { outbox.recover(previous) } } }
        scope = next
        cache = next?.let(cacheFactory)
        threadCache.clear()
        discardPrewarmedMedia()
        // Session flags set by the shell (plan features) survive the account reset.
        _ui.value = ChatUiState(currentUserId = next?.userId, callsEnabled = _ui.value.callsEnabled)
        if (next != null) {
            // Texts leased by a previous process never reached the server: let the durable worker send them.
            viewModelScope.launch(Dispatchers.IO) { runCatching { outbox.recover(next) } }
            refresh()
            pendingOpen?.let { request ->
                pendingOpen = null
                openConversationById(request.conversationId, request.hint, request.jumpTo)
            }
        }
    }

    fun refresh() {
        val scopedCache = cache ?: return
        if (_ui.value.loading) return
        _ui.update { st -> st.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            if (_ui.value.conversations.isEmpty()) {
                warm?.let { runCatching(it::loadConversations).getOrNull() }?.takeIf { it.isNotEmpty() }?.let { cached ->
                    if (_ui.value.conversations.isEmpty()) _ui.update { st -> st.copy(conversations = zeroUnread(cached, visibleConversationId())) }
                    adoptListedConversation(cached)
                }
            }
            // Resume and reconnect refreshes often hit a pooled socket the OS already
            // dropped; one quick retry absorbs that instead of flashing "Offline".
            runCatching(repository::loadConversations).recoverCatching { first ->
                if (first !is ApiError.Network) throw first
                delay(listRetryDelayMs)
                repository.loadConversations()
            }.fold(
                onSuccess = { conversations ->
                    scopedCache.replace(conversations)
                    val userIds = conversations.mapNotNull { it.otherUserId }.distinct()
                    val presence = runCatching { repository.loadPresence(userIds) }.getOrDefault(emptyMap())
                    _ui.update { st -> st.copy(
                        loading = false,
                        conversations = zeroUnread(conversations, visibleConversationId()),
                        presence = presence,
                        fromCache = false,
                        syncedAtMs = startedAt,
                        // A fresh list retires the stale-list notice from an earlier failure.
                        message = st.message.takeUnless { it in LIST_STALE_NOTICES },
                    ) }
                    adoptListedConversation(conversations)
                    warmThreads(scopedCache, conversations)
                },
                onFailure = { error ->
                    // Offline first: keep the exact scoped cache visible. Only a transport
                    // failure means "offline"; an HTTP or decode error is just a stale list.
                    val cached = runCatching { scopedCache.snapshot() }.getOrDefault(emptyList())
                    _ui.update { st -> st.copy(
                        loading = false,
                        conversations = zeroUnread(cached, visibleConversationId()),
                        fromCache = cached.isNotEmpty(),
                        error = if (cached.isEmpty()) userFacingMessage(error, "Could not load conversations") else null,
                        message = when {
                            cached.isEmpty() -> st.message.takeUnless { it in LIST_STALE_NOTICES }
                            error is ApiError.Network -> OFFLINE_LIST_NOTICE
                            else -> STALE_LIST_NOTICE
                        },
                    ) }
                },
            )
        }
    }

    fun onRealtimeEvent(event: RoutedRealtimeEvent) {
        when (event.event) {
            RealtimeEvent.ChatTyping -> {
                val typing = decodeChatRealtime<ChatTypingEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id != typing.conversationId || typing.userId == scope?.userId) return
                _ui.update { st -> st.copy(typingUserId = typing.userId) }
                typingExpiry?.cancel()
                typingExpiry = viewModelScope.launch {
                    delay(3_000)
                    if (_ui.value.typingUserId == typing.userId) _ui.update { st -> st.copy(typingUserId = null) }
                }
                return
            }
            RealtimeEvent.ChatReadReceipt -> {
                val receipt = decodeChatRealtime<ChatReadReceiptEvent>(event.data) ?: return
                _ui.update { st -> st.copy(conversations = applyListRead(st.conversations, receipt, scope?.userId)) }
                if (_ui.value.selectedConversation?.id == receipt.conversationId) {
                    _ui.update { st -> st.copy(receipts = applyRealtimeReceipt(st.receipts, receipt)) }
                }
                return
            }
            RealtimeEvent.ChatReaction -> {
                val reaction = decodeChatRealtime<ChatReactionEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == reaction.conversationId) {
                    _ui.update { st -> st.copy(messages = applyRealtimeReaction(st.messages, reaction)) }
                }
                // Reconcile after the immediate patch, matching web behavior.
            }
            RealtimeEvent.ChatEdit -> {
                val edit = decodeChatRealtime<ChatEditEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == edit.conversationId) {
                    _ui.update { st -> st.copy(messages = applyRealtimeEdit(st.messages, edit)) }
                }
                return
            }
            RealtimeEvent.ChatDelete -> {
                val delete = decodeChatRealtime<ChatDeleteEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == delete.conversationId) {
                    _ui.update { st -> st.copy(
                        messages = applyRealtimeDelete(st.messages, delete),
                        editingMessage = st.editingMessage?.takeUnless { it.id == delete.messageId },
                        infoContent = pruneSharedFiles(st.infoContent, setOf(delete.messageId)),
                    ) }
                }
                return
            }
            RealtimeEvent.ChatMessageDelivered -> {
                val delivered = decodeChatRealtime<ChatDeliveredEvent>(event.data) ?: return
                _ui.update { st -> st.copy(conversations = applyListDelivered(st.conversations, delivered, scope?.userId)) }
                if (_ui.value.selectedConversation?.id == delivered.conversationId) {
                    _ui.update { st -> st.copy(messages = applyDelivered(st.messages, delivered)) }
                }
                return
            }
            RealtimeEvent.ChatViewOnce -> {
                val viewed = decodeChatRealtime<ChatViewOnceEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == viewed.conversationId) {
                    _ui.update { st -> st.copy(messages = applyViewOnce(st.messages, viewed)) }
                }
                return
            }
            RealtimeEvent.ChatMediaJob -> {
                val job = decodeChatRealtime<ChatMediaJobEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == job.conversationId) {
                    _ui.update { st -> st.copy(messages = applyRealtimeMediaJob(st.messages, job)) }
                }
                return
            }
            RealtimeEvent.ChatPollVote -> {
                val vote = decodeChatRealtime<ChatPollVoteEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == vote.conversationId) loadPoll(vote.pollId)
                return
            }
            RealtimeEvent.ChatPin -> {
                val pin = decodeChatRealtime<ChatPinEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == pin.conversationId) {
                    val messages = applyRealtimePin(_ui.value.messages, pin)
                    _ui.update { st -> st.copy(messages = messages, pinnedMessages = updatePinnedList(st.pinnedMessages, messages, pin.messageId, pin.pinned)) }
                }
                return
            }
            RealtimeEvent.ChatMessage -> {
                // Patch immediately (web `reconcileOwnMessage`): the echo carries
                // clientMsgId, so the optimistic bubble is swapped in place instead
                // of sitting next to the persisted row until the thread reloads.
                val incoming = decodeChatRealtime<ChatRealtimeMessage>(event.data)
                if (incoming != null && incoming.formatType != "system") {
                    _ui.update { st -> st.copy(conversations = applyListLastMessage(st.conversations, incoming)) }
                }
                incoming?.toChatMessage()?.let { received -> acknowledgeDelivery(listOf(received)) }
                if (incoming != null && isCallHistoryMessage(incoming.formatType, incoming.metadata)) onCallActivity()
                if (incoming != null && _ui.value.selectedConversation?.id == incoming.conversationId) {
                    val message = incoming.toChatMessage()
                    echoedPendingMedia(_ui.value.pendingMedia, message, scope?.userId)?.let {
                        ChatMediaMemory.rememberSent(message.fileUrl, it.uri, it.aspect())
                    }
                    _ui.update { state ->
                        val messages = mergeIncomingMessage(state.messages, message)
                        state.copy(
                            messages = messages,
                            queuedMessages = reconcileQueuedMessages(state.queuedMessages, messages, scope?.userId),
                            pendingMedia = dropEchoedPendingMedia(state.pendingMedia, message, scope?.userId),
                            typingUserId = state.typingUserId.takeUnless { it == message.senderId },
                        )
                    }
                    // Read live, as Signal does for the thread on screen.
                    if (message.senderId != scope?.userId && visibleConversationId() == incoming.conversationId) {
                        scheduleMarkRead(incoming.conversationId)
                    }
                }
                incoming?.toChatMessage()?.let(::storeLiveMessage)
            }
            // Clear / delete are per-user and only reach this user's own devices.
            RealtimeEvent.ChatCleared -> {
                val cleared = decodeChatRealtime<ChatConversationEvent>(event.data) ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    forgetThreadLocally(cleared.conversationId)
                    if (isOpen(cleared.conversationId)) refreshThread()
                    refresh()
                }
                return
            }
            RealtimeEvent.GroupCallUpdated -> {
                val update = decodeChatRealtime<GroupCallUpdatedEvent>(event.data) ?: return
                if (isOpen(update.conversationId)) {
                    if (!update.active) _ui.update { st -> st.copy(activeGroupCall = null) } else refreshActiveCall(update.conversationId)
                }
                return
            }
            RealtimeEvent.ChatGroupRoleChanged, RealtimeEvent.ChatGroupAdded -> {
                val changed = decodeChatRealtime<ChatConversationEvent>(event.data)
                if (changed != null && isOpen(changed.conversationId)) onGroupChanged()
            }
            RealtimeEvent.ChatGroupRemoved -> {
                val removed = decodeChatRealtime<ChatConversationEvent>(event.data)
                if (removed != null && isOpen(removed.conversationId)) {
                    onLeftGroup(removed.conversationId)
                    return
                }
            }
            RealtimeEvent.ChatConvDeleted -> {
                val deleted = decodeChatRealtime<ChatConversationEvent>(event.data) ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    forgetConversationLocally(deleted.conversationId)
                    refresh()
                }
                return
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
        _ui.update { st -> st.copy(userSearch = value, error = null) }
        if (value.trim().length < 2) _ui.update { st -> st.copy(userResults = emptyList()) }
    }

    fun searchUsers() {
        val query = _ui.value.userSearch.trim()
        if (query.length < 2) {
            _ui.update { st -> st.copy(error = "Enter at least 2 characters") }
            return
        }
        _ui.update { st -> st.copy(searching = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.searchUsers(query) }.fold(
                onSuccess = { _ui.update { st -> st.copy(searching = false, userResults = it) } },
                onFailure = { _ui.update { st -> st.copy(searching = false, error = it.message ?: "User search failed") } },
            )
        }
    }

    private val _groupCandidates = kotlinx.coroutines.flow.MutableStateFlow(GroupCandidates())
    /** New-group people search; separate from the chat-list search (web `GroupModal`). */
    val groupCandidates: kotlinx.coroutines.flow.StateFlow<GroupCandidates> = _groupCandidates
    private var groupSearchJob: kotlinx.coroutines.Job? = null

    fun searchGroupCandidates(query: String, debounceMs: Long = 300) {
        groupSearchJob?.cancel()
        val term = query.trim()
        if (term.length < 2) {
            _groupCandidates.value = GroupCandidates(query = query)
            return
        }
        _groupCandidates.value = _groupCandidates.value.copy(query = query, searching = true, error = null)
        groupSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(debounceMs)
            runCatching { repository.searchUsers(term) }.fold(
                onSuccess = { users ->
                    val me = scope?.userId
                    if (_groupCandidates.value.query.trim() == term) {
                        _groupCandidates.value = GroupCandidates(query = query, results = users.filter { it.id != me })
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _groupCandidates.value = _groupCandidates.value.copy(searching = false, error = error.message ?: "User search failed")
                },
            )
        }
    }

    fun clearGroupCandidates() {
        groupSearchJob?.cancel()
        _groupCandidates.value = GroupCandidates()
    }

    fun startDirect(user: ChatUser) {
        _ui.update { st -> st.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createDirect(user.id) }.fold(
                onSuccess = { created ->
                    _ui.update { st -> st.copy(
                        loading = false,
                        userSearch = "",
                        userResults = emptyList(),
                        // Web opens the conversation straight away.
                        openConversationId = created.conversationId,
                    ) }
                    refresh()
                },
                onFailure = { _ui.update { st -> st.copy(loading = false, error = it.message ?: "Could not start conversation") } },
            )
        }
    }

    fun markRead(conversation: ChatConversation) {
        if (conversation.unreadCount == 0) return
        val original = _ui.value.conversations
        _ui.update { st -> st.copy(
            conversations = original.map { if (it.id == conversation.id) it.copy(unreadCount = 0) else it },
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.markRead(conversation.id) }.fold(
                onSuccess = { refresh() },
                onFailure = {
                    _ui.update { st -> st.copy(conversations = original, error = it.message ?: "Could not mark conversation read") }
                },
            )
        }
    }

    fun toggleConversationSelection(conversationId: Long) {
        val selected = _ui.value.selectedConversationIds
        _ui.update { st -> st.copy(
            selectedConversationIds = if (conversationId in selected) selected - conversationId else selected + conversationId,
        ) }
    }

    fun selectAll(conversationIds: Collection<Long>) {
        val ids = conversationIds.toSet()
        _ui.update { st -> st.copy(
            selectedConversationIds = if (ids.isNotEmpty() && ids.all(st.selectedConversationIds::contains)) emptySet() else ids,
        ) }
    }

    fun cancelConversationSelection() {
        _ui.update { st -> st.copy(selectedConversationIds = emptySet(), conversationSelectionMode = false) }
    }

    fun startConversationSelection() {
        _ui.update { st -> st.copy(conversationSelectionMode = true) }
    }

    /** Overflow "Mark all as read": clears the badges at once, then syncs each chat and refreshes once. */
    fun markAllRead(conversations: Collection<ChatConversation>) {
        val ids = conversations.filter { it.unreadCount > 0 }.map(ChatConversation::id).toSet()
        if (ids.isEmpty()) return
        val original = _ui.value.conversations
        _ui.update { st -> st.copy(conversations = st.conversations.map { if (it.id in ids) it.copy(unreadCount = 0) else it }) }
        viewModelScope.launch(Dispatchers.IO) {
            val failed = ids.filter { id -> runCatching { repository.markRead(id) }.isFailure }.toSet()
            if (failed.isNotEmpty()) {
                val restore = original.filter { it.id in failed }.associateBy(ChatConversation::id)
                _ui.update { st -> st.copy(
                    conversations = st.conversations.map { restore[it.id] ?: it },
                    error = "Could not mark ${failed.size} chat${if (failed.size == 1) "" else "s"} as read",
                ) }
            }
            refresh()
        }
    }

    fun deleteSelectedConversations() {
        val ids = _ui.value.selectedConversationIds
        if (ids.isEmpty() || _ui.value.deletingConversations) return
        _ui.update { st -> st.copy(deletingConversations = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val failed = ids.filter { id -> runCatching { repository.deleteConversation(id) }.isFailure }
            // Deleted for this user only: its local rows, memory copy and notification go too.
            ids.filterNot { it in failed }.forEach { forgetConversationLocally(it) }
            _ui.update { st -> st.copy(
                deletingConversations = false,
                selectedConversationIds = failed.toSet(),
                conversationSelectionMode = st.conversationSelectionMode && failed.isNotEmpty(),
                conversations = st.conversations.filterNot { it.id in ids && it.id !in failed },
                message = if (failed.isEmpty()) "${ids.size} conversation${if (ids.size == 1) "" else "s"} deleted" else null,
                error = if (failed.isNotEmpty()) "Could not delete ${failed.size} selected conversation${if (failed.size == 1) "" else "s"}" else null,
            ) }
        }
    }

    /** Chat row press: publish the thread before the navigation transition starts (Signal opens from memory). */
    fun prepareConversation(conversationId: Long) {
        if (_ui.value.selectedConversation?.id == conversationId) return
        _ui.value.conversations.firstOrNull { it.id == conversationId }?.let(::openConversation)
    }

    /**
     * Opens a thread knowing only its id (notification tap, deep link) without
     * waiting for the conversation list: the listed row when present, otherwise
     * a placeholder from [hint] that the real row replaces once a list loads.
     * Stored messages paint at once while the server page loads in parallel.
     */
    fun openConversationById(conversationId: Long, hint: ConversationHint? = null, jumpTo: Long? = null) {
        if (conversationId <= 0) return
        if (scope == null) {
            pendingOpen = OpenRequest(conversationId, hint, jumpTo)
            return
        }
        if (isOpen(conversationId)) {
            jumpTo?.let { id -> _ui.update { st -> st.copy(jumpToMessageId = id) } }
            return
        }
        jumpTo?.let { pendingJump = conversationId to it }
        val listed = _ui.value.conversations.firstOrNull { it.id == conversationId }
        if (listed != null) {
            openConversation(listed)
            return
        }
        openConversation(placeholderConversation(conversationId, hint))
        placeholderId = conversationId
        // The last list response knows the real row (name, group flag, unread) without the network.
        viewModelScope.launch(Dispatchers.IO) {
            warm?.let { runCatching(it::loadConversations).getOrNull() }?.let(::adoptListedConversation)
        }
        if (_ui.value.conversations.isEmpty()) refresh()
    }

    /** Swaps the open placeholder for its real list row, keeping the thread as painted. */
    private fun adoptListedConversation(conversations: List<ChatConversation>) {
        val id = placeholderId ?: return
        val real = conversations.firstOrNull { it.id == id } ?: return
        var adopted = false
        _ui.update { st ->
            if (st.selectedConversation?.id != id || placeholderId != id) return@update st
            adopted = true
            st.copy(
                selectedConversation = real,
                // The hint may predate later messages; the list knows the unread count at open.
                unreadAtOpen = if (st.unreadAtOpen == 0) real.unreadCount.coerceAtLeast(0) else st.unreadAtOpen,
            )
        }
        if (adopted) placeholderId = null
    }

    fun openConversation(conversation: ChatConversation) {
        rememberOpenThread()
        placeholderId = null
        deletedThreads -= conversation.id
        val cached = threadCache.get(conversation.id)
        _ui.update { st -> st.copy(
            selectedConversation = conversation,
            messages = cached?.messages.orEmpty(),
            receipts = cached?.receipts.orEmpty(),
            threadFromCache = false,
            threadRefreshError = null,
            olderMessagesError = null,
            loadingOlder = false,
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
        ) }
        pendingJump = null
        // Opening a thread clears its system notification (Signal behaviour);
        // the screen's ON_RESUME (onThreadVisible) coalesces into the same read mark.
        context?.let { app.aino.mobile.core.push.ChatNotifications.cancel(it, conversation.id) }
        scheduleMarkRead(conversation.id)
        refreshThread(secondaryDelayMs = THREAD_SETTLE_MS)
        viewModelScope.launch(Dispatchers.IO) {
            delay(THREAD_SETTLE_MS)
            if (!isOpen(conversation.id)) return@launch
            refreshPinned(conversation.id)
            if (conversation.isGroup) refreshActiveCall(conversation.id)
            // Members feed @mention suggestions (web MentionInput uses convMembers).
            val members = runCatching { repository.loadMembers(conversation.id) }.getOrNull() ?: return@launch
            if (isOpen(conversation.id)) _ui.update { st -> st.copy(members = members) }
        }
        if (pendingSettings == conversation.id) { pendingSettings = null; openInfo() }
    }

    /** Recipient sheet "Chat settings": open the thread straight onto its info page. */
    fun openSettingsOnOpen(conversationId: Long) { pendingSettings = conversationId }

    /** [conversationId] closes only that thread, so a late close never wipes a newer one. */
    fun closeConversation(conversationId: Long? = null) {
        if (conversationId != null && _ui.value.selectedConversation?.id != conversationId) return
        rememberOpenThread()
        typingExpiry?.cancel()
        typingDispatch?.cancel()
        _ui.value.selectedConversation?.let { app.aino.mobile.core.push.VisibleThread.clear(it.id) }
        _ui.update { st -> st.copy(
            selectedConversation = null,
            messages = emptyList(),
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
            threadRefreshError = null,
            olderMessagesError = null,
            loadingOlder = false,
        ) }
    }

    private fun isOpen(conversationId: Long) = _ui.value.selectedConversation?.id == conversationId

    private fun rememberOpenThread() {
        val state = _ui.value
        val id = state.selectedConversation?.id ?: return
        if (id in deletedThreads) return
        if (!state.threadFromCache) threadCache.put(id, state.messages, state.receipts)
    }

    /** A live row joins the stored thread and its memory copy, so the next open is current even offline. */
    private fun storeLiveMessage(message: ChatMessage) {
        val conversationId = message.conversationId ?: return
        val scopedCache = cache ?: return
        if (conversationId in deletedThreads) return
        if (!isOpen(conversationId)) {
            threadCache.get(conversationId)?.let { threadCache.put(conversationId, mergeIncomingMessage(it.messages, message)) }
        }
        viewModelScope.launch(Dispatchers.IO) { runCatching { scopedCache.upsertMessages(conversationId, listOf(message)) } }
    }

    /**
     * "Clear chat" for this user (all their devices): no layer may repaint the
     * old history — memory copy, Room rows, warm page, hidden ids, notification.
     */
    private suspend fun forgetThreadLocally(conversationId: Long) {
        threadCache.remove(conversationId)
        saveHidden(conversationId, emptySet())
        forgetWarmThread(conversationId)
        context?.let { app.aino.mobile.core.push.ChatNotifications.cancel(it, conversationId) }
        _ui.update { st ->
            val conversations = st.conversations.map {
                if (it.id != conversationId) it
                else it.copy(lastMessage = null, lastFileName = null, lastFileType = null, lastFormatType = null, lastDeleted = null, unreadCount = 0)
            }
            if (st.selectedConversation?.id != conversationId) st.copy(conversations = conversations)
            else st.copy(
                conversations = conversations,
                messages = emptyList(),
                pinnedMessages = emptyList(),
                hiddenMessageIds = emptySet(),
                selectedMessageIds = emptySet(),
                infoContent = null,
                unreadAtOpen = 0,
                editingMessage = null,
                replyingTo = null,
                jumpToMessageId = null,
                hasOlderMessages = false,
                threadFromCache = false,
                threadRefreshError = null,
                olderMessagesError = null,
                threadSearchMatches = emptyList(),
                threadSearchIndex = -1,
            )
        }
        runCatching { cache?.clearMessages(conversationId) }
    }

    /** "Delete chat" for this user: the row and everything stored for it; an open thread closes. */
    private suspend fun forgetConversationLocally(conversationId: Long) {
        deletedThreads += conversationId
        threadCache.remove(conversationId)
        saveHidden(conversationId, emptySet())
        forgetWarmThread(conversationId)
        context?.let { app.aino.mobile.core.push.ChatNotifications.cancel(it, conversationId) }
        _ui.update { st ->
            val wasOpen = st.selectedConversation?.id == conversationId
            st.copy(
                conversations = st.conversations.filterNot { it.id == conversationId },
                selectedConversationIds = st.selectedConversationIds - conversationId,
                closeThread = wasOpen || st.closeThread,
                showInfo = if (wasOpen) false else st.showInfo,
            )
        }
        runCatching { cache?.deleteConversation(conversationId) }
    }

    /** Signal keeps recent conversations in memory: the top chats open without touching disk. */
    private suspend fun warmThreads(scopedCache: ChatCache, conversations: List<ChatConversation>) {
        conversations.asSequence().filterNot { it.isArchived }.take(WARM_THREADS).filterNot { threadCache.contains(it.id) }.forEach { conversation ->
            val stored = runCatching { scopedCache.fullMessages(conversation.id, limit = CACHED_THREAD_MESSAGES) }.getOrNull().orEmpty()
            if (stored.isNotEmpty() && !threadCache.contains(conversation.id)) threadCache.put(conversation.id, stored)
        }
    }

    fun updateComposer(value: String) {
        _ui.update { st -> st.copy(composer = value.take(5_000), error = null) }
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

    fun refreshThread() = refreshThread(secondaryDelayMs = 0L)

    /** Messages publish as soon as they arrive; receipts follow once [secondaryDelayMs] (the open transition) has passed. */
    private fun refreshThread(secondaryDelayMs: Long) {
        val conversation = _ui.value.selectedConversation ?: return
        val scopedCache = cache ?: return
        val id = conversation.id
        _ui.update { st -> st.copy(threadLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (_ui.value.messages.isEmpty()) {
                val stored = runCatching { scopedCache.fullMessages(id, limit = CACHED_THREAD_MESSAGES) }.getOrNull().orEmpty()
                    .ifEmpty { warm?.let { runCatching { it.loadMessages(id) }.getOrNull() }.orEmpty() }
                if (stored.isNotEmpty()) _ui.update {
                    if (it.selectedConversation?.id == id && it.messages.isEmpty()) it.copy(messages = stored, hasOlderMessages = stored.size >= THREAD_PAGE_SIZE) else it
                }
            }
            runCatching { repository.loadMessages(id) }.fold(
                onSuccess = { latest ->
                    val next = _ui.updateAndGet { state ->
                        if (state.selectedConversation?.id != id) return@updateAndGet state
                        val merged = mergeLatestPage(state.messages, latest)
                        state.copy(
                            threadLoading = false,
                            messages = merged,
                            hasOlderMessages = if (merged.size > latest.size) state.hasOlderMessages else latest.size >= THREAD_PAGE_SIZE,
                            // REST rows omit clientMsgId. Reconcile one-to-one by
                            // own-message chronology rather than deleting every
                            // queued bubble when a single echo appears.
                            queuedMessages = reconcileQueuedMessages(state.queuedMessages, merged, scope?.userId),
                            threadFromCache = false,
                            threadRefreshError = null,
                            olderMessagesError = state.olderMessagesError?.takeIf { canMergeOlderPage(merged, it.before) },
                        )
                    }
                    // A load that was in flight when the chat was deleted for this user
                    // must not write the thread back to memory or Room.
                    if (id in deletedThreads) return@fold
                    val merged = if (next.selectedConversation?.id == id) next.messages
                    else mergeLatestPage(threadCache.get(id)?.messages.orEmpty(), latest)
                    threadCache.put(id, merged)
                    scopedCache.replaceMessages(id, merged)
                    if (next.selectedConversation?.id == id) restoreDraftTargets(id, merged)
                    acknowledgeDelivery(latest.takeLast(THREAD_PAGE_SIZE))
                },
                onFailure = { error ->
                    val failure = messageLoadFailure(error, "Could not refresh messages. Try again.")
                    if (!isOpen(id)) return@fold
                    // A failed refresh does not establish that the device is offline.
                    val shown = _ui.value.messages
                    val cached = shown.ifEmpty { runCatching { scopedCache.messageSnapshot(id) }.getOrDefault(emptyList()) }
                    _ui.update {
                        if (it.selectedConversation?.id != id) it else it.copy(
                            threadLoading = false,
                            messages = if (it.messages.isEmpty()) cached else it.messages,
                            threadFromCache = cached.isNotEmpty(),
                            threadRefreshError = failure,
                        )
                    }
                },
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            delay(secondaryDelayMs)
            if (!isOpen(id)) return@launch
            val receipts = runCatching { repository.loadReadReceipts(id) }.getOrNull() ?: return@launch
            // Equal receipts keep the old list instance so the bubbles skip recomposition.
            _ui.update { if (it.selectedConversation?.id == id && it.receipts != receipts) it.copy(receipts = receipts) else it }
            threadCache.putReceipts(id, receipts)
        }
    }

    fun loadOlderMessages() {
        val conversation = _ui.value.selectedConversation ?: return
        // Retry the failed window even if stored rows have moved the oldest visible id.
        val oldestId = _ui.value.olderMessagesError?.before ?: _ui.value.messages.minOfOrNull(ChatMessage::id) ?: return
        if (_ui.value.loadingOlder || !_ui.value.hasOlderMessages) return
        val id = conversation.id
        _ui.update { st -> st.copy(loadingOlder = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // Older rows already on disk paint first; the server page then revalidates them in place.
            val stored = runCatching { cache?.fullMessages(id, before = oldestId, limit = THREAD_PAGE_SIZE) }.getOrNull().orEmpty()
            if (stored.isNotEmpty()) _ui.update {
                if (it.selectedConversation?.id == id && canMergeOlderPage(it.messages, oldestId)) {
                    it.copy(messages = stored + it.messages.filter { message -> message.id >= oldestId })
                } else it
            }
            runCatching { repository.loadMessages(id, oldestId) }.fold(
                onSuccess = { older ->
                    var applied = false
                    val next = _ui.updateAndGet {
                        applied = it.selectedConversation?.id == id && canMergeOlderPage(it.messages, oldestId)
                        when {
                            it.selectedConversation?.id != id -> it
                            // The thread was reset meanwhile: drop the page, keep its hasOlder state.
                            !applied -> it.copy(loadingOlder = false)
                            else -> it.copy(
                                loadingOlder = false,
                                olderMessagesError = null,
                                messages = mergeOlderPage(it.messages, older, oldestId),
                                hasOlderMessages = older.size >= THREAD_PAGE_SIZE && older.any { message -> message.id < oldestId },
                            )
                        }
                    }
                    if (applied && next.selectedConversation?.id == id) cache?.replaceMessages(id, next.messages)
                },
                onFailure = { error ->
                    val failure = messageLoadFailure(error, "Could not load older messages. Try again.")
                    _ui.update {
                        if (it.selectedConversation?.id != id) it else it.copy(
                            loadingOlder = false,
                            olderMessagesError = if (canMergeOlderPage(it.messages, oldestId)) OlderMessagesFailure(oldestId, failure) else null,
                        )
                    }
                },
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
            _ui.update { st -> st.copy(error = "Message content is required") }
            return
        }
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val replyToId = _ui.value.replyingTo?.id
        val preview = _ui.value.composerLinkPreview?.takeIf { it.url == firstLinkIn(content) }
        val mentions = _ui.value.members.filter { it.id in mentionedIds && content.contains("@" + it.display()) }.map { it.id }
        val queued = QueuedMessage(clientId, conversation.id, currentScope.userId, content, now, sendSequence.incrementAndGet())
        _ui.update { st -> st.copy(
            composer = "", replyingTo = null, composerLinkPreview = null, dismissedPreviewUrl = null,
            queuedMessages = st.queuedMessages + queued, error = null,
        ) }
        saveDraft(conversation.id, "")
        saveDraftTarget("reply", null)
        mentionedIds.clear()
        linkPreviewJob?.cancel()
        context?.let(app.aino.mobile.core.notifications.NotificationSoundPrefs::playSendConfirmation)
        // Durable right away (leased: the worker won't overtake media queued ahead of it).
        val persisted = viewModelScope.async(Dispatchers.IO) {
            runCatching { outbox.persist(currentScope, conversation.id, content, replyToId, now, clientId) }.isSuccess
        }
        // Mentions and link previews only travel on the web's WS `chat_message`
        // frame (the REST send route drops them).
        val socketEnvelope = if (preview != null || mentions.isNotEmpty()) {
            chatMessageEnvelope(conversation.id, content, clientId, replyToId, mentions, preview)
        } else null
        sendQueue.submit(conversation.id) {
            val stored = persisted.await()
            if (scope != currentScope) return@submit
            if (socketEnvelope != null && realtimeSend(socketEnvelope)) {
                if (stored) runCatching { outbox.delivered(currentScope, clientId) }
                return@submit
            }
            val send = runCatching {
                withSendRetries(attempts = TEXT_SEND_ATTEMPTS) {
                    // Signed out / switched tenant meanwhile: setScope released the row to the durable worker.
                    if (scope != currentScope) throw ScopeChanged()
                    repository.sendMessage(conversation.id, content, replyToId, clientId)
                }
            }
            send.fold(
                onSuccess = { row ->
                    onOwnMessageSent(row.copy(conversationId = row.conversationId ?: conversation.id), clientId)
                    if (stored) runCatching { outbox.delivered(currentScope, clientId) }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (error is ScopeChanged) return@fold
                    // A final answer (blocked, not a participant, …): the text can never be sent.
                    if (stored) runCatching { outbox.delivered(currentScope, clientId) }
                    _ui.update { st -> st.copy(
                        queuedMessages = st.queuedMessages.filterNot { it.clientMessageId == clientId },
                        composer = if (st.selectedConversation?.id == conversation.id && st.composer.isBlank()) content else st.composer,
                        error = userFacingMessage(error, "Could not send message"),
                    ) }
                },
            )
        }
    }

    private class ScopeChanged : Exception()

    /** Swaps the optimistic bubble for the server row the moment the send returns (no wait for the WS echo). */
    private fun onOwnMessageSent(row: ChatMessage, clientId: String) {
        val conversationId = row.conversationId ?: return
        _ui.update { st ->
            if (st.selectedConversation?.id != conversationId) {
                st.copy(queuedMessages = st.queuedMessages.filterNot { it.clientMessageId == clientId })
            } else {
                val messages = mergeIncomingMessage(st.messages, row.copy(clientMessageId = row.clientMessageId ?: clientId))
                st.copy(
                    messages = messages,
                    queuedMessages = reconcileQueuedMessages(st.queuedMessages, messages, scope?.userId)
                        .filterNot { it.clientMessageId == clientId },
                )
            }
        }
    }

    /** Web `ChatInputBar`: debounce 600 ms, fetch OpenGraph for the first URL unless dismissed. */
    private fun scheduleLinkPreview(text: String) {
        val url = firstLinkIn(text)
        if (url == null) {
            linkPreviewJob?.cancel()
            _ui.update { st -> st.copy(composerLinkPreview = null, dismissedPreviewUrl = null) }
            return
        }
        if (url == _ui.value.dismissedPreviewUrl || _ui.value.composerLinkPreview?.url == url) return
        linkPreviewJob?.cancel()
        linkPreviewJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            val preview = runCatching { repository.loadLinkPreview(url) }.getOrNull()
            if (firstLinkIn(_ui.value.composer) == url) _ui.update { st -> st.copy(composerLinkPreview = preview) }
        }
    }

    fun dismissLinkPreview() {
        _ui.update { st -> st.copy(dismissedPreviewUrl = st.composerLinkPreview?.url, composerLinkPreview = null) }
    }

    /** Records a picked @mention so its user id is sent with the message (web `getMentionedIds`). */
    fun addMention(member: ConversationMember) {
        mentionedIds += member.id
        updateComposer(insertMention(_ui.value.composer, member))
    }

    fun beginReply(message: ChatMessage) {
        if (message.deletedAt != null) return
        _ui.update { st -> st.copy(replyingTo = message, editingMessage = null, error = null) }
        saveDraftTarget("reply", message.id)
        saveDraftTarget("edit", null)
    }

    fun cancelReply() {
        _ui.update { st -> st.copy(replyingTo = null) }
        saveDraftTarget("reply", null)
    }

    fun beginEdit(message: ChatMessage) {
        if (message.senderId != scope?.userId || message.deletedAt != null) return
        _ui.update { st -> st.copy(editingMessage = message, replyingTo = null, composer = message.content.orEmpty(), error = null) }
        saveDraftTarget("edit", message.id)
        saveDraftTarget("reply", null)
    }

    fun cancelEdit() {
        _ui.update { st -> st.copy(editingMessage = null, composer = "", error = null) }
        saveDraftTarget("edit", null)
    }

    private fun submitEdit(message: ChatMessage) {
        val content = _ui.value.composer.trim()
        if (content.isEmpty() || content.length > 5_000) {
            _ui.update { st -> st.copy(error = "Edited message must be 1–5000 characters") }
            return
        }
        saveDraftTarget("edit", null)
        val original = _ui.value.messages
        val optimistic = message.copy(content = content, editedAt = java.time.Instant.now().toString())
        _ui.update { st -> st.copy(
            messages = original.map { if (it.id == message.id) optimistic else it },
            editingMessage = null,
            composer = "",
            error = null,
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.editMessage(message.id, content) }.onFailure {
                _ui.update { st -> st.copy(messages = original, editingMessage = message, composer = content, error = it.message ?: "Could not edit message") }
            }
        }
    }

    fun deleteMessage(message: ChatMessage) {
        if (message.senderId != scope?.userId || message.deletedAt != null) return
        val original = _ui.value.messages
        _ui.update { st -> st.copy(
            messages = applyRealtimeDelete(original, ChatDeleteEvent(message.id, message.conversationId ?: st.selectedConversation?.id ?: return)),
            editingMessage = st.editingMessage?.takeUnless { it.id == message.id },
            infoContent = pruneSharedFiles(st.infoContent, setOf(message.id)),
            error = null,
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteMessage(message.id) }.onFailure {
                _ui.update { st -> st.copy(messages = original, error = it.message ?: "Could not delete message") }
            }
        }
    }

    fun toggleStar(message: ChatMessage) {
        if (message.deletedAt != null) return
        val original = _ui.value.messages
        _ui.update { st -> st.copy(messages = original.map { if (it.id == message.id) it.copy(starred = !it.starred) else it }) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleStar(message.id) }.fold(
                onSuccess = { result ->
                    _ui.update { st -> st.copy(messages = st.messages.map { if (it.id == message.id) it.copy(starred = result.starred) else it }) }
                },
                onFailure = { _ui.update { st -> st.copy(messages = original, error = it.message ?: "Could not update saved message") } },
            )
        }
    }

    fun toggleMessagePin(message: ChatMessage) {
        if (message.deletedAt != null) return
        val conversationId = message.conversationId ?: _ui.value.selectedConversation?.id ?: return
        val original = _ui.value.messages
        val optimisticPinned = message.pinnedAt == null
        _ui.update { st -> st.copy(
            messages = applyRealtimePin(
                original,
                ChatPinEvent(message.id, conversationId, optimisticPinned, scope?.userId),
            ),
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleMessagePin(message.id) }.fold(
                onSuccess = { response ->
                    val messages = applyRealtimePin(
                        _ui.value.messages,
                        ChatPinEvent(message.id, conversationId, response.pinned, scope?.userId),
                    )
                    _ui.update { st -> st.copy(
                        messages = messages,
                        pinnedMessages = updatePinnedList(st.pinnedMessages, messages, message.id, response.pinned, message),
                    ) }
                },
                onFailure = { _ui.update { st -> st.copy(messages = original, error = it.message ?: "Could not update pinned message") } },
            )
        }
    }

    fun beginForward(message: ChatMessage) {
        if (message.deletedAt != null) return
        _ui.update { st -> st.copy(
            forwardingMessage = message,
            forwardQuery = "",
            forwardTargets = emptySet(),
            error = null,
        ) }
    }

    fun cancelForward() {
        if (_ui.value.forwarding) return
        _ui.update { st -> st.copy(forwardingMessage = null, forwardQuery = "", forwardTargets = emptySet()) }
    }

    fun updateForwardQuery(value: String) {
        _ui.update { st -> st.copy(forwardQuery = value.take(100), error = null) }
    }

    fun toggleForwardTarget(conversationId: Long) {
        val current = _ui.value.forwardTargets
        if (conversationId !in current && current.size >= 20) {
            _ui.update { st -> st.copy(error = "Choose no more than 20 conversations") }
            return
        }
        _ui.update { st -> st.copy(
            forwardTargets = if (conversationId in current) current - conversationId else current + conversationId,
            error = null,
        ) }
    }

    fun submitForward() {
        val message = _ui.value.forwardingMessage ?: return
        val targets = _ui.value.forwardTargets.toList()
        if (targets.isEmpty()) {
            _ui.update { st -> st.copy(error = "Choose at least one conversation") }
            return
        }
        _ui.update { st -> st.copy(forwarding = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.forwardMessage(message.id, targets) }.fold(
                onSuccess = {
                    val refreshCurrent = _ui.value.selectedConversation?.id in targets
                    _ui.update { st -> st.copy(
                        forwarding = false,
                        forwardingMessage = null,
                        forwardQuery = "",
                        forwardTargets = emptySet(),
                        message = "Forwarded to ${targets.size} conversation${if (targets.size == 1) "" else "s"}",
                    ) }
                    refresh()
                    if (refreshCurrent) refreshThread()
                },
                onFailure = { _ui.update { st -> st.copy(forwarding = false, error = it.message ?: "Could not forward message") } },
            )
        }
    }

    fun react(message: ChatMessage, emoji: String) {
        if (message.deletedAt != null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleReaction(message.id, emoji) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not update reaction") } },
            )
        }
    }

    fun createPoll(question: String, options: List<String>, multiSelect: Boolean) {
        val conversation = _ui.value.selectedConversation ?: return
        val cleaned = options.map(String::trim).filter(String::isNotEmpty)
        if (question.isBlank() || cleaned.size < 2) {
            _ui.update { st -> st.copy(error = "A poll needs a question and at least two options") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createPoll(conversation.id, question.trim(), cleaned, multiSelect) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not create poll") } },
            )
        }
    }

    /** Web `PollDisplay`: the bubble shows the live tally from `GET /chat/polls/:id`. */
    fun loadPoll(pollId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadPoll(pollId) }.onSuccess { poll ->
                _ui.update { st -> st.copy(polls = st.polls + (pollId to poll)) }
            }
        }
    }

    fun votePoll(message: ChatMessage, optionIndex: Int) {
        val pollId = message.pollId() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.votePoll(pollId, optionIndex) }.fold(
                onSuccess = { loadPoll(pollId) },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not update poll") } },
            )
        }
    }

    // ---- Message selection (web Chat.tsx messageSelectionBar) ----

    fun enterMessageSelection(message: ChatMessage) {
        if (message.deletedAt != null) return
        _ui.update { st -> st.copy(selectedMessageIds = setOf(message.id)) }
    }

    fun toggleMessageSelection(messageId: Long) {
        val current = _ui.value.selectedMessageIds
        _ui.update { st -> st.copy(selectedMessageIds = if (messageId in current) current - messageId else current + messageId) }
    }

    fun clearMessageSelection() { _ui.update { st -> st.copy(selectedMessageIds = emptySet()) } }

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
        _ui.update { st -> st.copy(hiddenMessageIds = hidden, selectedMessageIds = emptySet(), infoContent = pruneSharedFiles(st.infoContent, hidden)) }
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
            _ui.update { st -> st.copy(showInfo = false, jumpToMessageId = message.id) }
        } else {
            _ui.update { st -> st.copy(showInfo = false, openConversationId = message.conversationId) }
        }
    }

    fun consumeJump() { _ui.update { st -> st.copy(jumpToMessageId = null) } }

    fun unstarFromList(message: ChatMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleStar(message.id) }.onSuccess {
                val content = _ui.value.infoContent as? InfoContent.Messages ?: return@onSuccess
                _ui.update { st -> st.copy(infoContent = InfoContent.Messages(content.messages.filterNot { it.id == message.id })) }
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
            edit != null -> _ui.update { st -> st.copy(editingMessage = edit, composer = st.composer.ifBlank { edit.content.orEmpty() }) }
            reply != null -> _ui.update { st -> st.copy(replyingTo = reply) }
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
        _ui.update { st -> st.copy(pendingAttachment = PendingAttachment(uri, mime, name), error = null) }
    }

    fun cancelAttachment() { _ui.update { st -> st.copy(pendingAttachment = null) } }

    fun sendAttachment(caption: String) {
        val pending = _ui.value.pendingAttachment ?: return
        _ui.update { st -> st.copy(pendingAttachment = null, composer = caption) }
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
        val currentScope = scope ?: return
        val conversation = _ui.value.selectedConversation ?: return
        val resolver = context?.contentResolver
        val now = System.currentTimeMillis()
        // Signal: every item gets its own outgoing bubble immediately, in send order.
        val pending = items.mapIndexed { index, item ->
            var name = item.uri.lastPathSegment ?: "file"
            runCatching {
                resolver?.query(item.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
            }
            val mime = item.mimeType ?: resolver?.getType(item.uri) ?: "application/octet-stream"
            PendingMedia(
                localId = "media-${UUID.randomUUID()}", conversationId = conversation.id,
                uri = item.uri, mimeType = mime, fileName = name,
                caption = caption.takeIf { index == 0 }, createdAtEpochMs = now + index,
                width = item.width, height = item.height, sequence = sendSequence.incrementAndGet(),
            )
        }
        pending.forEach { pendingOptions[it.localId] = UploadOptions(viewOnce, quality) }
        _ui.update { st -> st.copy(
            pendingMedia = st.pendingMedia + pending,
            composer = if (clearComposer) "" else st.composer,
            error = null,
        ) }
        if (clearComposer) saveDraft(conversation.id, "")
        context?.let(app.aino.mobile.core.notifications.NotificationSoundPrefs::playSendConfirmation)
        pending.forEach { enqueueUpload(currentScope, it) }
    }

    private data class UploadOptions(val viewOnce: Boolean, val quality: String?)
    private val pendingOptions = java.util.concurrent.ConcurrentHashMap<String, UploadOptions>()

    /** Compression starts now (in parallel); the upload itself waits its turn in the conversation's send queue. */
    private fun enqueueUpload(currentScope: CacheScope, media: PendingMedia) {
        val options = pendingOptions[media.localId] ?: UploadOptions(false, null)
        val prep = claimPrepared(media, options.quality)
        prepJobs[media.localId] = prep
        sendQueue.submit(media.conversationId) {
            if (scope != currentScope || _ui.value.pendingMedia.none { it.localId == media.localId }) {
                discard(prepJobs.remove(media.localId) ?: prep)
                return@submit
            }
            uploadQueued(media, prep, options)
        }
    }

    /** The send screen's pre-processed result when it matches (Signal pre-upload), else a fresh preparation. */
    private fun claimPrepared(media: PendingMedia, quality: String?): Deferred<PreparedMedia> {
        val key = PrepKey(media.uri, quality.takeIf { media.isImage || media.isVideo })
        synchronized(prewarmed) { prewarmed.remove(key) }?.let { return it }
        return startPreparation(MediaPrepRequest(media.uri, media.mimeType, media.fileName, key.quality, media.width, media.height))
    }

    private fun startPreparation(request: MediaPrepRequest): Deferred<PreparedMedia> =
        viewModelScope.async(Dispatchers.IO) {
            val prepare = preparer ?: throw IllegalStateException("Could not read the selected file")
            prepSlots.acquire()
            try { prepare.prepare(request) } finally { prepSlots.release() }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun discard(prep: Deferred<PreparedMedia>) {
        prep.cancel()
        prep.invokeOnCompletion { cause -> if (cause == null) runCatching { prep.getCompleted().release() } }
    }

    /** Signal `MediaUploadRepository.startUpload` while the send screen is open: here, compression/transcoding. */
    fun prewarmMedia(items: List<MediaUploadSpec>, highQuality: Boolean) {
        val quality = if (highQuality) "hd" else "standard"
        val wanted = items.filter { it.mimeType?.let { m -> m.startsWith("image/") || m.startsWith("video/") } == true }
        val keys = wanted.map { PrepKey(it.uri, quality) }.toSet()
        val stale = synchronized(prewarmed) {
            val gone = prewarmed.keys.filterNot(keys::contains)
            gone.mapNotNull(prewarmed::remove)
        }
        stale.forEach(::discard)
        wanted.forEach { item ->
            val key = PrepKey(item.uri, quality)
            synchronized(prewarmed) {
                if (key !in prewarmed) {
                    val name = item.uri.lastPathSegment ?: "file"
                    prewarmed[key] = startPreparation(MediaPrepRequest(item.uri, item.mimeType!!, name, quality, item.width, item.height))
                }
            }
        }
    }

    /** The send screen closed: drop whatever it pre-processed but did not send. */
    fun discardPrewarmedMedia() {
        val all = synchronized(prewarmed) { prewarmed.values.toList().also { prewarmed.clear() } }
        all.forEach(::discard)
    }

    private fun updatePending(localId: String, transform: (PendingMedia) -> PendingMedia) {
        _ui.update { st -> st.copy(pendingMedia = st.pendingMedia.map { if (it.localId == localId) transform(it) else it }) }
    }

    /** Signal ✕ on the progress ring: removes the bubble (an in-flight request may still land server-side). */
    fun cancelPendingMedia(localId: String) {
        pendingOptions.remove(localId)
        prepJobs.remove(localId)?.let(::discard)
        _ui.update { st -> st.copy(pendingMedia = st.pendingMedia.filterNot { it.localId == localId }) }
    }

    /** Signal resend: the item goes to the back of the conversation's queue (and so below later messages). */
    fun retryPendingMedia(localId: String) {
        val currentScope = scope ?: return
        val media = _ui.value.pendingMedia.firstOrNull { it.localId == localId && it.state == PendingMediaState.Failed } ?: return
        val requeued = media.copy(state = PendingMediaState.Uploading, progress = null, error = null, sequence = sendSequence.incrementAndGet())
        updatePending(localId) { requeued }
        enqueueUpload(currentScope, requeued)
    }

    private suspend fun uploadQueued(media: PendingMedia, prep: Deferred<PreparedMedia>, options: UploadOptions) {
        updatePending(media.localId) { it.copy(state = PendingMediaState.Uploading, progress = null, error = null) }
        var prepared: PreparedMedia? = null
        try {
            val ready = prep.await().also { prepared = it }
            if (_ui.value.pendingMedia.none { it.localId == media.localId }) return // cancelled while preparing
            updatePending(media.localId) { it.copy(width = it.width ?: ready.width, height = it.height ?: ready.height) }
            validateChatUpload(ready.fileName, ready.mimeType, ready.length)?.let { throw IllegalArgumentException(it) }
            val visual = media.isImage || media.isVideo
            // Keep the picked file's display name; only the extension follows a re-encode.
            val fileName = if (ready.mimeType.equals(media.mimeType, ignoreCase = true)) media.fileName
            else media.fileName.substringBeforeLast('.', media.fileName) + "." + ready.fileName.substringAfterLast('.', "bin")
            val upload = ChatUpload(
                fileName, ready.mimeType,
                content = media.caption,
                viewOnce = options.viewOnce && visual,
                quality = options.quality.takeIf { visual },
                width = ready.width ?: media.width, height = ready.height ?: media.height,
                source = ready.open, sourceLength = ready.length,
            )
            val uploaded = withSendRetries {
                repository.uploadFile(media.conversationId, upload) { sent, total ->
                    if (total > 0) updatePending(media.localId) { it.copy(progress = (sent.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
            if (_ui.value.pendingMedia.none { it.localId == media.localId }) return // cancelled mid-flight
            pendingOptions.remove(media.localId)
            val message = uploaded.copy(conversationId = uploaded.conversationId ?: media.conversationId)
            ChatMediaMemory.rememberSent(message.fileUrl, media.uri, media.aspect())
            // Swap the local bubble for the persisted row in one atomic update.
            _ui.update { state ->
                state.copy(
                    messages = if (state.selectedConversation?.id == media.conversationId) mergeIncomingMessage(state.messages, message) else state.messages,
                    pendingMedia = state.pendingMedia.filterNot { it.localId == media.localId },
                )
            }
            refresh()
        } catch (error: CancellationException) {
            if (_ui.value.pendingMedia.any { it.localId == media.localId } && !prep.isCancelled) throw error
        } catch (error: Throwable) {
            updatePending(media.localId) {
                it.copy(state = PendingMediaState.Failed, progress = null, error = userFacingMessage(error, "Upload failed"))
            }
        } finally {
            prepJobs.remove(media.localId)
            prepared?.release()
        }
    }

    /** View-once open: the server claims the single allowed view and returns the URL (null once consumed). */
    fun openViewOnce(message: ChatMessage, onUrl: (String?) -> Unit) {
        val me = scope?.userId ?: return
        if (message.senderId == me) return // Senders can't re-open their own view-once media.
        viewModelScope.launch(Dispatchers.IO) {
            val url = runCatching { repository.viewMessage(message.id) }.getOrNull()?.fileUrl
            val event = ChatViewOnceEvent(message.id, message.conversationId ?: 0, me)
            _ui.update { st -> st.copy(messages = applyViewOnce(st.messages, event)) }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onUrl(url) }
        }
    }

    /** Signal "Delete for me" on a single message (local hide, web `chatLocalDeletes`). */
    fun deleteForMe(message: ChatMessage) {
        val conversationId = _ui.value.selectedConversation?.id ?: return
        val hidden = _ui.value.hiddenMessageIds + message.id
        saveHidden(conversationId, hidden)
        _ui.update { st -> st.copy(hiddenMessageIds = hidden, infoContent = pruneSharedFiles(st.infoContent, hidden)) }
    }

    // ---- Signal in-chat search: toolbar field + "x of y" stepping ----
    fun openThreadSearch() { _ui.update { st -> st.copy(threadSearchOpen = true) } }

    fun closeThreadSearch() {
        threadSearchJob?.cancel()
        _ui.update { st -> st.copy(threadSearchOpen = false, threadSearchQuery = "", threadSearchMatches = emptyList(), threadSearchIndex = -1) }
    }

    fun updateThreadSearch(term: String) {
        _ui.update { st -> st.copy(threadSearchQuery = term) }
        threadSearchJob?.cancel()
        val conversation = _ui.value.selectedConversation ?: return
        if (term.trim().length < 2) {
            _ui.update { st -> st.copy(threadSearchMatches = emptyList(), threadSearchIndex = -1) }
            return
        }
        threadSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            runCatching { repository.searchMessages(term.trim(), conversation.id) }.onSuccess { found ->
                val newestFirst = found.filter { it.id !in _ui.value.hiddenMessageIds }.sortedByDescending { it.createdAt }.map { it.id }
                _ui.update { st -> st.copy(
                    threadSearchMatches = newestFirst,
                    threadSearchIndex = if (newestFirst.isEmpty()) -1 else 0,
                    jumpToMessageId = newestFirst.firstOrNull(),
                ) }
            }
        }
    }

    /** delta = +1 walks to older matches (Signal "up"), -1 to newer. */
    fun stepThreadSearch(delta: Int) {
        _ui.update { state ->
            val next = stepSearchMatch(state.threadSearchIndex, state.threadSearchMatches.size, delta)
            if (next < 0) state else state.copy(threadSearchIndex = next, jumpToMessageId = state.threadSearchMatches[next])
        }
    }

    // ---- Signal list search: chats + contacts + messages sections ----
    fun searchAllMessages(term: String) {
        globalSearchJob?.cancel()
        if (term.trim().length < 2) { _ui.update { st -> st.copy(messageResults = emptyList()) }; return }
        globalSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            runCatching { repository.searchMessages(term.trim()) }.onSuccess { _ui.update { st -> st.copy(messageResults = it) } }
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
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not cancel media") } },
            )
        }
    }

    fun retryMedia(message: ChatMessage) {
        val id = message.mediaJobId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.retryMediaJob(id) }.fold(
                onSuccess = { refreshThread() },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not retry media") } },
            )
        }
    }

    fun loadCalls() {
        if (_ui.value.callsLoading) return
        _ui.update { st -> st.copy(callsLoading = true, error = null) }
        val loadingScope = scope
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(repository::loadCalls).fold(
                onSuccess = {
                    callsLoadedFor = loadingScope
                    _ui.update { st -> st.copy(callsLoading = false, calls = it) }
                },
                onFailure = { _ui.update { st -> st.copy(callsLoading = false, error = it.message ?: "Could not load calls") } },
            )
        }
    }

    /** The Calls tab was loaded for this account: live call events keep it current. */
    @Volatile private var callsLoadedFor: CacheScope? = null
    private var callsReload: Job? = null

    /**
     * A call ended or its history row arrived (`chat_message` system / call):
     * refresh the Calls tab and the open conversation-info call history, once
     * per burst, and only where that data was already loaded.
     */
    fun onCallActivity() {
        callsReload?.cancel()
        callsReload = viewModelScope.launch {
            delay(CALLS_RELOAD_DEBOUNCE_MS)
            val current = scope ?: return@launch
            val infoConversation = _ui.value.selectedConversation?.takeIf { _ui.value.showInfo }
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                if (callsLoadedFor == current) {
                    runCatching(repository::loadCalls).onSuccess { calls ->
                        if (scope == current) _ui.update { st -> st.copy(calls = calls) }
                    }
                }
                if (infoConversation != null) {
                    runCatching { repository.loadConversationCalls(infoConversation.id) }.onSuccess { calls ->
                        _ui.update { st -> if (st.selectedConversation?.id == infoConversation.id) st.copy(conversationCalls = calls) else st }
                    }
                }
            }
        }
    }

    fun toggleCallSelection(callId: Long) {
        val selected = _ui.value.selectedCallIds
        _ui.update { st -> st.copy(
            selectedCallIds = if (callId in selected) selected - callId else selected + callId,
        ) }
    }

    fun selectAllCalls() {
        val ids = _ui.value.calls.map(CallLog::id).toSet()
        _ui.update { st -> st.copy(
            selectedCallIds = if (ids.isNotEmpty() && ids.all(st.selectedCallIds::contains)) emptySet() else ids,
        ) }
    }

    fun cancelCallSelection() {
        _ui.update { st -> st.copy(selectedCallIds = emptySet(), callSelectionMode = false) }
    }

    fun startCallSelection() {
        _ui.update { st -> st.copy(callSelectionMode = true) }
    }

    fun deleteSelectedCalls() {
        val ids = _ui.value.selectedCallIds
        if (ids.isEmpty() || _ui.value.deletingCalls) return
        _ui.update { st -> st.copy(deletingCalls = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (ids.size == _ui.value.calls.size) repository.deleteAllCalls()
                else repository.deleteCalls(ids.toList())
            }.fold(
                onSuccess = {
                    _ui.update { st -> st.copy(
                        deletingCalls = false,
                        selectedCallIds = emptySet(),
                        callSelectionMode = false,
                        calls = st.calls.filterNot { it.id in ids },
                        message = "${it.deleted} call${if (it.deleted == 1) "" else "s"} deleted",
                    ) }
                },
                onFailure = {
                    _ui.update { st -> st.copy(
                        deletingCalls = false,
                        error = it.message ?: "Could not delete call history",
                    ) }
                },
            )
        }
    }

    fun openInfo() {
        val conversation = _ui.value.selectedConversation ?: return
        _ui.update { st -> st.copy(showInfo = true, conversationCalls = emptyList(), infoContent = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val members = runCatching { repository.loadMembers(conversation.id) }.getOrDefault(emptyList())
            val calls = runCatching { repository.loadConversationCalls(conversation.id) }.getOrDefault(emptyList())
            _ui.update { st -> st.copy(members = members, conversationCalls = calls) }
        }
    }

    fun closeInfo() { _ui.update { st -> st.copy(showInfo = false) } }

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
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not update favourite") } },
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
                if (undoable && archiving) _ui.update { st -> st.copy(archiveUndo = current.copy(isArchived = true)) }
                refresh()
            },
        )
    }

    /** Signal snackbar Undo: puts the just-archived chat back. */
    fun undoArchive() {
        val archived = _ui.value.archiveUndo ?: return
        _ui.update { st -> st.copy(archiveUndo = null) }
        toggleArchive(archived)
    }

    fun dismissArchiveUndo() { _ui.update { st -> st.copy(archiveUndo = null) } }

    /** Signal list "Mark as unread". */
    fun markUnread(conversation: ChatConversation) {
        if (conversation.unreadCount > 0) return
        updateConversation(
            conversation, conversation.copy(unreadCount = 1), "Could not mark conversation unread",
            request = { repository.markUnread(conversation.id) },
        )
    }

    private fun updateSelected(conversation: ChatConversation) {
        _ui.update { st -> st.copy(
            selectedConversation = conversation,
            conversations = st.conversations.map { if (it.id == conversation.id) conversation else it },
        ) }
    }

    /** Patches a conversation in the list and, when it is the open one, in the thread. */
    private fun patchConversation(conversation: ChatConversation) {
        _ui.update { state ->
            state.copy(
                selectedConversation = state.selectedConversation?.let { if (it.id == conversation.id) conversation else it },
                conversations = state.conversations.map { if (it.id == conversation.id) conversation else it },
            )
        }
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
                    _ui.update { st -> st.copy(error = it.message ?: failure) }
                },
            )
        }
    }

    // ---- Conversation info sub-pages (web ConversationInfoPanel entry points) ----

    private fun loadInfo(block: suspend (ChatConversation) -> InfoContent) {
        val conversation = _ui.value.selectedConversation ?: return
        _ui.update { st -> st.copy(infoLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block(conversation) }.fold(
                onSuccess = { _ui.update { st -> st.copy(infoLoading = false, infoContent = it) } },
                onFailure = { _ui.update { st -> st.copy(infoLoading = false, error = it.message ?: "Could not load") } },
            )
        }
    }

    fun loadPinnedMessages() = loadInfo { InfoContent.Messages(repository.loadPinnedMessages(it.id)) }

    /** Signal pinned-message bar source; failures leave the bar hidden rather than erroring the thread. */
    private fun refreshPinned(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val pinned = runCatching { repository.loadPinnedMessages(conversationId) }.getOrNull() ?: return@launch
            if (_ui.value.selectedConversation?.id == conversationId) {
                _ui.update { st -> st.copy(pinnedMessages = pinned.filter { it.deletedAt == null }) }
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
                    // Deleted for this user only (all their devices); others keep the chat.
                    forgetConversationLocally(conversation.id)
                    refresh()
                },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not delete chat") } },
            )
        }
    }
    fun loadSavedMessages() = loadInfo { InfoContent.Messages(repository.loadStarredMessages()) }
    fun loadSharedFiles() = loadInfo { conversation ->
        pruneSharedFiles(InfoContent.Files(repository.loadSharedFiles(conversation.id)), _ui.value.hiddenMessageIds)!!
    }
    fun searchInConversation(term: String) {
        if (term.trim().length < 2) { _ui.update { st -> st.copy(infoContent = InfoContent.Messages(emptyList())) }; return }
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
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not update block") } },
            )
        }
    }

    fun clearChat() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.clearMessages(current.id) }.fold(
                onSuccess = {
                    // Cleared for this user only: drop every local copy, then read the (now empty) server view.
                    forgetThreadLocally(current.id)
                    _ui.update { st -> st.copy(showInfo = false) }
                    refreshThread()
                    refresh()
                },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not clear chat") } },
            )
        }
    }

    fun leaveGroup() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.leaveGroup(current.id) }.fold(
                onSuccess = { onLeftGroup(current.id) },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not leave group") } },
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
                    _ui.update { st -> st.copy(members = members) }
                    refresh()
                },
                onFailure = { _ui.update { st -> st.copy(error = it.message ?: "Could not update group") } },
            )
        }
    }

    /** Group settings changed something (members, roles, info, photo, policies): re-read the row and members. */
    fun onGroupChanged() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val members = runCatching { repository.loadMembers(current.id) }.getOrNull()
            if (members != null && isOpen(current.id)) _ui.update { st -> st.copy(members = members) }
            refresh()
        }
    }

    /**
     * Left (or removed from) the open group: close its thread once. The server's
     * `chat_group_removed` frame usually arrives before the leave response, so a
     * second call for a thread that is no longer open must not navigate back again.
     */
    fun onLeftGroup(conversationId: Long? = _ui.value.selectedConversation?.id) {
        val wasOpen = conversationId != null && isOpen(conversationId)
        if (wasOpen) {
            closeConversation()
            _ui.update { st -> st.copy(closeThread = true, showInfo = false) }
        }
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    // ---- Group call banner (group_call_updated / active-call) ----

    private fun refreshActiveCall(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val call = runCatching { repository.activeGroupCall(conversationId) }.getOrNull()
            if (isOpen(conversationId)) _ui.update { st -> st.copy(activeGroupCall = call) }
        }
    }

    /** Join the group's running call through the lobby (it never rings when joining). */
    fun joinActiveGroupCall() {
        val conversation = _ui.value.selectedConversation ?: return
        val call = _ui.value.activeGroupCall ?: return
        app.aino.mobile.core.navigation.RouteRequests.open(app.aino.mobile.core.navigation.groupCallLobbyRoute(conversation.id, call.callType))
    }

    // ---- Group invite links (join side) ----

    fun loadInvite(token: String) {
        _ui.update { st -> st.copy(invite = InviteSheetState(token = token, loading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.invitePreview(token) }.fold(
                onSuccess = { preview -> _ui.update { st -> st.copy(invite = st.invite?.takeIf { it.token == token }?.copy(loading = false, preview = preview)) } },
                onFailure = { error -> _ui.update { st -> st.copy(invite = st.invite?.takeIf { it.token == token }?.copy(loading = false, error = error.message ?: "This group link is no longer valid")) } },
            )
        }
    }

    fun joinInvite() {
        val invite = _ui.value.invite ?: return
        _ui.update { st -> st.copy(invite = invite.copy(joining = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.joinByInvite(invite.token) }.fold(
                onSuccess = { result ->
                    if (result.pending) {
                        _ui.update { st -> st.copy(invite = st.invite?.copy(joining = false, preview = st.invite.preview?.copy(pending = true))) }
                    } else {
                        refresh()
                        _ui.update { st -> st.copy(invite = null, openConversationId = result.conversationId) }
                    }
                },
                onFailure = { error -> _ui.update { st -> st.copy(invite = st.invite?.copy(joining = false, error = error.message ?: "Couldn't join this group")) } },
            )
        }
    }

    fun cancelJoinRequest() {
        val invite = _ui.value.invite ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.cancelJoinRequest(invite.token) }.onSuccess {
                _ui.update { st -> st.copy(invite = st.invite?.copy(preview = st.invite.preview?.copy(pending = false))) }
            }
        }
    }

    fun openInviteConversation() {
        val id = _ui.value.invite?.preview?.conversationId ?: return
        _ui.update { st -> st.copy(invite = null, openConversationId = id) }
    }

    fun dismissInvite() = _ui.update { st -> st.copy(invite = null) }

    /**
     * Signal new-group flow: creates the group, then uploads the optional [avatar].
     * A failed photo upload never loses the group; it opens with a notice instead.
     */
    fun createGroup(name: String, userIds: List<Long>, avatar: Uri? = null) {
        if (name.isBlank() || userIds.isEmpty()) {
            _ui.update { st -> st.copy(error = "Add a group name and at least one member") }
            return
        }
        if (_ui.value.creatingGroup) return
        _ui.update { st -> st.copy(creatingGroup = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createGroup(name.trim(), userIds) }.fold(
                onSuccess = { created ->
                    val photoFailed = avatar != null && runCatching {
                        val prepared = prepareGroupAvatar(avatar) ?: throw IllegalStateException("That image can't be used")
                        repository.uploadGroupAvatar(created.conversationId, prepared.fileName, prepared.mimeType, prepared.bytes)
                    }.isFailure
                    refresh()
                    _ui.update { st ->
                        st.copy(
                            creatingGroup = false,
                            openConversationId = created.conversationId,
                            message = if (photoFailed) "Group created, but the photo couldn't be uploaded" else st.message,
                        )
                    }
                },
                onFailure = { _ui.update { st -> st.copy(creatingGroup = false, error = it.message ?: "Could not create group") } },
            )
        }
    }

    fun consumeNavigation() { _ui.update { st -> st.copy(openConversationId = null, closeThread = false) } }

    override fun onCleared() {
        discardPrewarmedMedia()
        // The in-process send queue dies with this ViewModel; texts it still held go to the durable worker.
        val owner = scope ?: return
        if (_ui.value.queuedMessages.isNotEmpty()) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { outbox.recover(owner) } }
        }
    }

    companion object {
        private const val DRAFT_PREFS = "aino_chat_drafts"
        internal const val OFFLINE_LIST_NOTICE = "Offline · showing cached conversations"
        internal const val STALE_LIST_NOTICE = "Couldn't refresh chats · showing saved conversations"
        private val LIST_STALE_NOTICES = setOf(OFFLINE_LIST_NOTICE, STALE_LIST_NOTICE)

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
                    outbox,
                    warm = ChatRepository(container.cachedApi),
                    forgetWarmThread = { id -> app.aino.mobile.core.push.PushSync.forgetThread(context.applicationContext, id) },
                    prepareGroupAvatar = { uri -> app.aino.mobile.core.media.prepareSquareAvatar(context.applicationContext, uri) },
                ).also { it.context = context.applicationContext } as T
            }
        }
    }

    private var context: Context? = null
}