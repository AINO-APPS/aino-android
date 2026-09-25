package app.aino.mobile.feature.tasks

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

/** Detail editor fields (TaskDetailModal edit state). */
data class TaskEditDraft(
    val title: String,
    val description: String,
    val descriptionHtml: String,
    val priority: String,
    val assignedTo: Long?,
    val dueDate: String,
    val sprintId: Long?,
    val labels: List<Long>,
    val storyPoints: String?,
    val workItemTypeId: Long?,
    val projectId: Long?,
) {
    companion object {
        fun from(task: Task): TaskEditDraft {
            val plain = stripHtml(task.description)
            return TaskEditDraft(
                title = task.title,
                description = plain,
                descriptionHtml = task.description.orEmpty(),
                priority = task.priority,
                assignedTo = task.assignedTo,
                dueDate = task.dueDate?.take(10).orEmpty(),
                sprintId = task.sprintId,
                labels = task.labels.map { it.id },
                storyPoints = task.storyPoints?.let(::formatPoints),
                workItemTypeId = task.workItemTypeId,
                projectId = task.projectId,
            )
        }
    }
}

enum class DetailTab { Comments, History }

/** Comments shown either in the detail page or the card's inline comment sheet. */
data class CommentThread(val taskId: Long, val items: List<TaskComment> = emptyList(), val loading: Boolean = true)

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
    val selectedSprintId: Long? = null,
    val filters: TaskFilters = TaskFilters(),
    val filtersOpen: Boolean = false,
    val refreshing: Boolean = false,
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
    val importOpen: Boolean = false,
    val importTaskId: Long? = null,
    val importAssignedTo: Long? = null,
    val importDueDate: String = "",
    val lifecycleBusy: Boolean = false,
    val lifecycleError: String? = null,
    val completing: Boolean = false,
    val rolloverOptions: List<AvailableSprint> = emptyList(),
    val rolloverTo: String = "backlog",
    // Backlog.
    val backlog: List<Task> = emptyList(),
    val backlogSummary: BacklogSummary = BacklogSummary(),
    val backlogTotal: Int = 0,
    val backlogLimit: Int = 25,
    val backlogOffset: Int = 0,
    val backlogSort: BacklogSort = BacklogSort.Priority,
    val backlogLoading: Boolean = false,
    val backlogFormOpen: Boolean = false,
    val draft: BacklogDraft = BacklogDraft(),
    val scheduleTaskId: Long? = null,
    val scheduleDate: String = LocalDate.now().toString(),
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
) {
    val currentSprint: AvailableSprint? get() = sprints.firstOrNull { it.id == selectedSprintId }
    val sortedBacklog: List<Task> get() = sortBacklog(backlog, backlogSort)
    val sprintTabVisible: Boolean get() = agileEnabled && sprints.isNotEmpty()
    val filterCount: Int get() = filterCount(filters, tab)
    val importable: List<Task> get() = backlog.filter { it.sprintId != selectedSprintId && it.status != "done" }
}

