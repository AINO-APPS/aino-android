package app.aino.mobile.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.db.AinoDatabase
import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val loading: Boolean = false,
    val conversations: List<ChatConversation> = emptyList(),
    val presence: Map<Long, ChatPresence> = emptyMap(),
    val fromCache: Boolean = false,
    val userSearch: String = "",
    val userResults: List<ChatUser> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val unread: Int get() = totalUnread(conversations)
}

class ChatViewModel(
    private val repository: ChatRepository,
    private val cacheFactory: (CacheScope) -> ChatCache,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()
    private var scope: CacheScope? = null
    private var cache: ChatCache? = null

    fun setScope(tenantId: Long?, userId: Long?) {
        val next = if (tenantId != null && tenantId > 0 && userId != null && userId > 0) {
            CacheScope(tenantId, userId)
        } else null
        if (next == scope) return
        scope = next
        cache = next?.let(cacheFactory)
        _ui.value = ChatUiState()
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
        if (shouldRefreshConversationList(type)) refresh()
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

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens)
                val dao = AinoDatabase.get(context).dao()
                return ChatViewModel(
                    ChatRepository(api),
                    { scope -> ChatCache(scope, ScopedCache(scope, dao)) },
                ) as T
            }
        }
    }
}