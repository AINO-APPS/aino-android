package app.aino.mobile.feature.attendance

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import app.aino.mobile.core.common.TrackerStatus
import app.aino.mobile.core.common.preferredWorkMode
import app.aino.mobile.core.common.reanchor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * The four Attendance pages, in pager order. Mobile-first labels (Requests =
 * manual entry + overtime, Insights = analytics); the `hash` values stay the
 * web URL hashes so `attendance?tab=` deep links and legacy redirects keep working.
 */
enum class AttendanceTab(val id: String, val label: String, val hash: String) {
    Overview("overview", "Overview", ""),
    Leaves("leaves", "Leaves", "#leaves"),
    Manual("manual", "Requests", "#manual-entry"),
    Analytics("analytics", "Insights", "#analytics"),
    ;

    /** Index of this tab's page in the Attendance pager. */
    val page: Int get() = ordinal

    companion object {
        fun fromHash(hash: String?): AttendanceTab {
            val cleaned = hash?.removePrefix("#").orEmpty()
            return entries.firstOrNull { it.hash.removePrefix("#") == cleaned } ?: Overview
        }

        fun fromPage(page: Int): AttendanceTab = entries.getOrElse(page) { Overview }
    }
}

/** Bottom-sheet forms on the Attendance page; held in the VM so they survive tab switches. */
enum class AttendanceSheet { ApplyLeave, ManualEntry, Overtime }

/** Overview day-detail sheet: the day's raw entries from `tracker/entries/{date}`. */
data class DayDetailState(
    val date: LocalDate,
    val loading: Boolean = true,
    val entries: List<RawTimeEntry> = emptyList(),
    val error: String? = null,
)

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

/**
 * Android verify flow: ① office presence (Wi-Fi / location) → ② fingerprint
 * or PIN → submit. `Ready` means the identity prompt may be shown (the sheet
 * auto-launches it once); `Authenticating` means the OS prompt is on screen.
 */
enum class VerifyStep { Collecting, Ready, Authenticating, Submitting }

enum class VerifyErrorKind { Location, Face, Identity, Generic }

/** Remediation the sheet can offer for a verify error (one-tap fixes). */
enum class VerifyFix { None, RetryLocation, OpenAppSettings, EnableLocation, SetUpScreenLock, EnableFingerprint, Retry }

data class VerifySubmitError(
    val kind: VerifyErrorKind,
    val title: String,
    val message: String,
    val code: String? = null,
    val fix: VerifyFix = VerifyFix.None,
)

private val IDENTITY_CODES = setOf("DEVICE_CREDENTIAL_INVALID", "FINGERPRINT_LOCATION_REQUIRED")

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
        code == "DEVICE_CREDENTIAL_INVALID" ->
            VerifySubmitError(VerifyErrorKind.Identity, "Fingerprint Not Recognised", message, code, VerifyFix.EnableFingerprint)
        code != null && code in IDENTITY_CODES ->
            VerifySubmitError(VerifyErrorKind.Location, "Location Mismatch", message, code, VerifyFix.RetryLocation)
        isLocation -> VerifySubmitError(VerifyErrorKind.Location, "Location Mismatch", message, code, VerifyFix.RetryLocation)
        isFace -> VerifySubmitError(VerifyErrorKind.Face, "Face Mismatch", message, code)
        else -> VerifySubmitError(
            VerifyErrorKind.Generic,
            if (action == AttendanceAction.ClockOut) "Clock-out Failed" else "Login Failed",
            message,
            code,
        )
    }
}

const val LOCATION_DISABLED_CODE = "LOCATION_DISABLED"

/** The sheet is parked on "Location Is Off" for a pending action and location is now on: resume it. */
fun shouldResumeAfterLocationEnabled(session: VerifySession?, pendingAction: AttendanceAction?, locationEnabled: Boolean): Boolean =
    locationEnabled && pendingAction != null && session != null &&
        session.step == VerifyStep.Ready && session.submitError?.code == LOCATION_DISABLED_CODE

