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

/**
 * The four Attendance page tabs, mirroring `Attendance.tsx` TABS (P3.1/P3.8).
 * The `hash` values are the web URL hashes; the Android deep link carries the
 * same token as the `tab` query argument.
 */
enum class AttendanceTab(val id: String, val label: String, val hash: String) {
    Overview("overview", "Overview", ""),
    Leaves("leaves", "Leaves", "#leaves"),
    Manual("manual", "Manual Entry", "#manual-entry"),
    Analytics("analytics", "Analytics", "#analytics"),
    ;

    companion object {
        fun fromHash(hash: String?): AttendanceTab {
            val cleaned = hash?.removePrefix("#").orEmpty()
            return entries.firstOrNull { it.hash.removePrefix("#") == cleaned } ?: Overview
        }
    }
}

/** Inner tab bar of the Leaves page (`Leaves.tsx` SUB_TABS); last two are HR-only. */
enum class LeavesSubTab(val id: String, val label: String, val hrOnly: Boolean) {
    MyLeaves("request", "My Leaves", false),
    MyBalances("balances", "My Balances", false),
    Policies("policies", "Policies", true),
    AllBalances("allBalances", "All Balances", true),
}

// ---------------------------------------------------------------------------
// Verification sheet state (`ClockInVerifyModal` equivalent, P3.7)
// ---------------------------------------------------------------------------

enum class VerifyStep { Collecting, Ready, Submitting }

enum class VerifyErrorKind { Location, Face, Generic }

data class VerifySubmitError(
    val kind: VerifyErrorKind,
    val title: String,
    val message: String,
    val code: String? = null,
)

private val LOCATION_CODES = setOf(
    "OUTSIDE_GEOFENCE", "LOCATION_REQUIRED", "OFFICE_LOCATION_NOT_CONFIGURED", "LOCATION_TOO_COARSE",
)
private val FACE_CODES = setOf(
    "FACE_MISMATCH", "FACE_NOT_ENROLLED", "FACE_REQUIRED", "FACE_REPLAY", "FACE_ATTEMPTS_LOCKED",
)

/** `classifySubmitErr` port: code-first with keyword sniffing as the fallback. */
fun classifySubmitError(message: String, code: String?, action: AttendanceAction): VerifySubmitError {
    val lower = message.lowercase()
    val isLocation = (code != null && code in LOCATION_CODES) ||
        (code == null && (lower.contains("office") || lower.contains("geofence") ||
            lower.contains("location") || lower.contains(" m from")))
    val isFace = (code != null && code in FACE_CODES) || (code == null && lower.contains("face"))
    return when {
        isLocation -> VerifySubmitError(VerifyErrorKind.Location, "Location Mismatch", message, code)
        isFace -> VerifySubmitError(VerifyErrorKind.Face, "Face Mismatch", message, code)
        else -> VerifySubmitError(
            VerifyErrorKind.Generic,
            if (action == AttendanceAction.ClockOut) "Clock-out Failed" else "Login Failed",
            message,
            code,
        )
    }
}

data class VerifySession(
    val action: AttendanceAction,
    val workMode: WorkMode,
    val step: VerifyStep = VerifyStep.Collecting,
    /** Org has the office Wi-Fi allow-list configured. */
    val wifiConfigured: Boolean = false,
    /** Connected BSSID when on Wi-Fi, else null (mobile data / permissionless). */
    val wifiBssid: String? = null,
    /** Connected to a registered office AP. */
    val wifiVerified: Boolean = false,
    val location: LocationProof? = null,
    val locationError: String? = null,
    val submitError: VerifySubmitError? = null,
)

