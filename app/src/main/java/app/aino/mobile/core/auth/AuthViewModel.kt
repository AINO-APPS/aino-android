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
    /** Admin step-up: ask for an authenticator code before enrolling a device credential. */
    val stepUp: StepUpPrompt? = null,
)

data class StepUpPrompt(
    val message: String,
    val error: String? = null,
    val submitting: Boolean = false,
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
            // Sessions never time out, so a rejected stored credential was revoked (password change, removal, sign-out elsewhere).
            val pending = SessionRevocation.consumePendingMessage(context)
            val message = pending?.takeIf { state is AuthState.SignedOut }
                ?: SIGNED_IN_ELSEWHERE.takeIf { hadCredential && restored.isSuccess && state is AuthState.SignedOut }
            _ui.value = AuthUiState(state = state, message = message, biometricEnrolled = biometricCredentials.isEnrolled())
        }
        // A session_revoked push already cleared the credential while this UI runs.
        viewModelScope.launch {
            SessionRevocation.revoked.collect { message ->
                if (_ui.value.state is AuthState.Authenticated) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) { signOutRevoked(message) }
                }
            }
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
        app.aino.mobile.core.media.VideoCache.clear(context)
        app.aino.mobile.core.notifications.NotificationSoundPrefs.clear(context)
        app.aino.mobile.core.push.PushTokenRegistrar.forget(context)
        // The previous account's messages and alerts must not linger (or badge the icon).
        app.aino.mobile.core.push.NotificationReconciler.clearAll(context)
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
     * `onEnrolled` runs on success (e.g. continue a pending clock-in);
     * `onFailed` runs (on Main) when enrollment fails or is cancelled.
     */
    fun enrollBiometric(
        authenticatedCipher: Cipher,
        deviceLabel: String,
        onEnrolled: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { completeEnrollment(authenticatedCipher, deviceLabel, onEnrolled, freshStepUpToken()) }
                .onFailure { error ->
                    if (error is AuthFailure && error.code == MFA_STEP_UP_REQUIRED) {
                        pendingEnrollment = PendingEnrollment(authenticatedCipher, deviceLabel, onEnrolled, onFailed)
                        _ui.update {
                            it.copy(
                                error = null,
                                stepUp = StepUpPrompt(error.message ?: "Confirm it's you: enter the code from your authenticator app."),
                            )
                        }
                    } else {
                        val message = error.message ?: "Could not enable fingerprint"
                        _ui.update { it.copy(error = message) }
                        notifyFailed(onFailed, message)
                    }
                }
        }
    }

    /** Confirm the authenticator / recovery code, then finish the enrollment that asked for it. */
    fun submitStepUpCode(code: String) {
        val prompt = _ui.value.stepUp ?: return
        val pending = pendingEnrollment ?: return cancelStepUp()
        if (prompt.submitting) return
        if (code.isBlank()) {
            _ui.update { it.copy(stepUp = prompt.copy(error = "Enter the code from your authenticator app.")) }
            return
        }
        _ui.update { it.copy(stepUp = prompt.copy(submitting = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            val token = runCatching { repository.stepUp(code) }.getOrElse { error ->
                _ui.update { state ->
                    state.copy(stepUp = state.stepUp?.copy(submitting = false, error = error.message ?: "That code is not valid."))
                }
                return@launch
            }
            stepUpToken = token to System.currentTimeMillis()
            pendingEnrollment = null
            _ui.update { it.copy(stepUp = null) }
            runCatching { completeEnrollment(pending.cipher, pending.deviceLabel, pending.onEnrolled, token) }
                .onFailure { error ->
                    val message = error.message ?: "Could not enable fingerprint"
                    _ui.update { it.copy(error = message) }
                    notifyFailed(pending.onFailed, message)
                }
        }
    }

    fun cancelStepUp() {
        val pending = pendingEnrollment
        pendingEnrollment = null
        val message = "Fingerprint / PIN sign-in was not enabled: verification cancelled."
        _ui.update { it.copy(stepUp = null, error = message) }
        notifyFailed(pending?.onFailed, message)
    }

    private fun notifyFailed(onFailed: ((String) -> Unit)?, message: String) {
        onFailed?.let { callback -> viewModelScope.launch(Dispatchers.Main) { callback(message) } }
    }

    private fun completeEnrollment(cipher: Cipher, deviceLabel: String, onEnrolled: (() -> Unit)?, stepUp: String?) {
        val credential = repository.enrollBiometric(deviceLabel, stepUp)
        try {
            biometricCredentials.save(credential, cipher)
        } catch (error: Exception) {
            // The fingerprint / PIN confirmation expired while the code was entered.
            repository.revokeBiometric(credential.credentialId)
            throw IllegalStateException("Verification took too long. Tap \"Enable fingerprint / PIN\" again.", error)
        }
        _ui.update { it.copy(biometricEnrolled = true, message = "Fingerprint enabled for sign-in and clock-in.", error = null) }
        onEnrolled?.let { callback -> viewModelScope.launch(Dispatchers.Main) { callback() } }
    }

    /** A step-up token stays valid server-side for 10 minutes; reuse it a little less than that. */
    private fun freshStepUpToken(): String? =
        stepUpToken?.takeIf { System.currentTimeMillis() - it.second < STEP_UP_REUSE_MS }?.first

    private class PendingEnrollment(
        val cipher: Cipher,
        val deviceLabel: String,
        val onEnrolled: (() -> Unit)?,
        val onFailed: ((String) -> Unit)?,
    )

    @Volatile private var pendingEnrollment: PendingEnrollment? = null
    @Volatile private var stepUpToken: Pair<String, Long>? = null

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
     * longer expire and other devices no longer replace them (one session per
     * device), so it was revoked: sign-out, password change or removal. Confirm
     * with the API: sign out with an
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

    /**
     * The server closed this device's socket because the account signed in on
     * another phone. Drop the credential and local data right away.
     */
    fun onSessionRevoked(closeReason: String?) {
        if (_ui.value.state !is AuthState.Authenticated) return
        viewModelScope.launch(Dispatchers.IO) { signOutRevoked(SessionRevocation.messageFor(closeReason)) }
    }

    private suspend fun signOutRevoked(message: String) {
        stepUpToken = null
        repository.clearLocalCredential()
        persistOrClearScope(AuthState.SignedOut)
        wipeUserMedia()
        SessionRevocation.clearPendingMessage(context)
        _ui.value = AuthUiState(state = AuthState.SignedOut, message = message, biometricEnrolled = biometricCredentials.isEnrolled())
    }

    fun clearMessage() = _ui.update { it.copy(error = null, message = null) }

    /** Re-hydrate feature gates after a degraded session (P0.2 Retry action). */
    fun retryFeatureHydration() {
        execute { repository.refreshFeatures() }
    }

    private fun execute(successMessage: String? = null, action: () -> AuthState) {
        if (_ui.value.loading) return
        stepUpToken = null
        _ui.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(action).fold(
                onSuccess = { state ->
                    if (state is AuthState.Authenticated) SessionRevocation.clearPendingMessage(context)
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
        stepUpToken = null
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
                // The previous user's cached responses must not outlive their session.
                app.aino.mobile.core.AppContainer.get(context).responses.apply { this.scope = null; clearAll() }
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
        val responses = app.aino.mobile.core.AppContainer.get(context).responses
        if (tenantId != null && user.id > 0) {
            val next = CacheScope(tenantId, user.id)
            // A different user signed in: never show their predecessor's data.
            if (scopeStore.read().let { it != null && it != next }) responses.clearAll()
            responses.scope = "${tenantId}_${user.id}"
            scopeStore.save(next)
        } else if (state is AuthState.SignedOut) {
            responses.scope = null
            responses.clearAll()
            val stale = scopeStore.read()
            if (stale != null) {
                OutboxWorker.cancel(context, stale)
                AinoDatabase.get(context).dao().clearScope(stale.tenantId, stale.userId)
            }
            scopeStore.clear()
        }
    }

    companion object {
        const val SIGNED_IN_ELSEWHERE = "Your session ended. Please sign in again."
        private const val STEP_UP_REUSE_MS = 9 * 60 * 1000L

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