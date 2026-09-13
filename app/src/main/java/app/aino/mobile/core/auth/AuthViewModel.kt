package app.aino.mobile.core.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.OkHttpApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val state: AuthState = AuthState.Initializing,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val state = runCatching(repository::restoreSession).getOrElse { AuthState.SignedOut }
            _ui.value = AuthUiState(state = state)
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _ui.update { it.copy(error = "Username and password are required") }
            return
        }
        execute { repository.login(username, password) }
    }

    fun chooseRealm(realm: String) {
        val choice = _ui.value.state as? AuthState.ChoosingRealm ?: return
        execute { repository.chooseRealm(choice.ticket, realm) }
    }

    fun cancelRealmChoice() {
        _ui.value = AuthUiState(state = AuthState.SignedOut)
    }

    fun changePassword(current: String, next: String, confirmation: String) {
        validatePasswordChange(current, next, confirmation)?.let { error ->
            _ui.update { it.copy(error = error) }
            return
        }
        execute(successMessage = "Password changed. Sign in with your new password.") {
            repository.changePassword(current, next)
        }
    }

    fun logout() {
        execute { repository.logout(); AuthState.SignedOut }
    }

    fun clearMessage() = _ui.update { it.copy(error = null, message = null) }

    private fun execute(successMessage: String? = null, action: () -> AuthState) {
        if (_ui.value.loading) return
        _ui.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(action).fold(
                onSuccess = { state -> _ui.value = AuthUiState(state = state, message = successMessage) },
                onFailure = { error ->
                    _ui.update { it.copy(loading = false, error = error.message ?: "Request failed") }
                },
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = OkHttpApiClient(tokenProvider = tokens)
                return AuthViewModel(AuthRepository(api, tokens)) as T
            }
        }
    }
}