package app.aino.mobile.feature.attendance

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import app.aino.mobile.core.common.TrackerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

data class AttendanceUiState(
    val loading: Boolean = false,
    val status: TrackerStatus? = null,
    val policy: AttendancePolicy? = null,
    val workMode: WorkMode = WorkMode.Office,
    val pendingAction: AttendanceAction? = null,
    val locationProof: LocationProof? = null,
    val error: String? = null,
    val message: String? = null,
    val selectedTab: AttendanceTab = AttendanceTab.Today,
    val month: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val history: Map<LocalDate, AttendanceDay> = emptyMap(),
    val leaves: Map<LocalDate, LeaveOverlay> = emptyMap(),
    val holidays: Map<LocalDate, HolidayOverlay> = emptyMap(),
    val manualRequests: List<ManualEntryRequest> = emptyList(),
    val overtimeRequests: List<OvertimeRequest> = emptyList(),
    val manualDate: String = LocalDate.now().toString(),
    val manualClockIn: String = "09:00",
    val manualClockOut: String = "17:00",
    val manualMode: WorkMode = WorkMode.Office,
    val manualBreaks: List<ManualBreakPayload> = emptyList(),
    val manualEditMode: Boolean = false,
    val checkingManualDate: Boolean = false,
    val overtimeDate: String = LocalDate.now().toString(),
    val overtimeHours: String = "",
    val overtimeReason: String = "",
)

