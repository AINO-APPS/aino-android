package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.isTransientFailure
import app.aino.mobile.core.network.userFacingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Comments shown either in the detail page or the card's inline comment sheet. */
data class CommentThread(val taskId: Long, val items: List<TaskComment> = emptyList(), val loading: Boolean = true)

/** One-shot snackbar; [action] runs when its button is tapped (e.g. Undo). */
data class TaskNotice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Shared bottom sheets on the Tasks page. */
enum class TaskSheet { Filters, CreateTicket, Import, SprintPicker }

data class TaskUiState(
    // Session context (bound from the shell).
    val userId: Long? = null,
    val role: String = "employee",
    val teamName: String? = null,
    val agileEnabled: Boolean = false,
    val customFieldsEnabled: Boolean = false,
    // Metadata.
    val labels: List<TaskLabel> = emptyList(),
    val assignableUsers: List<AssignableUser> = emptyList(),
    val projects: List<ProjectOption> = emptyList(),
    val sprints: List<AvailableSprint> = emptyList(),
    val agile: AgileConfig = AgileConfig(),
    // Page.
    val tab: TaskTab = TaskTab.Backlog,
    /** Sprint tab shows the board or the split planner (D21). */
    val sprintView: SprintView = SprintView.Board,
    val selectedSprintId: Long? = null,
    val filters: TaskFilters = TaskFilters(),
    val sheet: TaskSheet? = null,
    val refreshing: Boolean = false,
    /** Ids patched by a realtime event a moment ago (cards flash briefly). */
    val recentlyUpdated: Set<Long> = emptySet(),
    /** The open detail changed elsewhere while the user was editing it. */
    val detailStale: Boolean = false,
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
    // Global search.
    val searchQuery: String = "",
    val searchResults: List<Task> = emptyList(),
    val searching: Boolean = false,
    val searchOpen: Boolean = false,
    // Sprint board.
    val tasks: List<Task> = emptyList(),
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
    /** Complete, unfiltered Plan snapshot, including sprint and completed work. */
    val planTasks: List<Task> = emptyList(),
    val planLoaded: Boolean = false,
    val quickFilters: BacklogQuickFilters = BacklogQuickFilters(),
    val backlog: List<Task> = emptyList(),
    val backlogSummary: BacklogSummary = BacklogSummary(),
    val backlogTotal: Int = 0,
    val backlogPageSize: Int = 25,
    val backlogSort: BacklogSort = BacklogSort.Priority,
    val backlogLoading: Boolean = false,
    val backlogLoadingMore: Boolean = false,
    val draft: BacklogDraft = BacklogDraft(),
    val creating: Boolean = false,
    // Scheduled (planner-dated tasks: overdue + the next weeks).
    val scheduled: List<Task> = emptyList(),
    val scheduledLoading: Boolean = false,
    val scheduledLoaded: Boolean = false,
    /** Day to bring into view after "View" on the schedule snackbar or a day-picker jump. */
    val scheduledFocus: String? = null,
    // Detail.
    val detail: Task? = null,
    val detailLoading: Boolean = false,
    val detailEditing: Boolean = false,
    val detailTab: DetailTab = DetailTab.Comments,
    val history: List<TaskHistoryEntry> = emptyList(),
    val detailComments: List<TaskComment> = emptyList(),
    // Inline comment panel (the card's 💬).
    val inlineComments: CommentThread? = null,
    /** Bumped on refresh while the Service Desk tab is visible; the tab reloads on change. */
    val serviceDeskVersion: Int = 0,
    // ── Next-generation surfaces ─────────────────────────────────────────
    /** Per-user Tasks preferences, shared with web so both clients agree. */
    val preferences: TaskPreferences = TaskPreferences(),
    /** User expansion choice is preserved while selection temporarily collapses it. */
    val completedOpen: Boolean = true,
    val completed: List<Task> = emptyList(),
    val completedCount: Int = 0,
    val completedLoading: Boolean = false,
    /** True once "View all completed" has dropped the recent-window filter. */
    val completedShowingAll: Boolean = false,
    /** Multi-select for bulk actions. */
    val selectedIds: Set<Long> = emptySet(),
    /** Epics with their rollups, for the epic filter. */
    val epics: List<EpicSummary> = emptyList(),
    val selectedEpicId: Long? = null,
    /** Read-only background context on the schedule. */
    val overlays: List<ScheduleOverlay> = emptyList(),
    /** Which overlay types are currently shown; each toggles independently. */
    val overlayTypes: Set<String> = setOf("leave", "holiday", "meeting"),
    /** The carryover pre-flight shown before a sprint is completed. */
    val completionPreview: CompletionPreview? = null,
    /** Committed points and the scope delta for the selected sprint. */
    val baseline: SprintBaseline? = null,
    /** Time tracking for the open detail. */
    val timerRunning: Boolean = false,
    val timerBusy: Boolean = false,
    /** "Updated by <name>" flashes keyed by task id, from realtime deltas. */
    val attribution: Map<Long, String> = emptyMap(),
) {
    val currentSprint: AvailableSprint? get() = sprints.firstOrNull { it.id == selectedSprintId }
    val sortedBacklog: List<Task> get() = sortBacklog(backlog, backlogSort)
    /** Display sections for the backlog list, or `null` for a flat list. */
    val backlogGroups: List<BacklogGroup>? get() = groupPlanBacklog(sortedBacklog, sprints)
    val sprintTabVisible: Boolean get() = agileEnabled && sprints.isNotEmpty()
    val visibleTabs: List<TaskTab> get() = TaskTab.entries.filter { it != TaskTab.Sprint || sprintTabVisible }
    val filterCount: Int get() = filterCount(filters, tab) + if (tab == TaskTab.Backlog) quickFilters.count + (if (selectedEpicId != null) 1 else 0) else 0
    /** Sprint planning is not narrowed by Backlog-only quick chips. */
    val planningBacklog: List<Task> get() = (if (planLoaded) planTasks else backlog).filter {
        it.isBacklogItem && !it.isCompleted && matchesFilters(it, filters, userId, includeStatus = false) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val importable: List<Task> get() = planningBacklog
    val backlogHasMore: Boolean get() = backlog.size < backlogTotal
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

/** One projection for loading, quick/advanced filters, mutations and realtime. */
internal fun TaskUiState.projectPlan(): TaskUiState {
    val filtered = planTasks.filter {
        (it.isBacklogItem || sprints.any { sprint -> sprint.id == it.sprintId }) &&
        matchesPlanFilters(it, filters, quickFilters, userId) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val open = filtered.filterNot { it.isCompleted }
    val done = filtered.filter { it.isCompleted }
    return copy(
        backlog = open,
        backlogTotal = open.size,
        backlogSummary = BacklogSummary(open.size, open.groupingBy { it.workflowStateKey.orEmpty() }.eachCount(), open.groupingBy { it.priority }.eachCount()),
        completed = done,
        completedCount = done.size,
        selectedIds = selectedIds.intersect(open.map { it.id }.toSet()),
    )
}

private fun TaskUiState.patchPlan(task: Task): TaskUiState =
    if (!planLoaded) this else copy(
        planTasks = upsertTask(planTasks, task, task.isBacklogItem || sprints.any { it.id == task.sprintId }),
    ).projectPlan()

/** The Scheduled view's fetch window, relative to today. */
internal fun scheduledWindow(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> =
    today.minusDays(SCHEDULED_PAST_DAYS) to today.plusDays(SCHEDULED_AHEAD_DAYS)

class TaskViewModel(
    private val repository: TaskRepository,
    internal val serviceDesk: ServiceDeskRepository? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(TaskUiState())
    val ui: StateFlow<TaskUiState> = _ui.asStateFlow()

    /** Snackbar messages (mutation results, undo, realtime hints). */
    private val _notices = Channel<TaskNotice>(Channel.BUFFERED)
    val notices: Flow<TaskNotice> = _notices.receiveAsFlow()

    private var errorJob: Job? = null
    private var searchJob: Job? = null
    private var carriedOn: String? = null
    /** The sprint landing has been applied once; a later tab choice is honoured. */
    private var landingApplied = false
    private var sprintJob: Job? = null
    private var backlogJob: Job? = null
    private var scheduledJob: Job? = null
    private var syncJob: Job? = null
    private val syncMutex = Mutex()
    /** Bumped on every list reload; a page/result from an older generation is discarded. */
    private val backlogGen = java.util.concurrent.atomic.AtomicInteger()
    private val scheduledGen = java.util.concurrent.atomic.AtomicInteger()
    private val pendingEvents = mutableListOf<TaskEvent>()
    private var pendingReload = false
    private val highlightJobs = mutableMapOf<Long, Job>()

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) { block() }

    private fun notify(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _notices.trySend(TaskNotice(text, actionLabel, action))
    }

    /** `useAutoDismiss("")` — errors clear after 5s. */
    private fun fail(message: String) {
        _ui.update { it.copy(error = message) }
        errorJob?.cancel()
        errorJob = viewModelScope.launch {
            delay(5_000)
            _ui.update { if (it.error == message) it.copy(error = null) else it }
        }
    }

    /** Background list reloads: a transient failure behind data already on screen stays silent. */
    private fun loadFailed(e: Throwable, hasData: Boolean, message: String) {
        if (hasData && isTransientFailure(e)) _ui.update { if (it.error == message) it.copy(error = null) else it }
        else fail(message)
    }

    private fun failure(e: Throwable, fallback: String): String =
        if (e is ApiError) userFacingMessage(e, fallback)
        else e.message?.takeIf { it.isNotBlank() && it != "Task action failed" && !it.startsWith("HTTP ") } ?: fallback

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
    fun bind(userId: Long, role: String, teamName: String?, agileEnabled: Boolean, customFieldsEnabled: Boolean = false) {
        val current = _ui.value
        if (current.userId == userId && current.role == role && current.teamName == teamName &&
            current.agileEnabled == agileEnabled && current.customFieldsEnabled == customFieldsEnabled
        ) return
        val changedUser = current.userId != null && current.userId != userId
        _ui.value = (if (changedUser) TaskUiState() else current).copy(
            userId = userId, role = role, teamName = teamName, agileEnabled = agileEnabled, customFieldsEnabled = customFieldsEnabled,
            // Agile switched off mid-session: bounce off the Sprint tab.
            tab = if (!agileEnabled && current.tab == TaskTab.Sprint) TaskTab.Backlog else if (changedUser) TaskTab.Backlog else current.tab,
        )
        loadPreferences()
        if (agileEnabled) loadEpics()
        refresh()
    }

    /** Pull-to-refresh, resume and reconnect: metadata plus the visible tab. */
    fun refresh(pull: Boolean = false) {
        if (_ui.value.userId == null) return
        if (pull) _ui.update { it.copy(refreshing = true) }
        io {
            val agile = _ui.value.agileEnabled
            val labels = runCatching(repository::loadLabels).getOrNull()
            val users = runCatching(repository::loadAssignableUsers).getOrNull()
            val projects = runCatching(repository::loadProjects).getOrNull()
            // A feature-gate 403 means "no sprints", not a retryable failure.
            val sprints = if (agile) runCatching(repository::loadAvailableSprints).getOrDefault(emptyList()) else emptyList()
            val config = if (agile) runCatching(repository::loadAgileConfig).getOrNull() else null
            _ui.update { s ->
                val selected = pickSprint(s.selectedSprintId, sprints)
                s.copy(
                    labels = labels ?: s.labels,
                    assignableUsers = users ?: s.assignableUsers,
                    projects = projects ?: s.projects,
                    sprints = sprints,
                    agile = config ?: s.agile,
                    selectedSprintId = selected,
                    tab = if (s.tab == TaskTab.Sprint && (!agile || sprints.isEmpty())) TaskTab.Backlog
                    else if (!landingApplied) landingTab(s.tab, sprints, agile)
                    else s.tab,
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
    fun pollVisible() {
        val s = _ui.value
        if (s.userId == null || s.detailEditing || s.sheet == TaskSheet.CreateTicket) return
        reloadTab()
    }

    // ── Realtime (targeted patch) ────────────────────────────────────────

    /** Raw `task_*` envelope from the shell; falls back to a reload when the payload is unusable. */
    fun onTaskEvent(type: String, data: JsonElement?) {
        if (_ui.value.userId == null) return
        // A field-level delta patches the card in place, so a teammate's edit
        // lands without a refetch and without flicker.
        val delta = if (type == "task_updated") parseTaskDelta(data) else null
        if (delta != null) {
            applyDelta(delta)
            return
        }
        val event = parseTaskEvent(type, data)
        synchronized(pendingEvents) {
            if (event == null) pendingReload = true else pendingEvents += event
        }
        scheduleSync()
    }

    /** Per-task timestamp of the last delta applied, so an out-of-order frame cannot roll a field back. */
    private val deltaAppliedAt = mutableMapOf<Long, String>()
    private val planRevision = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Patch one task from a field-level delta.
     *
     * Only the fields in the frame are touched, so two people editing different
     * fields of the same ticket both survive. The later write wins per field.
     */
    private fun applyDelta(delta: TaskDelta) {
        val previous = deltaAppliedAt[delta.taskId]
        if (delta.at != null && previous != null && delta.at < previous) return
        if (delta.at != null) deltaAppliedAt[delta.taskId] = delta.at

        planRevision.incrementAndGet()
        var patched: Task? = null
        _ui.update { s ->
            fun Task.patched(): Task =
                if (id == delta.taskId) applyTaskDelta(this, delta, previous).also { patched = it } else this
            s.copy(
                tasks = s.tasks.map { it.patched() },
                backlog = s.backlog.map { it.patched() },
                planTasks = s.planTasks.map { it.patched() },
                completed = s.completed.map { it.patched() },
                scheduled = s.scheduled.map { it.patched() },
                searchResults = s.searchResults.map { it.patched() },
                detail = s.detail?.patched(),
                attribution = delta.actorName?.let { s.attribution + (delta.taskId to it) } ?: s.attribution,
            ).let { if (it.planLoaded) it.projectPlan() else it }
        }
        if (patched == null) {
            synchronized(pendingEvents) { pendingReload = true }
            scheduleSync()
        } else if (delta.fields.keys.any { it in setOf("workflow_state_id", "assigned_to", "sprint_id", "project_id") }) {
            // IDs alone cannot update completion, avatars or joined names.
            synchronized(pendingEvents) { pendingEvents += TaskEvent(delta.taskId, TaskAction.Updated) }
            scheduleSync()
        }
        patched?.let { flash(delta.taskId) }
        // The attribution is a flash, not a permanent label.
        delta.actorName?.let { name ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                delay(ATTRIBUTION_MS)
                _ui.update { s ->
                    if (s.attribution[delta.taskId] == name) s.copy(attribution = s.attribution - delta.taskId)
                    else s
                }
            }
        }
    }

    /** Reconnect catch-up: the server keeps no replay log, so revalidate everything visible. */
    fun onTaskEvent() {
        if (_ui.value.userId == null) return
        reloadTab()
        if (_ui.value.detail != null && !_ui.value.detailEditing) refreshDetail()
    }

    /** Debounce only: the debounce delay may be cancelled, but each drained batch is applied serially. */
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SYNC_DEBOUNCE_MS)
            viewModelScope.launch(Dispatchers.IO) {
                syncMutex.withLock {
                    val (events, reload) = synchronized(pendingEvents) {
                        val copy = pendingEvents.toList()
                        pendingEvents.clear()
                        val r = pendingReload
                        pendingReload = false
                        copy to r
                    }
                    if (events.isNotEmpty() || reload) applySync(events, reload)
                }
            }
        }
    }

    private suspend fun applySync(events: List<TaskEvent>, forceReload: Boolean) {
        val byTask = coalesceTaskEvents(events)
        if (forceReload || byTask.size > MAX_TARGETED_SYNC) {
            onTaskEvent()
            return
        }
        var needsReload = false
        byTask.forEach { (id, actions) ->
            when {
                TaskAction.Deleted in actions -> removeTask(id, remote = true)
                actions == setOf(TaskAction.Comment) -> syncComments(id)
                else -> if (!syncTask(id)) needsReload = true
            }
        }
        if (needsReload) reloadTab()
        if (_ui.value.tab == TaskTab.Sprint) refreshSprintStats()
    }

    /** Fetches one task and patches every view of it. `false` = the fetch failed (caller reloads). */
    private suspend fun syncTask(id: Long): Boolean {
        val fresh = runCatching { repository.loadDetail(id) }.getOrElse { e ->
            // 404 = deleted or no longer visible to this user.
            if ((e as? TaskFailure)?.statusCode == 404 || (e as? ApiError.Http)?.statusCode == 404) {
                removeTask(id, remote = true)
                return true
            }
            return false
        }
        patchTask(fresh, highlight = true)
        val s = _ui.value
        if (s.detail?.id == id) {
            if (s.detailEditing) _ui.update { it.copy(detailStale = true) }
            else {
                _ui.update { it.copy(detail = fresh, detailComments = fresh.comments) }
                refreshHistory(id)
            }
        }
        if (s.inlineComments?.taskId == id) syncComments(id)
        return true
    }

    private suspend fun syncComments(id: Long) {
        val s = _ui.value
        val watching = s.detail?.id == id || s.inlineComments?.taskId == id
        if (!watching) {
            // Only the count is visible on cards; one fetch keeps it right.
            val count = runCatching { repository.loadComments(id).size }.getOrNull() ?: return
            _ui.update { st ->
                fun Task.fix() = if (this.id == id) copy(commentCount = count) else this
                st.copy(tasks = st.tasks.map { it.fix() }, backlog = st.backlog.map { it.fix() }, planTasks = st.planTasks.map { it.fix() })
                    .let { if (it.planLoaded) it.projectPlan() else it }
            }
            flash(id)
            return
        }
        val items = runCatching { repository.loadComments(id) }.getOrNull() ?: return
        _ui.update { st ->
            fun Task.fix() = if (this.id == id) copy(commentCount = items.size) else this
            st.copy(
                tasks = st.tasks.map { it.fix() },
                backlog = st.backlog.map { it.fix() },
                planTasks = st.planTasks.map { it.fix() },
                detailComments = if (st.detail?.id == id) items else st.detailComments,
                inlineComments = st.inlineComments?.let { if (it.taskId == id) it.copy(items = items, loading = false) else it },
            ).let { if (it.planLoaded) it.projectPlan() else it }
        }
        if (_ui.value.detail?.id == id) refreshHistory(id)
        flash(id)
    }

    /** Upserts [task] into the sprint board and backlog according to the server's list rules. */
    private fun patchTask(task: Task, highlight: Boolean) {
        planRevision.incrementAndGet()
        _ui.update { s ->
            val onBoard = belongsToSprint(task, s.selectedSprintId, s.filters, s.userId)
            val tasks = upsertTask(s.tasks, task, onBoard)
            val oldBacklog = s.backlog.firstOrNull { it.id == task.id }
            val inBacklog = belongsToBacklog(task, s.filters, s.userId)
            val backlog = upsertTask(s.backlog, task, inBacklog)
            val summary = adjustSummary(s.backlogSummary, oldBacklog, if (inBacklog) task else null)
            val totalDelta = (if (inBacklog) 1 else 0) - (if (oldBacklog != null) 1 else 0)
            val (from, to) = scheduledWindow()
            s.copy(
                tasks = tasks,
                stats = if (tasks !== s.tasks) recomputeStats(tasks) else s.stats,
                backlog = backlog,
                backlogSummary = summary,
                backlogTotal = maxOf(0, s.backlogTotal + totalDelta),
                scheduled = if (s.scheduledLoaded) upsertTask(s.scheduled, task, belongsToScheduled(task, from, to, s.filters, s.userId)) else s.scheduled,
                searchResults = s.searchResults.map { if (it.id == task.id) task else it },
            ).patchPlan(task)
        }
        if (highlight) flash(task.id)
    }

    private fun removeTask(id: Long, remote: Boolean) {
        planRevision.incrementAndGet()
        val wasOpen = _ui.value.detail?.id == id
        _ui.update { s ->
            val oldBacklog = s.backlog.firstOrNull { it.id == id }
            val tasks = s.tasks.filterNot { it.id == id }
            s.copy(
                tasks = tasks,
                planTasks = s.planTasks.filterNot { it.id == id },
                stats = if (tasks.size != s.tasks.size) recomputeStats(tasks) else s.stats,
                backlog = s.backlog.filterNot { it.id == id },
                scheduled = s.scheduled.filterNot { it.id == id },
                backlogSummary = adjustSummary(s.backlogSummary, oldBacklog, null),
                backlogTotal = if (oldBacklog != null) maxOf(0, s.backlogTotal - 1) else s.backlogTotal,
                searchResults = s.searchResults.filterNot { it.id == id },
                inlineComments = s.inlineComments?.takeIf { it.taskId != id },
                detail = s.detail?.takeIf { it.id != id },
            ).let { if (it.planLoaded) it.projectPlan() else it }
        }
        if (remote && wasOpen) notify("This task was deleted")
    }

    /** Callable from any thread; the job map is only touched on Main. */
    private fun flash(id: Long) {
        _ui.update { it.copy(recentlyUpdated = it.recentlyUpdated + id) }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            highlightJobs.remove(id)?.cancel()
            val job = launch {
                delay(HIGHLIGHT_MS)
                _ui.update { it.copy(recentlyUpdated = it.recentlyUpdated - id) }
            }
            highlightJobs[id] = job
            job.invokeOnCompletion { if (highlightJobs[id] === job) highlightJobs.remove(id) }
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

    fun loadScheduled() {
        scheduledJob?.cancel()
        _ui.update { it.copy(scheduledLoading = !it.scheduledLoaded) }
        val gen = scheduledGen.incrementAndGet()
        scheduledJob = io {
            val (from, to) = scheduledWindow()
            // Overlays are read-only context for the same window, so they load
            // alongside the tasks rather than on a second round trip.
            runCatching { repository.loadScheduleOverlays(from.toString(), to.toString()) }
                .onSuccess { list -> _ui.update { it.copy(overlays = list) } }
            runCatching { repository.loadScheduled(from, to, _ui.value.filters) }.fold(
                onSuccess = { list -> if (gen == scheduledGen.get()) _ui.update { it.copy(scheduled = list, scheduledLoading = false, scheduledLoaded = true) } },
                onFailure = {
                    if (gen != scheduledGen.get()) return@fold
                    val hadData = _ui.value.scheduledLoaded
                    _ui.update { it.copy(scheduledLoading = false) }
                    loadFailed(it, hadData, "Failed to load scheduled tasks")
                },
            )
        }
    }

    /** Opens the Scheduled tab scrolled to [date]. */
    fun showScheduled(date: String?) {
        _ui.update { it.copy(tab = TaskTab.Scheduled, scheduledFocus = date, searchOpen = false) }
        loadScheduled()
    }

    /** Switch the Sprint tab between the board and the split planner. */
    fun setSprintView(view: SprintView) {
        _ui.update { it.copy(sprintView = view) }
        if (view == SprintView.Plan) {
            // The planner needs the ranked backlog beside the sprint.
            if (_ui.value.backlog.isEmpty()) loadBacklog()
            _ui.value.selectedSprintId?.let { loadBaseline(it) }
        }
    }

    /** The sprint goal is editable inline from the planner. */
    fun saveSprintGoal(sprintId: Long, goal: String) {
        val trimmed = goal.trim()
        io {
            runCatching { repository.updateSprintGoal(sprintId, trimmed) }.fold(
                onSuccess = { updated ->
                    val saved = updated.sprint?.goal ?: trimmed
                    _ui.update { s ->
                        s.copy(sprints = s.sprints.map { if (it.id == sprintId) it.copy(goal = saved) else it })
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

    fun selectTab(tab: TaskTab) {
        if (tab == _ui.value.tab) return
        _ui.update { s ->
            s.copy(
                tab = tab,
                selectedSprintId = if (tab == TaskTab.Sprint) pickSprint(s.selectedSprintId, s.sprints) else s.selectedSprintId,
                searchOpen = false,
            )
        }
        reloadTab()
    }

    fun selectSprint(id: Long) {
        _ui.update { it.copy(selectedSprintId = id, completing = false, lifecycleError = null, sheet = null, tasks = emptyList()) }
        loadSprint()
    }

    fun openSheet(sheet: TaskSheet) {
        _ui.update { it.copy(sheet = sheet) }
        if (sheet == TaskSheet.Import && _ui.value.backlog.isEmpty()) loadBacklog()
    }

    fun closeSheet() = _ui.update { it.copy(sheet = null, importTaskId = null) }

    fun setFilters(filters: TaskFilters) {
        _ui.update { it.copy(filters = filters).let { state -> if (state.planLoaded) state.projectPlan() else state } }
        if (_ui.value.tab != TaskTab.Backlog || !_ui.value.planLoaded) reloadTab()
    }

    fun clearFilters() {
        _ui.update { it.copy(quickFilters = BacklogQuickFilters(), selectedEpicId = null) }
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
            _ui.update { it.copy(searchQuery = value, searchResults = emptyList(), searching = false) }
            return
        }
        _ui.update { it.copy(searchQuery = value, searching = true) }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            val results = runCatching { repository.search(value.trim()) }.getOrDefault(emptyList())
            _ui.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun openSearch() = _ui.update { it.copy(searchOpen = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searchOpen = false, searchQuery = "", searchResults = emptyList(), searching = false) }
    }

    // ── Sprint board ─────────────────────────────────────────────────────

    fun loadSprint() {
        val sprintId = _ui.value.selectedSprintId
        sprintJob?.cancel()
        if (sprintId == null) {
            _ui.update { it.copy(tasks = emptyList(), stats = TaskStats(), sprintStats = null, sprintLoading = false) }
            return
        }
        _ui.update { it.copy(sprintLoading = it.tasks.isEmpty()) }
        sprintJob = io {
            val filters = _ui.value.filters
            runCatching { repository.loadSprintTasks(sprintId, filters) }.fold(
                onSuccess = { result ->
                    _ui.update { if (it.selectedSprintId == sprintId) it.copy(tasks = result.tasks, stats = result.stats, sprintLoading = false) else it }
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
        if (task.workflowStateKey == state.key || (state.id != 0L && task.workflowStateId == state.id)) return
        if (!canChangeStatus(task, _ui.value.userId, _ui.value.role)) return notify(STATUS_FORBIDDEN)
        val previous = task
        val moved = task.copy(workflowStateKey = state.key, workflowStateId = state.id.takeIf { it != 0L } ?: task.workflowStateId)
        applyTask(moved)
        io {
            runCatching { repository.updateStatus(task.id, state.key) }.fold(
                onSuccess = { saved ->
                    patchServerTask(saved, moved)
                    refreshSprintStats()
                    notify("Moved to ${state.name.ifEmpty { state.key }}", "Undo") { revertMove(previous, state) }
                },
                onFailure = { e ->
                    applyTask(previous)
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
                onSuccess = { refreshSprintStats() },
                onFailure = { e ->
                    applyTask(current.copy(workflowStateKey = from.key))
                    fail(failure(e, "Failed to undo move"))
                },
            )
        }
    }

    /** Status/field writes return the bare row; keep the enrichment (labels, people) we already have. */
    private fun patchServerTask(saved: Task, local: Task) {
        val merged = local.copy(
            workflowStateKey = saved.workflowStateKey ?: local.workflowStateKey,
            workflowStateCategory = saved.workflowStateCategory ?: local.workflowStateCategory,
            workflowStateId = saved.workflowStateId ?: local.workflowStateId,
            completedAt = saved.completedAt,
        )
        applyTask(merged)
        _ui.update { s -> s.copy(detail = s.detail?.let { if (it.id == merged.id) it.copy(workflowStateKey = merged.workflowStateKey, workflowStateId = merged.workflowStateId, completedAt = merged.completedAt) else it }) }
    }

    private fun applyTask(task: Task) = _ui.update { s ->
        planRevision.incrementAndGet()
        val tasks = s.tasks.map { if (it.id == task.id) task else it }
        s.copy(
            tasks = tasks,
            stats = recomputeStats(tasks),
            backlog = s.backlog.map { if (it.id == task.id) task else it },
        ).patchPlan(task)
    }

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
                repository.assignSprint(taskId, sprintId)
                if (s.importAssignedTo != null || s.importDueDate.isNotEmpty()) {
                    repository.updateImportFields(taskId, ImportUpdatePayload(s.importAssignedTo, s.importDueDate.ifEmpty { null }))
                }
            }.fold(
                onSuccess = {
                    _ui.update { st ->
                        st.copy(backlog = st.backlog.filterNot { it.id == taskId }, importTaskId = null, importAssignedTo = null, importDueDate = "")
                    }
                    notify("Added to ${s.currentSprint?.name ?: "sprint"}")
                    if (!syncTask(taskId)) loadBacklog()
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
            val options = runCatching(repository::loadTeamSprints).getOrDefault(emptyList())
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
        if (sprints != null) {
            _ui.update { s ->
                val selected = pickSprint(s.selectedSprintId, sprints)
                s.copy(
                    sprints = sprints,
                    selectedSprintId = selected,
                    tab = if (s.tab == TaskTab.Sprint && sprints.isEmpty()) TaskTab.Backlog else s.tab,
                )
            }
        }
        reloadTab()
    }

    // ── Backlog (`useBacklog`, infinite scroll) ──────────────────────────

    /** Publish an atomic collection, not filtered fragments of server pages. */
    fun loadBacklog() {
        backlogJob?.cancel()
        _ui.update { it.copy(backlogLoading = it.backlog.isEmpty(), backlogLoadingMore = false) }
        val gen = backlogGen.incrementAndGet()
        backlogJob = io {
            val s = _ui.value
            val revision = planRevision.get()
            runCatching { repository.loadPlanTasks(s.sprints) }.fold(
                onSuccess = { tasks ->
                    if (gen != backlogGen.get()) return@fold
                    if (s.userId != _ui.value.userId) return@fold
                    if (revision != planRevision.get()) {
                        loadBacklog()
                        return@fold
                    }
                    _ui.update {
                        it.copy(
                            planTasks = tasks,
                            planLoaded = true,
                            backlogLoading = false,
                        ).projectPlan()
                    }
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

    /** All server pages are drained by loadPlanTasks before publishing counts. */
    fun loadMoreBacklog() {
        if (!_ui.value.planLoaded && !_ui.value.backlogLoading) loadBacklog()
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
                assignedTo = draft.assignedTo,
                dueDate = draft.dueDate.ifEmpty { null },
                labelIds = draft.labels.ifEmpty { null },
                sprintId = draft.sprintId,
                storyPoints = draft.storyPoints,
                workItemTypeId = draft.workItemTypeId,
                projectId = draft.projectId,
            )
            runCatching { repository.createBacklogTask(payload) }.fold(
                onSuccess = { created ->
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
        io {
            runCatching { repository.scheduleTask(taskId, date) }.fold(
                onSuccess = {
                    if (!syncTask(taskId)) removeTask(taskId, remote = false)
                    if (_ui.value.tab == TaskTab.Scheduled) loadScheduled()
                    notify("Scheduled for ${formatDate(date)}", "View") { showScheduled(date) }
                    if (closeAfter) withContext(Dispatchers.Main) { closeDetail() }
                },
                onFailure = { fail("Failed to schedule task") },
            )
        }
    }

    /** `handleUnscheduleTask`; the confirm dialog is skipped for the Scheduled list's swipe (it has Undo). */
    fun unschedule(taskId: Long, title: String?, closeAfter: Boolean = false, ask: Boolean = true) {
        val previousDate = (_ui.value.scheduled + _ui.value.tasks + _ui.value.planTasks).firstOrNull { it.id == taskId }?.plannedDate ?: _ui.value.detail?.takeIf { it.id == taskId }?.plannedDate
        val run = {
            io {
                runCatching { repository.unscheduleTask(taskId) }.fold(
                    onSuccess = {
                        if (!syncTask(taskId)) reloadTab()
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
        io {
            runCatching { repository.assignSprint(task.id, sprintId) }.fold(
                onSuccess = {
                    val name = sprintId?.let { id -> _ui.value.sprints.firstOrNull { it.id == id }?.name }
                    notify(if (name != null) "Added to $name" else "Moved to backlog")
                    if (!syncTask(task.id)) reloadTab()
                    if (_ui.value.tab == TaskTab.Sprint) refreshSprintStats()
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
                        if (_ui.value.tab == TaskTab.Sprint) refreshSprintStats()
                    },
                    onFailure = { e -> fail(failure(e, "Failed to cancel ticket")) },
                )
            }
        }
    }

    // ── Detail (`useTaskDetail`) ─────────────────────────────────────────

    fun openDetail(task: Task) {
        _ui.update {
            it.copy(
                detail = task, detailLoading = true, detailComments = emptyList(), detailTab = DetailTab.Comments,
                history = emptyList(), detailEditing = false, detailStale = false, searchOpen = false,
            )
        }
        io {
            runCatching { repository.loadDetail(task.id) }.fold(
                onSuccess = { data -> _ui.update { if (it.detail?.id == task.id) it.copy(detail = data, detailComments = data.comments) else it } },
                onFailure = {
                    val comments = runCatching { repository.loadComments(task.id) }.getOrDefault(emptyList())
                    _ui.update { if (it.detail?.id == task.id) it.copy(detailComments = comments) else it }
                },
            )
            _ui.update { it.copy(detailLoading = false) }
            refreshHistory(task.id)
            // The timer state belongs to the open task, so it loads with it.
            loadTimer(task.id)
        }
    }

    /** Replaces the open detail (Epic ↔ child links) or opens a `?task=<id>` deep link. */
    fun openDetailById(id: Long) = openDetail(Task(id = id, title = ""))

    /** Web `?tab=` / `?sprint_id=` query params. */
    fun applyLink(tab: String?, sprintId: Long?) {
        TaskTab.fromKey(tab)?.let { next -> _ui.update { it.copy(tab = next) } }
        sprintId?.let { id -> _ui.update { it.copy(selectedSprintId = id) } }
        if (tab != null || sprintId != null) reloadTab()
    }

    private suspend fun refreshHistory(taskId: Long) {
        val history = runCatching { repository.loadHistory(taskId) }.getOrNull() ?: return
        _ui.update { if (it.detail?.id == taskId) it.copy(history = history) else it }
    }

    fun closeDetail() = _ui.update {
        it.copy(detail = null, detailComments = emptyList(), detailEditing = false, history = emptyList(), detailTab = DetailTab.Comments, detailStale = false)
    }

    fun setDetailTab(tab: DetailTab) = _ui.update { it.copy(detailTab = tab) }

    fun startEdit() = _ui.update { it.copy(detailEditing = true) }

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
                _ui.update { if (it.detail?.id == id) it.copy(detail = data, detailComments = data.comments) else it }
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
        io {
            runCatching { repository.patchFields(task.id, fields) }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) { onSaved() }
                    done?.let { notify(it) }
                    runCatching { repository.loadDetail(task.id) }.onSuccess { data ->
                        _ui.update { if (it.detail?.id == task.id) it.copy(detail = data, detailComments = data.comments, detailStale = false) else it }
                        patchTask(data, highlight = false)
                    }
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
        if (!canChangeStatus(task, _ui.value.userId, _ui.value.role)) return notify(STATUS_FORBIDDEN)
        io {
            runCatching { repository.updateStatus(task.id, state.key) }.fold(
                onSuccess = {
                    notify("Status: ${state.name.ifEmpty { state.key }}")
                    runCatching { repository.loadDetail(task.id) }.onSuccess { data ->
                        _ui.update { if (it.detail?.id == task.id) it.copy(detail = data, detailComments = data.comments) else it }
                        patchTask(data, highlight = false)
                    }
                    refreshHistory(task.id)
                    if (_ui.value.tab == TaskTab.Sprint) refreshSprintStats()
                },
                onFailure = { e -> fail(failure(e, "Failed to update status")) },
            )
        }
    }

    // ── Comments (detail + inline panel) ─────────────────────────────────

    fun openComments(taskId: Long) {
        _ui.update { it.copy(inlineComments = CommentThread(taskId)) }
        io {
            val items = runCatching { repository.loadComments(taskId) }.getOrDefault(emptyList())
            _ui.update { s -> s.copy(inlineComments = s.inlineComments?.takeIf { it.taskId == taskId }?.copy(items = items, loading = false)) }
        }
    }

    fun closeComments() = _ui.update { it.copy(inlineComments = null) }

    private fun bumpCommentCount(taskId: Long, delta: Int) = _ui.update { s ->
        fun Task.bump() = if (id == taskId) copy(commentCount = maxOf(0, commentCount + delta)) else this
        s.copy(tasks = s.tasks.map { it.bump() }, backlog = s.backlog.map { it.bump() }, planTasks = s.planTasks.map { it.bump() })
            .let { if (it.planLoaded) it.projectPlan() else it }
    }

    private fun updateComments(taskId: Long, transform: (List<TaskComment>) -> List<TaskComment>) = _ui.update { s ->
        s.copy(
            detailComments = if (s.detail?.id == taskId) transform(s.detailComments) else s.detailComments,
            inlineComments = s.inlineComments?.let { if (it.taskId == taskId) it.copy(items = transform(it.items)) else it },
        )
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
                    viewModelScope.launch { onDone() }
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
                    viewModelScope.launch { onDone() }
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
            }
        }
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
        io {
            runCatching { repository.bulkAction(BulkPayload(ids, action, value)) }.fold(
                onSuccess = { result ->
                    clearSelection()
                    notify("${result.updated} updated")
                    reloadTab()
                },
                onFailure = { fail(failure(it, "Bulk action failed")) },
            )
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

    fun selectEpic(id: Long?) = _ui.update { it.copy(selectedEpicId = id).let { state -> if (state.planLoaded) state.projectPlan() else state } }

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
                    _ui.update { s ->
                        s.copy(
                            timerRunning = false,
                            timerBusy = false,
                            detail = s.detail?.let {
                                if (it.id == taskId) it.copy(loggedMinutes = it.loggedMinutes + stop.minutes) else it
                            },
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
                onSuccess = { task -> viewModelScope.launch { openDetail(task) } },
                onFailure = { fail(failure(it, "No task matches $key")) },
            )
        }
    }

    companion object {
        private const val SYNC_DEBOUNCE_MS = 400L
        private const val HIGHLIGHT_MS = 2_200L
        private const val ATTRIBUTION_MS = 4_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return TaskViewModel(TaskRepository(container.api), ServiceDeskRepository(container.api)) as T
            }
        }
    }
}

/** A picked comment attachment. */
class CommentFile(val name: String, val mimeType: String, val bytes: ByteArray)
