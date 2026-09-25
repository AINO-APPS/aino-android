package app.aino.mobile.feature.organization

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** `useAutoDismiss` message. */
data class OrgNotice(val ok: Boolean, val text: String)

data class TeamsData(val teams: List<Team>, val departments: List<Department>)

/** Teams.tsx `editForm` + the active sprint driving Pause/Resume. */
data class TeamEdit(
    val teamId: Long,
    val name: String,
    val departmentId: Long?,
    val leadId: Long?,
    val sprintDurationWeeks: Int = 2,
    val sprintStartDate: String = "",
    val sprintMode: String = "manual",
    val activeSprint: Sprint? = null,
    val paused: Boolean = false,
)

enum class NoticeSlot { Departments, Teams, Labels }

data class OrganizationUiState(
    val role: String = "",
    val orgLoading: Boolean = false,
    val orgLoaded: Boolean = false,
    val org: OrgInfo? = null,
    val orgError: String? = null,
    val tab: OrgTab = OrgTab.Departments,
    val departments: Section<List<Department>> = Section(),
    val teams: Section<TeamsData> = Section(),
    val members: Section<List<OrgMember>> = Section(),
    val chart: Section<OrgChart> = Section(),
    val labels: Section<List<TaskLabel>> = Section(),
    val notices: Map<NoticeSlot, OrgNotice> = emptyMap(),
    val createOrgError: String? = null,
    val teamEdit: TeamEdit? = null,
    /** A mutation is in flight; action buttons are disabled meanwhile. */
    val busy: Boolean = false,
) {
    val isAdmin: Boolean get() = isOrgAdmin(role)
    val tabs: List<OrgTab> get() = visibleTabs(role)

    /** Pull-to-refresh spinner: only once content is on screen (first load shows an inline indicator). */
    val refreshing: Boolean get() = orgLoaded && (orgLoading || currentSectionLoading)

    private val currentSectionLoading: Boolean get() = when (tab) {
        OrgTab.Departments -> departments.loading
        OrgTab.Teams -> teams.loading
        OrgTab.Chart -> chart.loading
        OrgTab.Labels -> labels.loading
    }
}

class OrganizationViewModel(private val repository: OrganizationRepository) : ViewModel() {
    private val _ui = MutableStateFlow(OrganizationUiState())
    val ui: StateFlow<OrganizationUiState> = _ui.asStateFlow()
    private val noticeJobs = mutableMapOf<NoticeSlot, Job>()
    private var createErrorJob: Job? = null
    private var boundUser: Pair<String, Long>? = null

    /**
     * Called by the screen on entry with the signed-in user: a different user
     * resets the page, and every entry refetches in the background like the
     * web's mount-time react-query refetch.
     */
    fun bind(userRole: String, userId: Long) {
        val key = userRole to userId
        val changed = boundUser != null && boundUser != key
        boundUser = key
        if (changed) _ui.value = OrganizationUiState()
        _ui.update { it.copy(role = userRole, tab = if (it.tab in visibleTabs(userRole)) it.tab else OrgTab.Departments) }
        refresh()
    }

    /** Refetch the org and the visible tab (pull-to-refresh / realtime). */
    fun refresh() {
        if (_ui.value.orgLoading) return
        _ui.update { it.copy(orgLoading = true) }
        io {
            val result = runCatching(repository::currentOrg)
            _ui.update { state ->
                result.fold(
                    onSuccess = { state.copy(orgLoading = false, orgLoaded = true, org = it, orgError = null) },
                    onFailure = { state.copy(orgLoading = false, orgLoaded = true, orgError = it.orgMessage("Failed to fetch organization")) },
                )
            }
            loadTab(_ui.value.tab)
        }
    }

    fun selectTab(tab: OrgTab) {
        if (tab !in _ui.value.tabs) return
        _ui.update { it.copy(tab = tab) }
        // react-query refetches a stale query on mount; cached rows stay visible meanwhile.
        loadTab(tab)
    }

    private fun loadTab(tab: OrgTab) {
        when (tab) {
            OrgTab.Departments -> { loadDepartments(); loadMembers() }
            OrgTab.Teams -> { loadTeams(); loadMembers() }
            OrgTab.Chart -> loadChart()
            OrgTab.Labels -> if (canManageLabels(_ui.value.role)) loadLabels()
        }
    }

