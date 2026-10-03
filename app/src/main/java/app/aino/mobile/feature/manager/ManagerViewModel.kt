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
    Requests("My Requests");

    companion object {
        /** Web `/manager?tab=` keys (`index.tsx` `setTab`), plus a few forgiving aliases. */
        fun fromParam(value: String?): ManagerTab? = when (value?.trim()?.lowercase()) {
            "attendance", "team" -> Attendance
            "approvals" -> Approvals
            "analytics" -> Analytics
            "requests", "my-requests", "my_requests" -> Requests
            else -> null
        }
    }
}

/** Which list a request detail belongs to: the manager's queue, or the user's own submissions. */
enum class RequestSource { Approvals, Mine }

/** The only roles the server lets approve / reject a request they filed themselves. */
internal val SELF_APPROVER_ROLES = setOf("super_admin", "platform_admin")

fun isOwnRequest(row: ApprovalRow, userId: Long?): Boolean =
    userId != null && row.requesterId != null && row.requesterId == userId

/**
 * Approve / Reject is offered on a pending request unless it is the viewer's
 * own and their role cannot self-approve (the server answers 403).
 */
fun canDecideRequest(row: ApprovalRow, role: String, userId: Long?): Boolean =
    row.status == "pending" && (!isOwnRequest(row, userId) || role in SELF_APPROVER_ROLES)

/** "Waiting for approval by …" for the viewer's own pending request they cannot decide. */
fun awaitingApprovalLabel(row: ApprovalRow, role: String, userId: Long?, source: RequestSource): String? {
    if (row.status != "pending") return null
    val own = source == RequestSource.Mine || isOwnRequest(row, userId)
    if (!own || role in SELF_APPROVER_ROLES) return null
    return row.approverName?.takeIf(String::isNotBlank)?.let { "Waiting for approval by $it" } ?: "Waiting for approval"
}

