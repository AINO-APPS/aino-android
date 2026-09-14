package app.aino.mobile.feature.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val loading: Boolean = false,
    val tab: ProfileTab = ProfileTab.Account,
    val user: ProfileUser? = null,
    val faceEnrolled: Boolean = false,
    val editing: Boolean = false,
    val draftName: String = "",
    val draftUsername: String = "",
    val draftEmail: String = "",
    val searchTerm: String = "",
    val searchResults: SearchResults = SearchResults(),
    val searching: Boolean = false,
    val searchRan: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class ProfileViewModel(private val repository: ProfileRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ProfileUiState())
    val ui: StateFlow<ProfileUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val user = repository.load()
                // Face enrolment is a tenant feature; a missing route or a
                // platform context must not break the profile screen.
                val face = runCatching(repository::faceStatus).getOrNull()
                user to (face?.enrolled ?: false)
            }.fold(
                onSuccess = { (user, faceEnrolled) ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        user = user,
                        faceEnrolled = faceEnrolled,
                        draftName = user.fullName.orEmpty(),
                        draftUsername = user.username,
                        draftEmail = user.email.orEmpty(),
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not load your profile") },
            )
        }
    }

    fun selectTab(tab: ProfileTab) { _ui.value = _ui.value.copy(tab = tab, error = null, message = null) }

    fun startEditing() { _ui.value = _ui.value.copy(editing = true, error = null, message = null) }

    fun cancelEditing() {
        val user = _ui.value.user
        _ui.value = _ui.value.copy(
            editing = false,
            draftName = user?.fullName.orEmpty(),
            draftUsername = user?.username.orEmpty(),
            draftEmail = user?.email.orEmpty(),
            error = null,
        )
    }

    fun updateDraft(name: String? = null, username: String? = null, email: String? = null) {
        _ui.value = _ui.value.copy(
            draftName = name ?: _ui.value.draftName,
            draftUsername = username ?: _ui.value.draftUsername,
            draftEmail = email ?: _ui.value.draftEmail,
            error = null,
        )
    }

    fun saveProfile() {
        val current = _ui.value
        validateProfileEdit(current.draftName, current.draftUsername)?.let {
            _ui.value = current.copy(error = it)
            return
        }
        _ui.value = current.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.updateProfile(current.draftName, current.draftUsername) }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(loading = false, editing = false, message = "Profile updated")
                    refresh()
                },
                // "Username already taken" is only knowable server-side.
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not update your profile") },
            )
        }
    }

    fun saveEmail() {
        val current = _ui.value
        validateEmail(current.draftEmail)?.let {
            _ui.value = current.copy(error = it)
            return
        }
        _ui.value = current.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.updateEmail(current.draftEmail) }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(loading = false, message = "Email updated")
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not update your email") },
            )
        }
    }

    fun updateSearchTerm(term: String) { _ui.value = _ui.value.copy(searchTerm = term, error = null) }

    fun search() {
        val term = _ui.value.searchTerm
        if (!searchable(term)) {
            _ui.value = _ui.value.copy(error = "Enter at least $SEARCH_MIN_LENGTH characters to search")
            return
        }
        _ui.value = _ui.value.copy(searching = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.search(term) }.fold(
                onSuccess = { _ui.value = _ui.value.copy(searching = false, searchResults = it, searchRan = true) },
                onFailure = { _ui.value = _ui.value.copy(searching = false, error = it.message ?: "Search failed") },
            )
        }
    }

    fun clearSearch() {
        _ui.value = _ui.value.copy(searchTerm = "", searchResults = SearchResults(), searchRan = false, error = null)
    }

    fun clearNotices() { _ui.value = _ui.value.copy(error = null, message = null) }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens)
                return ProfileViewModel(ProfileRepository(api)) as T
            }
        }
    }
}
