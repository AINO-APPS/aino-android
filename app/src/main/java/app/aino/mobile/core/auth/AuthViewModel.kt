package app.aino.mobile.core.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher

data class AuthUiState(
    val state: AuthState = AuthState.Initializing,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val biometricEnrolled: Boolean = false,
)

class AuthViewModel(
    private val repository: AuthRepository,
    private val biometricCredentials: BiometricCredentialStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUiState(biometricEnrolled = biometricCredentials.isEnrolled()))
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()
    private val lastActivitySentAt = AtomicLong(0)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val state = runCatching(repository::restoreSession).getOrElse { AuthState.SignedOut }
            _ui.value = AuthUiState(state = state, biometricEnrolled = biometricCredentials.isEnrolled())
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
        _ui.value = AuthUiState(state = AuthState.SignedOut, biometricEnrolled = biometricCredentials.isEnrolled())
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

    fun enrollBiometric(authenticatedCipher: Cipher, deviceLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val credential = repository.enrollBiometric(deviceLabel)
                biometricCredentials.save(credential, authenticatedCipher)
            }.fold(
                onSuccess = { _ui.update { it.copy(biometricEnrolled = true, message = "Biometric sign-in enabled.", error = null) } },
                onFailure = { error -> _ui.update { it.copy(error = error.message ?: "Could not enable biometric sign-in") } },
            )
        }
    }

    fun biometricLogin(authenticatedCipher: Cipher) {
        execute {
            try {
                repository.biometricLogin(biometricCredentials.read(authenticatedCipher))
            } catch (error: AuthFailure) {
                if (error.code == "BIOMETRIC_CREDENTIAL_INVALID") biometricCredentials.clear()
                throw error
            }
        }
    }

    fun disableBiometric() {
        biometricCredentials.clear()
        _ui.update { it.copy(biometricEnrolled = false, message = "Biometric sign-in disabled.") }
    }

    fun reportBiometricError(message: String) = _ui.update { it.copy(error = message) }

    fun encryptionCipher(): Cipher = biometricCredentials.createEncryptionCipher()
    fun decryptionCipher(): Cipher? = biometricCredentials.createDecryptionCipher()

    /**
     * Renew the server's inactivity window only after real foreground input.
     * Input callbacks can be very noisy, so at most one request is sent per minute.
     */
    fun recordUserActivity(nowMillis: Long = System.currentTimeMillis()) {
        if (_ui.value.state !is AuthState.Authenticated) return
        val previous = lastActivitySentAt.get()
        if (nowMillis - previous < ACTIVITY_INTERVAL_MS || !lastActivitySentAt.compareAndSet(previous, nowMillis)) return
        viewModelScope.launch(Dispatchers.IO) { runCatching(repository::recordActivity) }
    }

    fun clearMessage() = _ui.update { it.copy(error = null, message = null) }

    private fun execute(successMessage: String? = null, action: () -> AuthState) {
        if (_ui.value.loading) return
        _ui.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(action).fold(
                onSuccess = { state ->
                    _ui.value = AuthUiState(
                        state = state,
                        message = successMessage,
                        biometricEnrolled = biometricCredentials.isEnrolled(),
                    )
                },
                onFailure = { error ->
                    _ui.update { it.copy(loading = false, error = error.message ?: "Request failed") }
                },
            )
        }
    }

    companion object {
        private const val ACTIVITY_INTERVAL_MS = 60_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val rawApi = OkHttpApiClient(tokenProvider = tokens)
                val api = RefreshingApiClient(rawApi, tokens)
                return AuthViewModel(AuthRepository(api, tokens), BiometricCredentialStore(context)) as T
            }
        }
    }
}