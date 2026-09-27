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
    private val scopeStore = SessionScopeStore(context)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val hadCredential = repository.hasStoredCredential()
            val restored = runCatching(repository::restoreSession)
            val state = restored.getOrElse { AuthState.SignedOut }
            // A 401/403 clears the token in AuthRepository and may safely wipe
            // the exact stored scope. A transient network failure leaves the
            // credential intact, so preserve offline cache for the next retry.
            if (restored.isSuccess || !repository.hasStoredCredential()) persistOrClearScope(state)
            // Sessions never time out, so a rejected stored credential means it was replaced elsewhere.
            val message = SIGNED_IN_ELSEWHERE.takeIf { hadCredential && restored.isSuccess && state is AuthState.SignedOut }
            _ui.value = AuthUiState(state = state, message = message, biometricEnrolled = biometricCredentials.isEnrolled())
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
        app.aino.mobile.core.push.PushTokenRegistrar.forget(context)
    }

    /** Web `updateUser`: patch the signed-in user after a profile/avatar change. */
    fun updateUser(transform: (AinoUser) -> AinoUser) {
        _ui.update { current ->
            val state = current.state
            if (state is AuthState.Authenticated) current.copy(state = state.copy(user = transform(state.user))) else current
        }
    }

    fun biometricCredentialId(): String? = biometricCredentials.credentialId()

    /** Enrolled before key v2 (biometric-only): one re-enable is needed for PIN + clock-in. */
    fun biometricNeedsUpgrade(): Boolean = biometricCredentials.needsUpgrade()

    /** API 30+: the key needs a CryptoObject-bound prompt; API 26–29: time-bound after the prompt. */
    fun biometricUsesCryptoObject(): Boolean = biometricCredentials.usesCryptoObject()

    /**
     * Enroll this device once for both biometric sign-in and attendance.
     * `onEnrolled` runs on success (e.g. continue a pending clock-in).
     */
    fun enrollBiometric(authenticatedCipher: Cipher, deviceLabel: String, onEnrolled: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val credential = repository.enrollBiometric(deviceLabel)
                biometricCredentials.save(credential, authenticatedCipher)
            }.fold(
                onSuccess = {
                    _ui.update { it.copy(biometricEnrolled = true, message = "Fingerprint enabled for sign-in and clock-in.", error = null) }
                    onEnrolled?.let { callback -> kotlinx.coroutines.withContext(Dispatchers.Main) { callback() } }
                },
                onFailure = { error -> _ui.update { it.copy(error = error.message ?: "Could not enable fingerprint") } },
            )
        }
    }

    /**
     * Decrypt the device credential with an authenticated cipher for an
     * attendance request. Returns null (and wipes the local copy) when the
     * stored credential cannot be read, so the caller can offer re-enrollment.
     */
    fun unlockCredential(authenticatedCipher: Cipher): BiometricCredential? = runCatching {
        biometricCredentials.read(authenticatedCipher)
    }.getOrElse {
        biometricCredentials.clear()
        _ui.update { it.copy(biometricEnrolled = false) }
        null
    }

    /** The server rejected this device's credential (revoked elsewhere): forget it locally. */
    fun forgetInvalidCredential() {
        biometricCredentials.clear()
        _ui.update { it.copy(biometricEnrolled = false) }
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
        _ui.update { it.copy(biometricEnrolled = false, message = "Fingerprint sign-in and clock-in disabled on this device.") }
    }

    fun reportBiometricError(message: String) = _ui.update { it.copy(error = message) }

    fun encryptionCipher(): Cipher = biometricCredentials.createEncryptionCipher()
    fun decryptionCipher(): Cipher? = biometricCredentials.createDecryptionCipher()

    /** Refresh the enrolled flag (e.g. after a pre-v2 credential was dropped). */
    fun syncBiometricEnrollment() = _ui.update { it.copy(biometricEnrolled = biometricCredentials.isEnrolled()) }

    /** Drop a pre-v2 (biometric-only) credential so it can be re-enabled with PIN support. */
    fun dropLegacyCredential() {
        if (biometricCredentials.needsUpgrade()) biometricCredentials.clear()
        syncBiometricEnrollment()
    }

    /**
     * The server ended this session (the socket closed as terminal). Sessions no
     * longer expire, so under the one-session-per-user policy that means a newer
     * sign-in on another device or the web. Confirm with the API: sign out with an
     * explanation and return false; true if the session is fine. Network failures
     * keep the user signed in (and return false so callers don't reconnect-loop).
     */
    suspend fun verifySessionStillActive(): Boolean {
        if (_ui.value.state !is AuthState.Authenticated) return false
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val state = runCatching(repository::restoreSession).getOrNull() ?: return@withContext false
            if (state is AuthState.SignedOut) {
                persistOrClearScope(state)
                wipeUserMedia()
                _ui.value = AuthUiState(state = state, message = SIGNED_IN_ELSEWHERE, biometricEnrolled = biometricCredentials.isEnrolled())
                false
            } else {
                true
            }
        }
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
        const val SIGNED_IN_ELSEWHERE = "You were signed out because your account signed in on another device."

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