enum class AttendanceTab { Today, Overview, Manual }

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
            runCatching {
                val policy = repository.loadPolicy()
                val status = repository.loadStatus()
                val history = repository.loadHistory(monthRange(_ui.value.month)).associateBy { LocalDate.parse(it.date) }
                val range = monthRange(_ui.value.month)
                val leaves = runCatching { repository.loadLeaves(range) }.getOrDefault(emptyList())
                    .mapNotNull { leave -> normalizeDate(leave.date)?.let { it to leave } }.toMap()
                val holidays = runCatching { repository.loadHolidays(_ui.value.month.year) }.getOrDefault(emptyList())
                    .mapNotNull { holiday -> normalizeDate(holiday.date)?.let { it to holiday } }.toMap()
                val manual = runCatching(repository::loadManualRequests).getOrDefault(emptyList())
                val overtime = runCatching(repository::loadOvertimeRequests).getOrDefault(emptyList())
                LoadedAttendance(policy, status, history, leaves, holidays, manual, overtime)
            }.fold(
                onSuccess = { loaded ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        policy = loaded.policy,
                        status = loaded.status,
                        workMode = parseWorkMode(loaded.status.workMode),
                        history = loaded.history,
                        leaves = loaded.leaves,
                        holidays = loaded.holidays,
                        manualRequests = loaded.manualRequests,
                        overtimeRequests = loaded.overtimeRequests,
                    )
                    loadManualDate(_ui.value.manualDate)
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not load attendance") },
            )
        }
    }

    fun setWorkMode(mode: WorkMode) { _ui.value = _ui.value.copy(workMode = mode, error = null) }

    fun selectTab(tab: AttendanceTab) { _ui.value = _ui.value.copy(selectedTab = tab) }

    fun selectDate(date: LocalDate) { _ui.value = _ui.value.copy(selectedDate = date) }

    fun changeMonth(delta: Long) {
        val next = _ui.value.month.plusMonths(delta)
        _ui.value = _ui.value.copy(month = next, selectedDate = next.atDay(1))
        refresh()
    }

    fun currentMonth() {
        val now = YearMonth.now()
        _ui.value = _ui.value.copy(month = now, selectedDate = LocalDate.now())
        refresh()
    }

    fun updateManualForm(date: String? = null, clockIn: String? = null, clockOut: String? = null, mode: WorkMode? = null) {
        _ui.value = _ui.value.copy(
            manualDate = date ?: _ui.value.manualDate,
            manualClockIn = clockIn ?: _ui.value.manualClockIn,
            manualClockOut = clockOut ?: _ui.value.manualClockOut,
            manualMode = mode ?: _ui.value.manualMode,
            error = null,
        )
        if (date != null && runCatching { LocalDate.parse(date) }.isSuccess) loadManualDate(date)
    }

    fun addManualBreak() {
        if (_ui.value.manualBreaks.size >= 20) return
        _ui.value = _ui.value.copy(manualBreaks = _ui.value.manualBreaks + ManualBreakPayload("12:00", "12:30"))
    }

    fun updateManualBreak(index: Int, start: String? = null, end: String? = null) {
        _ui.value = _ui.value.copy(manualBreaks = _ui.value.manualBreaks.mapIndexed { i, item ->
            if (i == index) item.copy(start = start ?: item.start, end = end ?: item.end) else item
        })
    }

    fun removeManualBreak(index: Int) {
        _ui.value = _ui.value.copy(manualBreaks = _ui.value.manualBreaks.filterIndexed { i, _ -> i != index })
    }

    private fun loadManualDate(date: String) {
        _ui.value = _ui.value.copy(checkingManualDate = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadEntries(date) }.fold(
                onSuccess = { entries ->
                    val editable = editableDay(entries)
                    val selectedDate = LocalDate.parse(date)
                    val leave = _ui.value.leaves[selectedDate]
                    val currentlyActive = date == LocalDate.now().toString() && _ui.value.status?.state != "logged_out"
                    _ui.value = if (leave != null) {
                        _ui.value.copy(checkingManualDate = false, manualEditMode = false, error = "A leave exists on this date. Remove it before editing time.")
                    } else if (currentlyActive) {
                        _ui.value.copy(checkingManualDate = false, manualEditMode = false, error = "Finish the active session before editing today's entries.")
                    } else if (editable != null) {
                        _ui.value.copy(
                            checkingManualDate = false,
                            manualEditMode = true,
                            manualClockIn = editable.clockIn,
                            manualClockOut = editable.clockOut.orEmpty(),
                            manualMode = editable.workMode,
                            manualBreaks = editable.breaks,
                        )
                    } else {
                        _ui.value.copy(checkingManualDate = false, manualEditMode = false, manualBreaks = emptyList())
                    }
                },
                onFailure = { _ui.value = _ui.value.copy(checkingManualDate = false, error = it.message ?: "Could not check this date") },
            )
        }
    }

    fun updateOvertimeForm(date: String? = null, hours: String? = null, reason: String? = null) {
        _ui.value = _ui.value.copy(
            overtimeDate = date ?: _ui.value.overtimeDate,
            overtimeHours = hours ?: _ui.value.overtimeHours,
            overtimeReason = reason ?: _ui.value.overtimeReason,
            error = null,
        )
    }

    fun submitManualEntry() {
        val current = _ui.value
        validateManualEntry(current.manualDate, current.manualClockIn, current.manualClockOut.ifBlank { null }, LocalDate.now(), current.manualBreaks)?.let {
            _ui.value = current.copy(error = it)
            return
        }
        _ui.value = current.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val payload = ManualEntryPayload(
                    date = current.manualDate,
                    clockIn = current.manualClockIn,
                    clockOut = current.manualClockOut.ifBlank { null },
                    timezoneOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / -60_000,
                    workMode = current.manualMode.name.lowercase(),
                    breaks = current.manualBreaks,
                )
                if (current.manualEditMode) repository.updateManualEntry(payload) else repository.submitManualEntry(payload)
            }.fold(
                onSuccess = { response ->
                    val manual = repository.loadManualRequests()
                    val history = repository.loadHistory(monthRange(current.month)).associateBy { LocalDate.parse(it.date) }
                    _ui.value = _ui.value.copy(loading = false, manualRequests = manual, history = history, manualEditMode = false, message = response.message)
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Manual entry failed") },
            )
        }
    }

    fun submitOvertime() {
        val current = _ui.value
        validateOvertime(current.overtimeDate, current.overtimeHours, current.overtimeReason)?.let {
            _ui.value = current.copy(error = it)
            return
        }
        _ui.value = current.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.submitOvertime(
                    OvertimePayload(current.overtimeDate, current.overtimeHours.toDouble(), current.overtimeReason.trim()),
                )
            }.fold(
                onSuccess = { response ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        overtimeHours = "",
                        overtimeReason = "",
                        overtimeRequests = repository.loadOvertimeRequests(),
                        message = response.message,
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Overtime request failed") },
            )
        }
    }

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


    private fun normalizeDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

    private data class LoadedAttendance(
        val policy: AttendancePolicy,
        val status: TrackerStatus,
        val history: Map<LocalDate, AttendanceDay>,
        val leaves: Map<LocalDate, LeaveOverlay>,
        val holidays: Map<LocalDate, HolidayOverlay>,
        val manualRequests: List<ManualEntryRequest>,
        val overtimeRequests: List<OvertimeRequest>,
    )

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