package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.isTransientFailure
import app.aino.mobile.core.network.userFacingMessage
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

class TaskViewModel(
    private val repository: TaskRepository,
    internal val serviceDesk: ServiceDeskRepository? = null,
    ticketSession: TicketSession? = null,
) : ViewModel(), TicketCommentActions {
    private val ownsTicketSession = ticketSession == null
    val ticketSession = ticketSession ?: TicketSession()
    private val ticketSync = this.ticketSession.sync(repository)
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(this.ticketSession.job))
    private val _ui = this.ticketSession.state(TaskShellUiState(planScope = this.ticketSession.planScope.value))
    val ui: StateFlow<TaskShellUiState> = _ui.asStateFlow()
    init {
        ticketScope.launch {
            this@TaskViewModel.ticketSession.planScope.collect { scope ->
                synchronized(this@TaskViewModel.ticketSession.planScopeLock) {
                    if (scope == this@TaskViewModel.ticketSession.planScope.value) {
                        _ui.update { it.copy(planScope = scope) }
                    }
                }
            }
        }
        ticketScope.launch {
            this@TaskViewModel.ticketSession.store.state.collect { snapshot ->
                _ui.update { current ->
                    val changed = snapshot.tickets.values.filter { it !== current.tickets[it.id] }
                    val scheduledIds = current.scheduledTicketIds
                        .filter { id -> snapshot.tickets[id]?.let(current::containsScheduledTicket) == true }
                    val newlyScheduledIds = if (current.scheduledLoaded) {
                        changed.filter(current::containsScheduledTicket).map { it.id }
                    } else {
                        emptyList()
                    }
                    val searchableIds = current.searchResultIds.filter { id ->
                        snapshot.tickets[id]?.workflowStateKey?.let { it != "cancelled" } == true
                    }
                    current.copy(
                        tickets = snapshot.tickets,
                        recentlyUpdated = snapshot.recentlyChanged,
                        scheduledTicketIds = (scheduledIds + newlyScheduledIds).distinct(),
                        searchResultIds = searchableIds,
                        inlineCommentTaskId = current.inlineCommentTaskId?.takeIf { id ->
                            snapshot.tickets[id]?.let { it.workflowStateKey != "cancelled" } == true
                        },
                    )
                }
            }
        }
        ticketScope.launch {
            ticketSync.signals.collect(::handleTicketSyncSignal)
        }
        ticketScope.launch {
            ticketSync.changes.collect(::handleTicketSyncChange)
        }
        ticketScope.launch {
            this@TaskViewModel.ticketSession.planRefreshes.collect {
                refresh()
            }
        }
        ticketScope.launch {
            this@TaskViewModel.ticketSession.shellRequests.collect { request ->
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

    private var errorJob: Job? = null
    private var searchJob: Job? = null
    private var carriedOn: String? = null
    /** The sprint landing has been applied once; a later tab choice is honoured. */
    private var landingApplied = false
    private data class CachedTeamViews(
        val currentSprintId: Long?,
        val currentSprintRow: AvailableSprint?,
        val selectedSprintId: Long?,
        val scheduledTicketIds: List<Long>,
        val scheduledLoaded: Boolean,
    )
    private val teamViews = mutableMapOf<Long?, CachedTeamViews>()
    private var scheduledJob: Job? = null
    /** Bumped on every list reload; a page/result from an older generation is discarded. */
    private val scheduledGen = java.util.concurrent.atomic.AtomicInteger()

    init {
        this.ticketSession.onClose {
            synchronized(this.ticketSession.planScopeLock) { teamViews.clear() }
        }
    }

    private fun io(block: suspend () -> Unit) = ticketScope.launch(Dispatchers.IO) { block() }

    private fun notify(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        ticketSession.publishNotice(TaskNotice(text, actionLabel, action))
    }

    /** `useAutoDismiss("")` — errors clear after 5s. */
    private fun fail(message: String) {
        _ui.update { it.copy(error = message) }
        errorJob?.cancel()
        errorJob = ticketScope.launch {
            delay(5_000)
            _ui.update { if (it.error == message) it.copy(error = null) else it }
        }
    }

    /** Background list reloads: a transient failure behind data already on screen stays silent. */
    private fun loadFailed(e: Throwable, hasData: Boolean, message: String) {
        if (hasData && isTransientFailure(e)) _ui.update { if (it.error == message) it.copy(error = null) else it }
        else fail(message)
    }

    private fun failure(e: Throwable, fallback: String): String {
        val message = if (e is ApiError) userFacingMessage(e, fallback)
        else e.message?.takeIf { it.isNotBlank() && it != "Task action failed" && !it.startsWith("HTTP ") } ?: fallback
        return capabilityFailureCopy(message) ?: message
    }

    private fun visibleTask(id: Long): Task? {
        val s = _ui.value
        return ticketSession.store.state.value.tickets[id] ?: (s.scheduled + s.searchResults)
            .firstOrNull { it.id == id }
    }

    private fun confirm(title: String, message: String, confirmText: String, danger: Boolean = false, action: () -> Unit) {
        _ui.update { it.copy(confirm = ConfirmRequest(title, message, confirmText, danger, action)) }
    }

    fun acceptConfirm() {
        val request = _ui.value.confirm ?: return
        _ui.update { it.copy(confirm = null) }
        request.action()
    }

    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    fun clearError() = _ui.update { it.copy(error = null) }

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
            autoCarryForward()
            reloadTab()
            _ui.update { it.copy(refreshing = false) }
        }
    }

    /**
     * Light foreground poll while Tasks is on screen: teammates' edits never
     * reach this user's socket (the server only targets assignee/creator/actor).
     */
    fun pollVisible() = ticketSession.pollVisible()

    /** Compatibility entrypoint; the session owns queueing and reconciliation. */
    fun onTaskEvent(type: String, data: JsonElement?) = ticketSession.onRealtimeEvent(type, data)

    /** Reconnect catch-up: the server keeps no replay log, so revalidate every loaded view. */
    fun onTaskEvent() = ticketSession.onReconnect()

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
                if (state.scheduledLoaded) loadScheduled()
                if (state.tab == TaskTab.ServiceDesk) reloadTab()
                if (!state.scheduledLoaded &&
                    state.tab != TaskTab.ServiceDesk && state.tab != TaskTab.Sprint
                ) {
                    reloadTab()
                }
            }
        }
    }

    private fun handleTicketSyncChange(change: TicketSyncChange) {
        when (change) {
            is TicketSyncChange.Updated -> {
                val state = _ui.value
                if (change.refreshBoard && state.tab == TaskTab.Sprint) loadSprint()
            }
            is TicketSyncChange.Removed -> Unit
        }
    }

    /** Writes the authoritative Ticket; each loaded view resolves its membership from it. */
    private fun patchTask(task: Task, highlight: Boolean, writeToStore: Boolean = true): Task {
        ticketSession.planRevision.incrementAndGet()
        val current = if (writeToStore) ticketSession.store.upsert(task) else task
        if (highlight) flash(current.id)
        return current
    }

    private fun removeTask(id: Long, remote: Boolean) {
        ticketSession.planRevision.incrementAndGet()
        ticketSession.store.remove(id, preventReinsert = remote)
        _ui.update { s ->
            s.copy(
                inlineCommentTaskId = s.inlineCommentTaskId?.takeIf { it != id },
            )
        }
    }

    /** Callable from any thread; the job map is only touched on Main. */
    private fun flash(id: Long) {
        ticketSession.store.highlight(id)
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

    fun loadScheduled() {
        scheduledJob?.cancel()
        _ui.update { it.copy(scheduledLoading = !it.scheduledLoaded) }
        val gen = scheduledGen.incrementAndGet()
        val scope = _ui.value.teamScope
        val filters = _ui.value.filters
        scheduledJob = io {
            val (from, to) = scheduledWindow()
            // Overlays are read-only context for the same window, so they load
            // alongside the tasks rather than on a second round trip.
            runCatching { repository.loadScheduleOverlays(from.toString(), to.toString()) }
                .onSuccess { list -> _ui.update { it.copy(overlays = list) } }
            // Overlays are read-only context for the same window, so they load
            // alongside the tasks rather than on a second round trip.
            val scheduled = runCatching { repository.loadScheduled(filters, scope) }
            scheduled.fold(
                onSuccess = { list ->
                    if (gen != scheduledGen.get() || scope != _ui.value.teamScope) return@fold
                    ticketSession.store.upsert(list)
                    _ui.update {
                        if (scope == it.teamScope) it.copy(
                            scheduledTicketIds = list.map { ticket -> ticket.id }.distinct(),
                            scheduledLoading = false,
                            scheduledLoaded = true,
                        ) else it
                    }
                },
                onFailure = {
                    if (gen != scheduledGen.get()) return@fold
                    val hadData = _ui.value.scheduledLoaded
                    _ui.update { it.copy(scheduledLoading = false) }
                    loadFailed(it, hadData, "Failed to load scheduled tasks")
                },
            )
        }
    }

    /**
     * Opens the Scheduled tab scrolled to [date]. A sprint team has no such
     * tab, so the focus is kept for when it does and the tab is normalized like
     * every other tab write instead of selecting a page that does not exist.
     */
    fun showScheduled(date: String?) {
        _ui.update {
            val tab = normalizeTab(TaskTab.Scheduled, it.visibleTabs)
            it.copy(tab = tab, scheduledFocus = date, searchOpen = false)
        }
        loadScheduled()
    }

    /**
     * Open the Plan split view from the Backlog (its only entry point, as in
     * Jira). With no planned or running sprint there is nothing to plan into,
     * so the user is told where sprints come from instead of seeing an empty
     * planner.
     */
    fun openCreateForColumn(state: WorkflowState) = ticketSession.requestPlan(PlanRequest.CreateForColumn(state))

    fun consumeScheduledFocus() = _ui.update { it.copy(scheduledFocus = null) }

    /** Tasks.tsx runs carry-forward once per local day and shows a banner. */
    private suspend fun autoCarryForward() {
        val today = LocalDate.now().toString()
        if (carriedOn == today) return
        carriedOn = today
        runCatching(repository::carryForward).fold(
            onSuccess = { result ->
                if (result.carried > 0) {
                    notify("${result.carried} incomplete item${if (result.carried > 1) "s" else ""} from yesterday carried forward")
                }
            },
            onFailure = {
                if (isTransientFailure(it)) carriedOn = null else fail("Failed to carry forward tasks")
            },
        )
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

    private fun patchServerTask(saved: Task) {
        patchTask(saved, highlight = false)
    }

    fun loadBacklog() = ticketSession.requestPlan(PlanRequest.Load)

    fun schedule(taskId: Long, date: String) {
        if (date.isEmpty()) return
        val task = visibleTask(taskId)
        val decision = task?.capability(TaskCapability.Schedule) ?: TaskCapabilityDecision()
        if (!decision.allowed) return notify(decision.reasonCopy())
        io {
            runCatching { repository.scheduleTask(taskId, date) }.fold(
                onSuccess = { saved ->
                    patchTask(saved, highlight = true)
                    if (_ui.value.tab == TaskTab.Scheduled) loadScheduled()
                    notify("Scheduled for ${formatDate(date)}", "View") { showScheduled(date) }
                },
                onFailure = { e -> fail(failure(e, "Failed to schedule task")) },
            )
        }
    }

    /** `handleUnscheduleTask`; the confirm dialog is skipped for the Scheduled list's swipe (it has Undo). */
    fun unschedule(taskId: Long, title: String?, ask: Boolean = true) {
        val decision = visibleTask(taskId)?.capability(TaskCapability.Schedule) ?: TaskCapabilityDecision()
        if (!decision.allowed) return notify(decision.reasonCopy())
        val previousDate = ticketSession.store.state.value.tickets[taskId]?.plannedDate
        val run = {
            io {
                runCatching { repository.unscheduleTask(taskId) }.fold(
                    onSuccess = { saved ->
                        patchTask(saved, highlight = true)
                        val back = previousDate?.take(10)
                        if (back != null) notify("Moved to backlog", "Undo") { schedule(taskId, back) } else notify("Moved to backlog")
                    },
                    onFailure = { fail("Failed to move task to backlog") },
                )
            }
            Unit
        }
        if (ask) confirm("Move to Backlog", "Move \"${title ?: "this task"}\" to backlog? It will be removed from the planner.", "Move to Backlog") { run() }
        else run()
    }

    /** Backlog swipe / detail sprint picker (`PATCH /tasks/:id/assign-sprint`). */
    fun assignSprint(task: Task, sprintId: Long?) {
        val decision = task.capability(TaskCapability.PlaceInSprint)
        if (!decision.allowed) return notify(decision.reasonCopy())
        io {
            runCatching { repository.assignSprint(task.id, sprintId) }.fold(
                onSuccess = { saved ->
                    val name = sprintId?.let { id -> _ui.value.sprints.firstOrNull { it.id == id }?.name }
                    notify(if (name != null) "Added to $name" else "Moved to backlog")
                    patchTask(saved, highlight = true)
                    if (_ui.value.tab == TaskTab.Sprint) loadSprint()
                },
                onFailure = { e -> fail(failure(e, "Failed to assign sprint")) },
            )
        }
    }

    /**
     * "Cancel ticket" ConfirmDialog.
     *
     * Tickets are cancelled, never hard-deleted: the API moves the ticket to the
     * org's terminal "Cancelled" state, so its history, comments and time
     * survive and it can be moved back to an open state.
     */
    fun requestDelete(task: Task) {
        val decision = task.capability(TaskCapability.Cancel)
        if (!decision.allowed) return notify(decision.reasonCopy())
        confirm(
            "Cancel ticket",
            "Cancel \"${task.title}\"? The ticket and its history are kept, and it leaves the open work.",
            "Cancel ticket",
            danger = true,
        ) {
            io {
                runCatching { repository.deleteTask(task.id) }.fold(
                    onSuccess = {
                        removeTask(task.id, remote = false)
                        notify("Cancelled ${task.displayKey}")
                        if (_ui.value.tab == TaskTab.Sprint) loadSprint()
                    },
                    onFailure = { e -> fail(failure(e, "Failed to cancel ticket")) },
                )
            }
        }
    }

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

    // ── Comments (detail + inline panel) ─────────────────────────────────

    fun openComments(taskId: Long) {
        val threads = ticketSession.commentThreads.value
        val cached = threads[taskId]
        _ui.update { it.copy(inlineCommentTaskId = taskId) }
        ticketSession.setCommentThread((cached ?: CommentThread(taskId)).copy(loading = true))
        io {
            val items = runCatching { repository.loadComments(taskId) }.getOrDefault(emptyList())
            if (_ui.value.inlineCommentTaskId == taskId) {
                ticketSession.setCommentThread(CommentThread(taskId, items, loading = false))
            }
        }
    }

    fun closeComments() = _ui.update { it.copy(inlineCommentTaskId = null) }

    private fun bumpCommentCount(taskId: Long, delta: Int) {
        ticketSession.store.update(taskId) {
            it.copy(commentCount = maxOf(0, it.commentCount + delta))
        }
    }

    private fun updateComments(taskId: Long, transform: (List<TaskComment>) -> List<TaskComment>) {
        ticketSession.updateCommentThread(taskId) { thread ->
            thread.copy(items = transform(thread.items), loading = false)
        }
    }

    /** Text and/or one attachment (`CommentSection.handleAdd`). */
    override fun addComment(taskId: Long, text: String, file: CommentFile?, mentions: Map<String, Long>, onDone: () -> Unit) {
        val html = plainTextToHtml(text, mentions)
        if (text.isBlank() && file == null) return
        validateComment(text).takeIf { file == null }?.let { fail(it); return }
        io {
            runCatching {
                if (file != null) repository.addCommentWithFile(taskId, html, file.name, file.mimeType, file.bytes)
                else repository.addComment(taskId, html)
            }.fold(
                onSuccess = { comment ->
                    updateComments(taskId) { list -> if (list.any { it.id == comment.id }) list else list + comment }
                    bumpCommentCount(taskId, 1)
                    ticketScope.launch { onDone() }
                },
                onFailure = { fail("Failed to add comment") },
            )
        }
    }

    override fun editComment(taskId: Long, commentId: Long, text: String, onDone: () -> Unit) {
        if (text.isBlank()) return
        io {
            runCatching { repository.updateComment(taskId, commentId, plainTextToHtml(text)) }.fold(
                onSuccess = { updated ->
                    // The PUT returns the bare row; keep the author fields we already have.
                    updateComments(taskId) { list ->
                        list.map { c ->
                            if (c.id == commentId) updated.copy(
                                username = updated.username ?: c.username,
                                fullName = updated.fullName ?: c.fullName,
                                avatar = updated.avatar ?: c.avatar,
                            ) else c
                        }
                    }
                    ticketScope.launch { onDone() }
                },
                onFailure = { fail("Failed to update comment") },
            )
        }
    }

    override fun deleteComment(taskId: Long, commentId: Long) {
        confirm("Delete Comment", "Are you sure you want to delete this comment? This cannot be undone.", "Delete", danger = true) {
            io {
                runCatching { repository.deleteComment(taskId, commentId) }.fold(
                    onSuccess = {
                        updateComments(taskId) { list -> list.filterNot { it.id == commentId } }
                        bumpCommentCount(taskId, -1)
                    },
                    onFailure = { fail("Failed to delete comment") },
                )
            }
        }
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
                state.scheduled.map { it.id }, state.scheduledLoaded,
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
                scheduledTicketIds = cached?.scheduledTicketIds.orEmpty(),
                scheduledLoaded = cached?.scheduledLoaded ?: false,
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

    // ── Schedule overlays ────────────────────────────────────────────────

    fun loadOverlays(from: String, to: String) {
        io {
            runCatching { repository.loadScheduleOverlays(from, to) }.onSuccess { list ->
                _ui.update { it.copy(overlays = list) }
            }
        }
    }

    /** Each overlay type toggles independently; the choice is per session. */
    fun toggleOverlayType(type: String) {
        _ui.update {
            it.copy(overlayTypes = if (type in it.overlayTypes) it.overlayTypes - type else it.overlayTypes + type)
        }
    }

    companion object {
        fun factory(
            context: Context,
            session: TicketSession = app.aino.mobile.core.AppContainer.get(context).ticketSession.value,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return TaskViewModel(TaskRepository(container.api), ServiceDeskRepository(container.api), session) as T
            }
        }
    }
}

/** A picked comment attachment. */
class CommentFile(val name: String, val mimeType: String, val bytes: ByteArray)
