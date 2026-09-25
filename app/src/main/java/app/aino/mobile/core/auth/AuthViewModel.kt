package app.aino.mobile.core.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import app.aino.mobile.core.db.AinoDatabase
import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.OutboxWorker
import app.aino.mobile.core.db.SessionScopeStore
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
    private val context: Context,
) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUiState(biometricEnrolled = biometricCredentials.isEnrolled()))
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()
    private val lastActivitySentAt = AtomicLong(0)
    private val scopeStore = SessionScopeStore(context)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val restored = runCatching(repository::restoreSession)
            val state = restored.getOrElse { AuthState.SignedOut }
            // A 401/403 clears the token in AuthRepository and may safely wipe
            // the exact stored scope. A transient network failure leaves the
            // credential intact, so preserve offline cache for the next retry.
            if (restored.isSuccess || !repository.hasStoredCredential()) persistOrClearScope(state)
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
        executeWithScopeCleanup("Password changed. Sign in with your new password.") {
            repository.changePassword(current, next)
        }
    }

    fun logout() {
        executeWithScopeCleanup {
            repository.logout()
            wipeUserMedia()
            AuthState.SignedOut
        }
    }

    /** Photos and sound prefs belong to the signed-in user; drop them on sign-out. */
    private fun wipeUserMedia() {
        val loader = app.aino.mobile.core.AppContainer.get(context).imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        app.aino.mobile.core.notifications.NotificationSoundPrefs.clear(context)
    }

    /** Web `updateUser`: patch the signed-in user after a profile/avatar change. */
    fun updateUser(transform: (AinoUser) -> AinoUser) {
        _ui.update { current ->
            val state = current.state
            if (state is AuthState.Authenticated) current.copy(state = state.copy(user = transform(state.user))) else current
        }
    }

    fun biometricCredentialId(): String? = biometricCredentials.credentialId()

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

    /** Re-hydrate feature gates after a degraded session (P0.2 Retry action). */
    fun retryFeatureHydration() {
        execute { repository.refreshFeatures() }
    }

    private fun execute(successMessage: String? = null, action: () -> AuthState) {
        if (_ui.value.loading) return
        _ui.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(action).fold(
                onSuccess = { state ->
                    persistOrClearScope(state)
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

    private fun executeWithScopeCleanup(successMessage: String? = null, action: () -> AuthState) {
        if (_ui.value.loading) return
        val user = when (val state = _ui.value.state) {
            is AuthState.Authenticated -> state.user
            is AuthState.PasswordChangeRequired -> state.user
            else -> null
        }
        _ui.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val state = action()
                val currentScope = user?.tenantId?.let { tenantId ->
                    if (user.id > 0) CacheScope(tenantId, user.id) else null
                } ?: scopeStore.read()
                if (currentScope != null) {
                    val scope = currentScope
                    OutboxWorker.cancel(context, scope)
                    AinoDatabase.get(context).dao().clearScope(scope.tenantId, scope.userId)
                }
                scopeStore.clear()
                state
            }.fold(
                onSuccess = { state ->
                    _ui.value = AuthUiState(
                        state = state,
                        message = successMessage,
                        biometricEnrolled = biometricCredentials.isEnrolled(),
                    )
                },
                onFailure = { error -> _ui.update { it.copy(loading = false, error = error.message ?: "Request failed") } },
            )
        }
    }

    private suspend fun persistOrClearScope(state: AuthState) {
        val user = when (state) {
            is AuthState.Authenticated -> state.user
            is AuthState.PasswordChangeRequired -> state.user
            else -> null
        }
        val tenantId = user?.tenantId
        if (tenantId != null && user.id > 0) {
            scopeStore.save(CacheScope(tenantId, user.id))
        } else if (state is AuthState.SignedOut) {
            val stale = scopeStore.read()
            if (stale != null) {
                OutboxWorker.cancel(context, stale)
                AinoDatabase.get(context).dao().clearScope(stale.tenantId, stale.userId)
            }
            scopeStore.clear()
        }
    }

    companion object {
        private const val ACTIVITY_INTERVAL_MS = 60_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                val tokens = container.tokens
                val api = container.api
                return AuthViewModel(AuthRepository(api, tokens), BiometricCredentialStore(context), context.applicationContext) as T
            }
        }
    }
}