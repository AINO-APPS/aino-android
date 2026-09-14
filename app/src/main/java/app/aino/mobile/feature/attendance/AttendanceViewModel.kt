package app.aino.mobile.feature.attendance

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import app.aino.mobile.feature.home.DashboardStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AttendanceUiState(
    val loading: Boolean = false,
    val status: DashboardStatus? = null,
    val policy: AttendancePolicy? = null,
    val workMode: WorkMode = WorkMode.Office,
    val pendingAction: AttendanceAction? = null,
    val locationProof: LocationProof? = null,
    val error: String? = null,
    val message: String? = null,
)

class AttendanceViewModel(
    private val repository: AttendanceRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow(AttendanceUiState())
    val ui: StateFlow<AttendanceUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadPolicy() to repository.loadStatus() }.fold(
                onSuccess = { (policy, status) ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        policy = policy,
                        status = status,
                        workMode = parseWorkMode(status.workMode),
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not load attendance") },
            )
        }
    }

    fun setWorkMode(mode: WorkMode) { _ui.value = _ui.value.copy(workMode = mode, error = null) }

    fun reportError(message: String) { _ui.value = _ui.value.copy(loading = false, error = message) }

    fun resumePending(onPermissionRequired: () -> Unit, onBiometricRequired: () -> Unit) {
        val action = _ui.value.pendingAction ?: return
        prepare(action, onPermissionRequired, onBiometricRequired)
    }

    fun prepare(action: AttendanceAction, onPermissionRequired: () -> Unit, onBiometricRequired: () -> Unit) {
        val policy = _ui.value.policy ?: return
        val verificationRequired = requiresAttendanceVerification(
            policy,
            action,
            _ui.value.workMode,
            _ui.value.status?.workMode,
        )
        if (verificationRequired && _ui.value.workMode == WorkMode.Remote && action == AttendanceAction.ClockIn) {
            _ui.value = _ui.value.copy(error = "Remote verified clock-in requires native face matching, which is not enabled yet.")
            return
        }
        _ui.value = _ui.value.copy(loading = true, pendingAction = action, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            val proof = if (verificationRequired && requiresLocation(policy, _ui.value.workMode)) {
                if (!locationProvider.hasPrecisePermission()) {
                    _ui.value = _ui.value.copy(loading = false)
                    withContext(Dispatchers.Main) { onPermissionRequired() }
                    return@launch
                }
                runCatching { locationProvider.currentPreciseLocation() }.getOrElse {
                    _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not get precise location")
                    return@launch
                }
            } else null
            _ui.value = _ui.value.copy(loading = false, locationProof = proof)
            if (verificationRequired) {
                withContext(Dispatchers.Main) { onBiometricRequired() }
            } else submit(fingerprintVerified = false)
        }
    }

    fun submit(fingerprintVerified: Boolean) {
        val action = _ui.value.pendingAction ?: return
        val policy = _ui.value.policy ?: return
        val verificationRequired = requiresAttendanceVerification(
            policy,
            action,
            _ui.value.workMode,
            _ui.value.status?.workMode,
        )
        if (verificationRequired && !canUseFingerprintFallback(policy, _ui.value.workMode, _ui.value.locationProof)) {
            _ui.value = _ui.value.copy(error = "Precise office location is required before biometric confirmation.")
            return
        }
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (action) {
                    AttendanceAction.ClockIn -> repository.clockIn(_ui.value.workMode, _ui.value.locationProof, fingerprintVerified)
                    AttendanceAction.ClockOut -> repository.clockOut(_ui.value.locationProof, fingerprintVerified)
                }
            }.fold(
                onSuccess = { result ->
                    val status = repository.loadStatus()
                    _ui.value = _ui.value.copy(loading = false, status = status, pendingAction = null, message = result.message)
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, pendingAction = null, error = it.message ?: "Attendance action failed") },
            )
        }
    }

    fun breakAction(start: Boolean) {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { if (start) repository.startBreak() else repository.endBreak() }.fold(
                onSuccess = { result -> _ui.value = _ui.value.copy(loading = false, status = repository.loadStatus(), message = result.message) },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Break action failed") },
            )
        }
    }

    private fun parseWorkMode(value: String): WorkMode = WorkMode.entries.firstOrNull { it.name.equals(value, true) } ?: WorkMode.Office

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens)
                return AttendanceViewModel(AttendanceRepository(api), LocationProvider(context.applicationContext)) as T
            }
        }
    }
}