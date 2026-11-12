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
    private data class ProjectContext(
        val sprints: List<AvailableSprint>,
        val currentSprintId: Long?,
        val selectedSprintId: Long?,
        val agile: AgileConfig,
    )
    private val projectViews = mutableMapOf<Long, ProjectContext>()

    init {
        this.ticketSession.onClose { synchronized(this.ticketSession.planScopeLock) { projectViews.clear() } }
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
            projectViews.clear()
        }
        updatePlan { state ->
            val base = if (changedUser) TaskShellUiState() else state
            val planScope = base.planScope.copy(
                userId = userId, role = role, teamName = teamName, teamId = teamId,
                agileEnabled = agileEnabled, customFieldsEnabled = customFieldsEnabled,
                sprintEnabled = sprintEnabled,
                projectScoped = true,
            )
            base.copy(
                planScope = planScope,
                tab = if (changedUser) TaskTab.Backlog
                else normalizeTab(
                    current.tab,
                    planScope.visibleTabs,
                ),
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
            val labels = runCatching(repository::loadLabels).getOrNull()
            val users = runCatching(repository::loadAssignableUsers).getOrNull()
            val accessible = runCatching(repository::loadAccessibleProjects).getOrNull()
                ?: _ui.value.planScope.projects
            val mine = runCatching(repository::loadMyProjects).getOrNull() ?: _ui.value.planScope.myProjects
            updatePlan { state ->
                state.copy(planScope = state.planScope.copy(
                    labels = labels ?: state.labels,
                    assignableUsers = users ?: state.assignableUsers,
                    projects = accessible, myProjects = mine, projectScoped = true,
                    selectedProjectId = state.planScope.selectedProjectId?.takeIf { id -> accessible.any { it.id == id } }
                        ?: accessible.firstOrNull()?.id,
                    selectedTeamId = null, teams = emptyList(), teamResolved = true,
                ))
            }
            loadProjectContext()
            landingApplied = true
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
                refresh()
            }
            TicketSyncSignal.Reconnect -> {
                val state = _ui.value
                if (state.tab == TaskTab.Sprint) loadSprint()
                else ticketSession.requestBoard(BoardRequest.ReloadLoaded)
                ticketSession.requestPlan(PlanRequest.ReloadLoaded)
                if (state.tab == TaskTab.ServiceDesk) reloadTab()
            }
        }
    }

    private fun reloadTab() {
        when (_ui.value.tab) {
            TaskTab.Sprint -> loadSprint()
            TaskTab.Reports -> Unit
            TaskTab.KanbanBoard, TaskTab.List, TaskTab.Upcoming -> Unit
            TaskTab.Backlog -> if (_ui.value.planScope.projectType != "kanban") loadBacklog()
            TaskTab.ServiceDesk -> _ui.update { it.copy(serviceDeskVersion = it.serviceDeskVersion + 1) }
        }
    }

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
        if (sheet == TaskSheet.CreateTicket) ticketSession.requestPlan(PlanRequest.PrepareCreate)
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
            val query = value.trim()
            val results = runCatching {
                if (Regex("^[A-Za-z][A-Za-z0-9]*-[1-9][0-9]*$").matches(query)) {
                    listOf(repository.loadByKey(query.uppercase(java.util.Locale.ROOT)))
                } else repository.search(query)
            }.getOrDefault(emptyList())
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
            }
        }
    }

    fun selectProject(projectId: Long) {
        val scope = _ui.value.planScope
        if (!scope.projectScoped || scope.projects.none { it.id == projectId } || scope.selectedProjectId == projectId) return
        updatePlan { state ->
            state.planScope.selectedProjectId?.let { previous ->
                projectViews[previous] = ProjectContext(state.sprints, state.currentSprintId, state.selectedSprintId, state.agile)
            }
            val cached = projectViews[projectId]
            val planScope = state.planScope.copy(
                selectedProjectId = projectId, currentSprintId = cached?.currentSprintId,
                currentSprintRow = cached?.sprints?.firstOrNull { it.id == cached.currentSprintId },
                selectedSprintId = cached?.selectedSprintId, sprints = cached?.sprints.orEmpty(),
                agile = cached?.agile ?: AgileConfig(),
            )
            state.copy(
                planScope = planScope,
                tab = normalizeTab(state.tab, planScope.visibleTabs),
                sheet = null,
            )
        }
        io {
            loadProjectContext()
            reloadTab()
        }
    }

    private fun loadProjectContext() {
        val scope = _ui.value.planScope
        val projectId = scope.selectedProjectId ?: return
        val hasSprintTab = TaskTab.Sprint in scope.visibleTabs
        val list = if (hasSprintTab) runCatching { repository.loadProjectSprints(projectId) }.getOrNull() else null
        val config = if (
            hasSprintTab || scope.projectType == "task_list" ||
            (scope.agileEnabled && scope.projectType == "kanban")
        ) {
            runCatching { repository.loadAgileConfig(projectId) }.getOrNull()
        } else null
        updatePlan { state ->
            if (state.planScope.selectedProjectId != projectId) return@updatePlan state
            val rows = (list?.sprints ?: state.sprints).filter { it.projectId == projectId }
            val current = (if (list != null) list.currentSprintId else state.currentSprintId)
                ?.takeIf { id -> rows.any { it.id == id } }
            val planScope = state.planScope.copy(
                sprints = rows, currentSprintId = current,
                currentSprintRow = rows.firstOrNull { it.id == current },
                selectedSprintId = pickSprint(state.selectedSprintId, rows, current),
                agile = config ?: state.agile,
            )
            state.copy(
                planScope = planScope,
                tab = normalizeTab(
                    if (!landingApplied && current != null) TaskTab.Sprint else state.tab,
                    planScope.visibleTabs,
                ),
            )
        }
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