data class AttendanceUiState(
    val loading: Boolean = false,
    val status: TrackerStatus? = null,
    val policy: AttendancePolicy? = null,
    /**
     * True when the organization policy could not be loaded and a safe default
     * is in use. Clocking stays enabled — the server enforces verification
     * regardless — but the UI says so rather than appearing inert.
     */
    val policyDegraded: Boolean = false,
    val workMode: WorkMode = WorkMode.Office,
    val pendingAction: AttendanceAction? = null,
    val verifySession: VerifySession? = null,
    /** A clock-in/out or break request is in flight (web `actionLoading`); independent of page loads. */
    val clockBusy: Boolean = false,
    /** Bumped on every successful clock/break action so stale page loads cannot roll the status back. */
    val statusVersion: Int = 0,
    val error: String? = null,
    val message: String? = null,

    // ---- Overview tab (AttendanceCalendar) ----
    val selectedTab: AttendanceTab = AttendanceTab.Overview,
    val month: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val history: Map<LocalDate, AttendanceDay> = emptyMap(),
    val leaves: Map<LocalDate, LeaveOverlay> = emptyMap(),
    val holidays: Map<LocalDate, HolidayOverlay> = emptyMap(),

    // ---- Leaves tab ----
    val leavesSubTab: LeavesSubTab = LeavesSubTab.MyLeaves,
    val isHr: Boolean = false,
    val leavesMonth: YearMonth = YearMonth.now(),
    val leavesLoading: Boolean = false,
    val monthLeaves: List<LeaveOverlay> = emptyList(),
    val leaveBalances: List<LeaveBalance> = emptyList(),
    val leavePolicies: List<LeavePolicy> = emptyList(),
    val leaveIsRange: Boolean = false,
    val leaveDate: String = "",
    val leaveFrom: String = "",
    val leaveTo: String = "",
    val leaveSkipWeekends: Boolean = true,
    val leaveType: String = "",
    val leaveDuration: String = "full",
    val leaveReason: String = "",
    val leaveSubmitting: Boolean = false,
    val leaveError: String? = null,
    val leaveSuccess: String? = null,
    val withdrawCandidate: LeaveOverlay? = null,
    val balancesYear: Int = YearMonth.now().year,
    val myBalances: List<LeaveBalance> = emptyList(),
    val myBalancesLoading: Boolean = false,
    val policiesHolidays: List<HolidayOverlay> = emptyList(),
    val allBalances: List<UserLeaveBalance> = emptyList(),
    val allBalancesLoading: Boolean = false,
    val allBalancesQuery: String = "",

    // ---- Manual Entry tab ----
    val manualRequests: List<ManualEntryRequest> = emptyList(),
    val overtimeRequests: List<OvertimeRequest> = emptyList(),
    val manualDate: String = LocalDate.now().toString(),
    val manualClockIn: String = "09:00",
    val manualClockOut: String = "",
    val manualSkipClockOut: Boolean = false,
    val manualMode: WorkMode = WorkMode.Office,
    val manualBreaks: List<ManualBreakPayload> = emptyList(),
    val manualEditMode: Boolean = false,
    val manualLeaveConflict: LeaveOverlay? = null,
    val manualLiveSession: Boolean = false,
    val checkingManualDate: Boolean = false,
    val overtimeDate: String = LocalDate.now().toString(),
    val overtimeHours: String = "",
    val overtimeReason: String = "",

    // ---- Analytics tab ----
    /** 7 / 14 / 30, or null for a custom from/to range. */
    val analyticsDays: Int? = 7,
    val analyticsFrom: String = "",
    val analyticsTo: String = "",
    val analyticsData: List<AttendanceDay> = emptyList(),
    val analyticsHistory: List<AttendanceDay> = emptyList(),
    val analyticsWidgets: TrackerWidgets? = null,
    val notificationMetrics: NotificationMetrics? = null,
    val analyticsLoading: Boolean = false,
    val analyticsError: String? = null,
)

