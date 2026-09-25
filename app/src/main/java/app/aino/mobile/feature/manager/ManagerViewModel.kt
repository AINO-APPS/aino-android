package app.aino.mobile.feature.manager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A tab's query: last good data survives a failed refetch (stale-while-revalidate). */
data class Section<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null) {
    val initialLoading: Boolean get() = loading && data == null
}

enum class ManagerTab(val label: String) {
    Attendance("Team Attendance"),
    Approvals("Approvals"),
    Analytics("Analytics"),
    Requests("My Requests"),
}

enum class MemberTab(val label: String) {
    Overview("Overview"),
    Leaves("Leaves"),
    Requests("Requests"),
    Hours("Hours"),
    Tasks("Tasks"),
}

/** `index.tsx` top-level state, plus each tab's own filters. */
data class ManagerUiState(
    val role: String = "",
    val tab: ManagerTab = ManagerTab.Attendance,
    val busy: Boolean = false,

    // Team Attendance
    val attendanceDate: String = today(),
    val attendance: Section<List<TeamAttendanceMember>> = Section(),

    // Approvals
    val approvalsFilter: String = "pending",
    val approvals: Section<List<ApprovalRow>> = Section(),
    val selectedApprovalIds: Set<Long> = emptySet(),
    val rejectTargetId: Long? = null,
    val rejectReason: String = "",

    // My Requests
    val myRequests: Section<List<ApprovalRow>> = Section(),

    // Team Analytics
    val analyticsRange: String = "7",
    val analyticsCustomFrom: String = "",
    val analyticsCustomTo: String = "",
    val analyticsSearch: String = "",
    val analyticsSortBy: String = "hours",
    val analyticsSortAsc: Boolean = false,
    val analyticsFilterDept: String = "",
    val analyticsExpandedId: Long? = null,
    val teamAnalytics: Section<TeamAnalyticsResponse> = Section(),
) {
    /** Pull-to-refresh spinner: only once content is on screen. */
    val refreshing: Boolean get() = currentSectionLoading && currentSectionHasData

    private val currentSectionHasData: Boolean get() = when (tab) {
        ManagerTab.Attendance -> attendance.data != null
        ManagerTab.Approvals -> approvals.data != null
        ManagerTab.Analytics -> teamAnalytics.data != null
        ManagerTab.Requests -> myRequests.data != null
    }

    private val currentSectionLoading: Boolean get() = when (tab) {
        ManagerTab.Attendance -> attendance.loading
        ManagerTab.Approvals -> approvals.loading
        ManagerTab.Analytics -> teamAnalytics.loading
        ManagerTab.Requests -> myRequests.loading
    }
}

/** `EmployeeDashboard.tsx` state for the member detail sub-screen. */
data class MemberDetailUiState(
    val userId: Long = 0,
    val tab: MemberTab = MemberTab.Overview,
    val overview: Section<MemberOverviewResponse> = Section(),
    val hours: Section<List<MemberHourRow>> = Section(),
    val leaves: Section<List<MemberLeaveRow>> = Section(),
    val requests: Section<List<ApprovalRow>> = Section(),
    val tasks: Section<List<MemberTaskRow>> = Section(),
) {
    val displayUser: MemberUser? get() = overview.data?.user
    val refreshing: Boolean get() = when (tab) {
        MemberTab.Overview -> overview.loading && overview.data != null
        MemberTab.Leaves -> leaves.loading && leaves.data != null
        MemberTab.Requests -> requests.loading && requests.data != null
        MemberTab.Hours -> hours.loading && hours.data != null
        MemberTab.Tasks -> tasks.loading && tasks.data != null
    }
}

