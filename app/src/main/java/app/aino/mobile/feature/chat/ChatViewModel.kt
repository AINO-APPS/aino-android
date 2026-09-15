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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.UUID

data class ChatUiState(
    val currentUserId: Long? = null,
    val loading: Boolean = false,
    val conversations: List<ChatConversation> = emptyList(),
    val presence: Map<Long, ChatPresence> = emptyMap(),
    val fromCache: Boolean = false,
    val userSearch: String = "",
    val userResults: List<ChatUser> = emptyList(),
    val searching: Boolean = false,
    val selectedConversation: ChatConversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val queuedMessages: List<QueuedMessage> = emptyList(),
    val receipts: List<ReadReceipt> = emptyList(),
    val threadLoading: Boolean = false,
    val threadFromCache: Boolean = false,
    val composer: String = "",
    val uploading: Boolean = false,
    val calls: List<CallLog> = emptyList(),
    val callsLoading: Boolean = false,
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
    private val enqueueText: suspend (CacheScope, Long, String, Long, String) -> String,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()
    private var scope: CacheScope? = null
    private var cache: ChatCache? = null
    private var realtimeRefresh: Job? = null

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

    fun onRealtimeEvent(type: String) {
        if (!shouldRefreshConversationList(type)) return
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
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        loading = false,
                        userSearch = "",
                        userResults = emptyList(),
                        message = "Conversation ready",
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

    fun openConversation(conversation: ChatConversation) {
        _ui.value = _ui.value.copy(
            selectedConversation = conversation,
            messages = emptyList(),
            queuedMessages = emptyList(),
            receipts = emptyList(),
            composer = "",
            error = null,
        )
        markRead(conversation)
        refreshThread()
    }

    fun closeConversation() {
        _ui.value = _ui.value.copy(
            selectedConversation = null,
            messages = emptyList(),
            queuedMessages = emptyList(),
            receipts = emptyList(),
            composer = "",
            threadFromCache = false,
        )
    }

    fun updateComposer(value: String) {
        _ui.value = _ui.value.copy(composer = value.take(5_000), error = null)
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
                        // REST rows omit clientMsgId. Reconcile one-to-one by
                        // own-message chronology rather than deleting every
                        // queued bubble when a single echo appears.
                        queuedMessages = reconcileQueuedMessages(_ui.value.queuedMessages, messages, scope?.userId),
                        receipts = receipts,
                        threadFromCache = false,
                    )
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

    fun sendMessage() {
        val currentScope = scope ?: return
        val conversation = _ui.value.selectedConversation ?: return
        val content = _ui.value.composer.trim()
        if (content.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Message content is required")
            return
        }
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val queued = QueuedMessage(clientId, conversation.id, currentScope.userId, content, now)
        _ui.value = _ui.value.copy(composer = "", queuedMessages = _ui.value.queuedMessages + queued, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { enqueueText(currentScope, conversation.id, content, now, clientId) }.onFailure {
                _ui.value = _ui.value.copy(
                    queuedMessages = _ui.value.queuedMessages.filterNot { it.clientMessageId == clientId },
                    composer = content,
                    error = it.message ?: "Could not queue message",
                )
            }
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

    fun upload(uri: Uri) {
        val conversation = _ui.value.selectedConversation ?: return
        val appContext = context ?: return
        _ui.value = _ui.value.copy(uploading = true, error = null)
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
                val mime = resolver.getType(uri) ?: "application/octet-stream"
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
                repository.uploadFile(conversation.id, ChatUpload(name, mime, bytes, _ui.value.composer.trim().ifBlank { null }))
            }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(uploading = false, composer = "")
                    refreshThread()
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(uploading = false, error = it.message ?: "File upload failed") },
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

    fun openInfo() {
        val conversation = _ui.value.selectedConversation ?: return
        _ui.value = _ui.value.copy(showInfo = true, members = emptyList(), conversationCalls = emptyList(), error = null)
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

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens)
                val dao = AinoDatabase.get(context).dao()
                val outbox = OutboxCoordinator(context.applicationContext)
                return ChatViewModel(
                    ChatRepository(api),
                    { scope -> ChatCache(scope, ScopedCache(scope, dao)) },
                    { scope, conversationId, content, now, clientId ->
                        outbox.enqueueText(scope, conversationId, content, nowEpochMs = now, clientMessageId = clientId)
                    },
                ).also { it.context = context.applicationContext } as T
            }
        }
    }

    private var context: Context? = null
}