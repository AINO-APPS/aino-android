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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** The web `showConfirm(title, message, onConfirm, { confirmText, isDanger })`. */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmText: String,
    val danger: Boolean = false,
    val action: () -> Unit,
)

/** New Backlog Ticket form (`useBacklog` fields). */
data class BacklogDraft(
    val title: String = "",
    val description: String = "",
    val priority: String = "medium",
    val assignedTo: Long? = null,
    val dueDate: String = "",
    val labels: List<Long> = emptyList(),
    val sprintId: Long? = null,
    val storyPoints: String? = null,
    val workItemTypeId: Long? = null,
    val projectId: Long? = null,
    /** Set by a board column's "+": the ticket starts in that workflow state. */
    val workflowStateId: Long? = null,
)

/** Title/description editor; every other field is edited inline from the properties card. */
data class TaskEditDraft(
    val title: String,
    val description: String,
    val descriptionHtml: String,
) {
    companion object {
        fun from(task: Task): TaskEditDraft =
            TaskEditDraft(title = task.title, description = stripHtml(task.description), descriptionHtml = task.description.orEmpty())
    }
}

enum class DetailTab(val label: String) { Comments("Comments"), History("Activity"), Checklist("Checklist"), Links("Links"), Fields("Fields") }

/** One Ticket's shared comments, projected into Detail and the inline panel. */
data class CommentThread(val taskId: Long, val items: List<TaskComment> = emptyList(), val loading: Boolean = true)

/** One-shot snackbar; [action] runs when its button is tapped (e.g. Undo). */
data class TaskNotice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Shared bottom sheets on the Tasks page. */
enum class TaskSheet { Filters, CreateTicket, Import, SprintPicker }