/** The request detail bottom sheet (row tap or `/manager?request=` deep link). */
data class RequestDetail(
    val id: Long,
    val source: RequestSource,
    val row: ApprovalRow? = null,
    /** Resolving a deep-linked id that is not on screen yet. */
    val loading: Boolean = false,
    /** Deep link to an id the server no longer returns for this user. */
    val notFound: Boolean = false,
    val error: String? = null,
) {
    /** Approve / Reject only for the approver's still-pending requests. */
    val actionable: Boolean get() = source == RequestSource.Approvals && row?.status == "pending"
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
    /** Signed-in user's id, to recognise their own requests in the queue. */
    val userId: Long? = null,
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

    // Request detail sheet
    val detail: RequestDetail? = null,

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

    fun canDecide(row: ApprovalRow): Boolean = canDecideRequest(row, role, userId)

    /** Detail-sheet Approve / Reject: queue rows the viewer may decide, plus a self-approver's own pending request. */
    fun detailActionable(detail: RequestDetail): Boolean {
        val row = detail.row ?: return false
        return when (detail.source) {
            RequestSource.Approvals -> detail.actionable && canDecide(row)
            RequestSource.Mine -> row.status == "pending" && role in SELF_APPROVER_ROLES
        }
    }

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

class ManagerViewModel(
    private val repository: ManagerRepository,
    /** Same reads served from the last responses: tabs open with their last data. */
    private val warm: ManagerRepository? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(ManagerUiState())
    val ui: StateFlow<ManagerUiState> = _ui.asStateFlow()

    private val _memberDetail = MutableStateFlow<MemberDetailUiState?>(null)
    val memberDetail: StateFlow<MemberDetailUiState?> = _memberDetail.asStateFlow()

    private var boundRole: String? = null
    private var boundUserId: Long? = null

    /** Called by the screen on entry with the signed-in user's role and id. */
    fun bind(userRole: String, userId: Long? = null) {
        if (boundRole == userRole && boundUserId == userId) return
        boundRole = userRole
        boundUserId = userId
        _ui.update { it.copy(role = userRole, userId = userId) }
        refresh()
    }

    /** Refetch the active tab (pull-to-refresh / on-resume / realtime); an open detail re-resolves too. */
    fun refresh() {
        loadTab(_ui.value.tab)
        _ui.value.detail?.takeIf { !it.loading }?.let { resolveDetail(it.id, it.source) }
    }

    fun selectTab(tab: ManagerTab) {
        _ui.update { it.copy(tab = tab) }
        loadTab(tab)
    }

    private var handledDeepLink: Pair<String, String>? = null

    /**
     * `/manager?tab=&request=` deep link: selects the tab and opens the
     * request's detail sheet. A request without a tab is an approval. Applied
     * once per distinct link so recomposition / resume does not reopen a
     * dismissed sheet.
     */
    fun openDeepLink(tab: String?, request: String?) {
        val key = tab.orEmpty() to request.orEmpty()
        if (key == "" to "" || key == handledDeepLink) return
        handledDeepLink = key
        val requestId = request?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        val target = ManagerTab.fromParam(tab) ?: if (requestId != null) ManagerTab.Approvals else null
        if (requestId != null) {
            val source = if (target == ManagerTab.Requests) RequestSource.Mine else RequestSource.Approvals
            val onScreen = rowsFor(source).firstOrNull { it.id == requestId }
            _ui.update { it.copy(detail = RequestDetail(requestId, source, row = onScreen, loading = onScreen == null)) }
            resolveDetail(requestId, source)
        }
        // A request link reads the pending queue first, whatever filter was left selected.
        val filterReset = requestId != null && target == ManagerTab.Approvals && _ui.value.approvalsFilter != "pending"
        if (filterReset) _ui.update { it.copy(approvalsFilter = "pending", selectedApprovalIds = emptySet()) }
        when {
            target != null && target != _ui.value.tab -> selectTab(target)
            filterReset -> loadApprovals()
        }
    }

    /** Row tap. */
    fun openRequest(row: ApprovalRow, source: RequestSource) =
        _ui.update { it.copy(detail = RequestDetail(row.id, source, row = row)) }

    fun closeRequest() = _ui.update { it.copy(detail = null) }

    private fun rowsFor(source: RequestSource): List<ApprovalRow> = when (source) {
        RequestSource.Approvals -> _ui.value.approvals.data.orEmpty()
        RequestSource.Mine -> _ui.value.myRequests.data.orEmpty()
    }

    /**
     * Fetch the freshest copy of one request: pending first (the common deep
     * link), then every status so an already-decided request still opens with
     * its outcome. My Requests is a single `status=all` list.
     */
    private fun resolveDetail(id: Long, source: RequestSource) {
        io {
            val result = runCatching {
                when (source) {
                    RequestSource.Approvals -> repository.approvals("pending").firstOrNull { it.id == id }
                        ?: repository.approvals("all").firstOrNull { it.id == id }
                    RequestSource.Mine -> repository.myRequests("all").firstOrNull { it.id == id }
                }
            }
            _ui.update { state ->
                val detail = state.detail?.takeIf { it.id == id && it.source == source } ?: return@update state
                state.copy(
                    detail = result.fold(
                        onSuccess = { row ->
                            // Gone from every list (cancelled / withdrawn while open): drop the stale row and its actions.
                            if (row != null) detail.copy(row = row, loading = false, notFound = false, error = null)
                            else detail.copy(row = null, loading = false, notFound = true)
                        },
                        onFailure = {
                            detail.copy(loading = false, error = if (detail.row == null) it.managerMessage("Failed to load request") else null)
                        },
                    ),
                )
            }
        }
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
    ) { r -> r.teamAttendance(_ui.value.attendanceDate) }

    // ── Approvals ─────────────────────────────────────────────────────────────

    fun setApprovalsFilter(filter: String) {
        _ui.update { it.copy(approvalsFilter = filter, selectedApprovalIds = emptySet()) }
        loadApprovals()
    }

    private fun loadApprovals() = loadSection(
        get = { it.approvals },
        set = { s, v -> s.copy(approvals = v) },
        fallback = "Failed to fetch approvals",
    ) { r -> r.approvals(_ui.value.approvalsFilter.ifEmpty { null }) }

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
            val all = state.approvals.data.orEmpty().filter(state::canDecide).map { it.id }.toSet()
            state.copy(selectedApprovalIds = if (state.selectedApprovalIds.size == all.size) emptySet() else all)
        }
    }

    fun approve(id: Long) = mutation(
        action = { repository.approve(id) },
        onSuccess = {
            closeDetailFor(id)
            refreshAfterDecision()
        },
        // e.g. 403 "You cannot approve your own request": shown in the sheet, or above the list.
        onError = { error -> decisionError(id, error.managerMessage("Failed to approve request")) },
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
                closeDetailFor(id)
                refreshAfterDecision()
            },
            onError = { error ->
                _ui.update { it.copy(rejectTargetId = null, rejectReason = "") }
                decisionError(id, error.managerMessage("Failed to reject request"))
            },
        )
    }

    private fun closeDetailFor(id: Long) = _ui.update { if (it.detail?.id == id) it.copy(detail = null) else it }

    private fun refreshAfterDecision() {
        refreshApprovals()
        if (_ui.value.tab == ManagerTab.Requests) loadMyRequests()
    }

    private fun decisionError(id: Long, message: String) = _ui.update { state ->
        state.detail?.takeIf { it.id == id }?.let { state.copy(detail = it.copy(error = message)) }
            ?: state.copy(approvals = state.approvals.copy(error = message))
    }

    fun bulkApprove() = bulk("approve")
    fun bulkReject() = bulk("reject")

    private fun bulk(action: String) {
        val ids = _ui.value.selectedApprovalIds.toList()
        if (ids.isEmpty()) return
        mutation(
            action = { repository.bulkAction(ids, action, null) },
            onSuccess = { refreshApprovals() },
            onError = { error ->
                val message = error.managerMessage("Failed to $action requests")
                _ui.update { it.copy(approvals = it.approvals.copy(error = message)) }
            },
        )
    }

    // ── My Requests ───────────────────────────────────────────────────────────

    private fun loadMyRequests() = loadSection(
        get = { it.myRequests },
        set = { s, v -> s.copy(myRequests = v) },
        fallback = "Failed to fetch requests",
    ) { r -> r.myRequests("all") }

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
        ) { r ->
            if (range == "custom") r.teamAnalytics(null, from, to) else r.teamAnalytics(range, null, null)
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
            ) { r -> r.memberOverview(userId) }
            MemberTab.Leaves -> loadMemberSection(
                get = { it.leaves },
                set = { s, v -> s.copy(leaves = v) },
                fallback = "Failed to fetch member leaves",
            ) { r -> r.memberLeaves(userId, "${LocalDate.now().year}-01-01") }
            MemberTab.Requests -> loadMemberSection(
                get = { it.requests },
                set = { s, v -> s.copy(requests = v) },
                fallback = "Failed to fetch member requests",
            ) { r -> r.memberRequests(userId) }
            MemberTab.Hours -> loadMemberSection(
                get = { it.hours },
                set = { s, v -> s.copy(hours = v) },
                fallback = "Failed to fetch member hours",
            ) { r ->
                val to = today()
                val from = LocalDate.now().minusDays(30).toString()
                r.memberHours(userId, from, to)
            }
            MemberTab.Tasks -> loadMemberSection(
                get = { it.tasks },
                set = { s, v -> s.copy(tasks = v) },
                fallback = "Failed to fetch member tasks",
            ) { r -> r.memberTasks(userId) }
        }
    }

    private fun <T> loadMemberSection(
        get: (MemberDetailUiState) -> Section<T>,
        set: (MemberDetailUiState, Section<T>) -> MemberDetailUiState,
        fallback: String,
        fetch: (ManagerRepository) -> T,
    ) {
        if (get(_memberDetail.value ?: return).loading) return
        _memberDetail.update { it?.let { s -> set(s, get(s).copy(loading = true)) } }
        io {
            if (warm != null && _memberDetail.value?.let(get)?.data == null) {
                warm?.let { w -> runCatching { fetch(w) }.getOrNull() }?.let { cached ->
                    _memberDetail.update { it?.let { s -> if (get(s).data == null) set(s, get(s).copy(data = cached)) else s } }
                }
            }
            val result = runCatching { fetch(repository) }
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
        fetch: (ManagerRepository) -> T,
    ) {
        if (get(_ui.value).loading) return
        _ui.update { set(it, get(it).copy(loading = true)) }
        io {
            if (warm != null && get(_ui.value).data == null) {
                warm?.let { w -> runCatching { fetch(w) }.getOrNull() }?.let { cached ->
                    _ui.update { s -> if (get(s).data == null) set(s, get(s).copy(data = cached)) else s }
                }
            }
            val result = runCatching { fetch(repository) }
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

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(ioDispatcher) { block() }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return ManagerViewModel(ManagerRepository(container.api), ManagerRepository(container.cachedApi)) as T
            }
        }
    }
}

private fun today(): String = LocalDate.now().toString()