class AttendanceViewModel(
    private val repository: AttendanceRepository,
    private val locationProvider: LocationProvider,
    private val wifiProvider: WifiProvider? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(AttendanceUiState())
    val ui: StateFlow<AttendanceUiState> = _ui.asStateFlow()

    /** Keep-alive parity (P3.8): each tab fetches once, then keeps its state. */
    private val loadedTabs = mutableSetOf(AttendanceTab.Overview)
    private var refreshAgain = false
    private var policyRetried = false

    init { refresh() }

    // ------------------------------------------------------------------
    // Overview tab (calendar)
    // ------------------------------------------------------------------

    fun refresh() {
        // Coalesce: month changes / resume / pull while a load is in flight run
        // once afterwards instead of being silently dropped.
        if (_ui.value.loading) { refreshAgain = true; return }
        _ui.value = _ui.value.copy(loading = true, error = null)
        val startVersion = _ui.value.statusVersion
        viewModelScope.launch(Dispatchers.IO) {
            // Each source fails independently (A-101). Previously a single
            // runCatching wrapped policy + status + history, so one bad decode
            // discarded all of them — that is how a quoted NUMERIC in
            // `min_hours_present` silently disabled every clock-in button.
            val range = monthRange(_ui.value.month)
            val policy = runCatching(repository::loadPolicy)
            val status = runCatching(repository::loadStatus)
            val history = runCatching { repository.loadHistory(range) }
                .getOrDefault(emptyList())
                .associateBy { LocalDate.parse(it.date.take(10)) }
            val leaves = runCatching { repository.loadLeaves(range) }
                .getOrDefault(emptyList())
                .associateBy { LocalDate.parse(it.date.take(10)) }
            val holidays = runCatching { repository.loadHolidays(_ui.value.month.year) }
                .getOrDefault(emptyList())
                .associateBy { LocalDate.parse(it.date.take(10)) }
            val manual = runCatching(repository::loadManualRequests).getOrDefault(emptyList())
            val overtime = runCatching(repository::loadOvertimeRequests).getOrDefault(emptyList())
            val loadedPolicy = policy.getOrDefault(AttendancePolicy())
            // A clock action that finished while this load was in flight owns
            // the newer status; never overwrite it with the pre-action snapshot.
            val statusFresh = _ui.value.statusVersion == startVersion
            _ui.value = _ui.value.copy(
                loading = false,
                policy = loadedPolicy,
                policyDegraded = policy.isFailure,
                status = if (statusFresh) status.getOrNull() ?: _ui.value.status else _ui.value.status,
                history = history,
                leaves = leaves,
                holidays = holidays,
                manualRequests = manual,
                overtimeRequests = overtime,
                manualClockIn = if (_ui.value.manualEditMode) _ui.value.manualClockIn
                    else loadedPolicy.officeStartTime?.takeIf(::validOfficeStart) ?: _ui.value.manualClockIn,
                error = if (statusFresh) status.exceptionOrNull()?.let { it.message ?: "Could not load attendance status" } else _ui.value.error,
            )
            if (refreshAgain) { refreshAgain = false; refresh() }
        }
    }

    /** Clears the transient attendance banner/toast. */
    fun clearNotice() { _ui.value = _ui.value.copy(error = null, message = null) }

    private fun validOfficeStart(value: String): Boolean =
        Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)

    fun setWorkMode(mode: WorkMode) { _ui.value = _ui.value.copy(workMode = mode, error = null) }

    /**
     * Tab switching (P3.1/P3.8): first visit lazily loads the tab's data —
     * the web lazy-imports each tab on first open; afterwards every tab keeps
     * its state. Returning to Overview re-fetches the calendar (`refreshKey`
     * bump after manual-entry/leave changes).
     */
    fun selectTab(tab: AttendanceTab) {
        _ui.value = _ui.value.copy(selectedTab = tab, error = null, message = null)
        when (tab) {
            AttendanceTab.Overview -> refresh()
            AttendanceTab.Leaves -> if (loadedTabs.add(tab)) loadLeavesTab()
            AttendanceTab.Manual -> if (loadedTabs.add(tab)) refreshRequests()
            AttendanceTab.Analytics -> if (loadedTabs.add(tab)) loadAnalytics()
        }
    }

    /** Deep-link entry (`attendance?tab=leaves`): force-selects even if loaded. */
    fun openTab(tab: AttendanceTab) {
        loadedTabs.remove(tab)
        selectTab(tab)
    }

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

    // ------------------------------------------------------------------
    // Leaves tab
    // ------------------------------------------------------------------

    fun setHrRole(isHr: Boolean) {
        if (_ui.value.isHr == isHr) return
        _ui.value = _ui.value.copy(isHr = isHr)
        // An HR-only sub-tab selected before the role resolved snaps back.
        if (!isHr && _ui.value.leavesSubTab.hrOnly) {
            _ui.value = _ui.value.copy(leavesSubTab = LeavesSubTab.MyLeaves)
        }
    }

    fun loadLeavesTab() {
        if (_ui.value.leavesLoading) return
        _ui.value = _ui.value.copy(leavesLoading = true, leaveError = null)
        viewModelScope.launch(Dispatchers.IO) {
            val month = _ui.value.leavesMonth
            val from = month.atDay(1).toString()
            val to = month.atEndOfMonth().toString()
            val leaves = runCatching { repository.loadLeaves(from, to) }.getOrDefault(emptyList())
            val balances = runCatching { repository.loadLeaveBalances(month.year) }.getOrDefault(emptyList())
            val policies = runCatching(repository::loadLeavePolicies).getOrDefault(emptyList())
            _ui.value = _ui.value.copy(
                leavesLoading = false,
                monthLeaves = leaves,
                leaveBalances = balances,
                leavePolicies = policies,
                leaveType = _ui.value.leaveType.takeIf { t -> policies.any { it.leaveType == t && it.leaveType != "holiday" } }
                    ?: policies.firstOrNull { it.leaveType != "holiday" }?.leaveType.orEmpty(),
            )
        }
    }

    fun selectLeavesSubTab(tab: LeavesSubTab) {
        if (tab.hrOnly && !_ui.value.isHr) return
        _ui.value = _ui.value.copy(leavesSubTab = tab, leaveError = null, leaveSuccess = null)
        when (tab) {
            LeavesSubTab.MyLeaves -> loadLeavesTab()
            LeavesSubTab.MyBalances -> loadMyBalances()
            LeavesSubTab.Policies -> loadPoliciesTab()
            LeavesSubTab.AllBalances -> loadAllBalances()
        }
    }

    fun changeLeavesMonth(delta: Long) {
        _ui.value = _ui.value.copy(leavesMonth = _ui.value.leavesMonth.plusMonths(delta))
        loadLeavesTab()
    }

    fun loadMyBalances() {
        _ui.value = _ui.value.copy(myBalancesLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val balances = runCatching { repository.loadLeaveBalances(_ui.value.balancesYear) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(myBalancesLoading = false, myBalances = balances)
        }
    }

    fun changeBalancesYear(delta: Int) {
        _ui.value = _ui.value.copy(balancesYear = _ui.value.balancesYear + delta)
        loadMyBalances()
    }

    private fun loadPoliciesTab() {
        _ui.value = _ui.value.copy(leavesLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val policies = runCatching(repository::loadLeavePolicies).getOrDefault(emptyList())
            val holidays = runCatching { repository.loadHolidays(_ui.value.balancesYear) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(leavesLoading = false, leavePolicies = policies, policiesHolidays = holidays)
        }
    }

    private fun loadAllBalances() {
        _ui.value = _ui.value.copy(allBalancesLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val rows = runCatching(repository::loadAllLeaveBalances).getOrDefault(emptyList())
            _ui.value = _ui.value.copy(allBalancesLoading = false, allBalances = rows)
        }
    }

    fun setAllBalancesQuery(query: String) { _ui.value = _ui.value.copy(allBalancesQuery = query) }

    fun updateLeaveForm(
        isRange: Boolean? = null,
        date: String? = null,
        from: String? = null,
        to: String? = null,
        skipWeekends: Boolean? = null,
        type: String? = null,
        duration: String? = null,
        reason: String? = null,
    ) {
        _ui.value = _ui.value.copy(
            leaveIsRange = isRange ?: _ui.value.leaveIsRange,
            leaveDate = date ?: _ui.value.leaveDate,
            leaveFrom = from ?: _ui.value.leaveFrom,
            leaveTo = to ?: _ui.value.leaveTo,
            leaveSkipWeekends = skipWeekends ?: _ui.value.leaveSkipWeekends,
            leaveType = type ?: _ui.value.leaveType,
            leaveDuration = duration ?: _ui.value.leaveDuration,
            leaveReason = reason ?: _ui.value.leaveReason,
            leaveError = null,
            leaveSuccess = null,
        )
    }

    /** Dates the current leave form resolves to (single date or expanded range). */
    fun leaveRequestDates(): List<String> {
        val s = _ui.value
        return if (s.leaveIsRange) {
            if (s.leaveFrom.isBlank() || s.leaveTo.isBlank()) emptyList()
            else expandDateRange(s.leaveFrom, s.leaveTo, s.leaveSkipWeekends)
        } else {
            listOf(s.leaveDate).filter(String::isNotBlank)
        }
    }

    fun applyLeave() {
        val current = _ui.value
        val dates = leaveRequestDates()
        val policy = current.leavePolicies.firstOrNull { it.leaveType == current.leaveType }
        validateLeaveApplication(
            current.leaveType, dates, current.leaveDuration, current.leaveReason,
            policy, current.leavePolicies.isNotEmpty(),
        )?.let {
            _ui.value = current.copy(leaveError = it)
            return
        }
        _ui.value = current.copy(leaveSubmitting = true, leaveError = null, leaveSuccess = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.applyLeave(
                    ApplyLeavePayload(
                        leaveType = current.leaveType,
                        dates = dates,
                        duration = current.leaveDuration,
                        reason = current.leaveReason.trim().ifBlank { null },
                    ),
                )
            }.fold(
                onSuccess = { response ->
                    _ui.value = _ui.value.copy(
                        leaveSubmitting = false,
                        leaveReason = "",
                        leaveSuccess = response.message.ifBlank { "Leave request submitted" },
                    )
                    loadLeavesTab()
                },
                onFailure = {
                    _ui.value = _ui.value.copy(leaveSubmitting = false, leaveError = it.message ?: "Failed to submit leave request")
                },
            )
        }
    }

    fun confirmWithdraw(leave: LeaveOverlay?) { _ui.value = _ui.value.copy(withdrawCandidate = leave) }

    fun withdrawLeave() {
        val leave = _ui.value.withdrawCandidate ?: return
        _ui.value = _ui.value.copy(withdrawCandidate = null, leavesLoading = true, leaveError = null, leaveSuccess = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.withdrawLeave(leave.id) }.fold(
                onSuccess = { response ->
                    _ui.value = _ui.value.copy(
                        leavesLoading = false,
                        leaveSuccess = response.message.ifBlank { "Withdrawal request submitted" },
                    )
                    loadLeavesTab()
                },
                onFailure = {
                    _ui.value = _ui.value.copy(leavesLoading = false, leaveError = it.message ?: "Failed to withdraw leave")
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // Manual Entry tab
    // ------------------------------------------------------------------

    private fun refreshRequests() {
        viewModelScope.launch(Dispatchers.IO) {
            val manual = runCatching(repository::loadManualRequests).getOrDefault(emptyList())
            val overtime = runCatching(repository::loadOvertimeRequests).getOrDefault(emptyList())
            _ui.value = _ui.value.copy(manualRequests = manual, overtimeRequests = overtime)
        }
    }

    fun updateManualForm(
        date: String? = null,
        clockIn: String? = null,
        clockOut: String? = null,
        skipClockOut: Boolean? = null,
        mode: WorkMode? = null,
    ) {
        _ui.value = _ui.value.copy(
            manualDate = date ?: _ui.value.manualDate,
            manualClockIn = clockIn ?: _ui.value.manualClockIn,
            manualClockOut = clockOut ?: _ui.value.manualClockOut,
            manualSkipClockOut = skipClockOut ?: _ui.value.manualSkipClockOut,
            manualMode = mode ?: _ui.value.manualMode,
            error = null,
        )
        if (date != null && runCatching { LocalDate.parse(date) }.isSuccess) loadManualDate(date)
    }

    fun addManualBreak() {
        if (_ui.value.manualBreaks.size >= 20) return
        _ui.value = _ui.value.copy(manualBreaks = _ui.value.manualBreaks + ManualBreakPayload("", ""))
    }

    fun updateManualBreak(index: Int, start: String? = null, end: String? = null) {
        _ui.value = _ui.value.copy(manualBreaks = _ui.value.manualBreaks.mapIndexed { i, item ->
            if (i == index) item.copy(start = start ?: item.start, end = end ?: item.end) else item
        })
    }

    fun removeManualBreak(index: Int) {
        _ui.value = _ui.value.copy(manualBreaks = _ui.value.manualBreaks.filterIndexed { i, _ -> i != index })
    }

    /** True when the user has a live (non-manual) active session right now. */
    private fun isLiveActiveSession(status: TrackerStatus?): Boolean {
        if (status == null || status.state == "logged_out") return false
        val entries = status.entries
        if (entries.isEmpty()) return false
        return !entries.last().isManual
    }

    private fun loadManualDate(date: String) {
        _ui.value = _ui.value.copy(
            checkingManualDate = true, error = null,
            manualLeaveConflict = null, manualLiveSession = false,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadEntries(date) }.fold(
                onSuccess = { entries ->
                    val editable = editableDay(entries)
                    val selectedDate = LocalDate.parse(date)
                    val leave = _ui.value.leaves[selectedDate]
                        ?: _ui.value.monthLeaves.firstOrNull { it.date.take(10) == date }
                    val liveSession = date == LocalDate.now().toString() && isLiveActiveSession(_ui.value.status)
                    _ui.value = when {
                        leave != null -> _ui.value.copy(
                            checkingManualDate = false, manualEditMode = false,
                            manualLeaveConflict = leave, manualBreaks = emptyList(),
                        )
                        liveSession -> _ui.value.copy(
                            checkingManualDate = false, manualEditMode = false, manualLiveSession = true,
                        )
                        editable != null -> _ui.value.copy(
                            checkingManualDate = false,
                            manualEditMode = true,
                            manualClockIn = editable.clockIn,
                            manualClockOut = editable.clockOut.orEmpty(),
                            manualSkipClockOut = editable.clockOut == null,
                            manualMode = editable.workMode,
                            manualBreaks = editable.breaks,
                        )
                        else -> _ui.value.copy(
                            checkingManualDate = false, manualEditMode = false, manualBreaks = emptyList(),
                        )
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
        if (current.manualLeaveConflict != null || current.manualLiveSession) return
        val clockOut = if (current.manualSkipClockOut) null else current.manualClockOut.ifBlank { null }
        validateManualEntry(current.manualDate, current.manualClockIn, clockOut, LocalDate.now(), current.manualBreaks)?.let {
            _ui.value = current.copy(error = it)
            return
        }
        _ui.value = current.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val payload = ManualEntryPayload(
                    date = current.manualDate,
                    clockIn = current.manualClockIn,
                    clockOut = clockOut,
                    timezoneOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / -60_000,
                    workMode = current.manualMode.name.lowercase(),
                    breaks = current.manualBreaks,
                )
                if (current.manualEditMode) repository.updateManualEntry(payload) else repository.submitManualEntry(payload)
            }.fold(
                onSuccess = { response ->
                    val manual = repository.loadManualRequests()
                    val history = repository.loadHistory(monthRange(current.month)).associateBy { LocalDate.parse(it.date.take(10)) }
                    _ui.value = _ui.value.copy(
                        loading = false, manualRequests = manual, history = history,
                        manualEditMode = false, manualBreaks = emptyList(),
                        message = response.message,
                    )
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

    // ------------------------------------------------------------------
    // Analytics tab
    // ------------------------------------------------------------------

    fun setAnalyticsDays(days: Int?) {
        _ui.value = _ui.value.copy(analyticsDays = days)
        if (days != null) loadAnalytics()
        else if (_ui.value.analyticsFrom.isNotBlank() && _ui.value.analyticsTo.isNotBlank()) loadAnalytics()
    }

    fun setAnalyticsCustomRange(from: String? = null, to: String? = null) {
        _ui.value = _ui.value.copy(
            analyticsFrom = from ?: _ui.value.analyticsFrom,
            analyticsTo = to ?: _ui.value.analyticsTo,
        )
        if (_ui.value.analyticsDays == null && _ui.value.analyticsFrom.isNotBlank() && _ui.value.analyticsTo.isNotBlank()) {
            loadAnalytics()
        }
    }

    fun loadAnalytics() {
        val days = _ui.value.analyticsDays
        val from = _ui.value.analyticsFrom
        val to = _ui.value.analyticsTo
        if (days == null && (from.isBlank() || to.isBlank())) return
        _ui.value = _ui.value.copy(analyticsLoading = true, analyticsError = null)
        // Separate query on the web (staleTime 60s); a failure just leaves "No data".
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(repository::loadNotificationMetrics).onSuccess { _ui.value = _ui.value.copy(notificationMetrics = it) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // allSettled: widgets degrade to null; analytics and history are
            // hard requirements, matching the web queryFn.
            val analytics = runCatching { repository.loadAnalytics(days, from.takeIf { days == null }, to.takeIf { days == null }) }
            val rangeFrom = from.takeIf { days == null } ?: LocalDate.now().minusDays((days ?: 7) - 1L).toString()
            val rangeTo = to.takeIf { days == null } ?: LocalDate.now().toString()
            val history = runCatching { repository.loadHistory(MonthRange(LocalDate.parse(rangeFrom), LocalDate.parse(rangeTo))) }
            val widgets = runCatching(repository::loadWidgets).getOrNull()
            when {
                analytics.isFailure -> _ui.value = _ui.value.copy(
                    analyticsLoading = false, analyticsError = "Failed to load analytics chart data.",
                )
                history.isFailure -> _ui.value = _ui.value.copy(
                    analyticsLoading = false, analyticsError = "Failed to load history data.",
                )
                else -> _ui.value = _ui.value.copy(
                    analyticsLoading = false,
                    analyticsData = analytics.getOrThrow(),
                    analyticsHistory = history.getOrThrow(),
                    analyticsWidgets = widgets,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Clock actions + verification sheet (P3.6/P3.7)
    // ------------------------------------------------------------------

    fun reportError(message: String) { _ui.value = _ui.value.copy(loading = false, error = message) }

    fun resumePending(onPermissionRequired: () -> Unit) {
        val action = _ui.value.pendingAction ?: return
        prepare(action, onPermissionRequired)
    }

    fun dismissVerify() {
        if (_ui.value.verifySession?.step == VerifyStep.Submitting) return
        _ui.value = _ui.value.copy(verifySession = null, pendingAction = null)
    }

    /**
     * Opens the verification flow for a clock action. When the org policy does
     * not gate attendance the action submits immediately; otherwise the
     * `ClockInVerifySheet` collects office signals (Wi-Fi BSSID + geolocation)
     * and the face-capture step gates the device-biometric proof.
     */
    fun prepare(action: AttendanceAction, onPermissionRequired: () -> Unit) {
        // Double-tap / re-entry guard: one clock action at a time.
        if (_ui.value.clockBusy) return
        val policy = _ui.value.policy?.takeUnless { _ui.value.policyDegraded && !policyRetried } ?: run {
            // First tap can land before the page load finished (or after it
            // failed): fetch the policy now and continue instead of guessing.
            policyRetried = true
            viewModelScope.launch(Dispatchers.IO) {
                val loaded = runCatching(repository::loadPolicy)
                _ui.value = _ui.value.copy(policy = loaded.getOrDefault(_ui.value.policy ?: AttendancePolicy()), policyDegraded = loaded.isFailure)
                withContext(Dispatchers.Main) { prepare(action, onPermissionRequired) }
            }
            return
        }
        policyRetried = false
        val verificationRequired = requiresAttendanceVerification(
            policy, action, _ui.value.workMode, _ui.value.status?.workMode,
        )
        if (verificationRequired && _ui.value.workMode == WorkMode.Remote && action == AttendanceAction.ClockIn) {
            // Remote clock-ins need a face descriptor the mobile app cannot
            // produce today; say so plainly instead of looping the camera.
            _ui.value = _ui.value.copy(
                error = "Remote login requires native face matching. Enroll with the web app or use office login on this device.",
            )
            return
        }
        _ui.value = _ui.value.copy(pendingAction = action, error = null)
        if (!verificationRequired) {
            submit(fingerprintVerified = false)
            return
        }
        _ui.value = _ui.value.copy(
            verifySession = VerifySession(action = action, workMode = _ui.value.workMode),
        )
        viewModelScope.launch(Dispatchers.IO) {
            // Office signals in the same spirit as the web modal: Wi-Fi BSSID
            // plus a geolocation fix, either of which can satisfy the server.
            val bssid = wifiProvider?.currentBssid()
            val wifiConfigured = policy.wifiVerificationEnabled && policy.officeWifiBssids.isNotEmpty()
            val allowedBssids = policy.officeWifiBssids.mapNotNull(WifiProvider.Companion::normaliseBssid).toSet()
            val wifiVerified = wifiConfigured && bssid != null && bssid in allowedBssids

            var proof: LocationProof? = null
            var locationError: String? = null
            if (requiresLocation(policy, _ui.value.workMode) && !wifiVerified) {
                if (!locationProvider.hasPrecisePermission()) {
                    withContext(Dispatchers.Main) { onPermissionRequired() }
                    // Stay in Collecting; resumePending() re-enters after the
                    // permission prompt resolves.
                    return@launch
                }
                runCatching { locationProvider.currentPreciseLocation() }.fold(
                    onSuccess = { proof = it },
                    onFailure = { locationError = it.message ?: "Location unavailable" },
                )
            }
            _ui.value = _ui.value.copy(
                verifySession = VerifySession(
                    action = action,
                    workMode = _ui.value.workMode,
                    step = VerifyStep.Ready,
                    wifiConfigured = wifiConfigured,
                    wifiBssid = bssid,
                    wifiVerified = wifiVerified,
                    location = proof,
                    locationError = locationError,
                ),
            )
        }
    }

    fun submit(fingerprintVerified: Boolean) {
        // Same fail-loud rule as prepare(): a missing action or policy here
        // means biometric confirmation returned after state was cleared.
        val action = _ui.value.pendingAction ?: run {
            _ui.value = _ui.value.copy(
                error = "That attendance action expired. Tap clock in or out again.",
            )
            return
        }
        val policy = _ui.value.policy ?: run {
            _ui.value = _ui.value.copy(error = "Attendance settings are unavailable. Retrying now.")
            refresh()
            return
        }
        val session = _ui.value.verifySession
        val proof = session?.location
        val verificationRequired = requiresAttendanceVerification(
            policy,
            action,
            _ui.value.workMode,
            _ui.value.status?.workMode,
        )
        val officeProofOk = session?.wifiVerified == true || canUseFingerprintFallback(policy, _ui.value.workMode, proof)
        if (verificationRequired && !officeProofOk) {
            val message = session?.locationError ?: proof?.let { officeProofFailure(policy, it) }
                ?: "Precise office location or the office Wi-Fi is required before confirming."
            if (session != null) {
                _ui.value = _ui.value.copy(
                    verifySession = session.copy(
                        step = VerifyStep.Ready,
                        submitError = classifySubmitError(message, "LOCATION_REQUIRED", action),
                    ),
                )
            } else {
                _ui.value = _ui.value.copy(error = message)
            }
            return
        }
        _ui.value = _ui.value.copy(
            clockBusy = true, error = null, message = null,
            verifySession = session?.copy(step = VerifyStep.Submitting),
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (action) {
                    AttendanceAction.ClockIn -> repository.clockIn(_ui.value.workMode, proof, fingerprintVerified, session?.wifiBssid)
                    AttendanceAction.ClockOut -> repository.clockOut(proof, fingerprintVerified, session?.wifiBssid)
                }
            }.fold(
                onSuccess = { result ->
                    val status = runCatching { repository.loadStatus() }.getOrNull()
                    _ui.value = _ui.value.copy(
                        clockBusy = false, status = status ?: _ui.value.status, pendingAction = null,
                        statusVersion = _ui.value.statusVersion + 1,
                        verifySession = null, message = result.message,
                    )
                },
                onFailure = { error ->
                    val code = (error as? AttendanceFailure)?.code
                    val message = error.message ?: "Attendance action failed"
                    if (session != null) {
                        _ui.value = _ui.value.copy(
                            clockBusy = false,
                            verifySession = session.copy(
                                step = VerifyStep.Ready,
                                submitError = classifySubmitError(message, code, action),
                            ),
                        )
                    } else {
                        _ui.value = _ui.value.copy(clockBusy = false, pendingAction = null, error = message)
                    }
                    // "Already logged in" etc. mean our status is stale; resync it.
                    if ((error as? AttendanceFailure)?.statusCode in 400..409) resyncStatus()
                },
            )
        }
    }

    private fun resyncStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = runCatching { repository.loadStatus() }.getOrNull() ?: return@launch
            _ui.value = _ui.value.copy(status = status, statusVersion = _ui.value.statusVersion + 1)
        }
    }

    /** Location permission was denied from the verification flow: surface it in the sheet, don't hang in Collecting. */
    fun locationPermissionDenied() {
        val session = _ui.value.verifySession
        val message = "Precise location permission is required for verified office attendance. Allow \"Precise\" location, or connect to the office Wi-Fi."
        if (session == null) { _ui.value = _ui.value.copy(error = message); return }
        _ui.value = _ui.value.copy(
            verifySession = session.copy(
                step = VerifyStep.Ready,
                locationError = message,
                submitError = classifySubmitError(message, "LOCATION_REQUIRED", session.action),
            ),
        )
    }

    /** Device-identity (biometric/credential) prompt failed: show it inside the sheet. */
    fun reportVerifyError(message: String) {
        val session = _ui.value.verifySession ?: run { _ui.value = _ui.value.copy(error = message); return }
        _ui.value = _ui.value.copy(
            verifySession = session.copy(
                step = VerifyStep.Ready,
                submitError = VerifySubmitError(VerifyErrorKind.Generic, "Verification Failed", message, "DEVICE_AUTH"),
            ),
        )
    }

    /** Clears a sheet error so the user can retry the identity step. */
    fun retryVerify() {
        val session = _ui.value.verifySession ?: return
        _ui.value = _ui.value.copy(verifySession = session.copy(submitError = null))
    }

    fun breakAction(start: Boolean) {
        _ui.value = _ui.value.copy(clockBusy = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { if (start) repository.startBreak() else repository.endBreak() }.fold(
                onSuccess = { result ->
                    val status = runCatching { repository.loadStatus() }.getOrNull()
                    _ui.value = _ui.value.copy(
                        clockBusy = false, status = status ?: _ui.value.status,
                        statusVersion = _ui.value.statusVersion + 1, message = result.message,
                    )
                },
                onFailure = {
                    _ui.value = _ui.value.copy(clockBusy = false, error = it.message ?: "Break action failed")
                    resyncStatus()
                },
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                val tokens = container.tokens
                val api = container.api
                return AttendanceViewModel(
                    AttendanceRepository(api),
                    LocationProvider(context.applicationContext),
                    WifiProvider(context.applicationContext),
                ) as T
            }
        }
    }
}
