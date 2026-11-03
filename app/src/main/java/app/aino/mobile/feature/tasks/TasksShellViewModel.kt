package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TasksShellViewModel(
    private val repository: TaskRepository,
    internal val serviceDesk: ServiceDeskRepository? = null,
    ticketSession: TicketSession? = null,
) : ViewModel() {
    private val ownsTicketSession = ticketSession == null
    val ticketSession = ticketSession ?: TicketSession()
    private val ticketSync = this.ticketSession.sync(repository)
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(this.ticketSession.job))
    private val _ui = this.ticketSession.state(TaskShellUiState(planScope = this.ticketSession.planScope.value))
    val ui: StateFlow<TaskShellUiState> = _ui.asStateFlow()
    /** Search owns only membership; rows are always resolved from the session. */
    private val searchRows = this.ticketSession.state(emptyList<Task>())
    val searchResults: StateFlow<List<Task>> = searchRows.asStateFlow()
    init {
        ticketScope.launch {
            combine(_ui, this@TasksShellViewModel.ticketSession.store.state) { state, snapshot ->
                state.searchResultIds.mapNotNull(snapshot.tickets::get).filter { it.workflowStateKey != "cancelled" }
            }.collect { searchRows.value = it }
        }
        ticketScope.launch {
            this@TasksShellViewModel.ticketSession.planScope.collect { scope ->
                synchronized(this@TasksShellViewModel.ticketSession.planScopeLock) {
                    if (scope == this@TasksShellViewModel.ticketSession.planScope.value) {
                        _ui.update { it.copy(planScope = scope) }
                    }
                }
            }
        }
        ticketScope.launch {
            this@TasksShellViewModel.ticketSession.store.state.collect { snapshot ->
                _ui.update { current ->
                    val searchableIds = current.searchResultIds.filter { id ->
                        snapshot.tickets[id]?.workflowStateKey?.let { it != "cancelled" } == true
                    }
                    current.copy(
                        searchResultIds = searchableIds,
                    )
                }
            }
        }
        ticketScope.launch {
            ticketSync.signals.collect(::handleTicketSyncSignal)
        }
        ticketScope.launch {
            this@TasksShellViewModel.ticketSession.planRefreshes.collect {
                refresh()
            }
        }
        ticketScope.launch {
            this@TasksShellViewModel.ticketSession.shellRequests.collect { request ->
                when (request) {
                    ShellRequest.ShowScheduled -> _ui.update {
                        it.copy(tab = normalizeTab(TaskTab.Scheduled, it.visibleTabs), searchOpen = false)
                    }
                    ShellRequest.ReloadScheduledIfVisible -> if (_ui.value.tab == TaskTab.Scheduled) loadScheduled()
                    ShellRequest.CloseSheet -> closeSheet()
                    is ShellRequest.OpenSheet -> openSheet(request.sheet)
                    is ShellRequest.SelectSprint -> selectSprint(request.id)
                    is ShellRequest.SprintGoal -> updatePlan { state ->
                        state.copy(planScope = state.planScope.copy(sprints = state.sprints.map {
                            if (it.id == request.id) it.copy(goal = request.goal) else it
                        }))
                    }
                    is ShellRequest.ShowCompleted -> setShowCompleted(request.show)
                }
            }
        }
    }

    private fun updatePlan(transform: (TaskShellUiState) -> TaskShellUiState) {
        synchronized(ticketSession.planScopeLock) {
            if (!ticketSession.job.isActive) return
            _ui.update { transform(it.copy(planScope = ticketSession.planScope.value)) }
            ticketSession.writePlanScope(_ui.value.planScope)
        }
    }

    override fun onCleared() {
        ticketScope.cancel()
        if (ownsTicketSession) ticketSession.close()
    }

    /** Snackbar messages are session-scoped so the shell host survives route changes. */
    val notices: Flow<TaskNotice> = this.ticketSession.notices

    private var searchJob: Job? = null
    /** The sprint landing has been applied once; a later tab choice is honoured. */
    private var landingApplied = false
    private data class CachedTeamViews(
        val currentSprintId: Long?,
        val currentSprintRow: AvailableSprint?,
        val selectedSprintId: Long?,
    )
    private val teamViews = mutableMapOf<Long?, CachedTeamViews>()

    init {
        this.ticketSession.onClose {
            synchronized(this.ticketSession.planScopeLock) { teamViews.clear() }
        }
    }

    private fun io(block: suspend () -> Unit) = ticketScope.launch(Dispatchers.IO) { block() }

    /** The signed-in user context the web reads from AuthContext / FeaturesContext. */
    fun bind(
        userId: Long,
        role: String,
        teamName: String?,
        agileEnabled: Boolean,
        customFieldsEnabled: Boolean = false,
        teamId: Long? = null,
        sprintEnabled: Boolean? = null,
    ) {
        val current = _ui.value
        if (current.userId == userId && current.role == role && current.teamName == teamName &&
            current.teamId == teamId && current.sprintEnabled == sprintEnabled &&
            current.agileEnabled == agileEnabled && current.customFieldsEnabled == customFieldsEnabled
        ) return
        val changedUser = current.userId != null && current.userId != userId
        if (changedUser) {
            ticketSession.store.clear()
            ticketSession.clearCommentThreads()
        }
        if (changedUser) teamViews.clear()
        updatePlan { state ->
            (if (changedUser) TaskShellUiState() else state).copy(
                planScope = (if (changedUser) PlanScope() else state.planScope).copy(
                    userId = userId, role = role, teamName = teamName, teamId = teamId,
                    agileEnabled = agileEnabled, customFieldsEnabled = customFieldsEnabled,
                    sprintEnabled = sprintEnabled,
                ),
                // Sprint switched off mid-session (agile, or no current sprint):
                // bounce off the board rather than show a tab that is gone.
                tab = if (changedUser) TaskTab.Backlog
                else normalizeTab(current.tab, planningTabs(agileEnabled, current.teamSprints, current.currentSprintId)),
            )
        }
        loadPreferences()
        if (agileEnabled) ticketSession.requestPlan(PlanRequest.Epics)
        refresh()
    }

    /** Pull-to-refresh, resume and reconnect: metadata plus the visible tab. */
    fun refresh(pull: Boolean = false) {
        if (_ui.value.userId == null) return
        ticketSync.resetRefreshCycle()
        if (pull) _ui.update { it.copy(refreshing = true) }
        io {
            val agile = _ui.value.agileEnabled
            val labels = runCatching(repository::loadLabels).getOrNull()
            val users = runCatching(repository::loadAssignableUsers).getOrNull()
            val projects = runCatching(repository::loadProjects).getOrNull()
            // A feature-gate 403 means "no sprints", not a retryable failure.
            val sprints = if (agile) runCatching(repository::loadAvailableSprints).getOrDefault(emptyList()) else emptyList()
            val config = if (agile) runCatching(repository::loadAgileConfig).getOrNull() else null
            val before = _ui.value
            val teams = if (showsTeamPicker(before.role)) {
                before.teams.ifEmpty { runCatching(repository::loadTeams).getOrDefault(emptyList()) }
            } else {
                emptyList()
            }
            val teamId = if (showsTeamPicker(before.role)) {
                before.selectedTeamId ?: defaultTeamId(
                    remembered = before.preferences.defaultTeamId,
                    ownTeamId = before.teamId,
                    teams = teams,
                )
            } else {
                null
            }
            val sprintList = if (agile) {
                runCatching { repository.loadTeamSprints(teamId.takeIf { showsTeamPicker(before.role) }) }.getOrNull()
            } else {
                null
            }
            updatePlan { s ->
                // The admin's picker narrows the rows before anything reads
                // them, so tabs, landing and the board all see one team.
                val resolvedTeams = if (showsTeamPicker(s.role)) s.teams.ifEmpty { teams } else emptyList()
                val resolvedTeamId = if (showsTeamPicker(s.role)) s.selectedTeamId ?: teamId else null
                val sameTeam = resolvedTeamId == teamId
                val currentSprintId = when {
                    sameTeam && sprintList != null -> sprintList.currentSprintId
                    resolvedTeamId == s.selectedTeamId -> s.currentSprintId
                    else -> null
                }
                val currentSprintRow = when {
                    sameTeam && sprintList != null -> sprintList.sprints.firstOrNull { it.id == currentSprintId }
                    currentSprintId == s.currentSprintId && resolvedTeamId == s.selectedTeamId -> s.currentSprintRow
                    else -> null
                }
                val scoped = sprintsForTeam(sprints, resolvedTeamId, showsTeamPicker(s.role))
                val current = currentSprintRow?.takeIf { it.id == currentSprintId }
                val teamSprints = if (current != null && scoped.none { it.id == current.id }) scoped + current else scoped
                val selected = pickSprint(s.selectedSprintId, teamSprints, currentSprintId)
                s.copy(
                    planScope = s.planScope.copy(
                        labels = labels ?: s.labels,
                        assignableUsers = users ?: s.assignableUsers,
                        projects = projects ?: s.projects,
                        sprints = sprints,
                        currentSprintId = currentSprintId,
                        currentSprintRow = currentSprintRow,
                        teams = resolvedTeams,
                        selectedTeamId = resolvedTeamId,
                        teamResolved = true,
                        agile = config ?: s.agile,
                        selectedSprintId = selected,
                    ),
                    tab = normalizeTab(
                        if (!landingApplied) landingTab(s.tab, teamSprints, currentSprintId, agile) else s.tab,
                        planningTabs(agile, teamSprints, currentSprintId),
                    ),
                )
            }
            landingApplied = true
            ticketSession.requestScheduled(ScheduledRequest.CarryForward)
            reloadTab()
            _ui.update { it.copy(refreshing = false) }
        }
    }

    private fun handleTicketSyncSignal(signal: TicketSyncSignal) {
        when (signal) {
            TicketSyncSignal.VisiblePoll -> {
                val state = _ui.value
                if (state.userId != null && state.sheet != TaskSheet.CreateTicket
                ) reloadTab()
            }
            TicketSyncSignal.FullReload -> {
                reloadTab()
            }
            TicketSyncSignal.Reconnect -> {
                val state = _ui.value
                if (state.tab == TaskTab.Sprint) loadSprint()
                else ticketSession.requestBoard(BoardRequest.ReloadLoaded)
                ticketSession.requestPlan(PlanRequest.ReloadLoaded)
                if (state.tab == TaskTab.Scheduled) loadScheduled()
                else ticketSession.requestScheduled(ScheduledRequest.ReloadLoaded)
                if (state.tab == TaskTab.ServiceDesk) reloadTab()
            }
        }
    }

    private fun reloadTab() {
        when (_ui.value.tab) {
            TaskTab.Sprint -> loadSprint()
            TaskTab.Backlog -> loadBacklog()
            TaskTab.Scheduled -> loadScheduled()
            TaskTab.ServiceDesk -> _ui.update { it.copy(serviceDeskVersion = it.serviceDeskVersion + 1) }
        }
    }

    // ── Scheduled ────────────────────────────────────────────────────────

    fun loadScheduled() = ticketSession.requestScheduled(ScheduledRequest.Load)

    // ── Tabs, sheets, sprint picker, filters ─────────────────────────────

    fun selectTab(requested: TaskTab) {
        val tab = normalizeTab(requested, _ui.value.visibleTabs)
        if (tab == _ui.value.tab) return
        updatePlan { s ->
            s.copy(
                tab = tab,
                planScope = s.planScope.copy(
                    selectedSprintId = if (tab == TaskTab.Sprint) pickSprint(s.selectedSprintId, s.teamSprints, s.currentSprintId) else s.selectedSprintId,
                ),
                searchOpen = false,
            )
        }
        ticketSession.requestPlan(PlanRequest.Close)
        reloadTab()
    }

    fun selectSprint(id: Long) {
        updatePlan { it.copy(planScope = it.planScope.copy(selectedSprintId = id), sheet = null) }
        loadSprint()
    }

    fun openSheet(sheet: TaskSheet) {
        _ui.update { it.copy(sheet = sheet) }
        if (sheet == TaskSheet.Import) ticketSession.requestPlan(PlanRequest.PrepareImport)
    }

    fun closeSheet() {
        _ui.update { it.copy(sheet = null) }
        ticketSession.requestPlan(PlanRequest.CancelImport)
    }

    fun setFilters(filters: TaskFilters) {
        updatePlan { it.copy(planScope = it.planScope.copy(filters = filters)) }
        if (_ui.value.tab != TaskTab.Backlog) reloadTab()
        else ticketSession.requestPlan(PlanRequest.EnsureLoaded)
    }

    fun clearFilters() {
        ticketSession.requestPlan(PlanRequest.ClearFilters)
        updatePlan { it.copy(planScope = it.planScope.copy(selectedEpicId = null)) }
        setFilters(TaskFilters())
    }

    fun togglePriority(value: String) {
        val current = _ui.value.filters
        setFilters(current.copy(priority = if (value.isEmpty() || current.priority == value) "" else value))
    }

    /** Status chip filter on the Sprint board (server allow-list keys only). */
    fun toggleStatus(value: String) {
        val current = _ui.value.filters
        setFilters(current.copy(status = if (value.isEmpty() || current.status == value) "" else value))
    }

    // ── Global search (`useGlobalSearch`) ────────────────────────────────

    fun setSearch(value: String) {
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _ui.update { it.copy(searchQuery = value, searchResultIds = emptyList(), searching = false) }
            return
        }
        _ui.update { it.copy(searchQuery = value, searching = true) }
        searchJob = ticketScope.launch(Dispatchers.IO) {
            delay(300)
            val results = runCatching { repository.search(value.trim()) }.getOrDefault(emptyList())
            ticketSession.store.upsert(results)
            _ui.update {
                it.copy(
                    searchResultIds = results.filter { ticket -> ticket.workflowStateKey != "cancelled" }
                        .map { ticket -> ticket.id }.distinct(),
                    searching = false,
                )
            }
        }
    }

    fun openSearch() = _ui.update { it.copy(searchOpen = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searchOpen = false, searchQuery = "", searchResultIds = emptyList(), searching = false) }
    }

    // ── Sprint board ─────────────────────────────────────────────────────

    fun loadSprint() = ticketSession.requestBoard(BoardRequest.Load)

    fun loadBacklog() = ticketSession.requestPlan(PlanRequest.Load)

    /** Web `?tab=` / `?sprint_id=` query params. */
    fun applyLink(tab: String?, sprintId: Long?) {
        TaskTab.fromKey(tab)?.let { next ->
            val visible = _ui.value
            // A deep link may arrive before the team context resolves; keep the
            // request until the tab is known to be visible rather than dropping it.
            val target = if (visible.userId == null) next else normalizeTab(next, visible.visibleTabs)
            _ui.update { it.copy(tab = target) }
        }

        sprintId?.let { id -> updatePlan { it.copy(planScope = it.planScope.copy(selectedSprintId = id)) } }
        if (tab != null || sprintId != null) reloadTab()
    }

    internal val repo: TaskRepository get() = repository

    // ── Preferences (shared with web) ────────────────────────────────────

    /** Load the per-user Tasks preferences so both clients agree. */
    fun loadPreferences() {
        io {
            runCatching { repository.loadPreferences() }.onSuccess { prefs ->
                _ui.update { it.copy(preferences = prefs) }
                // The remembered team is only known once preferences land, so
                // apply it here too (a cold start may beat the first refresh).
                val s = _ui.value
                if (showsTeamPicker(s.role) && s.selectedTeamId == null && s.teams.isNotEmpty()) {
                    val teamId = defaultTeamId(prefs.defaultTeamId, s.teamId, s.teams)
                    if (teamId != null) selectTeam(teamId, persist = false)
                }
            }
        }
    }

    /**
     * Show another team's sprint, backlog and scheduled tickets.
     *
     * Only super admin and above reach this. The chosen team narrows the sprint
     * list, drives the tabs and is sent with every list query; the choice is
     * remembered in the shared Tasks preferences (`default_team_id`).
     */
    fun selectTeam(teamId: Long?, persist: Boolean = true) {
        val s = _ui.value
        val scoped = sprintsForTeam(s.sprints, teamId, pickerVisible = true)
        updatePlan { state ->
            teamViews[state.selectedTeamId] = CachedTeamViews(
                state.currentSprintId, state.currentSprintRow, state.selectedSprintId,
            )
            val cached = teamViews[teamId]
            val currentSprintId = cached?.currentSprintId
            val currentSprintRow = cached?.currentSprintRow?.takeIf { it.id == currentSprintId }
            val teamSprints = if (currentSprintRow != null && scoped.none { row -> row.id == currentSprintRow.id }) {
                scoped + currentSprintRow
            } else {
                scoped
            }
            val tabs = planningTabs(state.agileEnabled, teamSprints, currentSprintId)
            val selectedSprintId = cached?.selectedSprintId
            state.copy(
                planScope = state.planScope.copy(
                    selectedTeamId = teamId,
                    teamResolved = true,
                    currentSprintId = currentSprintId,
                    currentSprintRow = currentSprintRow,
                    selectedSprintId = pickSprint(selectedSprintId, teamSprints, currentSprintId),
                ),
                preferences = state.preferences.copy(defaultTeamId = teamId?.takeIf { id -> id > 0 }),
                tab = if (currentSprintId != null) TaskTab.Sprint else normalizeTab(state.tab, tabs),
            )
        }
        io {
            val sprintList = runCatching { repository.loadTeamSprints(teamId) }.getOrNull() ?: return@io
            updatePlan { current ->
                if (current.selectedTeamId != teamId) return@updatePlan current
                val rows = sprintsForTeam(current.sprints, teamId, pickerVisible = true)
                val currentSprintId = sprintList.currentSprintId
                val currentSprintRow = sprintList.sprints.firstOrNull { it.id == currentSprintId }
                val teamSprints = if (currentSprintRow != null && rows.none { it.id == currentSprintRow.id }) {
                    rows + currentSprintRow
                } else {
                    rows
                }
                val tabs = planningTabs(current.agileEnabled, teamSprints, currentSprintId)
                current.copy(
                    planScope = current.planScope.copy(
                        currentSprintId = currentSprintId,
                        currentSprintRow = currentSprintRow,
                        selectedSprintId = pickSprint(current.selectedSprintId, teamSprints, currentSprintId),
                    ),
                    tab = if (currentSprintId != null) TaskTab.Sprint else normalizeTab(current.tab, tabs),
                )
            }
            if (_ui.value.tab == TaskTab.Sprint) loadSprint()
        }
        if (persist) {
            io {
                runCatching {
                    repository.savePreferences(
                        buildJsonObject { put("default_team_id", teamId?.let { id -> JsonPrimitive(id) } ?: JsonNull) },
                    )
                }
            }
        }
        loadBacklog()
        loadScheduled()
        if (_ui.value.tab == TaskTab.Sprint) loadSprint()
    }

    /** Toggle the completed section; the choice persists per user. */
    fun setShowCompleted(show: Boolean) {
        _ui.update { it.copy(preferences = it.preferences.copy(showCompleted = show)) }
        io {
            runCatching {
                repository.savePreferences(
                    kotlinx.serialization.json.buildJsonObject {
                        put("show_completed", kotlinx.serialization.json.JsonPrimitive(show))
                    },
                )
            }.onSuccess { prefs -> _ui.update { it.copy(preferences = prefs) } }
        }
    }

    /** Switch between comfortable and compact cards; the choice persists per user. */
    fun setCardDensity(compact: Boolean) {
        val density = if (compact) "compact" else "comfortable"
        _ui.update { it.copy(preferences = it.preferences.copy(cardDensity = density)) }
        io {
            runCatching {
                repository.savePreferences(
                    kotlinx.serialization.json.buildJsonObject {
                        put("card_density", kotlinx.serialization.json.JsonPrimitive(density))
                    },
                )
            }.onSuccess { prefs -> _ui.update { it.copy(preferences = prefs) } }
        }
    }

    fun selectEpic(id: Long?) = updatePlan { it.copy(planScope = it.planScope.copy(selectedEpicId = id)) }

    companion object {
        fun factory(
            context: Context,
            session: TicketSession = app.aino.mobile.core.AppContainer.get(context).ticketSession.value,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return TasksShellViewModel(TaskRepository(container.api), ServiceDeskRepository(container.api), session) as T
            }
        }
    }
}