    private val orgId: Long? get() = _ui.value.org?.id

    private fun loadDepartments() = loadSection(
        get = { it.departments },
        set = { s, v -> s.copy(departments = v) },
        fallback = "Failed to fetch departments",
    ) { repository.departments(orgId) }

    private fun loadTeams() = loadSection(
        get = { it.teams },
        set = { s, v -> s.copy(teams = v) },
        fallback = "Failed to fetch teams",
    ) { TeamsData(repository.teams(orgId), repository.departments(orgId)) }

    /** Head / lead pickers: `enabled: canManage`. */
    private fun loadMembers() {
        if (!_ui.value.isAdmin) return
        loadSection(
            get = { it.members },
            set = { s, v -> s.copy(members = v) },
            fallback = "Failed to fetch members",
        ) { repository.activeMembers(orgId) }
    }

    private fun loadChart() = loadSection(
        get = { it.chart },
        set = { s, v -> s.copy(chart = v) },
        fallback = "Failed to fetch org chart",
    ) { repository.orgChart(orgId) }

    private fun loadLabels() = loadSection(
        get = { it.labels },
        set = { s, v -> s.copy(labels = v) },
        fallback = "Failed to fetch labels",
    ) { repository.taskLabels() }

    private fun <T> loadSection(
        get: (OrganizationUiState) -> Section<T>,
        set: (OrganizationUiState, Section<T>) -> OrganizationUiState,
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
                        onFailure = { current.copy(loading = false, error = it.orgMessage(fallback)) },
                    ),
                )
            }
        }
    }

    // ── Create organization (super_admin without an org) ────────────────────

    fun createOrg(name: String, onCreated: (Long) -> Unit) = mutation(
        action = { repository.createOrg(name).id },
        onSuccess = { id ->
            onCreated(id)
            refresh()
        },
        onError = { error ->
            _ui.update { it.copy(createOrgError = error.orgMessage("Failed")) }
            createErrorJob?.cancel()
            createErrorJob = viewModelScope.launch {
                delay(NOTICE_DISMISS_MS)
                _ui.update { it.copy(createOrgError = null) }
            }
        },
    )

    // ── Departments ─────────────────────────────────────────────────────────

    fun createDepartment(name: String, headId: Long?, onDone: () -> Unit) = mutation(
        action = { repository.createDepartment(name, headId, orgId) },
        onSuccess = { onDone(); loadDepartments() },
        onError = { notify(NoticeSlot.Departments, false, it.orgMessage("Failed")) },
    )

    fun updateDepartment(id: Long, name: String, headId: Long?, onDone: () -> Unit) = mutation(
        action = { repository.updateDepartment(id, name, headId) },
        onSuccess = { onDone(); loadDepartments() },
        onError = { notify(NoticeSlot.Departments, false, it.orgMessage("Failed")) },
    )

    fun deleteDepartment(id: Long) = mutation(
        action = { repository.deleteDepartment(id) },
        onSuccess = { loadDepartments() },
        onError = { notify(NoticeSlot.Departments, false, it.orgMessage("Failed")) },
    )

    // ── Teams ───────────────────────────────────────────────────────────────

    fun createTeam(name: String, departmentId: Long?, leadId: Long?, onDone: () -> Unit) = mutation(
        action = { repository.createTeam(name, departmentId, leadId, orgId) },
        onSuccess = { onDone(); loadTeams() },
        onError = { notify(NoticeSlot.Teams, false, it.orgMessage("Failed")) },
    )

    /** Edit click: seed the form, then load the sprint config and the active sprint. */
    fun beginTeamEdit(team: Team) {
        _ui.update { it.copy(teamEdit = TeamEdit(team.id, team.name, team.departmentId, team.leadId)) }
        io {
            runCatching { repository.teamSprintConfig(team.id) }.onSuccess { config ->
                updateEditFor(team.id) {
                    it.copy(
                        sprintDurationWeeks = config.sprintDurationWeeks?.takeIf { w -> w != 0 } ?: 2,
                        sprintStartDate = normalizeDate(config.sprintStartDate).orEmpty(),
                        sprintMode = config.sprintMode?.takeIf(String::isNotEmpty) ?: "manual",
                        paused = config.sprintPaused,
                    )
                }
            }
            // Non-fatal: Pause/Resume is simply not offered without it.
            runCatching(repository::activeSprint).onSuccess { sprint ->
                updateEditFor(team.id) {
                    if (sprint != null) it.copy(activeSprint = sprint, paused = sprint.status == "paused") else it.copy(activeSprint = null)
                }
            }
        }
    }

    fun updateTeamEdit(transform: (TeamEdit) -> TeamEdit) {
        _ui.update { state -> state.copy(teamEdit = state.teamEdit?.let(transform)) }
    }

    fun cancelTeamEdit() = _ui.update { it.copy(teamEdit = null) }

    private fun updateEditFor(teamId: Long, transform: (TeamEdit) -> TeamEdit) {
        _ui.update { state ->
            val edit = state.teamEdit
            if (edit?.teamId == teamId) state.copy(teamEdit = transform(edit)) else state
        }
    }

    fun saveTeamEdit() {
        val edit = _ui.value.teamEdit ?: return
        mutation(
            action = {
                repository.updateTeam(edit.teamId, edit.name, edit.departmentId, edit.leadId)
                repository.updateTeamSprintConfig(
                    edit.teamId,
                    edit.sprintDurationWeeks.takeIf { it != 0 } ?: 2,
                    edit.sprintStartDate.ifEmpty { null },
                    edit.sprintMode.ifEmpty { "manual" },
                )
            },
            onSuccess = {
                _ui.update { s -> if (s.teamEdit?.teamId == edit.teamId) s.copy(teamEdit = null) else s }
                loadTeams()
            },
            onError = { notify(NoticeSlot.Teams, false, it.orgMessage("Failed")) },
        )
    }

    fun toggleSprintPause() {
        val edit = _ui.value.teamEdit ?: return
        val sprint = edit.activeSprint ?: return
        val wasPaused = edit.paused
        mutation(
            action = { if (wasPaused) repository.resumeSprint(sprint.id) else repository.pauseSprint(sprint.id) },
            onSuccess = {
                updateEditFor(edit.teamId) { it.copy(paused = !wasPaused) }
                notify(NoticeSlot.Teams, true, if (wasPaused) "Sprint resumed" else "Sprint paused")
            },
            onError = { notify(NoticeSlot.Teams, false, it.orgMessage("Failed")) },
        )
    }

    fun deleteTeam(id: Long) = mutation(
        action = { repository.deleteTeam(id) },
        onSuccess = { loadTeams() },
        onError = { notify(NoticeSlot.Teams, false, it.orgMessage("Failed")) },
    )

    // ── Task labels ─────────────────────────────────────────────────────────

    fun createLabel(name: String, color: String, onDone: () -> Unit) {
        if (name.isBlank()) return
        mutation(
            action = { repository.createTaskLabel(name, color) },
            onSuccess = { onDone(); notify(NoticeSlot.Labels, true, "Label created"); loadLabels() },
            onError = { notify(NoticeSlot.Labels, false, it.orgMessage("Failed to create label")) },
        )
    }

    fun updateLabel(id: Long, name: String, color: String, onDone: () -> Unit) {
        if (name.isBlank()) return
        mutation(
            action = { repository.updateTaskLabel(id, name, color) },
            onSuccess = { onDone(); notify(NoticeSlot.Labels, true, "Label updated"); loadLabels() },
            onError = { notify(NoticeSlot.Labels, false, it.orgMessage("Failed to update label")) },
        )
    }

    fun deleteLabel(id: Long) = mutation(
        action = { repository.deleteTaskLabel(id) },
        onSuccess = { notify(NoticeSlot.Labels, true, "Label deleted"); loadLabels() },
        onError = { notify(NoticeSlot.Labels, false, "Failed to delete label") },
    )

    // ── Plumbing ────────────────────────────────────────────────────────────

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

    private fun notify(slot: NoticeSlot, ok: Boolean, text: String) {
        _ui.update { it.copy(notices = it.notices + (slot to OrgNotice(ok, text))) }
        noticeJobs[slot]?.cancel()
        noticeJobs[slot] = viewModelScope.launch {
            delay(NOTICE_DISMISS_MS)
            _ui.update { it.copy(notices = it.notices - slot) }
        }
    }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) { block() }

    companion object {
        /** `useAutoDismiss` default delay. */
        private const val NOTICE_DISMISS_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return OrganizationViewModel(OrganizationRepository(container.api)) as T
            }
        }
    }
}
