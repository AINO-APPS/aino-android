package app.aino.mobile.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Group settings screens: what is loaded and in flight. The conversation itself stays in [ChatUiState]. */
data class GroupSettingsUiState(
    val members: List<ConversationMember> = emptyList(),
    val membersLoading: Boolean = false,
    val inviteLink: InviteLinkState? = null,
    val inviteLoading: Boolean = false,
    val joinRequests: List<JoinRequest> = emptyList(),
    val requestsLoading: Boolean = false,
    val candidates: List<ChatUser> = emptyList(),
    val candidateQuery: String = "",
    val searching: Boolean = false,
    val avatarUploading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/**
 * Group management (Signal-style group settings): members, roles, ownership,
 * group info and photo, posting/adding permissions, the group link and join
 * requests. Every action is server-authoritative; on success the conversation
 * row is refreshed through [onGroupChanged].
 */
class GroupSettingsViewModel(
    private val repository: ChatRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val prepareAvatar: (Uri) -> app.aino.mobile.core.media.PreparedAvatar? = { null },
) : ViewModel() {
    private val _ui = MutableStateFlow(GroupSettingsUiState())
    val ui: StateFlow<GroupSettingsUiState> = _ui.asStateFlow()
    private var conversationId: Long? = null
    private var currentUserId: Long? = null
    private var searchJob: Job? = null
    private var onGroupChanged: () -> Unit = {}
    private var onLeft: () -> Unit = {}

    fun bind(conversationId: Long, currentUserId: Long?, initialMembers: List<ConversationMember>, onGroupChanged: () -> Unit, onLeft: () -> Unit) {
        this.onGroupChanged = onGroupChanged
        this.onLeft = onLeft
        this.currentUserId = currentUserId
        if (this.conversationId == conversationId) return
        this.conversationId = conversationId
        _ui.value = GroupSettingsUiState(members = sortMembers(initialMembers))
        loadMembers()
    }

    fun loadMembers() = launchFor { id ->
        _ui.update { it.copy(membersLoading = true) }
        val members = repository.loadMembers(id)
        _ui.update { it.copy(members = sortMembers(members), membersLoading = false) }
    }

    // ── Members ──────────────────────────────────────────────────────────

    fun searchCandidates(query: String, debounceMs: Long = 300) {
        searchJob?.cancel()
        _ui.update { it.copy(candidateQuery = query) }
        val term = query.trim()
        if (term.length < 2) {
            _ui.update { it.copy(candidates = emptyList(), searching = false) }
            return
        }
        _ui.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch(io) {
            delay(debounceMs)
            val found = runCatching { repository.searchUsers(term) }.getOrElse {
                _ui.update { st -> st.copy(searching = false, error = it.message ?: "Search failed") }
                return@launch
            }
            if (_ui.value.candidateQuery.trim() != term) return@launch
            val existing = _ui.value.members.map { it.id }.toSet()
            _ui.update { it.copy(candidates = found.filter { user -> user.id !in existing && user.id != currentUserId }, searching = false) }
        }
    }

    fun addMembers(userIds: Collection<Long>, onDone: () -> Unit = {}) {
        if (userIds.isEmpty()) return
        mutate("Could not add members", done = { _ui.update { it.copy(candidateQuery = "", candidates = emptyList()) }; onDone() }) { id ->
            repository.addMembers(id, userIds.toList())
            "${userIds.size} ${if (userIds.size == 1) "person" else "people"} added"
        }
    }

    fun removeMember(member: ConversationMember) = mutate("Could not remove ${member.display()}") { id ->
        repository.removeMember(id, member.id)
        _ui.update { st -> st.copy(members = st.members.filterNot { it.id == member.id }) }
        "${member.display()} removed"
    }

    fun setAdmin(member: ConversationMember, admin: Boolean) = mutate("Could not change role") { id ->
        val role = repository.setParticipantRole(id, member.id, if (admin) "admin" else "member").role
        _ui.update { st -> st.copy(members = sortMembers(st.members.map { if (it.id == member.id) it.copy(role = role) else it })) }
        if (admin) "${member.display()} is now an admin" else "${member.display()} is no longer an admin"
    }

    fun transferOwnership(member: ConversationMember) = mutate("Could not transfer ownership") { id ->
        repository.transferOwner(id, member.id)
        _ui.update { st ->
            st.copy(members = sortMembers(st.members.map {
                when (it.id) {
                    member.id -> it.copy(role = "owner")
                    currentUserId -> it.copy(role = "admin")
                    else -> it
                }
            }))
        }
        "${member.display()} is now the group owner"
    }

    fun leave() = mutate("Could not leave group", done = { onLeft() }) { id -> repository.leaveGroup(id); null }

    // ── Group info ──────────────────────────────────────────────────────

    fun saveInfo(name: String, description: String, original: ChatConversation, onDone: () -> Unit = {}) {
        val trimmedName = name.trim()
        val trimmedDescription = description.trim()
        val request = GroupUpdateRequest(
            name = trimmedName.takeIf { it.isNotEmpty() && it != original.groupName },
            description = trimmedDescription.takeIf { it != original.groupDescription.orEmpty() },
        )
        if (request.name == null && request.description == null) return onDone()
        mutate("Could not update group", done = onDone) { id -> repository.updateGroup(id, request); null }
    }

    fun uploadAvatar(uri: Uri) {
        if (_ui.value.avatarUploading) return
        _ui.update { it.copy(avatarUploading = true, error = null) }
        launchFor({ _ui.update { st -> st.copy(avatarUploading = false, error = it.message ?: "Could not update the group photo") } }) { id ->
            val prepared = prepareAvatar(uri) ?: throw IllegalStateException("That image can't be used")
            repository.uploadGroupAvatar(id, prepared.fileName, prepared.mimeType, prepared.bytes)
            _ui.update { it.copy(avatarUploading = false, message = "Group photo updated") }
            onGroupChanged()
        }
    }

    fun removeAvatar() = mutate("Could not remove the group photo") { id -> repository.removeGroupAvatar(id); "Group photo removed" }

    /** Owner only: who may send messages / add members (`all` | `admins`). */
    fun setPolicies(postPolicy: String? = null, addPolicy: String? = null) = mutate("Could not update permissions") { id ->
        repository.updateGroup(id, GroupUpdateRequest(postPolicy = postPolicy, addPolicy = addPolicy))
        null
    }

    // ── Group link and requests ─────────────────────────────────────────

    fun loadInviteLink() = launchFor({ _ui.update { st -> st.copy(inviteLoading = false, error = it.message ?: "Could not load the group link") } }) { id ->
        _ui.update { it.copy(inviteLoading = true) }
        val link = repository.inviteLink(id)
        _ui.update { it.copy(inviteLink = link, inviteLoading = false) }
    }

    fun setInviteEnabled(enabled: Boolean) = updateLink { id -> repository.updateInviteLink(id, InviteLinkUpdate(enabled = enabled)) }

    fun setRequiresApproval(required: Boolean) = updateLink { id -> repository.updateInviteLink(id, InviteLinkUpdate(requiresApproval = required)) }

    fun resetInviteLink() = updateLink(message = "Group link reset. The old link no longer works.") { id -> repository.resetInviteLink(id) }

    fun loadJoinRequests() = launchFor({ _ui.update { st -> st.copy(requestsLoading = false, error = it.message ?: "Could not load requests") } }) { id ->
        _ui.update { it.copy(requestsLoading = true) }
        val requests = repository.joinRequests(id)
        _ui.update { it.copy(joinRequests = requests, requestsLoading = false) }
    }

    fun resolveRequest(request: JoinRequest, approve: Boolean) = mutate(if (approve) "Could not approve" else "Could not deny") { id ->
        repository.resolveJoinRequest(id, request.id, approve)
        _ui.update { st ->
            st.copy(
                joinRequests = st.joinRequests.filterNot { it.id == request.id },
                inviteLink = st.inviteLink?.let { it.copy(pendingRequests = (it.pendingRequests - 1).coerceAtLeast(0)) },
            )
        }
        if (approve) { loadMembersNow(id); "${request.display()} added" } else "Request denied"
    }

    fun consumeMessage() = _ui.update { it.copy(message = null, error = null) }

    // ── plumbing ────────────────────────────────────────────────────────

    private suspend fun loadMembersNow(id: Long) {
        runCatching { repository.loadMembers(id) }.onSuccess { members -> _ui.update { it.copy(members = sortMembers(members)) } }
    }

    private fun updateLink(message: String? = null, block: suspend (Long) -> InviteLinkState) =
        mutate("Could not update the group link", refreshGroup = false) { id ->
            val link = block(id)
            _ui.update { it.copy(inviteLink = link) }
            message
        }

    /** Runs a server action, then refreshes members and the conversation row; returns a confirmation message. */
    private fun mutate(failure: String, refreshGroup: Boolean = true, done: () -> Unit = {}, block: suspend (Long) -> String?) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        launchFor({ _ui.update { st -> st.copy(busy = false, error = it.message ?: failure) } }) { id ->
            val message = block(id)
            if (refreshGroup) loadMembersNow(id)
            _ui.update { it.copy(busy = false, message = message) }
            if (refreshGroup) onGroupChanged()
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { done() }
        }
    }

    private fun launchFor(onError: (Throwable) -> Unit = { e -> _ui.update { it.copy(membersLoading = false, error = e.message) } }, block: suspend (Long) -> Unit) {
        val id = conversationId ?: return
        viewModelScope.launch(io) {
            runCatching { block(id) }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                onError(error)
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                return GroupSettingsViewModel(
                    ChatRepository(app.aino.mobile.core.AppContainer.get(appContext).api),
                    prepareAvatar = { uri -> app.aino.mobile.core.media.prepareSquareAvatar(appContext, uri) },
                ) as T
            }
        }
    }
}
