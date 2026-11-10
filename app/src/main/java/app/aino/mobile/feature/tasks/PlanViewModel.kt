package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.isTransientFailure
import app.aino.mobile.core.network.userFacingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Activity-scoped Plan state; Tickets and shared context belong to the session. */
class PlanViewModel(
    private val repository: TaskRepository,
    private val ticketSession: TicketSession,
) : ViewModel() {
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(ticketSession.job))
    private val _ui = ticketSession.state(PlanUiState(planScope = ticketSession.planScope.value))
    val ui: StateFlow<PlanUiState> = _ui.asStateFlow()
    private val listActions = TicketListActions(repository, ticketSession, ticketScope,
        confirm = { request -> _ui.update { it.copy(confirm = request) } },
        fail = { error, message -> fail(failure(error, message)) },
    )
    fun assignSprint(task: Task, sprintId: Long?) = listActions.assignSprint(task, sprintId)
    fun requestDelete(task: Task) = listActions.requestDelete(task)
    private var backlogJob: Job? = null
    private var errorJob: Job? = null
    private val backlogGen = java.util.concurrent.atomic.AtomicInteger()
    private data class CachedPlan(
        val ids: List<Long>, val loaded: Boolean, val baseline: TicketPlanBaseline?,
        val offset: Int, val hasMore: Boolean,
    )
    private val teamViews = mutableMapOf<TeamScope, CachedPlan>()

    init {
        ticketScope.launch {
            ticketSession.planScope.collect { scope ->
                synchronized(ticketSession.planScopeLock) {
                    if (scope != ticketSession.planScope.value) return@synchronized
                    val previous = _ui.value
                    if (previous.userId != null && previous.userId != scope.userId) {
                        backlogGen.incrementAndGet()
                        backlogJob?.cancel()
                        teamViews.clear()
                        _ui.update { PlanUiState(planScope = scope) }
                    } else if (previous.teamScope != scope.teamScope) {
                        backlogGen.incrementAndGet()
                        backlogJob?.cancel()
                        teamViews[previous.teamScope] = CachedPlan(
                            previous.planTicketIds, previous.planLoaded, previous.planBaseline,
                            previous.planOffset, previous.planHasMore,
                        )
                        val cached = teamViews[scope.teamScope]
                        _ui.update {
                            it.copy(
                                planScope = scope, tickets = ticketSession.store.state.value.tickets,
                                planTicketIds = cached?.ids.orEmpty(), planLoaded = cached?.loaded ?: false,
                                planBaseline = cached?.baseline, planOffset = cached?.offset ?: 0,
                                planHasMore = cached?.hasMore ?: false, backlogLoading = false,
                                selectedIds = emptySet(), planOpen = false, backlogLoadingMore = false,
                                error = null, confirm = null,
                            ).projectPlan()
                        }
                    } else _ui.update { it.copy(planScope = scope).projectPlan() }
                }
            }
        }
        ticketScope.launch {
            ticketSession.store.state.collect { snapshot ->
                _ui.update { current ->
                    val changed = snapshot.tickets.values.filter { it !== current.tickets[it.id] }
                    current.copy(
                        tickets = snapshot.tickets,
                        recentlyUpdated = snapshot.recentlyChanged,
                        planTicketIds = (current.planTicketIds.filter(snapshot.tickets::containsKey) +
                            changed.filter(current::containsPlanTicket).map { it.id }).distinct(),
                    ).projectPlan()
                }
            }
        }
        ticketScope.launch {
            ticketSession.planRequests.collect { request ->
                when (request) {
                    PlanRequest.PrepareCreate -> prepareCreate()
                    PlanRequest.Load -> loadBacklog()
                    PlanRequest.PrepareImport -> if (_ui.value.backlog.isEmpty()) loadBacklog()
                    PlanRequest.EnsureLoaded -> if (!_ui.value.planLoaded && !_ui.value.backlogLoading) loadBacklog()
                    PlanRequest.ReloadLoaded -> if (_ui.value.planLoaded) loadBacklog()
                    PlanRequest.Close -> closeSprintPlan()
                    PlanRequest.ClearFilters -> setQuickFilters(BacklogQuickFilters())
                    PlanRequest.Epics -> loadEpics()
                    PlanRequest.CancelImport -> cancelImportConfig()
                    is PlanRequest.CreateForColumn -> openCreateForColumn(request.state)
                }
            }
        }
        ticketScope.launch {
            ticketSession.planRefreshes.collect { if (_ui.value.planLoaded) loadBacklog() }
        }
        ticketSession.onClose { synchronized(ticketSession.planScopeLock) { teamViews.clear() } }
    }

    private fun io(block: suspend () -> Unit) = ticketScope.launch(Dispatchers.IO) { block() }
    private fun notify(text: String) = ticketSession.publishNotice(TaskNotice(text))
    private fun failure(e: Throwable, fallback: String): String {
        val message = if (e is ApiError) userFacingMessage(e, fallback)
        else e.message?.takeIf { it.isNotBlank() && it != "Task action failed" && !it.startsWith("HTTP ") } ?: fallback
        return capabilityFailureCopy(message) ?: message
    }
    private fun fail(message: String) {
        _ui.update { it.copy(error = message) }
        errorJob?.cancel()
        errorJob = ticketScope.launch {
            delay(5_000)
            _ui.update { if (it.error == message) it.copy(error = null) else it }
        }
    }
    private fun loadFailed(e: Throwable, hasData: Boolean, message: String) {
        if (hasData && isTransientFailure(e)) _ui.update { if (it.error == message) it.copy(error = null) else it }
        else fail(message)
    }
    private fun applyTicket(ticket: Task, highlight: Boolean = false): Task {
        ticketSession.planRevision.incrementAndGet()
        val saved = ticketSession.store.upsert(ticket)
        if (highlight) ticketSession.store.highlight(saved.id)
        return saved
    }

    fun clearError() = _ui.update { it.copy(error = null) }
    fun acceptConfirm() {
        val request = _ui.value.confirm ?: return
        dismissConfirm()
        request.action()
    }
    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    fun loadBacklog() {
        backlogJob?.cancel()
        _ui.update { it.copy(backlogLoading = it.backlog.isEmpty(), backlogLoadingMore = false) }
        val gen = backlogGen.incrementAndGet()
        val s = _ui.value
        backlogJob = io {
            val revision = ticketSession.planRevision.get()
            runCatching { repository.loadPlanPage(500, 0, s.teamScope) }.fold(
                onSuccess = { page ->
                    if (gen != backlogGen.get() || s.userId != _ui.value.userId || s.teamScope != _ui.value.teamScope) return@fold
                    if (revision != ticketSession.planRevision.get()) {
                        loadBacklog()
                        return@fold
                    }
                    ticketSession.store.upsert(page.tasks)
                    _ui.update {
                        val next = it.copy(
                            tickets = ticketSession.store.state.value.tickets,
                            planTicketIds = page.tasks.map { ticket -> ticket.id },
                            planLoaded = true, backlogLoading = false,
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
                            planHasMore = page.pagination?.let { it.hasMore || offset < it.total } == true && page.tasks.isNotEmpty(),
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
    fun setQuickFilters(filters: BacklogQuickFilters) {
        _ui.update { it.copy(quickFilters = filters).projectPlan() }
        if (!_ui.value.planLoaded && !_ui.value.backlogLoading) loadBacklog()
    }
    fun updateDraft(transform: (BacklogDraft) -> BacklogDraft) = _ui.update { it.copy(draft = transform(it.draft)) }
    private fun prepareCreate() {
        val scope = ticketSession.planScope.value
        if (!scope.projectScoped) return
        _ui.update { state ->
            val projectId = scope.selectedProjectId?.takeIf { id -> scope.createProjects.any { it.id == id } }
            state.copy(draft = state.draft.copy(projectId = projectId))
        }
    }

    fun setDraftProject(projectId: Long?) = _ui.update { state ->
        state.copy(draft = state.draft.copy(
            projectId = projectId, sprintId = null, workflowStateId = null,
        ))
    }
    fun setDraftSprint(id: Long?) = _ui.update { s ->
        val end = id?.let { wanted -> s.sprints.firstOrNull { it.id == wanted }?.endDate?.take(10) }
        s.copy(draft = s.draft.copy(sprintId = id, dueDate = if (id == null) "" else end ?: s.draft.dueDate))
    }

    fun submitBacklog() {
        val s = _ui.value
        val draft = s.draft
        if (draft.title.isBlank() || s.creating) return
        if (s.planScope.projectScoped && s.planScope.createProjects.none { it.id == draft.projectId }) {
            fail("Choose a project where you can create tickets.")
            return
        }
        if (s.planScope.projectScoped && draft.sprintId != null &&
            s.sprints.none { it.id == draft.sprintId && it.projectId == draft.projectId }
        ) {
            fail(capabilityReasonCopy("SPRINT_PROJECT_MISMATCH"))
            return
        }
        _ui.update { it.copy(creating = true) }
        io {
            val payload = CreateBacklogPayload(
                title = draft.title.trim(), description = plainTextToHtml(draft.description),
                priority = draft.priority, teamId = s.selectedTeamId?.takeIf { !s.planScope.projectScoped && it > 0 },
                assignedTo = draft.assignedTo, dueDate = draft.dueDate.ifEmpty { null },
                labelIds = draft.labels.ifEmpty { null }, sprintId = draft.sprintId,
                storyPoints = draft.storyPoints, workItemTypeId = draft.workItemTypeId,
                projectId = draft.projectId, workflowStateId = draft.workflowStateId,
            )
            runCatching { repository.createBacklogTask(payload) }.fold(
                onSuccess = { created ->
                    applyTicket(created)
                    _ui.update { it.copy(draft = BacklogDraft(), creating = false) }
                    ticketSession.requestShell(ShellRequest.CloseSheet)
                    ticketSession.publishNotice(TaskNotice("Created ${created.displayKey}", "Open", openTicketId = created.id))
                    if (created.sprintId != null) ticketSession.requestBoard(BoardRequest.Load)
                },
                onFailure = {
                    _ui.update { it.copy(creating = false) }
                    fail(failure(it, "Failed to create task"))
                },
            )
        }
    }

    fun openCreateForColumn(state: WorkflowState) {
        val s = _ui.value
        val sprintId = s.selectedSprintId ?: return
        _ui.update {
            it.copy(draft = BacklogDraft(
                sprintId = sprintId, workflowStateId = state.id.takeIf { id -> id != 0L },
                dueDate = s.sprints.firstOrNull { sp -> sp.id == sprintId }?.endDate?.take(10).orEmpty(),
            ))
        }
        ticketSession.requestShell(ShellRequest.OpenSheet(TaskSheet.CreateTicket))
    }

    fun openSprintPlan() {
        val s = _ui.value
        val targetId = planTargetId(s.teamSprints, s.currentSprintId)
        if (targetId == null) {
            notify(if (canManageSprint(s.role)) "No sprint yet — create one in Admin → Teams"
            else "No sprint yet — an admin can create one in Admin → Teams")
            return
        }
        _ui.update { it.copy(planOpen = true) }
        ticketSession.requestShell(ShellRequest.SelectSprint(targetId))
        if (s.backlog.isEmpty()) loadBacklog()
        ticketSession.requestBoard(BoardRequest.Baseline(targetId))
    }
    fun closeSprintPlan() = _ui.update { it.copy(planOpen = false) }
    fun saveSprintGoal(sprintId: Long, goal: String) {
        val trimmed = goal.trim()
        io {
            runCatching { repository.updateSprintGoal(sprintId, trimmed) }.fold(
                onSuccess = { updated ->
                    ticketSession.requestShell(ShellRequest.SprintGoal(sprintId, updated.sprint?.goal ?: trimmed))
                },
                onFailure = { fail(failure(it, "Could not save the sprint goal")) },
            )
        }
    }

    fun configureImport(task: Task) = _ui.update { s ->
        s.copy(importTaskId = task.id, importAssignedTo = task.assignedTo,
            importDueDate = s.currentSprint?.endDate?.take(10) ?: task.dueDate?.take(10).orEmpty())
    }
    fun cancelImportConfig() = _ui.update { it.copy(importTaskId = null, importAssignedTo = null, importDueDate = "") }
    fun setImportAssignee(id: Long?) = _ui.update { it.copy(importAssignedTo = id) }
    fun setImportDueDate(date: String) = _ui.update { it.copy(importDueDate = date) }
    fun confirmImport() {
        val s = _ui.value
        val taskId = s.importTaskId ?: return
        val sprintId = s.selectedSprintId ?: return
        io {
            runCatching {
                applyTicket(repository.assignSprint(taskId, sprintId), highlight = true)
                if (s.importAssignedTo != null || s.importDueDate.isNotEmpty()) {
                    applyTicket(repository.updateImportFields(taskId, ImportUpdatePayload(s.importAssignedTo, s.importDueDate.ifEmpty { null })))
                }
            }.fold(
                onSuccess = {
                    cancelImportConfig()
                    notify("Added to ${s.currentSprint?.name ?: "sprint"}")
                    ticketSession.requestBoard(BoardRequest.Load)
                },
                onFailure = { fail("Failed to import task to sprint") },
            )
        }
    }

    fun toggleCompletedSection() {
        if (_ui.value.hasSelection) return
        val open = !_ui.value.completedOpen
        _ui.update { it.copy(completedOpen = open) }
        if (open && _ui.value.completed.isEmpty()) loadCompleted()
    }
    fun setShowCompleted(show: Boolean) {
        _ui.update { it.copy(completedOpen = show) }
        ticketSession.requestShell(ShellRequest.ShowCompleted(show))
        if (show) loadCompleted()
    }
    fun loadCompleted(all: Boolean = false) {
        _ui.update { it.copy(completedShowingAll = all).projectPlan() }
        if (!_ui.value.planLoaded && !_ui.value.backlogLoading) loadBacklog()
    }
    fun toggleSelection(id: Long) = _ui.update {
        it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
    }
    fun clearSelection() = _ui.update { it.copy(selectedIds = emptySet()) }

    fun bulkAction(action: String, value: String? = null) {
        val ids = _ui.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val denied = ids.asSequence().map { id ->
            val ticket = ticketSession.store.state.value.tickets[id] ?: return@map TaskCapabilityDecision()
            when (action) {
                "delete" -> ticket.capability(TaskCapability.Cancel)
                "move_to_sprint" -> ticket.capability(TaskCapability.PlaceInSprint)
                "assign" -> ticket.capability(TaskCapability.Edit)
                "status" -> _ui.value.agile.workflowStates.firstOrNull { it.key == value }
                    ?.let { ticket.dropTargetDecision(it.id, accepting = true) } ?: TaskCapabilityDecision()
                else -> TaskCapabilityDecision()
            }
        }.firstOrNull { !it.allowed }
        if (denied != null) return notify(denied.reasonCopy())
        io {
            runCatching { repository.bulkAction(BulkPayload(ids, action, value)) }.fold(
                onSuccess = { result ->
                    ticketSession.planRevision.incrementAndGet()
                    val changedIds = if (result.results.isNotEmpty()) {
                        result.results.filter { it.ok }.map { it.id }.distinct()
                    } else if (result.changed > 0) ids else emptyList()
                    var refreshFailed = false
                    val saved = changedIds.mapNotNull { id ->
                        runCatching { repository.loadDetail(id) }.getOrElse {
                            refreshFailed = true
                            if (action == "delete") ticketSession.store.markCancelled(id, null)
                            null
                        }
                    }
                    if (action == "delete") saved.forEach { ticketSession.store.markCancelled(it.id, it) }
                    else ticketSession.store.upsert(saved)
                    clearSelection()
                    notify(bulkSummary(result))
                    loadBacklog()
                    if (refreshFailed) {
                        fail("${bulkSummary(result)}; could not refresh all updated tickets. Refresh to retry.")
                        ticketSession.onReconnect()
                    }
                },
                onFailure = { fail(failure(it, "Bulk action failed")) },
            )
        }
    }
    private fun bulkSummary(result: BulkResult): String {
        if (result.failed == 0) return "${result.changed} updated"
        val first = result.failures.firstOrNull()?.error?.let { capabilityFailureCopy(it) ?: it }
        return if (first.isNullOrBlank()) "${result.changed} updated, ${result.failed} skipped"
        else "${result.changed} updated, ${result.failed} skipped — $first"
    }
    fun confirmBulkDelete() {
        val count = _ui.value.selectedIds.size
        if (count == 0) return
        _ui.update { it.copy(confirm = ConfirmRequest(
            "Cancel $count ${if (count == 1) "ticket" else "tickets"}?",
            "They keep their history and leave the open work.", "Cancel tickets", true,
        ) { bulkAction("delete") }) }
    }
    fun reorder(taskId: Long, beforeId: Long?, afterId: Long?) {
        val decision = ticketSession.store.state.value.tickets[taskId]?.capability(TaskCapability.Edit) ?: TaskCapabilityDecision()
        if (!decision.allowed) return notify(decision.reasonCopy())
        io {
            runCatching { repository.rankTask(taskId, beforeId, afterId) }.fold(
                onSuccess = { applyTicket(it) },
                onFailure = { fail(failure(it, "Failed to reorder")) },
            )
        }
    }
    fun loadEpics() {
        io { runCatching { repository.loadEpics() }.onSuccess { list -> _ui.update { it.copy(epics = list) } } }
    }
    override fun onCleared() { ticketScope.cancel() }

    companion object {
        fun factory(context: Context, ticketSession: TicketSession): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val container = app.aino.mobile.core.AppContainer.get(context)
                    return PlanViewModel(TaskRepository(container.api), ticketSession) as T
                }
            }
    }
}