class TaskViewModel(
    private val repository: TaskRepository,
    internal val serviceDesk: ServiceDeskRepository? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(TaskUiState())
    val ui: StateFlow<TaskUiState> = _ui.asStateFlow()

    private var errorJob: Job? = null
    private var searchJob: Job? = null
    private var carriedJob: Job? = null
    private var carriedOn: String? = null
    private var sprintJob: Job? = null
    private var backlogJob: Job? = null

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) { block() }

    /** `useAutoDismiss("")` — errors clear after 5s. */
    private fun fail(message: String) {
        _ui.update { it.copy(error = message) }
        errorJob?.cancel()
        errorJob = viewModelScope.launch {
            delay(5_000)
            _ui.update { if (it.error == message) it.copy(error = null) else it }
        }
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
        refresh()
    }

    /** Pull-to-refresh, resume and realtime: metadata plus the visible tab. */
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
                    tab = if (s.tab == TaskTab.Sprint && (!agile || sprints.isEmpty())) TaskTab.Backlog else s.tab,
                )
            }
            autoCarryForward()
            reloadTab()
            _ui.update { it.copy(refreshing = false) }
        }
    }

    /** Realtime `task_*` events: reload the visible tab (and an open detail) without refetching metadata. */
    fun onTaskEvent() {
        if (_ui.value.userId == null) return
        reloadTab()
        if (_ui.value.detail != null && !_ui.value.detailEditing) refreshDetail()
    }

    private fun reloadTab() {
        when (_ui.value.tab) {
            TaskTab.Sprint -> loadSprint()
            TaskTab.Backlog -> loadBacklog()
            TaskTab.ServiceDesk -> _ui.update { it.copy(serviceDeskVersion = it.serviceDeskVersion + 1) }
        }
    }

    /** Tasks.tsx runs carry-forward once per local day and shows a 4s banner. */
    private suspend fun autoCarryForward() {
        val today = LocalDate.now().toString()
        if (carriedOn == today) return
        carriedOn = today
        runCatching(repository::carryForward).fold(
            onSuccess = { result ->
                if (result.carried > 0) {
                    _ui.update { it.copy(carriedCount = result.carried) }
                    carriedJob?.cancel()
                    carriedJob = viewModelScope.launch {
                        delay(4_000)
                        _ui.update { it.copy(carriedCount = 0) }
                    }
                }
            },
            onFailure = { fail("Failed to carry forward tasks") },
        )
    }

    // ── Tabs, sprint picker, filters ─────────────────────────────────────

    fun selectTab(tab: TaskTab) {
        if (tab == _ui.value.tab) return
        _ui.update { s ->
            s.copy(
                tab = tab,
                selectedSprintId = if (tab == TaskTab.Sprint) pickSprint(s.selectedSprintId, s.sprints) else s.selectedSprintId,
            )
        }
        reloadTab()
    }

    fun selectSprint(id: Long) {
        _ui.update { it.copy(selectedSprintId = id, completing = false, lifecycleError = null) }
        loadSprint()
    }

    fun toggleFilters() = _ui.update { it.copy(filtersOpen = !it.filtersOpen) }

    fun setFilters(filters: TaskFilters) {
        _ui.update { it.copy(filters = filters, backlogOffset = 0) }
        reloadTab()
    }

    fun clearFilters() = setFilters(TaskFilters())

    /** Summary "Total" chip: open filters and clear priority + status. */
    fun summaryTotal() {
        _ui.update { it.copy(filtersOpen = true) }
        setFilters(_ui.value.filters.copy(priority = "", status = ""))
    }

    /** Summary priority chip toggles that priority filter. */
    fun summaryPriority(value: String) {
        _ui.update { it.copy(filtersOpen = true) }
        val current = _ui.value.filters
        setFilters(current.copy(priority = if (current.priority == value) "" else value))
    }

    // ── Global search (`useGlobalSearch`) ────────────────────────────────

    fun setSearch(value: String) {
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _ui.update { it.copy(searchQuery = value, searchResults = emptyList(), searchOpen = false, searching = false) }
            return
        }
        _ui.update { it.copy(searchQuery = value, searching = true, searchOpen = true) }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            val results = runCatching { repository.search(value.trim()) }.getOrDefault(emptyList())
            _ui.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun reopenSearch() = _ui.update { s ->
        if (s.searchResults.isNotEmpty() || s.searchQuery.trim().length >= 2) s.copy(searchOpen = true) else s
    }

    fun closeSearch() = _ui.update { it.copy(searchOpen = false) }

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
                    _ui.update { it.copy(tasks = result.tasks, stats = result.stats, sprintLoading = false) }
                },
                onFailure = {
                    _ui.update { it.copy(sprintLoading = false) }
                    fail("Failed to load tasks")
                },
            )
            val stats = runCatching { repository.loadSprintStats(sprintId) }.getOrNull()
            _ui.update { it.copy(sprintStats = stats) }
        }
    }

    /** Drag-and-drop move (`useDragDrop.onDrop`) — the board's long-press "Move to". */
    fun requestMove(task: Task, state: WorkflowState) {
        if (task.status == state.key || (state.id != 0L && task.workflowStateId == state.id)) return
        confirm("Change Status", "Move \"${task.title}\" to ${state.name.ifEmpty { state.key }}?", "Move") {
            val previous = task
            applyTask(task.copy(status = state.key, workflowStateId = state.id.takeIf { it != 0L } ?: task.workflowStateId))
            io {
                runCatching { repository.updateStatus(task.id, state.key) }.fold(
                    onSuccess = { loadSprint() },
                    onFailure = {
                        // Roll back so the card snaps back (e.g. WIP limit 409).
                        applyTask(previous)
                        fail(it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to move item")
                    },
                )
            }
        }
    }

    private fun applyTask(task: Task) = _ui.update { s ->
        val tasks = s.tasks.map { if (it.id == task.id) task else it }
        s.copy(tasks = tasks, stats = recomputeStats(tasks))
    }

    fun toggleImport() {
        val opening = !_ui.value.importOpen
        _ui.update { it.copy(importOpen = opening, importTaskId = if (opening) it.importTaskId else null) }
        if (opening && _ui.value.backlog.isEmpty()) loadBacklog()
    }

    fun closeImport() = _ui.update { it.copy(importOpen = false, importTaskId = null) }

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
                    loadSprint()
                    loadBacklog()
                },
                onFailure = { fail("Failed to import task to sprint") },
            )
        }
    }

    // ── Sprint lifecycle (`SprintLifecycleControls`) ─────────────────────

    fun startSprint() {
        val sprint = _ui.value.currentSprint ?: return
        if (_ui.value.lifecycleBusy) return
        _ui.update { it.copy(lifecycleBusy = true, lifecycleError = null) }
        io {
            runCatching { repository.startSprint(sprint.id) }.fold(
                onSuccess = { lifecycleChanged() },
                onFailure = { e -> _ui.update { it.copy(lifecycleError = e.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to start sprint") } },
            )
            _ui.update { it.copy(lifecycleBusy = false) }
        }
    }

    fun beginComplete() {
        val sprint = _ui.value.currentSprint ?: return
        _ui.update { it.copy(completing = true, rolloverTo = "backlog") }
        io {
            val options = runCatching(repository::loadTeamSprints).getOrDefault(emptyList())
                .filter { it.id != sprint.id && it.status != "completed" }
            _ui.update { it.copy(rolloverOptions = options) }
        }
    }

    fun cancelComplete() = _ui.update { it.copy(completing = false) }

    fun setRollover(value: String) = _ui.update { it.copy(rolloverTo = value) }

    fun completeSprint() {
        val sprint = _ui.value.currentSprint ?: return
        if (_ui.value.lifecycleBusy) return
        _ui.update { it.copy(lifecycleBusy = true, lifecycleError = null) }
        io {
            runCatching { repository.completeSprint(sprint.id, _ui.value.rolloverTo) }.fold(
                onSuccess = {
                    _ui.update { it.copy(completing = false) }
                    lifecycleChanged()
                },
                onFailure = { e -> _ui.update { it.copy(lifecycleError = e.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to complete sprint") } },
            )
            _ui.update { it.copy(lifecycleBusy = false) }
        }
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

    // ── Backlog (`useBacklog`) ───────────────────────────────────────────

    fun loadBacklog() {
        backlogJob?.cancel()
        _ui.update { it.copy(backlogLoading = it.backlog.isEmpty()) }
        backlogJob = io {
            val s = _ui.value
            runCatching { repository.loadBacklog(s.filters, s.backlogLimit, s.backlogOffset) }.fold(
                onSuccess = { result ->
                    _ui.update {
                        it.copy(
                            backlog = result.tasks,
                            backlogSummary = result.summary ?: it.backlogSummary,
                            backlogTotal = result.pagination?.total ?: result.summary?.total ?: result.tasks.size,
                            backlogLoading = false,
                        )
                    }
                },
                onFailure = {
                    _ui.update { it.copy(backlogLoading = false) }
                    fail("Failed to load backlog")
                },
            )
        }
    }

    fun setSort(sort: BacklogSort) = _ui.update { it.copy(backlogSort = sort) }

    fun setPage(offset: Int) {
        _ui.update { it.copy(backlogOffset = maxOf(0, offset)) }
        loadBacklog()
    }

    fun setPageSize(limit: Int) {
        _ui.update { it.copy(backlogLimit = limit, backlogOffset = 0) }
        loadBacklog()
    }

    fun toggleBacklogForm() = _ui.update { it.copy(backlogFormOpen = !it.backlogFormOpen) }

    fun closeBacklogForm() = _ui.update { it.copy(backlogFormOpen = false) }

    fun updateDraft(transform: (BacklogDraft) -> BacklogDraft) = _ui.update { it.copy(draft = transform(it.draft)) }

    /** Sprint pick fills the due date with the sprint end (SprintSelector `onChange`). */
    fun setDraftSprint(id: Long?) = _ui.update { s ->
        val end = id?.let { wanted -> s.sprints.firstOrNull { it.id == wanted }?.endDate?.take(10) }
        s.copy(draft = s.draft.copy(sprintId = id, dueDate = if (id == null) "" else end ?: s.draft.dueDate))
    }

    fun submitBacklog() {
        val s = _ui.value
        val draft = s.draft
        if (draft.title.isBlank()) return
        io {
            val payload = CreateBacklogPayload(
                title = draft.title,
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
                onSuccess = {
                    _ui.update { it.copy(draft = BacklogDraft(), backlogFormOpen = false) }
                    loadBacklog()
                    if (draft.sprintId != null && _ui.value.tab == TaskTab.Sprint) loadSprint()
                },
                onFailure = { fail(it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to create backlog item") },
            )
        }
    }

    fun startSchedule(taskId: Long) = _ui.update { it.copy(scheduleTaskId = taskId, scheduleDate = LocalDate.now().toString()) }

    fun cancelSchedule() = _ui.update { it.copy(scheduleTaskId = null) }

    fun setScheduleDate(date: String) = _ui.update { it.copy(scheduleDate = date) }

    /** `handleScheduleTask` (confirm, then `PATCH /tasks/:id/schedule`). */
    fun schedule(taskId: Long, title: String?, date: String? = null, closeAfter: Boolean = false) {
        val day = date?.ifEmpty { null } ?: _ui.value.scheduleDate.ifEmpty { return }
        confirm("Schedule Task", "Schedule \"${title ?: "this task"}\" to $day?", "Schedule") {
            io {
                runCatching { repository.scheduleTask(taskId, day) }.fold(
                    onSuccess = {
                        _ui.update { it.copy(scheduleTaskId = null) }
                        loadBacklog()
                        if (closeAfter) closeDetail()
                    },
                    onFailure = { fail("Failed to schedule task") },
                )
            }
        }
    }

    /** `handleUnscheduleTask`. */
    fun unschedule(taskId: Long, title: String?, closeAfter: Boolean = false) {
        confirm(
            "Move to Backlog",
            "Move \"${title ?: "this task"}\" to backlog? It will be removed from the planner.",
            "Move to Backlog",
        ) {
            io {
                runCatching { repository.unscheduleTask(taskId) }.fold(
                    onSuccess = {
                        if (_ui.value.tab == TaskTab.Sprint) loadSprint()
                        if (_ui.value.tab == TaskTab.Backlog) loadBacklog()
                        if (closeAfter) closeDetail()
                    },
                    onFailure = { fail("Failed to move task to backlog") },
                )
            }
        }
    }

    /** "Delete Item" ConfirmDialog. */
    fun requestDelete(task: Task) {
        confirm("Delete Item", "Are you sure you want to delete \"${task.title}\"? This cannot be undone.", "Delete", danger = true) {
            io {
                runCatching { repository.deleteTask(task.id) }.fold(
                    onSuccess = {
                        _ui.update { s ->
                            s.copy(
                                inlineComments = s.inlineComments?.takeIf { it.taskId != task.id },
                                detail = s.detail?.takeIf { it.id != task.id },
                            )
                        }
                        reloadTab()
                    },
                    onFailure = { fail("Failed to delete item") },
                )
            }
        }
    }

    // ── Detail (`useTaskDetail`) ─────────────────────────────────────────

    fun openDetail(task: Task) {
        _ui.update {
            it.copy(detail = task, detailLoading = true, detailComments = emptyList(), detailTab = DetailTab.Comments, history = emptyList(), detailEditing = false)
        }
        io {
            runCatching { repository.loadDetail(task.id) }.fold(
                onSuccess = { data -> _ui.update { it.copy(detail = data, detailComments = data.comments) } },
                onFailure = {
                    val comments = runCatching { repository.loadComments(task.id) }.getOrDefault(emptyList())
                    _ui.update { it.copy(detailComments = comments) }
                },
            )
            _ui.update { it.copy(detailLoading = false) }
            refreshHistory(task.id)
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
        it.copy(detail = null, detailComments = emptyList(), detailEditing = false, history = emptyList(), detailTab = DetailTab.Comments)
    }

    fun setDetailTab(tab: DetailTab) = _ui.update { it.copy(detailTab = tab) }

    fun startEdit() = _ui.update { it.copy(detailEditing = true) }

    fun cancelEdit() = _ui.update { it.copy(detailEditing = false) }

    /** Re-fetch the open detail after a panel change (blocker, parent…) and the board. */
    fun refreshDetail() {
        val id = _ui.value.detail?.id ?: return
        io {
            runCatching { repository.loadDetail(id) }.onSuccess { data ->
                _ui.update { if (it.detail?.id == id) it.copy(detail = data, detailComments = data.comments) else it }
            }
            refreshHistory(id)
            if (_ui.value.tab == TaskTab.Sprint) loadSprint()
        }
    }

    /** `saveDetailEdit`: confirm, `PUT /tasks/:id`, re-fetch. */
    fun saveEdit(draft: TaskEditDraft) {
        val task = _ui.value.detail ?: return
        confirm("Save Changes", "Save changes to \"${draft.title.ifEmpty { task.title }}\"?", "Save") {
            io {
                // Untouched text keeps the original rich HTML instead of flattening it.
                val description = if (draft.description == stripHtml(draft.descriptionHtml)) draft.descriptionHtml else plainTextToHtml(draft.description)
                val payload = UpdateTaskPayload(
                    title = draft.title,
                    description = description,
                    priority = draft.priority,
                    assignedTo = draft.assignedTo,
                    dueDate = draft.dueDate.ifEmpty { null },
                    sprintId = draft.sprintId,
                    labelIds = draft.labels,
                    storyPoints = draft.storyPoints?.ifEmpty { null },
                    workItemTypeId = draft.workItemTypeId,
                    projectId = draft.projectId,
                )
                runCatching { repository.updateTask(task.id, payload) }.fold(
                    onSuccess = {
                        _ui.update { it.copy(detailEditing = false) }
                        runCatching { repository.loadDetail(task.id) }.onSuccess { data ->
                            _ui.update { it.copy(detail = data, detailComments = data.comments) }
                        }
                        refreshHistory(task.id)
                        reloadTab()
                    },
                    onFailure = { fail("Failed to update item") },
                )
            }
        }
    }

    /** Detail "Move to:" buttons (`handleDetailStatusChange`). */
    fun detailStatus(task: Task, column: ColumnDef) {
        confirm("Change Status", "Change status of \"${task.title}\" to ${column.label}?", "Move") {
            io {
                runCatching { repository.updateStatus(task.id, column.id) }.fold(
                    onSuccess = {
                        _ui.update { s -> s.copy(detail = s.detail?.let { if (it.id == task.id) it.copy(status = column.id) else it }) }
                        refreshHistory(task.id)
                        reloadTab()
                    },
                    onFailure = { fail("Failed to update status") },
                )
            }
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
        s.copy(tasks = s.tasks.map { it.bump() }, backlog = s.backlog.map { it.bump() })
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
                    updateComments(taskId) { it + comment }
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

    companion object {
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