data class VerifySession(
    val action: AttendanceAction,
    val workMode: WorkMode,
    val step: VerifyStep = VerifyStep.Collecting,
    /** Step ① applies (office/hybrid clock-in, office-session clock-out). */
    val needsPresence: Boolean = true,
    /** Hybrid clock-in: a missing/outside fix downgrades to remote instead of blocking. */
    val presenceOptional: Boolean = false,
    /** Office Wi-Fi or an inside-geofence fix proved presence (checked on-device first). */
    val presenceProven: Boolean = false,
    /** Org has the office Wi-Fi allow-list configured. */
    val wifiConfigured: Boolean = false,
    /** Connected BSSID when on Wi-Fi, else null (mobile data / permissionless). */
    val wifiBssid: String? = null,
    /** Connected to a registered office AP. */
    val wifiVerified: Boolean = false,
    val location: LocationProof? = null,
    /** Distance from the office for the status row, when a fix was taken. */
    val distanceMeters: Int? = null,
    val locationError: String? = null,
    val submitError: VerifySubmitError? = null,
    /** Bumped each time the identity prompt should (re)launch automatically. */
    val promptToken: Int = 0,
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
    /** Clock-in refused because today's first clock-in fixed another mode (`409 WORK_MODE_LOCKED`). */
    val modeChange: ModeChangeDraft? = null,
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
    /** Month [history] was loaded for; other months render neutral until their load lands. */
    val historyMonth: YearMonth? = null,
    val leaves: Map<LocalDate, LeaveOverlay> = emptyMap(),
    val holidays: Map<LocalDate, HolidayOverlay> = emptyMap(),
    val dayDetail: DayDetailState? = null,
    val sheet: AttendanceSheet? = null,

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
    /** Same reads served from the last responses: status and calendar paint instantly. */
    private val warm: AttendanceRepository? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(AttendanceUiState())
    val ui: StateFlow<AttendanceUiState> = _ui.asStateFlow()

    /**
     * Live work-timer anchor, re-derived whenever [AttendanceUiState.status]
     * changes (refresh, resync, clock actions). Lives here, not in `remember`,
     * so recomposition and tab switches never restart the seconds at :00, and
     * refreshes within the smoothing window keep the on-screen count.
     */
    val timer: StateFlow<app.aino.mobile.core.common.TimerAnchor?> = _ui
        .map { it.status }
        .distinctUntilChanged()
        .scan(null as app.aino.mobile.core.common.TimerAnchor?) { current, status ->
            status?.let {
                val now = System.currentTimeMillis()
                current.reanchor(app.aino.mobile.core.common.TimerAnchor.of(it, nowEpochMs = now), now)
            }
        }
        .stateIn(viewModelScope + Dispatchers.Unconfined, SharingStarted.Eagerly, null)

    /** Keep-alive parity (P3.8): each tab fetches once, then keeps its state. */
    private val loadedTabs = mutableSetOf(AttendanceTab.Overview)
    private var refreshAgain = false
    private var policyRetried = false
    /** Date the manual form was last checked for; reopening the sheet on it keeps the user's input. */
    private var manualCheckedDate: String? = null

    // No refresh() in init: MainActivity creates this before sign-in and a
    // token-less `tracker/status` answers HTTP 400 (requireTenant). AinoApp
    // refreshes once a tenant session exists and calls reset() on sign-out.

    /** Sign-out: forget the previous user's attendance state. */
    fun reset() {
        refreshAgain = false
        policyRetried = false
        manualCheckedDate = null
        loadedTabs.clear()
        loadedTabs.add(AttendanceTab.Overview)
        _ui.value = AttendanceUiState()
    }

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
            val loadMonth = _ui.value.month
            val range = monthRange(loadMonth)
            if (warm != null && _ui.value.status == null) {
                // A status cached on an earlier day is not today's: leave it to the network load.
                val cachedStatus = runCatching(warm::loadStatus).getOrNull()?.takeIf { it.isFromDayOf(System.currentTimeMillis()) }
                val cachedPolicy = runCatching(warm::loadPolicy).getOrNull()
                val cachedHistory = runCatching { warm.loadHistory(range) }.getOrNull()?.associateBy { LocalDate.parse(it.date.take(10)) }
                if (_ui.value.status == null) _ui.value = _ui.value.copy(
                    status = cachedStatus,
                    policy = _ui.value.policy ?: cachedPolicy,
                    history = cachedHistory ?: _ui.value.history,
                    historyMonth = if (cachedHistory != null) loadMonth else _ui.value.historyMonth,
                )
            }
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
            _ui.value = withStatus(_ui.value, if (statusFresh) status.getOrNull() else null).copy(
                loading = false,
                policy = loadedPolicy,
                policyDegraded = policy.isFailure,
                history = history,
                historyMonth = loadMonth,
                leaves = leaves,
                holidays = holidays,
                manualRequests = manual,
                overtimeRequests = overtime,
                manualClockIn = if (_ui.value.manualEditMode) _ui.value.manualClockIn
                    else loadedPolicy.officeStartTime?.takeIf(::validOfficeStart) ?: _ui.value.manualClockIn,
                error = if (statusFresh) status.exceptionOrNull()?.let { app.aino.mobile.core.network.userFacingMessage(it, "Could not load attendance status") } else _ui.value.error,
            )
            if (refreshAgain) { refreshAgain = false; refresh() }
        }
    }

    /** Clears the transient attendance banner/toast. */
    fun clearNotice() { _ui.value = _ui.value.copy(error = null, message = null) }

    private fun validOfficeStart(value: String): Boolean =
        Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)

    fun setWorkMode(mode: WorkMode) { _ui.value = _ui.value.copy(workMode = mode, error = null) }

    /** Opens the "Request mode change" sheet for the selected mode (also used after a lock refusal). */
    fun openModeChange(requested: WorkMode = _ui.value.workMode) {
        val locked = _ui.value.status?.lockedWorkMode?.let(::workModeOf) ?: return
        if (locked == requested) return
        _ui.value = _ui.value.copy(modeChange = ModeChangeDraft(locked, requested), error = null)
    }

    fun updateModeChangeReason(reason: String) {
        val draft = _ui.value.modeChange ?: return
        _ui.value = _ui.value.copy(modeChange = draft.copy(reason = reason.take(500), error = null))
    }

    fun dismissModeChange() { _ui.value = _ui.value.copy(modeChange = null) }

    fun submitModeChange() {
        val draft = _ui.value.modeChange ?: return
        if (draft.sending || draft.sent) return
        if (draft.reason.isBlank()) {
            _ui.value = _ui.value.copy(modeChange = draft.copy(error = "Please add a reason for your manager."))
            return
        }
        _ui.value = _ui.value.copy(modeChange = draft.copy(sending = true, error = null))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.requestWorkModeChange(draft.requested, draft.reason) }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(modeChange = _ui.value.modeChange?.copy(sending = false, sent = true))
                    resyncStatus()
                },
                onFailure = { error ->
                    _ui.value = _ui.value.copy(modeChange = _ui.value.modeChange?.copy(
                        sending = false,
                        error = error.message ?: "Could not send the request. Please try again.",
                    ))
                },
            )
        }
    }

    /** A status arrived: keep the selected mode on what the next clock-in may use. */
    private fun withStatus(state: AttendanceUiState, status: TrackerStatus?): AttendanceUiState {
        if (status == null) return state
        val preferred = workModeOf(status.preferredWorkMode()) ?: state.workMode
        return state.copy(status = status, workMode = if (status.state == "logged_out") preferred else state.workMode)
    }

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

    /** Overview day tap: open the detail sheet and fetch that day's raw entries. */
    fun openDayDetail(date: LocalDate) {
        val future = date > LocalDate.now()
        _ui.value = _ui.value.copy(selectedDate = date, dayDetail = DayDetailState(date, loading = !future))
        if (future) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.loadEntries(date.toString()) }
            val current = _ui.value.dayDetail?.takeIf { it.date == date } ?: return@launch
            _ui.value = _ui.value.copy(
                dayDetail = current.copy(
                    loading = false,
                    entries = result.getOrDefault(current.entries),
                    error = result.exceptionOrNull()?.let { app.aino.mobile.core.network.userFacingMessage(it, "Could not load this day") },
                ),
            )
        }
    }

    fun closeDayDetail() { _ui.value = _ui.value.copy(dayDetail = null) }

    /** Day sheet "Request correction": Requests page + manual-entry sheet loaded with that day's entries. */
    fun requestCorrection(date: LocalDate) {
        _ui.value = _ui.value.copy(dayDetail = null)
        selectTab(AttendanceTab.Manual)
        _ui.value = _ui.value.copy(sheet = AttendanceSheet.ManualEntry, error = null)
        updateManualForm(date = date.toString())
    }

    fun openSheet(sheet: AttendanceSheet) {
        _ui.value = _ui.value.copy(sheet = sheet, error = null, leaveError = null, leaveSuccess = null)
        // Same date check the form runs on a date change: detects edit mode / leave / live session.
        if (sheet == AttendanceSheet.ManualEntry && manualCheckedDate != _ui.value.manualDate) {
            updateManualForm(date = _ui.value.manualDate)
        }
    }

    fun closeSheet() {
        val formSheet = _ui.value.sheet == AttendanceSheet.ManualEntry || _ui.value.sheet == AttendanceSheet.Overtime
        _ui.value = _ui.value.copy(
            sheet = null,
            error = if (formSheet) null else _ui.value.error,
            leaveError = null,
        )
    }

    /** The snackbar showed the success message: clear it so it is not shown twice. */
    fun consumeMessage() { _ui.value = _ui.value.copy(message = null, leaveSuccess = null) }

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
                        sheet = if (_ui.value.sheet == AttendanceSheet.ApplyLeave) null else _ui.value.sheet,
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
            manualLeaveConflict = null, manualLiveSession = false, manualEditMode = false,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadEntries(date) }.fold(
                onSuccess = { entries ->
                    manualCheckedDate = date
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
                        // Entries without a clock-in still mean the day has attendance: edit request, not a new day.
                        entries.isNotEmpty() -> _ui.value.copy(checkingManualDate = false, manualEditMode = true)
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
                repository.saveManualEntry(payload, hasExistingEntries = current.manualEditMode)
            }.fold(
                onSuccess = { response ->
                    manualCheckedDate = null
                    // The request is already filed; a failed refresh must not turn it into an error.
                    val manual = runCatching(repository::loadManualRequests).getOrNull()
                    val history = runCatching {
                        repository.loadHistory(monthRange(current.month)).associateBy { LocalDate.parse(it.date.take(10)) }
                    }.getOrNull()
                    _ui.value = _ui.value.copy(
                        loading = false,
                        manualRequests = manual ?: _ui.value.manualRequests,
                        history = history ?: _ui.value.history,
                        historyMonth = if (history != null) current.month else _ui.value.historyMonth,
                        manualEditMode = false, manualBreaks = emptyList(),
                        message = manualEntrySuccessMessage(response),
                        sheet = if (_ui.value.sheet == AttendanceSheet.ManualEntry) null else _ui.value.sheet,
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
                        sheet = if (_ui.value.sheet == AttendanceSheet.Overtime) null else _ui.value.sheet,
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

    /**
     * Location was switched on (in-app dialog, Settings, or Quick Settings):
     * continue the parked clock action straight into presence + fingerprint.
     * Idempotent: only the "Location Is Off" state resumes, and it is cleared first.
     */
    fun onLocationSettingsChanged(onPermissionRequired: () -> Unit, locationEnabled: Boolean) {
        val state = _ui.value
        if (!shouldResumeAfterLocationEnabled(state.verifySession, state.pendingAction, locationEnabled)) return
        _ui.value = state.copy(
            verifySession = state.verifySession?.copy(step = VerifyStep.Collecting, submitError = null, locationError = null),
        )
        resumePending(onPermissionRequired)
    }

    fun dismissVerify() {
        val step = _ui.value.verifySession?.step
        if (step == VerifyStep.Submitting || step == VerifyStep.Authenticating) return
        _ui.value = _ui.value.copy(verifySession = null, pendingAction = null)
    }

    /**
     * Opens the clock flow. Same trigger as web/desktop: when the org does not
     * enforce attendance verification the action submits directly. Otherwise:
     *   ① office presence — office Wi-Fi, else a precise location fix checked
     *      against the geofence on-device (office/hybrid clock-in, office-session
     *      clock-out). Remote skips this step.
     *   ② fingerprint / PIN — the sheet auto-launches the OS prompt, which
     *      unlocks the enrolled device credential; the server verifies it.
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
        val mode = _ui.value.workMode
        val sessionMode = _ui.value.status?.workMode
        _ui.value = _ui.value.copy(pendingAction = action, error = null)
        if (!requiresAttendanceVerification(policy, action, mode, sessionMode)) {
            submitUnverified()
            return
        }
        val needsPresence = requiresOfficePresence(policy, action, mode, sessionMode)
        val optional = officePresenceIsOptional(action, mode)
        val existing = _ui.value.verifySession?.takeIf { it.action == action }
        _ui.value = _ui.value.copy(
            verifySession = VerifySession(
                action = action,
                workMode = mode,
                step = if (needsPresence) VerifyStep.Collecting else VerifyStep.Ready,
                needsPresence = needsPresence,
                presenceOptional = optional,
                promptToken = (existing?.promptToken ?: 0) + if (needsPresence) 0 else 1,
            ),
        )
        if (!needsPresence) return
        viewModelScope.launch(Dispatchers.IO) { collectPresence(policy, action, optional, onPermissionRequired) }
    }

    private suspend fun collectPresence(
        policy: AttendancePolicy,
        action: AttendanceAction,
        optional: Boolean,
        onPermissionRequired: () -> Unit,
    ) {
        // Office Wi-Fi first: it proves presence without GPS (same as web).
        val bssid = wifiProvider?.currentBssid()
        val wifiConfigured = policy.wifiVerificationEnabled && policy.officeWifiBssids.isNotEmpty()
        val allowedBssids = policy.officeWifiBssids.mapNotNull(WifiProvider.Companion::normaliseBssid).toSet()
        val wifiVerified = wifiConfigured && bssid != null && bssid in allowedBssids

        var fix: LocationProof? = null
        var locationError: VerifySubmitError? = null
        if (!wifiVerified) {
            when {
                !locationProvider.hasPrecisePermission() -> {
                    // Prompt now; resumePending() re-enters after the dialog.
                    withContext(Dispatchers.Main) { onPermissionRequired() }
                    return
                }
                !locationProvider.isLocationEnabled() -> locationError = VerifySubmitError(
                    VerifyErrorKind.Location, "Location Is Off",
                    "Turn on location to verify you are at the office, or connect to the office Wi-Fi.",
                    LOCATION_DISABLED_CODE, VerifyFix.EnableLocation,
                )
                else -> runCatching { locationProvider.currentPreciseLocation() }.fold(
                    onSuccess = { fix = it },
                    onFailure = {
                        locationError = VerifySubmitError(
                            VerifyErrorKind.Location, "Location Unavailable",
                            it.message ?: "Location unavailable", "LOCATION_REQUIRED", VerifyFix.RetryLocation,
                        )
                    },
                )
            }
        }
        val proof = fix
        val proven = wifiVerified || (proof != null && canUseFingerprintFallback(policy, WorkMode.Office, proof))
        val lat = policy.officeLatitude
        val lng = policy.officeLongitude
        val distance = if (proof != null && lat != null && lng != null) {
            distanceMeters(proof.latitude, proof.longitude, lat, lng).toInt()
        } else null
        // Check the geofence on-device so the user sees why *before* the
        // fingerprint prompt, not after a server round trip.
        val blocking = when {
            proven || optional -> null
            locationError != null -> locationError
            proof != null -> classifySubmitError(officeProofFailure(policy, proof), "OUTSIDE_GEOFENCE", action)
            else -> classifySubmitError("Precise office location or the office Wi-Fi is required.", "LOCATION_REQUIRED", action)
        }
        val current = _ui.value.verifySession ?: return
        _ui.value = _ui.value.copy(
            verifySession = current.copy(
                step = VerifyStep.Ready,
                wifiConfigured = wifiConfigured,
                wifiBssid = bssid,
                wifiVerified = wifiVerified,
                presenceProven = proven,
                location = proof,
                distanceMeters = distance,
                locationError = locationError?.message,
                submitError = blocking,
                // Presence settled (or optional for hybrid): launch the prompt.
                promptToken = if (blocking == null) current.promptToken + 1 else current.promptToken,
            ),
        )
    }

    /** The OS fingerprint/PIN prompt is now on screen (sheet stays put, Cancel disabled). */
    fun identityPromptShown() {
        val session = _ui.value.verifySession ?: return
        if (session.step != VerifyStep.Ready) return
        _ui.value = _ui.value.copy(verifySession = session.copy(step = VerifyStep.Authenticating, submitError = null))
    }

    /** Re-launch the identity prompt (user tapped "Use fingerprint / PIN"). */
    fun requestIdentityPrompt() {
        val session = _ui.value.verifySession ?: return
        if (session.step == VerifyStep.Submitting || session.step == VerifyStep.Authenticating) return
        _ui.value = _ui.value.copy(
            verifySession = session.copy(step = VerifyStep.Ready, submitError = null, promptToken = session.promptToken + 1),
        )
    }

    /**
     * Inline fingerprint / PIN setup (first clock-in) finished: the sheet is
     * still Authenticating from the setup prompt, so move it back to Ready and
     * launch the verify prompt with the new credential.
     */
    fun identityEnrollmentFinished() {
        val session = _ui.value.verifySession ?: return
        if (session.step == VerifyStep.Submitting) return
        _ui.value = _ui.value.copy(
            verifySession = session.copy(step = VerifyStep.Ready, submitError = null, promptToken = session.promptToken + 1),
        )
    }

    /**
     * The OS prompt unlocked the device credential: submit with it. The server
     * re-checks office presence (Wi-Fi/geofence) and verifies the credential.
     */
    fun submitWithCredential(credential: DeviceCredentialProof) {
        val action = _ui.value.pendingAction ?: run {
            _ui.value = _ui.value.copy(verifySession = null, error = "That attendance action expired. Tap clock in or out again.")
            return
        }
        execute(action, _ui.value.verifySession, credential)
    }

    /** No verification required by the org: submit exactly like web/desktop. */
    private fun submitUnverified() {
        val action = _ui.value.pendingAction ?: return
        execute(action, null, null)
    }

    private fun execute(action: AttendanceAction, session: VerifySession?, credential: DeviceCredentialProof?) {
        _ui.value = _ui.value.copy(
            clockBusy = true, error = null, message = null,
            verifySession = session?.copy(step = VerifyStep.Submitting, submitError = null),
        )
        val mode = _ui.value.workMode
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (action) {
                    AttendanceAction.ClockIn -> repository.clockIn(mode, session?.location, credential, session?.wifiBssid)
                    AttendanceAction.ClockOut -> repository.clockOut(session?.location, credential, session?.wifiBssid)
                }
            }.fold(
                onSuccess = { result ->
                    val status = runCatching { repository.loadStatus() }.getOrNull()
                    _ui.value = withStatus(_ui.value, status).copy(
                        clockBusy = false, pendingAction = null,
                        statusVersion = _ui.value.statusVersion + 1,
                        verifySession = null, message = result.message,
                    )
                },
                onFailure = { error ->
                    val failure = error as? AttendanceFailure
                    val message = error.message ?: "Attendance action failed"
                    val current = _ui.value.verifySession
                    if (failure?.code == WORK_MODE_LOCKED) {
                        // Today's first clock-in fixed the mode: offer the approval request instead.
                        val locked = failure.lockedMode?.let(::workModeOf) ?: _ui.value.status?.lockedWorkMode?.let(::workModeOf)
                        _ui.value = _ui.value.copy(
                            clockBusy = false, pendingAction = null, verifySession = null,
                            modeChange = locked?.let { ModeChangeDraft(it, mode) },
                            error = if (locked == null) message else null,
                        )
                    } else if (current != null) {
                        _ui.value = _ui.value.copy(
                            clockBusy = false,
                            verifySession = current.copy(
                                step = VerifyStep.Ready,
                                submitError = classifySubmitError(message, failure?.code, action),
                            ),
                        )
                    } else {
                        _ui.value = _ui.value.copy(clockBusy = false, pendingAction = null, error = message)
                    }
                    // "Already logged in" etc. mean our status is stale; resync it.
                    if (failure?.statusCode in 400..409) resyncStatus()
                },
            )
        }
    }

    private fun resyncStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = runCatching { repository.loadStatus() }.getOrNull() ?: return@launch
            _ui.value = withStatus(_ui.value, status).copy(statusVersion = _ui.value.statusVersion + 1)
        }
    }

    /**
     * Own attendance changed elsewhere — clock/break on another device, or a
     * manual entry filed / deleted (`attendance_update`): the tracker status,
     * the visible month's calendar, manual/overtime requests and, once opened,
     * Analytics. Status keeps its clockBusy/statusVersion guards.
     */
    fun onRemoteAttendanceChange() {
        if (!_ui.value.clockBusy) resyncStatus()
        scheduleRemoteRefresh(RemoteRefresh.Calendar, RemoteRefresh.Analytics)
    }

    /** A leave was applied / approved / rejected / withdrawn elsewhere: calendar overlay, Leaves tab, balances. */
    fun onRemoteLeaveChange() =
        scheduleRemoteRefresh(RemoteRefresh.Calendar, RemoteRefresh.Leaves, RemoteRefresh.Balances)

    /**
     * A manual-entry / overtime / leave request was decided (`approval_update`):
     * the requests lists and calendar (both reloaded by [refresh]) and, once
     * opened, the Leaves tab.
     */
    fun onRemoteApprovalChange() {
        // A work-mode change decision changes what the next clock-in may use.
        if (!_ui.value.clockBusy) resyncStatus()
        scheduleRemoteRefresh(RemoteRefresh.Calendar, RemoteRefresh.Leaves, RemoteRefresh.Analytics)
    }

    /** HR changed holidays / leave policies / balances (`leave_policy_changed { scope }`). */
    fun onRemoteLeavePolicyChange(scope: String?) = when (scope) {
        "holidays" -> scheduleRemoteRefresh(RemoteRefresh.Calendar, RemoteRefresh.Policies)
        "policies" -> scheduleRemoteRefresh(RemoteRefresh.Leaves, RemoteRefresh.Policies)
        "balances" -> scheduleRemoteRefresh(RemoteRefresh.Leaves, RemoteRefresh.Balances)
        else -> scheduleRemoteRefresh(*RemoteRefresh.entries.toTypedArray())
    }

    private enum class RemoteRefresh { Calendar, Leaves, Balances, Policies, Analytics }

    private val pendingRemoteRefresh = mutableSetOf<RemoteRefresh>()
    private var remoteRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * Coalesces realtime bursts (one approval emits approval_update +
     * attendance_update + leave_update): the first event opens a short window,
     * later ones join it, then each affected surface reloads once. Tabs the
     * user never opened stay lazy.
     */
    private fun scheduleRemoteRefresh(vararg parts: RemoteRefresh) {
        pendingRemoteRefresh += parts
        if (remoteRefreshJob?.isActive == true) return
        remoteRefreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(remoteRefreshWindowMs)
            val due = pendingRemoteRefresh.toSet()
            pendingRemoteRefresh.clear()
            val state = _ui.value
            if (RemoteRefresh.Calendar in due) refresh()
            if (RemoteRefresh.Leaves in due && AttendanceTab.Leaves in loadedTabs) loadLeavesTab()
            if (RemoteRefresh.Balances in due) {
                if (state.leavesSubTab == LeavesSubTab.MyBalances || state.myBalances.isNotEmpty()) loadMyBalances()
                if (state.isHr && (state.leavesSubTab == LeavesSubTab.AllBalances || state.allBalances.isNotEmpty())) loadAllBalances()
            }
            if (RemoteRefresh.Policies in due &&
                (state.leavesSubTab == LeavesSubTab.Policies || state.policiesHolidays.isNotEmpty())
            ) loadPoliciesTab()
            if (RemoteRefresh.Analytics in due && AttendanceTab.Analytics in loadedTabs) loadAnalytics()
        }
    }

    private val remoteRefreshWindowMs = 400L

    /**
     * Location permission was not granted from the flow. `permanentlyDenied`
     * means Android will no longer show the dialog, so offer app settings.
     */
    fun locationPermissionDenied(permanentlyDenied: Boolean = false, approximateOnly: Boolean = false) {
        val session = _ui.value.verifySession
        val message = when {
            approximateOnly -> "Only approximate location was allowed. Choose \"Precise\" so the office geofence can be checked, or connect to the office Wi-Fi."
            permanentlyDenied -> "Location permission is off for AINO. Open app settings → Permissions → Location, choose Allow and turn on Precise location."
            else -> "Location permission is required to verify you are at the office. Allow precise location, or connect to the office Wi-Fi."
        }
        if (session == null) { _ui.value = _ui.value.copy(error = message); return }
        if (session.presenceOptional) {
            // Hybrid: continue as remote rather than blocking the clock-in.
            _ui.value = _ui.value.copy(
                verifySession = session.copy(step = VerifyStep.Ready, locationError = message, promptToken = session.promptToken + 1),
            )
            return
        }
        val error = VerifySubmitError(
            VerifyErrorKind.Location, "Location Permission Needed", message, "LOCATION_REQUIRED",
            if (permanentlyDenied) VerifyFix.OpenAppSettings else VerifyFix.RetryLocation,
        )
        _ui.value = _ui.value.copy(
            verifySession = session.copy(step = VerifyStep.Ready, locationError = message, submitError = error),
        )
    }

    /** The identity prompt failed or is unavailable: show it inside the sheet. */
    fun reportVerifyError(message: String, fix: VerifyFix = VerifyFix.Retry, title: String = "Verification Failed") {
        val session = _ui.value.verifySession ?: run { _ui.value = _ui.value.copy(error = message); return }
        _ui.value = _ui.value.copy(
            verifySession = session.copy(
                step = VerifyStep.Ready,
                submitError = VerifySubmitError(VerifyErrorKind.Identity, title, message, "DEVICE_AUTH", fix),
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
                    warm = AttendanceRepository(container.cachedApi),
                ) as T
            }
        }
    }
}
