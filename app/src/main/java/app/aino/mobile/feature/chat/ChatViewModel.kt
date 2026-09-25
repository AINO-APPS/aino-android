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
    /** 0..1 bytes-sent fraction of the in-flight upload (web `_mediaProgress`). */
    val uploadProgress: Float? = null,
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
    val error: String? = null,
    val message: String? = null,
) {
    val unread: Int get() = totalUnread(conversations)
}

class ChatViewModel(
    private val repository: ChatRepository,
    private val cacheFactory: (CacheScope) -> ChatCache,
    private val enqueueText: suspend (CacheScope, Long, String, Long?, Long, String) -> String,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()
    private var scope: CacheScope? = null
    private var cache: ChatCache? = null
    private var realtimeRefresh: Job? = null
    private var typingExpiry: Job? = null
    private var typingDispatch: Job? = null
    private var linkPreviewJob: Job? = null
    private val mentionedIds = mutableSetOf<Long>()
    private var realtimeSend: (RealtimeEnvelope) -> Boolean = { false }

    /** Injected from the process-owned realtime ViewModel after composition. */
    fun setRealtimeSender(sender: (RealtimeEnvelope) -> Boolean) {
        realtimeSend = sender
    }

    fun startCall(callType: String) {
        val conversation = _ui.value.selectedConversation ?: return
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
            runCatching(repository::loadConversations).fold(
                onSuccess = { conversations ->
                    scopedCache.replace(conversations)
                    val userIds = conversations.mapNotNull { it.otherUserId }.distinct()
                    val presence = runCatching { repository.loadPresence(userIds) }.getOrDefault(emptyMap())
                    _ui.value = _ui.value.copy(
                        loading = false,
                        conversations = conversations,
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
                        conversations = cached,
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
                    )
                }
                return
            }
            RealtimeEvent.ChatPin -> {
                val pin = decodeChatRealtime<ChatPinEvent>(event.data) ?: return
                if (_ui.value.selectedConversation?.id == pin.conversationId) {
                    _ui.value = _ui.value.copy(messages = applyRealtimePin(_ui.value.messages, pin))
                }
                return
            }
            else -> Unit
        }
        if (!shouldRefreshConversationList(event.type)) return
        // Reconnect replay and multi-device fan-out can deliver a burst of
        // equivalent invalidations. One authoritative refresh after a short
        // coalescing window is enough and avoids overlapping REST/cache writes.
        realtimeRefresh?.cancel()
        realtimeRefresh = viewModelScope.launch {
            delay(150)
            refresh()
            if (_ui.value.selectedConversation != null) refreshThread()
        }
    }

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
            jumpToMessageId = null,
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
        markRead(conversation)
        refreshThread()
        // Members feed @mention suggestions (web MentionInput uses convMembers).
        viewModelScope.launch(Dispatchers.IO) {
            val members = runCatching { repository.loadMembers(conversation.id) }.getOrNull() ?: return@launch
            if (_ui.value.selectedConversation?.id == conversation.id) _ui.value = _ui.value.copy(members = members)
        }
    }

    fun closeConversation() {
        typingExpiry?.cancel()
        typingDispatch?.cancel()
        _ui.value = _ui.value.copy(
            selectedConversation = null,
            messages = emptyList(),
            queuedMessages = emptyList(),
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
                    _ui.value = _ui.value.copy(
                        messages = applyRealtimePin(
                            _ui.value.messages,
                            ChatPinEvent(message.id, conversationId, response.pinned, scope?.userId),
                        ),
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
        _ui.value = _ui.value.copy(hiddenMessageIds = hidden, selectedMessageIds = emptySet())
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

    fun upload(uri: Uri, mimeOverride: String? = null, keepComposer: Boolean = false) {
        val conversation = _ui.value.selectedConversation ?: return
        val appContext = context ?: return
        _ui.value = _ui.value.copy(uploading = true, uploadProgress = 0f, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = appContext.contentResolver
                var name = "file"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0) ?: "file"
                        size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                    }
                }
                val mime = mimeOverride ?: resolver.getType(uri) ?: "application/octet-stream"
                if (size > MAX_CHAT_FILE_BYTES) throw IllegalArgumentException("Files must be 25 MB or smaller")
                val bytes = resolver.openInputStream(uri)?.use { input ->
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
                validateChatUpload(name, mime, bytes.size.toLong())?.let { throw IllegalArgumentException(it) }
                repository.uploadFile(
                    conversation.id,
                    ChatUpload(name, mime, bytes, if (keepComposer) null else _ui.value.composer.trim().ifBlank { null }),
                ) { sent, total ->
                    if (total > 0) _ui.value = _ui.value.copy(uploadProgress = (sent.toFloat() / total).coerceIn(0f, 1f))
                }
            }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(uploading = false, uploadProgress = null, composer = if (keepComposer) _ui.value.composer else "")
                    if (!keepComposer) saveDraft(conversation.id, "")
                    refreshThread()
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(uploading = false, uploadProgress = null, error = it.message ?: "File upload failed") },
            )
        }
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

    fun togglePin() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.togglePinConversation(current.id) }.fold(
                onSuccess = { updateSelected(current.copy(isPinned = it.pinned)); refresh() },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not update pin") },
            )
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setMute(current.id, if (current.isMuted) null else "always") }.fold(
                onSuccess = { result -> updateSelected(current.copy(isMuted = result.muted)) },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not update mute") },
            )
        }
    }

    fun toggleArchive() {
        val current = _ui.value.selectedConversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.toggleArchive(current.id) }.fold(
                onSuccess = { result ->
                    updateSelected(current.copy(isArchived = result.archived))
                    closeConversation()
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(error = it.message ?: "Could not archive conversation") },
            )
        }
    }

    private fun updateSelected(conversation: ChatConversation) {
        _ui.value = _ui.value.copy(
            selectedConversation = conversation,
            conversations = _ui.value.conversations.map { if (it.id == conversation.id) conversation else it },
        )
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
    fun loadSavedMessages() = loadInfo { InfoContent.Messages(repository.loadStarredMessages()) }
    fun loadSharedFiles() = loadInfo { InfoContent.Files(repository.loadSharedFiles(it.id)) }
    fun searchInConversation(term: String) {
        if (term.trim().length < 2) { _ui.value = _ui.value.copy(infoContent = InfoContent.Messages(emptyList())); return }
        loadInfo { InfoContent.Messages(repository.searchMessages(term.trim(), it.id)) }
    }

    fun toggleBlock() {
        val current = _ui.value.selectedConversation ?: return
        val userId = current.otherUserId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { if (current.isBlocked) repository.unblockUser(userId) else repository.blockUser(userId) }.fold(
                onSuccess = { updateSelected(current.copy(isBlocked = it.blocked)); refresh() },
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
                ).also { it.context = context.applicationContext } as T
            }
        }
    }

    private var context: Context? = null
}