data class TaskUiState(
    val planScope: PlanScope = PlanScope(),
    // Page.
    val tab: TaskTab = TaskTab.Backlog,
    /** The Backlog is showing the Plan split view (backlog beside a sprint). */
    val planOpen: Boolean = false,
    val sheet: TaskSheet? = null,
    val refreshing: Boolean = false,
    /** Ids patched by a realtime event a moment ago (cards flash briefly). */
    val recentlyUpdated: Set<Long> = emptySet(),
    val tickets: Map<Long, Task> = emptyMap(),
    /** The open detail changed elsewhere while the user was editing it. */
    val detailStale: Boolean = false,
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
    // Global search.
    val searchQuery: String = "",
    val searchResultIds: List<Long> = emptyList(),
    val searching: Boolean = false,
    val searchOpen: Boolean = false,
    // Sprint board.
    val boardTicketIds: List<Long> = emptyList(),
    val board: SprintBoardResponse? = null,
    val stats: TaskStats = TaskStats(),
    val sprintStats: SprintStats? = null,
    val sprintLoading: Boolean = true,
    val carriedCount: Int = 0,
    val importTaskId: Long? = null,
    val importAssignedTo: Long? = null,
    val importDueDate: String = "",
    val lifecycleBusy: Boolean = false,
    val lifecycleError: String? = null,
    val completing: Boolean = false,
    val rolloverOptions: List<AvailableSprint> = emptyList(),
    val rolloverTo: String = "backlog",
    // Backlog.
    /** Loaded, unfiltered Plan ids, including completed work. */
    val planTicketIds: List<Long> = emptyList(),
    val planLoaded: Boolean = false,
    val planBaseline: TicketPlanBaseline? = null,
    val planOffset: Int = 0,
    val planHasMore: Boolean = false,
    val quickFilters: BacklogQuickFilters = BacklogQuickFilters(),
    val backlogPageSize: Int = 25,
    val backlogSort: BacklogSort = BacklogSort.Priority,
    val backlogLoading: Boolean = false,
    val backlogLoadingMore: Boolean = false,
    val draft: BacklogDraft = BacklogDraft(),
    val creating: Boolean = false,
    // Scheduled membership is retained as ids; ticket state lives in the session store.
    val scheduledTicketIds: List<Long> = emptyList(),
    val scheduledLoading: Boolean = false,
    val scheduledLoaded: Boolean = false,
    /** Day to bring into view after "View" on the schedule snackbar or a day-picker jump. */
    val scheduledFocus: String? = null,
    // Detail.
    val detailId: Long? = null,
    val detailLoading: Boolean = false,
    val detailEditing: Boolean = false,
    val detailTab: DetailTab = DetailTab.Comments,
    val history: List<TaskHistoryEntry> = emptyList(),
    val commentThreads: Map<Long, CommentThread> = emptyMap(),
    // Inline comment panel (the card's 💬).
    val inlineCommentTaskId: Long? = null,
    /** Bumped on refresh while the Service Desk tab is visible; the tab reloads on change. */
    val serviceDeskVersion: Int = 0,
    // ── Next-generation surfaces ─────────────────────────────────────────
    /** Per-user Tasks preferences, shared with web so both clients agree. */
    val preferences: TaskPreferences = TaskPreferences(),
    /** User expansion choice is preserved while selection temporarily collapses it. */
    val completedOpen: Boolean = true,
    val completedLoading: Boolean = false,
    /** True once "View all completed" has dropped the recent-window filter. */
    val completedShowingAll: Boolean = false,
    /** Multi-select for bulk actions. */
    val selectedIds: Set<Long> = emptySet(),
    /** Epics with their rollups, for the epic filter. */
    val epics: List<EpicSummary> = emptyList(),
    /** Read-only background context on the schedule. */
        val overlays: ScheduleOverlays = ScheduleOverlays(),
    /** Which overlay types are currently shown; each toggles independently. */
    val overlayTypes: Set<String> = setOf("leave", "holiday", "meeting"),
    /** The carryover pre-flight shown before a sprint is completed. */
    val completionPreview: CompletionPreview? = null,
    /** Committed points and the scope delta for the selected sprint. */
        val baseline: BaselineResponse? = null,
    /** Time tracking for the open detail. */
    val timerRunning: Boolean = false,
    val timerBusy: Boolean = false,
    /** "Updated by <name>" flashes keyed by task id, from realtime deltas. */
    val attribution: Map<Long, String> = emptyMap(),
) {
    val userId get() = planScope.userId
    val role get() = planScope.role
    val teamName get() = planScope.teamName
    val teamId get() = planScope.teamId
    val agileEnabled get() = planScope.agileEnabled
    val sprintEnabled get() = planScope.sprintEnabled
    val customFieldsEnabled get() = planScope.customFieldsEnabled
    val selectedTeamId get() = planScope.selectedTeamId
    val teams get() = planScope.teams
    val teamResolved get() = planScope.teamResolved
    val labels get() = planScope.labels
    val assignableUsers get() = planScope.assignableUsers
    val projects get() = planScope.projects
    val sprints get() = planScope.sprints
    val currentSprintId get() = planScope.currentSprintId
    val currentSprintRow get() = planScope.currentSprintRow
    val agile get() = planScope.agile
    val selectedSprintId get() = planScope.selectedSprintId
    val filters get() = planScope.filters
    val selectedEpicId get() = planScope.selectedEpicId

    val detailComments: List<TaskComment>
        get() = detailId?.let { commentThreads[it]?.items }.orEmpty()
    val inlineComments: CommentThread?
        get() = inlineCommentTaskId?.let { commentThreads[it] ?: CommentThread(it) }

    internal fun inScope(ticket: Task): Boolean = when {
        !showsTeamPicker(role) -> teamId == null || ticket.teamId == teamId
        selectedTeamId == -1L -> ticket.teamId == null
        selectedTeamId != null -> ticket.teamId == selectedTeamId
        else -> true
    }

    private fun resolve(ids: List<Long>, belongs: (Task) -> Boolean): List<Task> =
        ids.mapNotNull(tickets::get).filter { inScope(it) && belongs(it) }

    internal fun containsBoardTicket(ticket: Task): Boolean =
        ticket.container == "sprint" && ticket.sprintId == selectedSprintId && ticket.workflowStateKey != "cancelled"

    internal fun containsPlanTicket(ticket: Task): Boolean =
        ticket.workflowStateKey != "cancelled" && (ticket.container == "backlog" ||
            (ticket.container == "sprint" && ticket.sprintId != null && sprints.none { it.id == ticket.sprintId })
        )

    internal fun containsScheduledTicket(ticket: Task): Boolean {
        val (from, to) = scheduledWindow()
        val plannedDay = localDateOf(ticket.plannedDate) ?: return false
        return inScope(ticket) &&
            ticket.container == "scheduled" &&
            ticket.workflowStateKey != "cancelled" &&
            !plannedDay.isBefore(from) &&
            !plannedDay.isAfter(to) &&
            matchesFilters(ticket, filters, userId, includeStatus = true)
    }

    val tasks: List<Task> get() = resolve(boardTicketIds) {
        containsBoardTicket(it) && matchesFilters(it, filters, userId, includeStatus = true) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val sprintBoard: SprintBoardResponse? get() = board?.takeIf { it.sprint.id == selectedSprintId }?.copy(
        tickets = resolve(boardTicketIds, ::containsBoardTicket),
    )
    val planTasks: List<Task> get() = resolve(planTicketIds, ::containsPlanTicket)
    private val filteredPlan: List<Task> get() = planTasks.filter {
        containsPlanTicket(it) && matchesPlanFilters(it, filters, quickFilters, userId) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val backlog: List<Task> get() = filteredPlan.filterNot { it.isCompleted }
    val completed: List<Task> get() = filteredPlan.filter { it.isCompleted }
    internal val planFiltersActive: Boolean get() = filters != TaskFilters() || !quickFilters.isAll || selectedEpicId != null
    val backlogTotal: Int get() = readPlanTotals().total
    val completedCount: Int get() = readPlanTotals().completed
    val backlogSummary: BacklogSummary get() = readPlanTotals().summary
    val detail: Task? get() = tickets[detailId]?.takeUnless { it.workflowStateKey == "cancelled" }
    val currentSprint: AvailableSprint? get() = planScope.currentSprint
    val sortedBacklog: List<Task> get() = sortBacklog(backlog, backlogSort)
    /** Display sections for the backlog list, or `null` for a flat list. */
    val backlogGroups: List<BacklogGroup>? get() = strandedSprintGroups(sortedBacklog, sprints)
    /** The resolved answer: the server's flag, or the sprint list when it is unknown. */
    val sprintsEnabled: Boolean get() = planScope.sprintsEnabled
    /** The sprint rows on screen belong to the selected team only. */
    val teamSprints: List<AvailableSprint> get() = planScope.teamSprints
    val sprintTabVisible: Boolean get() = agileEnabled && currentSprintId != null && teamSprints.any { it.id == currentSprintId }
    val visibleTabs: List<TaskTab> get() = planningTabs(agileEnabled, teamSprints, currentSprintId)
    /** The query scope for backlog, scheduled and sprint loads. */
    val teamScope: TeamScope get() = planScope.teamScope
    /**
     * The heading's team. The admin's pick wins; otherwise the user's own team,
     * then the sprint's team for an admin browsing one.
     */
    val teamTitle: String
        get() = teams.firstOrNull { it.id == selectedTeamId }?.name
            ?: if (selectedTeamId == -1L) "Unassigned" else null
            ?: teamName
            ?: currentSprint?.teamName
            ?: "Plan"
    val filterCount: Int get() = filterCount(filters, tab) + if (tab == TaskTab.Backlog) quickFilters.count + (if (selectedEpicId != null) 1 else 0) else 0
    /** Sprint planning is not narrowed by Backlog-only quick chips. */
    val planningBacklog: List<Task> get() = (if (planLoaded) planTasks else backlog).filter {
        it.container == "backlog" && it.workflowStateKey != "cancelled" && !it.isCompleted &&
            matchesFilters(it, filters, userId, includeStatus = false) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val importable: List<Task> get() = planningBacklog
    val backlogHasMore: Boolean get() = planHasMore
    val scheduled: List<Task> get() = scheduledTicketIds.mapNotNull(tickets::get).filter(::containsScheduledTicket)
    val searchResults: List<Task> get() = searchResultIds.mapNotNull(tickets::get)
    val scheduledGroups: List<ScheduledGroup> get() = groupScheduled(scheduled)
    val scheduledOverdue: Int get() = scheduledGroups.firstOrNull { it.date == null }?.tasks?.size ?: 0
    /** Compact cards keep key, title and points; comfortable adds the rest. */
    val compact: Boolean get() = preferences.isCompact
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val completedExpanded: Boolean get() = completedOpen && !hasSelection
    /** Selection mode swaps the "New ticket" FAB for the bulk action bar so the two never overlap. */
    val showNewTicketFab: Boolean get() = tab == TaskTab.Backlog && !searchOpen && !hasSelection
    /** The epic filter narrows the backlog and board to one epic's children. */
    val epicFiltered: List<Task> get() = selectedEpicId?.let { epic ->
        backlog.filter { it.parentTaskId == epic }
    } ?: backlog
}

/**
 * One projection for loading, quick/advanced filters, mutations and realtime.
 *
 * Only sprint-less tickets are backlog. A sprint row that reaches us anyway — a
 * stale page, or a task whose sprint was deleted server-side — is kept so it
 * cannot vanish, but it is rendered in a separate "in a sprint" group rather
 * than mixed into the backlog. A task whose sprint is still open is excluded:
 * it belongs on that sprint's board and nowhere else.
 */
internal fun TaskUiState.projectPlan(): TaskUiState {
    return copy(selectedIds = selectedIds.intersect(backlog.map { it.id }.toSet()))
}

/** The Scheduled view's fetch window, relative to today. */
internal fun scheduledWindow(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> =
    today.minusDays(SCHEDULED_PAST_DAYS) to today.plusDays(SCHEDULED_AHEAD_DAYS)

class TaskViewModel(
    private val repository: TaskRepository,
    internal val serviceDesk: ServiceDeskRepository? = null,
    ticketSession: TicketSession? = null,
) : ViewModel() {
    private val ownsTicketSession = ticketSession == null
    val ticketSession = ticketSession ?: TicketSession()
    private val ticketSync = this.ticketSession.sync(repository)
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(this.ticketSession.job))
    private val _ui = this.ticketSession.state(TaskUiState(planScope = this.ticketSession.planScope.value))
    val ui: StateFlow<TaskUiState> = _ui.asStateFlow()
    init {
        ticketScope.launch {
            this@TaskViewModel.ticketSession.planScope.collect { scope ->
                synchronized(this@TaskViewModel.ticketSession.planScopeLock) {
                    if (scope == this@TaskViewModel.ticketSession.planScope.value) {
                        _ui.update { it.copy(planScope = scope).projectPlan() }
                    }
                }
            }
        }
        ticketScope.launch {
            this@TaskViewModel.ticketSession.store.state.collect { snapshot ->
                _ui.update { current ->
                    val changed = snapshot.tickets.values.filter { it !== current.tickets[it.id] }
                    val detailUnavailable = current.detailId?.let(snapshot.tickets::get)
                        ?.let { it.workflowStateKey == "cancelled" } ?: (current.detailId != null)
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
                    val commentThreads = current.commentThreads.filterKeys { id ->
                        snapshot.tickets[id]?.let { it.workflowStateKey != "cancelled" } == true
                    }
                    current.copy(
                        tickets = snapshot.tickets,
                        recentlyUpdated = snapshot.recentlyChanged,
                        boardTicketIds = (
                            current.boardTicketIds.filter(snapshot.tickets::containsKey) +
                                changed.filter(current::containsBoardTicket).map { it.id }
                            ).distinct(),
                        planTicketIds = (
                            current.planTicketIds.filter(snapshot.tickets::containsKey) +
                                changed.filter(current::containsPlanTicket).map { it.id }
                            ).distinct(),
                        scheduledTicketIds = (scheduledIds + newlyScheduledIds).distinct(),
                        searchResultIds = searchableIds,
                        commentThreads = commentThreads,
                        history = if (detailUnavailable) emptyList() else current.history,
                        detailStale = current.detailStale && !detailUnavailable,
                        inlineCommentTaskId = current.inlineCommentTaskId?.takeIf { id ->
                            snapshot.tickets[id]?.let { it.workflowStateKey != "cancelled" } == true
                        },
                    ).projectPlan()
                        .let { state -> state.copy(stats = recomputeStats(state.tasks)) }
                }
            }
        }
        ticketScope.launch {
            this@TaskViewModel.ticketSession.commentThreads.collect { threads ->
                _ui.update { state ->
                    state.copy(
                        commentThreads = threads.filterKeys { id ->
                            state.tickets[id]?.let { it.workflowStateKey != "cancelled" } == true
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
    }

    private fun updatePlan(transform: (TaskUiState) -> TaskUiState) {
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
        val board: SprintBoardResponse?,
        val boardTicketIds: List<Long>,
        val planTicketIds: List<Long>,
        val planLoaded: Boolean,
        val planBaseline: TicketPlanBaseline?,
        val planOffset: Int,
        val planHasMore: Boolean,
        val scheduledTicketIds: List<Long>,
        val scheduledLoaded: Boolean,
    )
    private val teamViews = mutableMapOf<Long?, CachedTeamViews>()
    private var sprintJob: Job? = null
    private var backlogJob: Job? = null
    private var scheduledJob: Job? = null
    /** Bumped on every list reload; a page/result from an older generation is discarded. */
    private val backlogGen = java.util.concurrent.atomic.AtomicInteger()
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

    private fun statusDecision(task: Task, stateId: Long): TaskCapabilityDecision {
        val s = _ui.value
        val accepting = if (task.sprintId != null && task.sprintId == s.selectedSprintId) {
            s.sprintBoard?.columns?.firstOrNull { it.stateId == stateId }?.accepting ?: false
        } else true
        return task.dropTargetDecision(stateId, accepting)
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
            (if (changedUser) TaskUiState() else state).copy(
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
        if (agileEnabled) loadEpics()
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
                val hasLoadedList = state.board != null || state.planLoaded || state.scheduledLoaded
                if (state.userId != null && (state.detailId == null || hasLoadedList) &&
                    !state.detailEditing && state.sheet != TaskSheet.CreateTicket
                ) reloadTab()
            }
            TicketSyncSignal.FullReload -> {
                val state = _ui.value
                val hasLoadedList = state.board != null || state.planLoaded || state.scheduledLoaded
                if (hasLoadedList || state.detailId == null || state.tab == TaskTab.ServiceDesk) reloadTab()
                if (state.detail != null && !state.detailEditing) refreshDetail()
            }
            TicketSyncSignal.Reconnect -> {
                val state = _ui.value
                if (state.board != null || state.tab == TaskTab.Sprint) loadSprint()
                if (state.planLoaded) loadBacklog()
                if (state.scheduledLoaded) loadScheduled()
                if (state.detail != null && !state.detailEditing) refreshDetail()
                if (state.tab == TaskTab.ServiceDesk) reloadTab()
                if (state.board == null && !state.planLoaded && !state.scheduledLoaded &&
                    state.detailId == null && state.tab != TaskTab.ServiceDesk
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
                if (state.detailId == change.ticketId) {
                    if (change.staleDetail && state.detailEditing) {
                        _ui.update { it.copy(detailStale = true) }
                    } else {
                        if (change.staleDetail) {
                            ticketSession.setCommentThreadIfAbsent(
                                CommentThread(change.ticketId, change.comments, loading = false),
                            )
                        }
                        ticketScope.launch { refreshHistory(change.ticketId) }
                    }
                }
                if (change.refreshBoard && state.tab == TaskTab.Sprint) loadSprint()
            }
            is TicketSyncChange.Removed -> {
                val wasOpen = _ui.value.detailId == change.ticketId
                _ui.update { state ->
                    if (!wasOpen) state
                    else state.copy(
                        detailId = null,
                        detailLoading = false,
                        detailEditing = false,
                        detailStale = false,
                        history = emptyList(),
                    )
                }
                if (wasOpen && change.reason == RemovalReason.Deleted) notify("This task was deleted")
            }
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
        val wasOpen = _ui.value.detail?.id == id
        ticketSession.store.remove(id, preventReinsert = remote)
        _ui.update { s ->
            s.copy(
                inlineCommentTaskId = s.inlineCommentTaskId?.takeIf { it != id },
                detailId = s.detailId?.takeIf { it != id },
            ).let { if (it.planLoaded) it.projectPlan() else it }
        }
        if (remote && wasOpen) notify("This task was deleted")
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
    fun openSprintPlan() {
        val s = _ui.value
        val targetId = planTargetId(s.teamSprints, s.currentSprintId)
        if (targetId == null) {
            notify(
                if (canManageSprint(s.role)) "No sprint yet — create one in Admin → Teams"
                else "No sprint yet — an admin can create one in Admin → Teams",
            )
            return
        }
        updatePlan { it.copy(planOpen = true, planScope = it.planScope.copy(selectedSprintId = targetId)) }
        if (s.backlog.isEmpty()) loadBacklog()
        loadSprint()
        loadBaseline(targetId)
    }

    fun closeSprintPlan() = _ui.update { it.copy(planOpen = false) }

    /**
     * A board column's "+": the create sheet opens pre-filled with the sprint on
     * screen and that column's workflow state, so the ticket lands on the board
     * rather than in the backlog (Jira board behaviour).
     */
    fun openCreateForColumn(state: WorkflowState) {
        val s = _ui.value
        val sprintId = s.selectedSprintId ?: return
        _ui.update {
            it.copy(
                sheet = TaskSheet.CreateTicket,
                draft = BacklogDraft(
                    sprintId = sprintId,
                    workflowStateId = state.id.takeIf { id -> id != 0L },
                    dueDate = s.sprints.firstOrNull { sp -> sp.id == sprintId }?.endDate?.take(10).orEmpty(),
                ),
            )
        }
    }

    /** The sprint goal is editable inline from the planner. */
    fun saveSprintGoal(sprintId: Long, goal: String) {
        val trimmed = goal.trim()
        io {
            runCatching { repository.updateSprintGoal(sprintId, trimmed) }.fold(
                onSuccess = { updated ->
                    val saved = updated.sprint?.goal ?: trimmed
                    updatePlan { s ->
                        s.copy(planScope = s.planScope.copy(sprints = s.sprints.map { if (it.id == sprintId) it.copy(goal = saved) else it }))
                    }
                },
                onFailure = { fail(failure(it, "Could not save the sprint goal")) },
            )
        }
    }

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
                // The planner belongs to the Backlog; any tab change leaves it.
                planOpen = false,
                planScope = s.planScope.copy(
                    selectedSprintId = if (tab == TaskTab.Sprint) pickSprint(s.selectedSprintId, s.teamSprints, s.currentSprintId) else s.selectedSprintId,
                ),
                searchOpen = false,
            )
        }
        reloadTab()
    }

    fun selectSprint(id: Long) {
        updatePlan { it.copy(planScope = it.planScope.copy(selectedSprintId = id), completing = false, lifecycleError = null, sheet = null, boardTicketIds = emptyList()) }
        loadSprint()
    }

    fun openSheet(sheet: TaskSheet) {
        _ui.update { it.copy(sheet = sheet) }
        if (sheet == TaskSheet.Import && _ui.value.backlog.isEmpty()) loadBacklog()
    }

    fun closeSheet() = _ui.update { it.copy(sheet = null, importTaskId = null) }

    fun setFilters(filters: TaskFilters) {
        updatePlan { it.copy(planScope = it.planScope.copy(filters = filters)).let { state -> if (state.planLoaded) state.projectPlan() else state } }
        if (_ui.value.tab != TaskTab.Backlog || !_ui.value.planLoaded) reloadTab()
    }

    fun clearFilters() {
        updatePlan { it.copy(quickFilters = BacklogQuickFilters(), planScope = it.planScope.copy(selectedEpicId = null)) }
        setFilters(TaskFilters())
    }

    fun setQuickFilters(filters: BacklogQuickFilters) {
        _ui.update { it.copy(quickFilters = filters).let { state -> if (state.planLoaded) state.projectPlan() else state } }
        if (!_ui.value.planLoaded && !_ui.value.backlogLoading) loadBacklog()
    }

    /** Summary priority chip toggles that priority filter ("" = all). */
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

    fun loadSprint() {
        val sprintId = _ui.value.selectedSprintId
        sprintJob?.cancel()
        if (sprintId == null) {
            _ui.update { it.copy(boardTicketIds = emptyList(), board = null, stats = TaskStats(), sprintStats = null, sprintLoading = false) }
            return
        }
        _ui.update { it.copy(sprintLoading = it.tasks.isEmpty()) }
        sprintJob = io {
            val filters = _ui.value.filters
            runCatching { repository.loadSprintBoard(sprintId) }.fold(
                onSuccess = { result ->
                    ticketSession.store.upsert(result.tickets)
                    val tickets = result.tickets.filter {
                        it.workflowStateKey != "cancelled" && matchesFilters(it, filters, _ui.value.userId, includeStatus = true)
                    }
                    _ui.update {
                        if (it.selectedSprintId == sprintId) it.copy(
                            board = result.copy(tickets = emptyList()),
                            boardTicketIds = result.tickets.map { ticket -> ticket.id },
                            stats = recomputeStats(tickets),
                            sprintLoading = false,
                        ) else it
                    }
                },
                onFailure = {
                    val hadData = _ui.value.tasks.isNotEmpty()
                    _ui.update { it.copy(sprintLoading = false) }
                    loadFailed(it, hadData, "Failed to load tasks")
                },
            )
            refreshSprintStats()
        }
    }

    private suspend fun refreshSprintStats() {
        val sprintId = _ui.value.selectedSprintId ?: return
        val stats = runCatching { repository.loadSprintStats(sprintId) }.getOrNull() ?: return
        _ui.update { if (it.selectedSprintId == sprintId) it.copy(sprintStats = stats) else it }
    }

    /**
     * Board move (drag onto a status chip, or the "Move to" sheet). Applied
     * optimistically with an Undo snackbar; a server rejection (e.g. a WIP
     * limit 409) snaps the card back.
     */
    fun moveTask(task: Task, state: WorkflowState) {
        val current = ticketSession.store.state.value.tickets[task.id] ?: task.copy(comments = emptyList())
        if (current.workflowStateKey == state.key || (state.id != 0L && current.workflowStateId == state.id)) return
        val columnAccepting = _ui.value.sprintBoard?.columns?.firstOrNull { it.stateId == state.id }?.accepting ?: false
        val decision = current.dropTargetDecision(state.id, columnAccepting)
        if (!decision.allowed) return notify(decision.reasonCopy())
        val previous = current
        val moved = previous.copy(workflowStateKey = state.key, workflowStateId = state.id.takeIf { it != 0L } ?: previous.workflowStateId)
        val optimistic = applyTask(moved)
        io {
            runCatching { repository.updateStatus(task.id, state.key) }.fold(
                onSuccess = { saved ->
                    patchServerTask(saved)
                    loadSprint()
                    notify("Moved to ${state.name.ifEmpty { state.key }}", "Undo") { revertMove(previous, state) }
                },
                onFailure = { e ->
                    if (ticketSession.store.restoreIfCurrent(optimistic, previous)) {
                        patchTask(previous, highlight = false, writeToStore = false)
                    }
                    fail(failure(e, "Failed to move item"))
                },
            )
        }
    }

    private fun revertMove(previous: Task, from: WorkflowState) {
        val current = _ui.value.tasks.firstOrNull { it.id == previous.id } ?: previous
        applyTask(previous)
        io {
            runCatching { repository.updateStatus(previous.id, previous.workflowStateKey.orEmpty()) }.fold(
                onSuccess = { saved -> patchServerTask(saved); loadSprint() },
                onFailure = { e ->
                    applyTask(current.copy(workflowStateKey = from.key))
                    fail(failure(e, "Failed to undo move"))
                },
            )
        }
    }

    private fun patchServerTask(saved: Task) {
        patchTask(saved, highlight = false)
    }

    private fun applyTask(task: Task) = patchTask(task, highlight = false)

    fun configureImport(task: Task) = _ui.update { s ->
        s.copy(
            importTaskId = task.id,
            importAssignedTo = task.assignedTo,
            importDueDate = s.currentSprint?.endDate?.take(10) ?: task.dueDate?.take(10).orEmpty(),
        )
    }

    fun cancelImportConfig() = _ui.update { it.copy(importTaskId = null, importAssignedTo = null, importDueDate = "") }

    fun setImportAssignee(id: Long?) = _ui.update { it.copy(importAssignedTo = id) }

    fun setImportDueDate(date: String) = _ui.update { it.copy(importDueDate = date) }

    /** `handleImportToSprint`: assign, then patch assignee/due date when set. */
    fun confirmImport() {
        val s = _ui.value
        val taskId = s.importTaskId ?: return
        val sprintId = s.selectedSprintId ?: return
        io {
            runCatching {
                patchTask(repository.assignSprint(taskId, sprintId), highlight = true)
                if (s.importAssignedTo != null || s.importDueDate.isNotEmpty()) {
                    patchServerTask(repository.updateImportFields(taskId, ImportUpdatePayload(s.importAssignedTo, s.importDueDate.ifEmpty { null })))
                }
            }.fold(
                onSuccess = {
                    _ui.update { st ->
                        st.copy(importTaskId = null, importAssignedTo = null, importDueDate = "")
                    }
                    notify("Added to ${s.currentSprint?.name ?: "sprint"}")
                    loadSprint()
                },
                onFailure = { fail("Failed to import task to sprint") },
            )
        }
    }

    // ── Sprint lifecycle (`SprintLifecycleControls`) ─────────────────────

    private fun lifecycle(fallback: String, call: suspend (Long) -> Unit, done: String) {
        val sprint = _ui.value.currentSprint ?: return
        if (_ui.value.lifecycleBusy) return
        _ui.update { it.copy(lifecycleBusy = true, lifecycleError = null) }
        io {
            runCatching { call(sprint.id) }.fold(
                onSuccess = {
                    _ui.update { it.copy(completing = false) }
                    notify(done)
                    lifecycleChanged()
                },
                onFailure = { e -> _ui.update { it.copy(lifecycleError = failure(e, fallback)) } },
            )
            _ui.update { it.copy(lifecycleBusy = false) }
        }
    }

    fun startSprint() = lifecycle("Failed to start sprint", { repository.startSprint(it) }, "Sprint started")

    fun pauseSprint() = lifecycle("Failed to pause sprint", { repository.pauseSprint(it) }, "Sprint paused")

    fun resumeSprint() = lifecycle("Failed to resume sprint", { repository.resumeSprint(it) }, "Sprint resumed")

    fun beginComplete() {
        val sprint = _ui.value.currentSprint ?: return
        _ui.update { it.copy(completing = true, rolloverTo = "backlog") }
        io {
            val options = runCatching {
                repository.loadTeamSprints(sprint.teamId.takeIf { showsTeamPicker(_ui.value.role) }).sprints
            }.getOrDefault(emptyList())
                .filter { it.id != sprint.id && it.status != "completed" }
            _ui.update { it.copy(rolloverOptions = options) }
            // The pre-flight summary is what makes the dialog honest (D32).
            runCatching { repository.loadCompletionPreview(sprint.id) }
                .onSuccess { preview -> _ui.update { it.copy(completionPreview = preview) } }
        }
    }

    fun cancelComplete() = _ui.update { it.copy(completing = false, completionPreview = null) }

    fun setRollover(value: String) = _ui.update { it.copy(rolloverTo = value) }

    fun completeSprint() {
        val rollover = _ui.value.rolloverTo
        lifecycle("Failed to complete sprint", { repository.completeSprint(it, rollover) }, "Sprint completed")
    }

    /** `onChanged`: refresh the sprint list (badge/controls), tasks and stats. */
    private suspend fun lifecycleChanged() {
        val sprints = runCatching(repository::loadAvailableSprints).getOrNull()
        val sprintList = runCatching {
            val current = _ui.value
            repository.loadTeamSprints(current.selectedTeamId.takeIf { showsTeamPicker(current.role) })
        }.getOrNull()
        if (sprints != null || sprintList != null) {
            updatePlan { s ->
                val allSprints = sprints ?: s.sprints
                val currentSprintId = sprintList?.currentSprintId ?: s.currentSprintId.takeIf { sprintList == null }
                val currentSprintRow = if (sprintList != null) {
                    sprintList.sprints.firstOrNull { it.id == currentSprintId }
                } else {
                    s.currentSprintRow
                }
                val teamSprints = sprintsForTeam(allSprints, s.selectedTeamId, showsTeamPicker(s.role))
                    .let { rows ->
                        currentSprintRow?.takeIf { it.id == currentSprintId && rows.none { row -> row.id == it.id } }
                            ?.let(rows::plus) ?: rows
                    }
                s.copy(
                    planScope = s.planScope.copy(
                        sprints = allSprints,
                        currentSprintId = currentSprintId,
                        currentSprintRow = currentSprintRow,
                        selectedSprintId = pickSprint(s.selectedSprintId, teamSprints, currentSprintId),
                    ),
                    tab = normalizeTab(s.tab, planningTabs(s.agileEnabled, teamSprints, currentSprintId)),
                )
            }
        }
        reloadTab()
    }

    // ── Backlog (`useBacklog`, infinite scroll) ──────────────────────────

    /** Keep unfiltered pages so Plan-only filters retain their existing meaning. */
    fun loadBacklog() {
        backlogJob?.cancel()
        _ui.update { it.copy(backlogLoading = it.backlog.isEmpty(), backlogLoadingMore = false) }
        val gen = backlogGen.incrementAndGet()
        backlogJob = io {
            val s = _ui.value
            val revision = ticketSession.planRevision.get()
            runCatching { repository.loadPlanPage(500, 0, s.teamScope) }.fold(
                onSuccess = { page ->
                    if (gen != backlogGen.get()) return@fold
                    if (s.userId != _ui.value.userId || s.teamScope != _ui.value.teamScope) return@fold
                    if (revision != ticketSession.planRevision.get()) {
                        loadBacklog()
                        return@fold
                    }
                    ticketSession.store.upsert(page.tasks)
                    _ui.update {
                        val next = it.copy(
                            tickets = ticketSession.store.state.value.tickets,
                            planTicketIds = page.tasks.map { ticket -> ticket.id },
                            planLoaded = true,
                            backlogLoading = false,
                            planOffset = page.tasks.size,
                            planHasMore = page.pagination?.let { paging ->
                                paging.hasMore || page.tasks.size < paging.total
                            } == true && page.tasks.isNotEmpty(),
                        )
                        next.copy(planBaseline = next.pageBaseline(page)).projectPlan()
                    }
                    loadMoreBacklog()
                },
                onFailure = {
                    if (gen != backlogGen.get()) return@fold
                    val hadData = _ui.value.backlog.isNotEmpty()
                    _ui.update { it.copy(backlogLoading = false) }
                    loadFailed(it, hadData, "Failed to load backlog")
                },
            )
        }
    }

    fun loadMoreBacklog() {
        val s = _ui.value
        if (!s.planLoaded) {
            if (!s.backlogLoading) loadBacklog()
            return
        }
        if (!s.planHasMore || s.backlogLoading || s.backlogLoadingMore) return
        _ui.update { it.copy(backlogLoadingMore = true) }
        val gen = backlogGen.get()
        val revision = ticketSession.planRevision.get()
        backlogJob = io {
            runCatching { repository.loadPlanPage(500, s.planOffset, s.teamScope) }.fold(
                onSuccess = { page ->
                    if (gen != backlogGen.get() || s.teamScope != _ui.value.teamScope) return@fold
                    if (revision != ticketSession.planRevision.get()) {
                        loadBacklog()
                        return@fold
                    }
                    ticketSession.store.upsert(page.tasks)
                    _ui.update { current ->
                        val offset = s.planOffset + page.tasks.size
                        val next = current.copy(
                            tickets = ticketSession.store.state.value.tickets,
                            planTicketIds = (current.planTicketIds + page.tasks.map { it.id }).distinct(),
                            planOffset = offset,
                            planHasMore = page.pagination?.let { it.hasMore || offset < it.total } == true &&
                                page.tasks.isNotEmpty(),
                            backlogLoadingMore = false,
                        )
                        next.copy(planBaseline = next.pageBaseline(page)).projectPlan()
                    }
                    loadMoreBacklog()
                },
                onFailure = {
                    if (gen != backlogGen.get()) return@fold
                    _ui.update { it.copy(backlogLoadingMore = false) }
                    loadFailed(it, true, "Failed to load backlog")
                },
            )
        }
    }

    fun setSort(sort: BacklogSort) = _ui.update { it.copy(backlogSort = sort) }

    fun updateDraft(transform: (BacklogDraft) -> BacklogDraft) = _ui.update { it.copy(draft = transform(it.draft)) }

    /** Sprint pick fills the due date with the sprint end (SprintSelector `onChange`). */
    fun setDraftSprint(id: Long?) = _ui.update { s ->
        val end = id?.let { wanted -> s.sprints.firstOrNull { it.id == wanted }?.endDate?.take(10) }
        s.copy(draft = s.draft.copy(sprintId = id, dueDate = if (id == null) "" else end ?: s.draft.dueDate))
    }

    fun submitBacklog() {
        val s = _ui.value
        val draft = s.draft
        if (draft.title.isBlank() || s.creating) return
        _ui.update { it.copy(creating = true) }
        io {
            val payload = CreateBacklogPayload(
                title = draft.title.trim(),
                description = plainTextToHtml(draft.description),
                priority = draft.priority,
                // The team the page is showing, so an admin browsing another
                // team creates the ticket there (web `scopeParams.team_id`).
                // Without it the server falls back to the creator's own team,
                // and the ticket is invisible in the filtered list.
                teamId = s.selectedTeamId?.takeIf { it > 0 },
                assignedTo = draft.assignedTo,
                dueDate = draft.dueDate.ifEmpty { null },
                labelIds = draft.labels.ifEmpty { null },
                sprintId = draft.sprintId,
                storyPoints = draft.storyPoints,
                workItemTypeId = draft.workItemTypeId,
                projectId = draft.projectId,
                workflowStateId = draft.workflowStateId,
            )
            runCatching { repository.createBacklogTask(payload) }.fold(
                onSuccess = { created ->
                    patchServerTask(created)
                    _ui.update { it.copy(draft = BacklogDraft(), sheet = null, creating = false) }
                    notify("Created ${created.displayKey}", "Open") { openDetail(created) }
                    loadBacklog()
                    if (draft.sprintId != null && _ui.value.tab == TaskTab.Sprint) loadSprint()
                },
                onFailure = { e ->
                    _ui.update { it.copy(creating = false) }
                    fail(failure(e, "Failed to create backlog item"))
                },
            )
        }
    }

    /**
     * `handleScheduleTask` (`PATCH /tasks/:id/schedule`); the picker is the
     * confirmation. Also reschedules an already-dated task. The snackbar's
     * "View" jumps to that day in the Scheduled tab.
     */
    fun schedule(taskId: Long, date: String, closeAfter: Boolean = false) {
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
                    if (closeAfter) withContext(Dispatchers.Main) { closeDetail() }
                },
                onFailure = { e -> fail(failure(e, "Failed to schedule task")) },
            )
        }
    }

    /** `handleUnscheduleTask`; the confirm dialog is skipped for the Scheduled list's swipe (it has Undo). */
    fun unschedule(taskId: Long, title: String?, closeAfter: Boolean = false, ask: Boolean = true) {
        val decision = visibleTask(taskId)?.capability(TaskCapability.Schedule) ?: TaskCapabilityDecision()
        if (!decision.allowed) return notify(decision.reasonCopy())
        val previousDate = (_ui.value.scheduled + _ui.value.tasks + _ui.value.planTasks).firstOrNull { it.id == taskId }?.plannedDate ?: _ui.value.detail?.takeIf { it.id == taskId }?.plannedDate
        val run = {
            io {
                runCatching { repository.unscheduleTask(taskId) }.fold(
                    onSuccess = { saved ->
                        patchTask(saved, highlight = true)
                        val back = previousDate?.take(10)
                        if (back != null) notify("Moved to backlog", "Undo") { schedule(taskId, back) } else notify("Moved to backlog")
                        if (closeAfter) withContext(Dispatchers.Main) { closeDetail() }
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

    // ── Detail (`useTaskDetail`) ─────────────────────────────────────────

    fun openDetail(task: Task) {
        if (task.id !in ticketSession.store.state.value.tickets) ticketSession.store.upsert(task)
        openTicketDetail(task.id)
    }

    private fun openTicketDetail(id: Long) {
        val threads = ticketSession.commentThreads.value
        _ui.update {
            it.copy(
                detailId = id, detailLoading = true, commentThreads = threads, detailTab = DetailTab.Comments,
                history = emptyList(), detailEditing = false, detailStale = false, searchOpen = false,
            )
        }
        io {
            runCatching { repository.loadDetail(id) }.fold(
                onSuccess = { data ->
                    ticketSession.store.upsert(data)
                    ticketSession.setCommentThreadIfAbsent(CommentThread(id, data.comments, loading = false))
                },
                onFailure = {
                    if (ticketSession.commentThreads.value[id] == null) {
                        val loaded = runCatching { repository.loadComments(id) }.getOrDefault(emptyList())
                        ticketSession.setCommentThreadIfAbsent(CommentThread(id, loaded, loading = false))
                    }
                },
            )
            _ui.update { it.copy(detailLoading = false) }
            refreshHistory(id)
            // The timer state belongs to the open task, so it loads with it.
            loadTimer(id)
        }
    }

    /** Replaces the open detail (Epic ↔ child links) or opens a `?task=<id>` deep link. */
    fun openDetailById(id: Long) = openTicketDetail(id)

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

    private suspend fun refreshHistory(taskId: Long) {
        val history = runCatching { repository.loadHistory(taskId) }.getOrNull() ?: return
        _ui.update { if (it.detail?.id == taskId) it.copy(history = history) else it }
    }

    fun closeDetail() = _ui.update {
        it.copy(detailId = null, detailEditing = false, history = emptyList(), detailTab = DetailTab.Comments, detailStale = false)
    }

    fun setDetailTab(tab: DetailTab) = _ui.update { it.copy(detailTab = tab) }

    fun startEdit() {
        val decision = _ui.value.detail?.capability(TaskCapability.Edit) ?: TaskCapabilityDecision()
        if (!decision.allowed) return notify(decision.reasonCopy())
        _ui.update { it.copy(detailEditing = true) }
    }

    fun cancelEdit() {
        val stale = _ui.value.detailStale
        _ui.update { it.copy(detailEditing = false) }
        if (stale) refreshDetail()
    }

    /** Re-fetch the open detail after a panel change (blocker, parent…) and patch the lists. */
    fun refreshDetail() {
        val id = _ui.value.detail?.id ?: return
        _ui.update { it.copy(detailStale = false) }
        io {
            runCatching { repository.loadDetail(id) }.onSuccess { data ->
                ticketSession.setCommentThreadIfAbsent(CommentThread(id, data.comments, loading = false))
                patchTask(data, highlight = false)
            }
            refreshHistory(id)
        }
    }

    /** Title + description editor (`saveDetailEdit`). */
    fun saveEdit(draft: TaskEditDraft) {
        val task = _ui.value.detail ?: return
        if (draft.title.isBlank()) return
        // Untouched text keeps the original rich HTML instead of flattening it.
        val description = if (draft.description == stripHtml(draft.descriptionHtml)) draft.descriptionHtml else plainTextToHtml(draft.description)
        updateFields(task, buildJsonObject {
            put("title", draft.title.trim())
            put("description", description)
        }, "Saved") { _ui.update { it.copy(detailEditing = false) } }
    }

    /**
     * Inline property edit from the detail page: `PUT /tasks/:id` with just
     * the changed keys, then a detail re-fetch so the enriched fields
     * (assignee, labels, sprint, issue key) stay authoritative.
     */
    fun updateFields(task: Task, fields: JsonObject, done: String? = null, onSaved: () -> Unit = {}) {
        val decision = task.capability(TaskCapability.Edit)
        if (!decision.allowed) return notify(decision.reasonCopy())
        io {
            runCatching { repository.patchFields(task.id, fields) }.fold(
                onSuccess = { saved ->
                    patchServerTask(saved)
                    withContext(Dispatchers.Main) { onSaved() }
                    done?.let { notify(it) }
                    refreshHistory(task.id)
                    if (_ui.value.tab == TaskTab.Sprint) refreshSprintStats()
                },
                onFailure = { e -> fail(failure(e, "Failed to update item")) },
            )
        }
    }

    fun setPriority(task: Task, value: String) = updateFields(task, buildJsonObject { put("priority", value) })

    fun setAssignee(task: Task, userId: Long?) = updateFields(task, buildJsonObject { put("assigned_to", userId?.let(::JsonPrimitive) ?: JsonNull) })

    fun setDueDate(task: Task, date: String) = updateFields(task, buildJsonObject { put("due_date", date.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull) })

    fun setLabels(task: Task, ids: List<Long>) = updateFields(task, buildJsonObject { put("label_ids", JsonArray(ids.map(::JsonPrimitive))) })

    fun setStoryPoints(task: Task, value: String?) = updateFields(task, buildJsonObject { put("story_points", value?.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull) })

    fun setWorkItemType(task: Task, id: Long?) = updateFields(task, buildJsonObject { put("work_item_type_id", id?.let(::JsonPrimitive) ?: JsonNull) })

    /** One-way: an issue key is permanent once a project is set. */
    fun setProject(task: Task, id: Long) = updateFields(task, buildJsonObject { put("project_id", id) })

    /** Detail status chip (`handleDetailStatusChange`), workflow-state aware. */
    fun setStatus(task: Task, state: WorkflowState) {
        if (task.workflowStateKey == state.key) return
        val decision = statusDecision(task, state.id)
        if (!decision.allowed) return notify(decision.reasonCopy())
        io {
            runCatching { repository.updateStatus(task.id, state.key) }.fold(
                onSuccess = { saved ->
                    patchServerTask(saved)
                    notify("Status: ${state.name.ifEmpty { state.key }}")
                    refreshHistory(task.id)
                    if (_ui.value.tab == TaskTab.Sprint) loadSprint()
                },
                onFailure = { e -> fail(failure(e, "Failed to update status")) },
            )
        }
    }

    // ── Comments (detail + inline panel) ─────────────────────────────────

    fun openComments(taskId: Long) {
        val threads = ticketSession.commentThreads.value
        val cached = threads[taskId]
        _ui.update { it.copy(inlineCommentTaskId = taskId, commentThreads = threads) }
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
    fun addComment(taskId: Long, text: String, file: CommentFile? = null, mentions: Map<String, Long> = emptyMap(), onDone: () -> Unit = {}) {
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
                    if (_ui.value.detail?.id == taskId) refreshHistory(taskId)
                },
                onFailure = { fail("Failed to add comment") },
            )
        }
    }

    fun editComment(taskId: Long, commentId: Long, text: String, onDone: () -> Unit = {}) {
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
                    if (_ui.value.detail?.id == taskId) refreshHistory(taskId)
                },
                onFailure = { fail("Failed to update comment") },
            )
        }
    }

    fun deleteComment(taskId: Long, commentId: Long) {
        confirm("Delete Comment", "Are you sure you want to delete this comment? This cannot be undone.", "Delete", danger = true) {
            io {
                runCatching { repository.deleteComment(taskId, commentId) }.fold(
                    onSuccess = {
                        updateComments(taskId) { list -> list.filterNot { it.id == commentId } }
                        bumpCommentCount(taskId, -1)
                        if (_ui.value.detail?.id == taskId) refreshHistory(taskId)
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
                state.board, state.boardTicketIds, state.planTicketIds, state.planLoaded,
                state.planBaseline, state.planOffset, state.planHasMore,
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
                planOpen = false,
                planLoaded = cached?.planLoaded ?: false,
                planTicketIds = cached?.planTicketIds.orEmpty(),
                planBaseline = cached?.planBaseline,
                planOffset = cached?.planOffset ?: 0,
                planHasMore = cached?.planHasMore ?: false,
                board = cached?.board,
                boardTicketIds = cached?.boardTicketIds.orEmpty(),
                selectedIds = emptySet(),
                scheduledTicketIds = cached?.scheduledTicketIds.orEmpty(),
                scheduledLoaded = cached?.scheduledLoaded ?: false,
                tab = if (currentSprintId != null) TaskTab.Sprint else normalizeTab(state.tab, tabs),
            ).let { next ->
                next.copy(
                    stats = recomputeStats(next.tasks),
                )
            }
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
        _ui.update { it.copy(preferences = it.preferences.copy(showCompleted = show), completedOpen = show) }
        io {
            runCatching {
                repository.savePreferences(
                    kotlinx.serialization.json.buildJsonObject {
                        put("show_completed", kotlinx.serialization.json.JsonPrimitive(show))
                    },
                )
            }.onSuccess { prefs -> _ui.update { it.copy(preferences = prefs) } }
        }
        if (show) loadCompleted()
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

    fun toggleCompletedSection() {
        if (_ui.value.hasSelection) return
        val open = !_ui.value.completedOpen
        _ui.update { it.copy(completedOpen = open) }
        if (open && _ui.value.completed.isEmpty()) loadCompleted()
    }

    /** Plan already holds completed work from every drained page and available sprint. */
    fun loadCompleted(all: Boolean = false) {
        _ui.update { it.copy(completedShowingAll = all).let { state -> if (state.planLoaded) state.projectPlan() else state } }
        if (!_ui.value.planLoaded && !_ui.value.backlogLoading) loadBacklog()
    }

    // ── Bulk actions ─────────────────────────────────────────────────────

    fun toggleSelection(id: Long) {
        _ui.update { s ->
            s.copy(selectedIds = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id)
        }
    }

    fun clearSelection() = _ui.update { it.copy(selectedIds = emptySet()) }

    /** One request for a multi-select action, with an Undo affordance. */
    fun bulkAction(action: String, value: String? = null) {
        val ids = _ui.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val denied = ids.asSequence().map { id ->
            val task = visibleTask(id) ?: return@map TaskCapabilityDecision()
            when (action) {
                "delete" -> task.capability(TaskCapability.Cancel)
                "move_to_sprint" -> task.capability(TaskCapability.PlaceInSprint)
                "assign" -> task.capability(TaskCapability.Edit)
                "status" -> {
                    val target = _ui.value.agile.workflowStates.firstOrNull { it.key == value }
                    target?.let { statusDecision(task, it.id) } ?: TaskCapabilityDecision()
                }
                else -> TaskCapabilityDecision()
            }
        }.firstOrNull { !it.allowed }
        if (denied != null) return notify(denied.reasonCopy())
        io {
            runCatching { repository.bulkAction(BulkPayload(ids, action, value)) }.fold(
                onSuccess = { result ->
                    clearSelection()
                        notify(bulkSummary(result))
                    reloadTab()
                },
                onFailure = { fail(failure(it, "Bulk action failed")) },
            )
        }
    }

        /** The result line: what changed, plus the first blocked ticket's reason.
         *
         *  The server names the remedy in `error` (`INVALID_TRANSITION`,
         *  `WIP_EXCEEDED`, `SPRINT_TEAM_MISMATCH`), so it is shown verbatim rather
         *  than reduced to a count. */
        private fun bulkSummary(result: BulkResult): String {
            if (result.failed == 0) return "${result.changed} updated"
            val first = result.failures.firstOrNull()?.error?.let { capabilityFailureCopy(it) ?: it }
            return if (first.isNullOrBlank()) {
                "${result.changed} updated, ${result.failed} skipped"
            } else {
                "${result.changed} updated, ${result.failed} skipped — $first"
            }
        }

    /** Cancelling a batch is destructive, so it asks first. */
    fun confirmBulkDelete() {
        val count = _ui.value.selectedIds.size
        if (count == 0) return
        confirm(
            "Cancel $count ${if (count == 1) "ticket" else "tickets"}?",
            "They keep their history and leave the open work.",
            "Cancel tickets",
            danger = true,
        ) { bulkAction("delete") }
    }

    // ── Ranked reorder ───────────────────────────────────────────────────

        /** Move a backlog row between two neighbours; the server computes the rank. */
        fun reorder(taskId: Long, beforeId: Long?, afterId: Long?) {
            val decision = visibleTask(taskId)?.capability(TaskCapability.Edit) ?: TaskCapabilityDecision()
            if (!decision.allowed) return notify(decision.reasonCopy())
            io {
                runCatching { repository.rankTask(taskId, beforeId, afterId) }.fold(
                    onSuccess = { ranked -> patchTask(ranked, highlight = false) },
                    onFailure = { fail(failure(it, "Failed to reorder")) },
                )
            }
        }

    // ── Epics ────────────────────────────────────────────────────────────

    fun loadEpics() {
        io {
            runCatching { repository.loadEpics() }.onSuccess { list ->
                _ui.update { it.copy(epics = list) }
            }
        }
    }

    fun selectEpic(id: Long?) = updatePlan { it.copy(planScope = it.planScope.copy(selectedEpicId = id)).let { state -> if (state.planLoaded) state.projectPlan() else state } }

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

    // ── Sprint pre-flight and baseline ───────────────────────────────────

    /** The carryover pre-flight shown before a sprint is completed. */
    fun loadCompletionPreview(sprintId: Long) {
        io {
            runCatching { repository.loadCompletionPreview(sprintId) }.onSuccess { preview ->
                _ui.update { it.copy(completionPreview = preview) }
            }
        }
    }

    fun loadBaseline(sprintId: Long) {
        io {
            runCatching { repository.loadBaseline(sprintId) }.onSuccess { baseline ->
                _ui.update { it.copy(baseline = baseline) }
            }
        }
    }

    // ── Time tracking ────────────────────────────────────────────────────

    /** Is a timer running for the open task? */
    fun loadTimer(taskId: Long) {
        io {
            runCatching { repository.loadTimer(taskId) }.onSuccess { state ->
                _ui.update { it.copy(timerRunning = state.running) }
            }
        }
    }

    /** Start a timer attributed to this task; the elapsed time rolls into the timesheet. */
    fun startTimer(taskId: Long) {
        _ui.update { it.copy(timerBusy = true) }
        io {
            runCatching { repository.startTimer(taskId) }.fold(
                onSuccess = { state ->
                    _ui.update { it.copy(timerRunning = state.running, timerBusy = false) }
                },
                onFailure = {
                    _ui.update { it.copy(timerBusy = false) }
                    fail(failure(it, "Could not start the timer"))
                },
            )
        }
    }

    /** Stop the timer and add the elapsed minutes to the task. */
    fun stopTimer(taskId: Long) {
        _ui.update { it.copy(timerBusy = true) }
        io {
            runCatching { repository.stopTimer(taskId) }.fold(
                onSuccess = { stop ->
                    ticketSession.store.state.value.tickets[taskId]?.let {
                        ticketSession.store.upsert(it.copy(loggedMinutes = it.loggedMinutes + stop.minutes))
                    }
                    _ui.update { s ->
                        s.copy(
                            timerRunning = false,
                            timerBusy = false,
                        )
                    }
                    notify("Logged ${stop.minutes}m")
                },
                onFailure = {
                    _ui.update { it.copy(timerBusy = false) }
                    fail(failure(it, "Could not stop the timer"))
                },
            )
        }
    }

    // ── Canonical key lookup ─────────────────────────────────────────────

    /** Resolve a `PROJ-123` reference to its task and open it. */
    fun openByKey(key: String) {
        io {
            runCatching { repository.loadByKey(key) }.fold(
                onSuccess = { task ->
                    ticketSession.store.upsert(task)
                    ticketScope.launch { openDetail(task) }
                },
                onFailure = { fail(failure(it, "No task matches $key")) },
            )
        }
    }

    companion object {
        private const val ATTRIBUTION_MS = 4_000L

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