class ManagerViewModel(private val repository: ManagerRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ManagerUiState())
    val ui: StateFlow<ManagerUiState> = _ui.asStateFlow()

    private val _memberDetail = MutableStateFlow<MemberDetailUiState?>(null)
    val memberDetail: StateFlow<MemberDetailUiState?> = _memberDetail.asStateFlow()

    private var boundRole: String? = null

    /** Called by the screen on entry with the signed-in user's role. */
    fun bind(userRole: String) {
        if (boundRole == userRole) return
        boundRole = userRole
        _ui.update { it.copy(role = userRole) }
        refresh()
    }

    /** Refetch the active tab (pull-to-refresh / on-resume). */
    fun refresh() = loadTab(_ui.value.tab)

    fun selectTab(tab: ManagerTab) {
        _ui.update { it.copy(tab = tab) }
        loadTab(tab)
    }

    private fun loadTab(tab: ManagerTab) {
        when (tab) {
            ManagerTab.Attendance -> loadAttendance()
            ManagerTab.Approvals -> loadApprovals()
            ManagerTab.Analytics -> loadAnalytics()
            ManagerTab.Requests -> loadMyRequests()
        }
    }

    // ── Team Attendance ──────────────────────────────────────────────────────

    fun setAttendanceDate(date: String) {
        _ui.update { it.copy(attendanceDate = date) }
        loadAttendance()
    }

    private fun loadAttendance() = loadSection(
        get = { it.attendance },
        set = { s, v -> s.copy(attendance = v) },
        fallback = "Failed to fetch team attendance",
    ) { repository.teamAttendance(_ui.value.attendanceDate) }

    // ── Approvals ─────────────────────────────────────────────────────────────

    fun setApprovalsFilter(filter: String) {
        _ui.update { it.copy(approvalsFilter = filter, selectedApprovalIds = emptySet()) }
        loadApprovals()
    }

    private fun loadApprovals() = loadSection(
        get = { it.approvals },
        set = { s, v -> s.copy(approvals = v) },
        fallback = "Failed to fetch approvals",
    ) { repository.approvals(_ui.value.approvalsFilter.ifEmpty { null }) }

    private fun refreshApprovals() {
        _ui.update { it.copy(selectedApprovalIds = emptySet()) }
        loadApprovals()
    }

    fun toggleApprovalSelect(id: Long) {
        _ui.update { state ->
            val next = state.selectedApprovalIds.toMutableSet()
            if (!next.remove(id)) next.add(id)
            state.copy(selectedApprovalIds = next)
        }
    }

    fun toggleApprovalSelectAll() {
        _ui.update { state ->
            val all = state.approvals.data.orEmpty().map { it.id }.toSet()
            state.copy(selectedApprovalIds = if (state.selectedApprovalIds.size == all.size) emptySet() else all)
        }
    }

    fun approve(id: Long) = mutation(
        action = { repository.approve(id) },
        onSuccess = { refreshApprovals() },
        onError = { /* web: console.error only, no visible notice */ },
    )

    fun openReject(id: Long) = _ui.update { it.copy(rejectTargetId = id, rejectReason = "") }
    fun updateRejectReason(text: String) = _ui.update { it.copy(rejectReason = text) }
    fun cancelReject() = _ui.update { it.copy(rejectTargetId = null, rejectReason = "") }

    fun confirmReject() {
        val id = _ui.value.rejectTargetId ?: return
        val reason = _ui.value.rejectReason
        mutation(
            action = { repository.reject(id, reason.ifBlank { null }) },
            onSuccess = {
                _ui.update { it.copy(rejectTargetId = null, rejectReason = "") }
                refreshApprovals()
            },
            onError = { /* web: console.error only, no visible notice */ },
        )
    }

    fun bulkApprove() = bulk("approve")
    fun bulkReject() = bulk("reject")

    private fun bulk(action: String) {
        val ids = _ui.value.selectedApprovalIds.toList()
        if (ids.isEmpty()) return
        mutation(
            action = { repository.bulkAction(ids, action, null) },
            onSuccess = { refreshApprovals() },
            onError = { /* web: console.error only, no visible notice */ },
        )
    }

    // ── My Requests ───────────────────────────────────────────────────────────

    private fun loadMyRequests() = loadSection(
        get = { it.myRequests },
        set = { s, v -> s.copy(myRequests = v) },
        fallback = "Failed to fetch requests",
    ) { repository.myRequests("all") }

    // ── Team Analytics ───────────────────────────────────────────────────────

    fun setAnalyticsRange(range: String) {
        _ui.update {
            it.copy(
                analyticsRange = range,
                analyticsCustomFrom = if (range == "custom") it.analyticsCustomFrom else "",
                analyticsCustomTo = if (range == "custom") it.analyticsCustomTo else "",
            )
        }
        if (range != "custom") loadAnalytics()
    }

    fun setAnalyticsCustomRange(from: String, to: String) {
        _ui.update { it.copy(analyticsCustomFrom = from, analyticsCustomTo = to) }
        if (from.isNotEmpty() && to.isNotEmpty() && from <= to) loadAnalytics()
    }

    fun setAnalyticsSearch(text: String) = _ui.update { it.copy(analyticsSearch = text) }
    fun setAnalyticsFilterDept(dept: String) = _ui.update { it.copy(analyticsFilterDept = dept) }
    fun toggleAnalyticsExpanded(id: Long) = _ui.update { it.copy(analyticsExpandedId = if (it.analyticsExpandedId == id) null else id) }

    fun sortAnalyticsBy(column: String) {
        _ui.update {
            if (it.analyticsSortBy == column) it.copy(analyticsSortAsc = !it.analyticsSortAsc)
            else it.copy(analyticsSortBy = column, analyticsSortAsc = false)
        }
    }

    private fun loadAnalytics() {
        val range = _ui.value.analyticsRange
        val from = _ui.value.analyticsCustomFrom
        val to = _ui.value.analyticsCustomTo
        if (range == "custom" && (from.isEmpty() || to.isEmpty() || from > to)) return
        loadSection(
            get = { it.teamAnalytics },
            set = { s, v -> s.copy(teamAnalytics = v) },
            fallback = "Failed to fetch team analytics",
        ) {
            if (range == "custom") repository.teamAnalytics(null, from, to) else repository.teamAnalytics(range, null, null)
        }
    }

    // ── Member detail (`manager/member/{userId}`) ───────────────────────────

    fun openMemberDetail(userId: Long) {
        _memberDetail.value = MemberDetailUiState(userId = userId)
        loadMemberTab(MemberTab.Overview)
    }

    fun closeMemberDetail() {
        _memberDetail.value = null
    }

    fun selectMemberTab(tab: MemberTab) {
        _memberDetail.update { it?.copy(tab = tab) }
        loadMemberTab(tab)
    }

    /** `RefetchOnResume` for the member-detail sub-screen. */
    fun refreshMemberDetail() {
        val tab = _memberDetail.value?.tab ?: return
        loadMemberTab(tab)
    }

    private fun loadMemberTab(tab: MemberTab) {
        val userId = _memberDetail.value?.userId ?: return
        when (tab) {
            MemberTab.Overview -> loadMemberSection(
                get = { it.overview },
                set = { s, v -> s.copy(overview = v) },
                fallback = "Failed to fetch member overview",
            ) { repository.memberOverview(userId) }
            MemberTab.Leaves -> loadMemberSection(
                get = { it.leaves },
                set = { s, v -> s.copy(leaves = v) },
                fallback = "Failed to fetch member leaves",
            ) { repository.memberLeaves(userId, "${LocalDate.now().year}-01-01") }
            MemberTab.Requests -> loadMemberSection(
                get = { it.requests },
                set = { s, v -> s.copy(requests = v) },
                fallback = "Failed to fetch member requests",
            ) { repository.memberRequests(userId) }
            MemberTab.Hours -> loadMemberSection(
                get = { it.hours },
                set = { s, v -> s.copy(hours = v) },
                fallback = "Failed to fetch member hours",
            ) {
                val to = today()
                val from = LocalDate.now().minusDays(30).toString()
                repository.memberHours(userId, from, to)
            }
            MemberTab.Tasks -> loadMemberSection(
                get = { it.tasks },
                set = { s, v -> s.copy(tasks = v) },
                fallback = "Failed to fetch member tasks",
            ) { repository.memberTasks(userId) }
        }
    }

    private fun <T> loadMemberSection(
        get: (MemberDetailUiState) -> Section<T>,
        set: (MemberDetailUiState, Section<T>) -> MemberDetailUiState,
        fallback: String,
        fetch: () -> T,
    ) {
        if (get(_memberDetail.value ?: return).loading) return
        _memberDetail.update { it?.let { s -> set(s, get(s).copy(loading = true)) } }
        io {
            val result = runCatching(fetch)
            _memberDetail.update { state ->
                state?.let { s ->
                    val current = get(s)
                    set(
                        s,
                        result.fold(
                            onSuccess = { Section(data = it) },
                            onFailure = { current.copy(loading = false, error = it.managerMessage(fallback)) },
                        ),
                    )
                }
            }
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private fun <T> loadSection(
        get: (ManagerUiState) -> Section<T>,
        set: (ManagerUiState, Section<T>) -> ManagerUiState,
        fallback: String,
        fetch: () -> T,
    ) {
        if (get(_ui.value).loading) return
        _ui.update { set(it, get(it).copy(loading = true)) }
        io {
            val result = runCatching(fetch)
            _ui.update { state ->
                val current = get(state)
                set(
                    state,
                    result.fold(
                        onSuccess = { Section(data = it) },
                        onFailure = { current.copy(loading = false, error = it.managerMessage(fallback)) },
                    ),
                )
            }
        }
    }

    private fun <T> mutation(action: () -> T, onSuccess: (T) -> Unit, onError: (Throwable) -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        io {
            val result = runCatching(action)
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(busy = false) }
                result.fold(onSuccess, onError)
            }
        }
    }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) { block() }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return ManagerViewModel(ManagerRepository(container.api)) as T
            }
        }
    }
}

private fun today(): String = LocalDate.now().toString()
