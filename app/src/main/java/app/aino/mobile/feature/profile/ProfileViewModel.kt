package app.aino.mobile.feature.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.notifications.NotificationSoundPrefs
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** `useAsyncAction` message: success or error text shown under a form. */
data class Notice(val ok: Boolean, val text: String)

enum class ProfileForm { Profile, Email, Password, Delete, Devices, Face }

data class ProfileUiState(
    val user: ProfileUser? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    // Status v2
    val status: StatusPayload? = null,
    val statusBusy: Boolean = false,
    // Avatar
    val avatarUploading: Boolean = false,
    val removeAvatarConfirming: Boolean = false,
    /** ProfileMenu uses `alert()` for avatar errors; shown as a dialog. */
    val alert: String? = null,
    // Edit Profile
    val draftName: String = "",
    val draftUsername: String = "",
    val draftEmail: String = "",
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val deletePassword: String = "",
    val deleteConfirming: Boolean = false,
    val busy: Set<ProfileForm> = emptySet(),
    val notices: Map<ProfileForm, Notice> = emptyMap(),
    val biometricDevices: List<BiometricDevice> = emptyList(),
    // Face enrollment
    val face: FaceStatus? = null,
    val faceLoading: Boolean = false,
    val faceClearConfirming: Boolean = false,
    // Notification sounds
    val prefs: NotificationPrefs = NotificationPrefs(),
    // Sign out
    val signOutConfirming: Boolean = false,
    val signingOut: Boolean = false,
)

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : ViewModel() {
    private val _ui = MutableStateFlow(ProfileUiState())
    val ui: StateFlow<ProfileUiState> = _ui.asStateFlow()
    private val lastPing = AtomicLong(0)
    private val noticeJobs = mutableMapOf<ProfileForm, Job>()
    private var myUserId: Long? = null

    /**
     * Session start for a tenant user (web StatusProvider / ThemeProvider /
     * NotificationPrefsProvider): resolve status, prefs and the server theme.
     */
    fun start(userId: Long, onServerTheme: (dark: Boolean) -> Unit) {
        if (myUserId == userId) return
        myUserId = userId
        _ui.value = ProfileUiState()
        io {
            runCatching(repository::getStatus).onSuccess { status -> _ui.update { it.copy(status = status) } }
        }
        io {
            runCatching(repository::notificationPrefs).onSuccess(::applyPrefs)
        }
        io {
            runCatching(repository::getTheme).onSuccess { theme ->
                if (theme.theme == "dark" || theme.theme == "light") main { onServerTheme(theme.theme == "dark") }
            }
        }
        recordActivity()
    }

    fun stop() {
        myUserId = null
        _ui.value = ProfileUiState()
    }

    /** Profile page entry: the full user row, draft fields and face status. */
    fun refresh() {
        if (_ui.value.loading) return
        _ui.update { it.copy(loading = true, loadError = null) }
        io {
            runCatching(repository::load).fold(
                onSuccess = { user ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            user = user,
                            draftName = user.fullName.orEmpty(),
                            draftUsername = user.username,
                            draftEmail = user.email.orEmpty(),
                        )
                    }
                },
                onFailure = { error -> _ui.update { it.copy(loading = false, loadError = error.message ?: "Could not load your profile") } },
            )
        }
    }

    // ── Status (StatusPicker / StatusContext) ────────────────────────────────

    fun setManualStatus(key: String) {
        _ui.update { it.copy(statusBusy = true) }
        io {
            runCatching {
                // Picking a positive status makes the user visible again.
                if (_ui.value.status?.presencePreference == "invisible") repository.setPresencePreference("auto")
                repository.setStatus(key)
            }.onSuccess { status -> _ui.update { it.copy(status = status, statusBusy = false) } }
                .onFailure { _ui.update { it.copy(statusBusy = false) } }
        }
    }

    fun toggleInvisible() {
        val next = if (_ui.value.status?.presencePreference == "invisible") "auto" else "invisible"
        _ui.update { it.copy(statusBusy = true) }
        io {
            runCatching { repository.setPresencePreference(next) }
                .onSuccess { status -> _ui.update { it.copy(status = status, statusBusy = false) } }
                .onFailure { _ui.update { it.copy(statusBusy = false) } }
        }
    }

    /** Unified `user_status` WS event; only our own row updates this state. */
    fun onStatusEvent(data: JsonElement?) {
        val payload = data?.let { runCatching { json.decodeFromJsonElement<StatusPayload>(it) }.getOrNull() } ?: return
        if (payload.userId == null || payload.userId != myUserId) return
        _ui.update { it.copy(status = payload) }
    }

    /** Throttled `activity-ping` on real input, so the resolver clears `away`. */
    fun recordActivity(nowMillis: Long = System.currentTimeMillis()) {
        if (myUserId == null) return
        val previous = lastPing.get()
        if (nowMillis - previous < ACTIVITY_PING_THROTTLE_MS || !lastPing.compareAndSet(previous, nowMillis)) return
        io { runCatching(repository::activityPing) }
    }

    // ── Theme ────────────────────────────────────────────────────────────────

    fun pushTheme(dark: Boolean) {
        if (myUserId == null) return
        io { runCatching { repository.setTheme(if (dark) "dark" else "light") } }
    }

    // ── Avatar (ProfileMenu) ─────────────────────────────────────────────────

    fun uploadAvatar(uri: Uri, onChanged: (String?) -> Unit) {
        if (_ui.value.avatarUploading) return
        _ui.update { it.copy(avatarUploading = true) }
        io {
            runCatching {
                val file = readAvatar(uri) ?: throw IllegalStateException(AVATAR_TOO_LARGE)
                repository.uploadAvatar(file.first, file.second, file.third)
            }.fold(
                onSuccess = { response -> applyAvatar(response.avatar, onChanged) },
                onFailure = { error ->
                    _ui.update { it.copy(avatarUploading = false, alert = error.message ?: "Avatar upload failed. Please try again.") }
                },
            )
        }
    }

    fun askRemoveAvatar(confirming: Boolean) = _ui.update { it.copy(removeAvatarConfirming = confirming) }

    fun removeAvatar(onChanged: (String?) -> Unit) {
        _ui.update { it.copy(removeAvatarConfirming = false) }
        io {
            runCatching(repository::removeAvatar).fold(
                onSuccess = { applyAvatar(null, onChanged) },
                onFailure = { error -> _ui.update { it.copy(alert = error.message.takeUnless { it == "An error occurred" } ?: "Failed to remove photo. Please try again.") } },
            )
        }
    }

    fun dismissAlert() = _ui.update { it.copy(alert = null) }

    private suspend fun applyAvatar(avatar: String?, onChanged: (String?) -> Unit) {
        _ui.update { it.copy(avatarUploading = false, user = it.user?.copy(avatar = avatar)) }
        main { onChanged(avatar) }
    }

    /**
     * The web uploads the original file (≤5 MB, jpg/png/webp/gif). Camera
     * photos are often larger or HEIC, so anything else is downscaled and
     * re-encoded as JPEG rather than rejected.
     */
    private fun readAvatar(uri: Uri): Triple<String, String, ByteArray>? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        var name = "avatar"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (avatarUploadableAsIs(mime, size)) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            return Triple(name, mime!!, bytes)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= AVATAR_EDGE_PX && bounds.outHeight / (sample * 2) >= AVATAR_EDGE_PX) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = AVATAR_EDGE_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val bytes = out.toByteArray()
        if (bytes.size > MAX_AVATAR_BYTES) return null
        return Triple(name.substringBeforeLast('.') + ".jpg", "image/jpeg", bytes)
    }

    // ── Edit Profile ─────────────────────────────────────────────────────────

    fun updateDraft(
        name: String? = null,
        username: String? = null,
        email: String? = null,
        currentPassword: String? = null,
        newPassword: String? = null,
        confirmPassword: String? = null,
        deletePassword: String? = null,
    ) = _ui.update {
        it.copy(
            draftName = name ?: it.draftName,
            draftUsername = username?.let(::normalizeUsernameInput) ?: it.draftUsername,
            draftEmail = email ?: it.draftEmail,
            currentPassword = currentPassword ?: it.currentPassword,
            newPassword = newPassword ?: it.newPassword,
            confirmPassword = confirmPassword ?: it.confirmPassword,
            deletePassword = deletePassword ?: it.deletePassword,
        )
    }

    fun saveProfile(onChanged: (ProfileUser) -> Unit) {
        val current = _ui.value
        validateProfileEdit(current.draftName, current.draftUsername)?.let { return notice(ProfileForm.Profile, false, it) }
        perform(ProfileForm.Profile) {
            val user = repository.updateProfile(current.draftName, current.draftUsername)
            _ui.update { it.copy(user = it.user?.copy(fullName = user.fullName, username = user.username) ?: user) }
            main { onChanged(user) }
            "Profile updated!"
        }
    }

    fun saveEmail(onChanged: (String) -> Unit) {
        val email = _ui.value.draftEmail.trim()
        validateEmail(email)?.let { return notice(ProfileForm.Email, false, it) }
        perform(ProfileForm.Email) {
            repository.updateEmail(email)
            _ui.update { it.copy(user = it.user?.copy(email = email)) }
            main { onChanged(email) }
            "Email updated!"
        }
    }

    fun changePassword() {
        val current = _ui.value
        validateProfilePassword(current.newPassword, current.confirmPassword)?.let { return notice(ProfileForm.Password, false, it) }
        perform(ProfileForm.Password) {
            repository.changePassword(current.currentPassword, current.newPassword)
            _ui.update { it.copy(currentPassword = "", newPassword = "", confirmPassword = "") }
            "Password changed successfully!"
        }
    }

    fun askDeleteAccount(confirming: Boolean) = _ui.update {
        it.copy(deleteConfirming = confirming, deletePassword = if (confirming) it.deletePassword else "", notices = it.notices - ProfileForm.Delete)
    }

    fun deleteAccount(onDeleted: () -> Unit) {
        val password = _ui.value.deletePassword
        if (password.isEmpty()) return notice(ProfileForm.Delete, false, "Please enter your password to confirm")
        perform(ProfileForm.Delete) {
            repository.deleteAccount(password)
            main { onDeleted() }
            null
        }
    }

    fun loadBiometricDevices() {
        io { runCatching(repository::biometricDevices).onSuccess { devices -> _ui.update { it.copy(biometricDevices = devices) } } }
    }

    /** Removing this device's credential also forgets it locally (back to password sign-in). */
    fun revokeBiometricDevice(id: String, thisDeviceId: String?, onThisDeviceRevoked: () -> Unit) {
        perform(ProfileForm.Devices) {
            repository.revokeBiometricDevice(id)
            if (id == thisDeviceId) main { onThisDeviceRevoked() }
            _ui.update { state -> state.copy(biometricDevices = state.biometricDevices.filterNot { it.id == id }) }
            runCatching(repository::biometricDevices).onSuccess { devices -> _ui.update { it.copy(biometricDevices = devices) } }
            "Device removed."
        }
    }

    // ── Face enrollment ──────────────────────────────────────────────────────

    fun loadFace() {
        _ui.update { it.copy(faceLoading = true) }
        io {
            val face = runCatching(repository::faceStatus).getOrNull()
            _ui.update { it.copy(faceLoading = false, face = face ?: it.face) }
        }
    }

    fun askClearFace(confirming: Boolean) = _ui.update { it.copy(faceClearConfirming = confirming) }

    fun clearFace() {
        _ui.update { it.copy(faceClearConfirming = false) }
        perform(ProfileForm.Face, errorText = "Failed to clear enrollment.") {
            repository.clearFaceEnrollment()
            _ui.update { it.copy(face = runCatching(repository::faceStatus).getOrDefault(FaceStatus())) }
            "Face enrollment cleared."
        }
    }

    // ── Notification sounds (NotificationPrefsContext) ───────────────────────

    /** Optimistic like the web; the server merges the partial update. */
    fun updatePref(key: String, value: Boolean) {
        val next = json.decodeFromJsonElement<NotificationPrefs>(
            JsonObject(json.encodeToJsonElement(_ui.value.prefs).jsonObject + (key to JsonPrimitive(value))),
        )
        applyPrefs(next)
        io { runCatching { repository.updateNotificationPrefs(JsonObject(mapOf(key to JsonPrimitive(value)))) } }
    }

    fun resetPrefs() {
        val defaults = NotificationPrefs()
        applyPrefs(defaults)
        NotificationSoundPrefs.setRingtoneUri(context, null)
        io { runCatching { repository.updateNotificationPrefs(json.encodeToJsonElement(defaults).jsonObject) } }
    }

    private fun applyPrefs(prefs: NotificationPrefs) {
        _ui.update { it.copy(prefs = prefs) }
        NotificationSoundPrefs.save(context, prefs.muteAll, prefs.playWhenFocused, prefs.playOnSend)
    }

    // ── Sign out (ProfileMenu `confirmSignOut`) ──────────────────────────────

    fun askSignOut(confirming: Boolean) = _ui.update { it.copy(signOutConfirming = confirming) }

    fun confirmSignOut(workState: String?, workMode: String?, onSignOut: () -> Unit) {
        when (val plan = signOutPlan(workState, workMode)) {
            SignOutPlan.SignOut -> { _ui.update { it.copy(signOutConfirming = false) }; onSignOut() }
            is SignOutPlan.Blocked -> _ui.update { it.copy(signOutConfirming = false, alert = plan.message) }
            SignOutPlan.ClockOutThenSignOut -> {
                _ui.update { it.copy(signingOut = true) }
                io {
                    runCatching(repository::clockOutForSignOut).fold(
                        onSuccess = {
                            _ui.update { it.copy(signingOut = false, signOutConfirming = false) }
                            main { onSignOut() }
                        },
                        onFailure = { error ->
                            val text = error.message.takeUnless { it == "An error occurred" }
                                ?: "Clock-out failed. Please clock out from the Work Timer before signing out."
                            _ui.update { it.copy(signingOut = false, signOutConfirming = false, alert = text) }
                        },
                    )
                }
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun perform(form: ProfileForm, errorText: String? = null, action: suspend () -> String?) {
        if (form in _ui.value.busy) return
        _ui.update { it.copy(busy = it.busy + form, notices = it.notices - form) }
        io {
            val result = runCatching { action() }
            _ui.update { it.copy(busy = it.busy - form) }
            result.fold(
                onSuccess = { text -> text?.let { notice(form, true, it) } },
                onFailure = { error -> notice(form, false, errorText ?: error.message ?: "An error occurred") },
            )
        }
    }

    /** `useAutoDismiss`: a form message clears itself after five seconds. */
    private fun notice(form: ProfileForm, ok: Boolean, text: String) {
        val value = Notice(ok, text)
        _ui.update { it.copy(notices = it.notices + (form to value)) }
        noticeJobs.remove(form)?.cancel()
        noticeJobs[form] = viewModelScope.launch {
            delay(NOTICE_DISMISS_MS)
            _ui.update { if (it.notices[form] == value) it.copy(notices = it.notices - form) else it }
        }
    }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) { block() }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    companion object {
        /** `ACTIVITY_PING_THROTTLE_MS` in `client/src/status/constants.ts`. */
        const val ACTIVITY_PING_THROTTLE_MS = 60_000L
        private const val NOTICE_DISMISS_MS = 5_000L
        private const val AVATAR_EDGE_PX = 1024

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return ProfileViewModel(ProfileRepository(container.api, container.tokens), context.applicationContext) as T
            }
        }
    }
}
