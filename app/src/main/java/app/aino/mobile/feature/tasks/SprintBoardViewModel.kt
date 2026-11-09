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

data class SprintBoardUiState(
    val planScope: PlanScope = PlanScope(),
    val tickets: Map<Long, Task> = emptyMap(),
    val boardTicketIds: List<Long> = emptyList(),
    /** The selected sprint's cancelled Tickets: history under the board, never a column. */
    val cancelledTicketIds: List<Long> = emptyList(),
    val board: SprintBoardResponse? = null,
    val sprintLoading: Boolean = true,
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
    val recentlyUpdated: Set<Long> = emptySet(),
    val sprintStats: SprintStats? = null,
    val lifecycleBusy: Boolean = false,
    val lifecycleError: String? = null,
    val completing: Boolean = false,
    val rolloverOptions: List<AvailableSprint> = emptyList(),
    val rolloverTo: String = "backlog",
    val completionPreview: CompletionPreview? = null,
    val completionPreviewLoading: Boolean = false,
    val completionPreviewError: String? = null,
    val baseline: BaselineResponse? = null,
) {
    val canCompleteSprint: Boolean get() = completing && completionPreview != null &&
        !completionPreviewLoading && completionPreviewError == null && !lifecycleBusy
    val selectedSprintId get() = planScope.selectedSprintId
    val currentSprint get() = planScope.currentSprint
    val role get() = planScope.role
    val agile get() = planScope.agile
    val selectedTeamId get() = planScope.selectedTeamId
    internal fun containsBoardTicket(ticket: Task): Boolean =
        ticket.container == "sprint" && ticket.sprintId == selectedSprintId && !ticket.isCancelled
    internal fun containsCancelledTicket(ticket: Task): Boolean =
        ticket.container == "sprint" && ticket.sprintId == selectedSprintId && ticket.isCancelled

    private fun inScope(ticket: Task): Boolean = planScope.contains(ticket)

    private val boardTickets get() = boardTicketIds.mapNotNull(tickets::get)
        .filter { inScope(it) && containsBoardTicket(it) }
    val tasks get() = boardTickets.filter {
        matchesFilters(it, planScope.filters, planScope.userId, includeStatus = true) &&
            (planScope.selectedEpicId == null || it.parentTaskId == planScope.selectedEpicId)
    }
    val cancelled get() = cancelledTicketIds.mapNotNull(tickets::get).filter { inScope(it) && containsCancelledTicket(it) }
    val sprintBoard get() = board?.takeIf { it.sprint.id == selectedSprintId }?.copy(tickets = boardTickets)
    val stats get() = recomputeStats(tasks)
}

/** Activity-scoped Board state; shared Ticket and Plan context belong to the session. */
class SprintBoardViewModel(
    private val repository: TaskRepository,
    private val ticketSession: TicketSession,
) : ViewModel() {
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(ticketSession.job))
    private val _ui = ticketSession.state(SprintBoardUiState(planScope = ticketSession.planScope.value))
    val ui: StateFlow<SprintBoardUiState> = _ui.asStateFlow()
    private val listActions = TicketListActions(repository, ticketSession, ticketScope,
        confirm = { request -> _ui.update { it.copy(confirm = request) } },
        fail = { error, message -> fail(failure(error, message)) },
    )
    fun requestDelete(task: Task) = listActions.requestDelete(task)
    fun openCreateForColumn(state: WorkflowState) = ticketSession.requestPlan(PlanRequest.CreateForColumn(state))
    fun acceptConfirm() {
        val request = _ui.value.confirm ?: return
        dismissConfirm()
        request.action()
    }
    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }
    private var sprintJob: Job? = null
    private var errorJob: Job? = null
    private val sprintGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val completionGeneration = java.util.concurrent.atomic.AtomicInteger()
    private data class CachedBoard(
        val board: SprintBoardResponse?, val ids: List<Long>, val stats: SprintStats?, val baseline: BaselineResponse?,
        val cancelledIds: List<Long> = emptyList(),
    )
    private val boards = mutableMapOf<Pair<TeamScope, Long?>, CachedBoard>()

    init {
        ticketScope.launch {
            ticketSession.planScope.collect { scope ->
                synchronized(ticketSession.planScopeLock) {
                    if (scope != ticketSession.planScope.value) return@synchronized
                    val previous = _ui.value
                    val changed = scope.teamScope != previous.planScope.teamScope || scope.selectedSprintId != previous.selectedSprintId
                    if (changed) {
                        sprintGeneration.incrementAndGet()
                        completionGeneration.incrementAndGet()
                        sprintJob?.cancel()
                        boards[previous.planScope.teamScope to previous.selectedSprintId] = CachedBoard(
                            previous.board, previous.boardTicketIds, previous.sprintStats, previous.baseline, previous.cancelledTicketIds,
                        )
                        val cached = boards[scope.teamScope to scope.selectedSprintId]
                        _ui.update {
                            it.copy(
                                planScope = scope, board = cached?.board, boardTicketIds = cached?.ids.orEmpty(),
                                cancelledTicketIds = cached?.cancelledIds.orEmpty(),
                                sprintStats = cached?.stats, baseline = cached?.baseline, completionPreview = null,
                                completing = false, completionPreviewLoading = false, completionPreviewError = null,
                                lifecycleBusy = false, lifecycleError = null, error = null, confirm = null,
                                sprintLoading = scope.selectedSprintId != null && cached == null,
                            )
                        }
                    } else _ui.update { it.copy(planScope = scope) }
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
                        boardTicketIds = (current.boardTicketIds.filter(snapshot.tickets::containsKey) +
                            changed.filter(current::containsBoardTicket).map { it.id }).distinct(),
                        cancelledTicketIds = (current.cancelledTicketIds.filter(snapshot.tickets::containsKey) +
                            changed.filter(current::containsCancelledTicket).map { it.id }).distinct(),
                    )
                }
            }
        }
        ticketScope.launch {
            ticketSession.boardRequests.collect { request ->
                when (request) {
                    BoardRequest.Load -> loadSprint()
                    BoardRequest.ReloadLoaded -> if (_ui.value.board != null) loadSprint()
                    BoardRequest.Start -> startSprint()
                    is BoardRequest.Baseline -> loadBaseline(request.sprintId)
                }
            }
        }
        ticketScope.launch {
            ticketSession.sync(repository).changes.collect { change ->
                if (change is TicketSyncChange.Updated && change.refreshBoard && _ui.value.board != null) loadSprint()
            }
        }
        ticketSession.onClose { synchronized(ticketSession.planScopeLock) { boards.clear() } }
    }

    private fun io(block: suspend () -> Unit) = ticketScope.launch(Dispatchers.IO) { block() }
    private fun notify(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) =
        ticketSession.publishNotice(TaskNotice(text, actionLabel, action))

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

    private fun applyTask(ticket: Task): Task {
        ticketSession.planRevision.incrementAndGet()
        return ticketSession.store.upsert(ticket)
    }

    fun loadSprint() {
        val sprintId = _ui.value.selectedSprintId
        val teamId = _ui.value.selectedTeamId
        val teamScope = _ui.value.planScope.teamScope
        val generation = sprintGeneration.incrementAndGet()
        sprintJob?.cancel()
        if (sprintId == null) {
            _ui.update { it.copy(boardTicketIds = emptyList(), cancelledTicketIds = emptyList(), board = null, sprintStats = null, sprintLoading = false) }
            return
        }
        _ui.update { it.copy(sprintLoading = it.tasks.isEmpty()) }
        sprintJob = io {
            runCatching { repository.loadSprintBoard(sprintId) }.fold(
                onSuccess = { result ->
                    if (sprintGeneration.get() != generation || _ui.value.selectedSprintId != sprintId || _ui.value.selectedTeamId != teamId) return@io
                    ticketSession.store.upsert(result.tickets)
                    _ui.update {
                        if (it.selectedSprintId == sprintId) it.copy(
                            board = result.copy(tickets = emptyList()),
                            boardTicketIds = result.tickets.map { ticket -> ticket.id },
                            sprintLoading = false,
                        ) else it
                    }
                },
                onFailure = {
                    if (sprintGeneration.get() == generation && _ui.value.selectedSprintId == sprintId && _ui.value.selectedTeamId == teamId) {
                        _ui.update { it.copy(sprintLoading = false) }
                        if (_ui.value.tasks.isNotEmpty() && isTransientFailure(it)) {
                            _ui.update { if (it.error == "Failed to load tasks") it.copy(error = null) else it }
                        } else fail("Failed to load tasks")
                    }
                },
            )
            // The board endpoint drops cancelled Tickets; fetch them as history. A failure
            // keeps whatever cancelled rows are already known rather than blanking them.
            runCatching { repository.loadCancelledSprintTasks(sprintId, teamScope) }.onSuccess { cancelled ->
                if (sprintGeneration.get() != generation || _ui.value.selectedSprintId != sprintId || _ui.value.selectedTeamId != teamId) return@onSuccess
                val rows = cancelled.filter { it.isCancelled }
                ticketSession.store.upsert(rows)
                _ui.update {
                    if (it.selectedSprintId == sprintId) it.copy(
                        cancelledTicketIds = (rows.map { row -> row.id } + it.cancelledTicketIds).distinct(),
                    ) else it
                }
            }.onFailure {
                if (sprintGeneration.get() == generation) fail(failure(it, "Failed to load cancelled tickets"))
            }
            val stats = runCatching { repository.loadSprintStats(sprintId) }.getOrNull() ?: return@io
            _ui.update {
                if (sprintGeneration.get() == generation && it.selectedSprintId == sprintId && it.selectedTeamId == teamId) {
                    it.copy(sprintStats = stats)
                } else it
            }
        }
    }

    fun moveTask(task: Task, state: WorkflowState) {
        val previous = ticketSession.store.state.value.tickets[task.id] ?: task.copy(comments = emptyList())
        if (previous.workflowStateKey == state.key || (state.id != 0L && previous.workflowStateId == state.id)) return
        val accepting = _ui.value.sprintBoard?.columns?.firstOrNull { it.stateId == state.id }?.accepting ?: false
        val decision = previous.dropTargetDecision(state.id, accepting)
        if (!decision.allowed) return notify(decision.reasonCopy())
        val optimistic = applyTask(previous.copy(
            workflowStateKey = state.key,
            workflowStateId = state.id.takeIf { it != 0L } ?: previous.workflowStateId,
        ))
        io {
            runCatching { repository.updateStatus(task.id, state.key) }.fold(
                onSuccess = { saved ->
                    applyTask(saved)
                    loadSprint()
                    notify("Moved to ${state.name.ifEmpty { state.key }}", "Undo") { revertMove(previous) }
                },
                onFailure = {
                    if (ticketSession.store.restoreIfCurrent(optimistic, previous)) ticketSession.planRevision.incrementAndGet()
                    fail(failure(it, "Failed to move item"))
                },
            )
        }
    }

    private fun revertMove(previous: Task) {
        val current = ticketSession.store.state.value.tickets[previous.id] ?: return
        val optimistic = applyTask(current.copy(
            workflowStateKey = previous.workflowStateKey,
            workflowStateId = previous.workflowStateId,
        ))
        io {
            runCatching { repository.updateStatus(previous.id, previous.workflowStateKey.orEmpty()) }.fold(
                onSuccess = { applyTask(it); loadSprint() },
                onFailure = {
                    if (ticketSession.store.restoreIfCurrent(optimistic, current)) ticketSession.planRevision.incrementAndGet()
                    fail(failure(it, "Failed to undo move"))
                },
            )
        }
    }

    private fun lifecycle(fallback: String, call: suspend (Long) -> Unit, done: String, rollover: Boolean = false) {
        val sprint = _ui.value.currentSprint ?: return
        val teamId = _ui.value.selectedTeamId
        if (_ui.value.lifecycleBusy) return
        val ids = ticketSession.store.state.value.tickets.values
            .filter { it.container == "sprint" && it.sprintId == sprint.id }
            .map { it.id }
        _ui.update { it.copy(lifecycleBusy = true, lifecycleError = null) }
        io {
            runCatching { call(sprint.id) }.fold(
                onSuccess = {
                    var refetchFailed = false
                    // Completion returns no Tickets. Re-read the enriched rows so
                    // rollover also changes Placement in already-open views.
                    if (rollover) ids.forEach { id ->
                        runCatching { applyTask(repository.loadDetail(id)) }.onFailure { refetchFailed = true }
                    }
                    _ui.update {
                        if (it.selectedSprintId == sprint.id && it.selectedTeamId == teamId) {
                            it.copy(completing = false, completionPreview = null)
                        } else it
                    }
                    notify(done)
                    ticketSession.requestPlanRefresh()
                    loadSprint()
                    if (refetchFailed) fail("Could not refresh carried-over tickets")
                },
                onFailure = { e ->
                    _ui.update {
                        if (it.selectedSprintId == sprint.id && it.selectedTeamId == teamId) {
                            it.copy(lifecycleError = failure(e, fallback))
                        } else it
                    }
                },
            )
            _ui.update {
                if (it.selectedSprintId == sprint.id && it.selectedTeamId == teamId) it.copy(lifecycleBusy = false) else it
            }
        }
    }

    fun startSprint() = lifecycle("Failed to start sprint", { repository.startSprint(it) }, "Sprint started")
    fun pauseSprint() = lifecycle("Failed to pause sprint", { repository.pauseSprint(it) }, "Sprint paused")
    fun resumeSprint() = lifecycle("Failed to resume sprint", { repository.resumeSprint(it) }, "Sprint resumed")

    fun beginComplete() {
        val sprint = _ui.value.currentSprint ?: return
        if (_ui.value.lifecycleBusy) return
        _ui.update { it.copy(
            completing = true, rolloverTo = "backlog", completionPreview = null,
            rolloverOptions = emptyList(), lifecycleError = null,
        ) }
        loadCompletionPreview(sprint.id)
    }

    fun cancelComplete() {
        if (_ui.value.lifecycleBusy) return
        completionGeneration.incrementAndGet()
        _ui.update { it.copy(
            completing = false, completionPreview = null,
            completionPreviewLoading = false, completionPreviewError = null,
        ) }
    }

    fun setRollover(value: String) {
        val state = _ui.value
        val preview = state.completionPreview ?: return
        if (!state.completing || state.lifecycleBusy || value == state.rolloverTo) return
        val supported = preview.supportsNewSprint == true
        val allowed = if (supported) {
            value == "new" || value == "backlog" || (value == "next" && state.rolloverOptions.isNotEmpty())
        } else value == "backlog" || value == preview.defaultTarget
        if (!allowed) return
        _ui.update { it.copy(rolloverTo = value, lifecycleError = null) }
        if (supported) fetchCompletionPreview(state.selectedSprintId ?: return, value)
    }

    fun completeSprint() {
        val state = _ui.value
        if (!state.canCompleteSprint) return
        val rollover = state.rolloverTo
        lifecycle("Failed to complete sprint", { repository.completeSprint(it, rollover) }, "Sprint completed", rollover = true)
    }

    fun retryCompletionPreview() {
        val state = _ui.value
        val sprintId = state.selectedSprintId ?: return
        if (state.completionPreview?.supportsNewSprint == true) fetchCompletionPreview(sprintId, state.rolloverTo)
        else loadCompletionPreview(sprintId)
    }

    fun loadCompletionPreview(sprintId: Long) = fetchCompletionPreview(sprintId, null)

    private fun fetchCompletionPreview(sprintId: Long, target: String?) {
        val state = _ui.value
        if (!state.completing || state.lifecycleBusy || state.selectedSprintId != sprintId) return
        val scope = state.planScope.teamScope
        val generation = completionGeneration.incrementAndGet()
        _ui.update { it.copy(completionPreviewLoading = true, completionPreviewError = null) }
        io {
            runCatching {
                repository.loadCompletionPreview(sprintId, target).also { preview ->
                    check(preview.defaultTarget != null) { "Sprint completion preview is unavailable" }
                    check(preview.sprint == null || preview.sprint.id == sprintId) { "Sprint completion preview changed. Please retry." }
                    if (target != null) check(preview.supportsNewSprint == true && preview.selectedTarget == target) {
                        "Sprint completion preview changed. Please retry."
                    }
                }
            }.fold(
                onSuccess = { preview ->
                    _ui.update { current ->
                        if (completionGeneration.get() != generation || current.selectedSprintId != sprintId ||
                            current.planScope.teamScope != scope || !current.completing) current
                        else {
                            val next = preview.nextPlannedSprint ?: preview.defaultTarget?.toLongOrNull()?.let { id ->
                                current.planScope.sprints.firstOrNull { it.id == id }
                                    ?.let { CompletionPreviewSprint(it.id, it.name, it.status) }
                                    ?: CompletionPreviewSprint(id, "Sprint $id", "planned")
                            }
                            val selected = target ?: if (preview.supportsNewSprint == true) {
                                preview.defaultTargetType ?: if (next != null) "next" else "backlog"
                            } else preview.defaultTarget!!
                            current.copy(
                                completionPreview = preview, rolloverTo = selected,
                                rolloverOptions = listOfNotNull(next?.let {
                                    AvailableSprint(it.id, it.name, status = "planned", projectId = current.planScope.selectedProjectId)
                                }),
                                completionPreviewLoading = false, completionPreviewError = null,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _ui.update { current ->
                        if (completionGeneration.get() == generation && current.selectedSprintId == sprintId &&
                            current.planScope.teamScope == scope && current.completing) {
                            current.copy(completionPreviewLoading = false, completionPreviewError = failure(error, "Failed to load completion preview"))
                        } else current
                    }
                },
            )
        }
    }

    fun loadBaseline(sprintId: Long) {
        io {
            runCatching { repository.loadBaseline(sprintId) }.onSuccess { baseline ->
                _ui.update { if (it.selectedSprintId == sprintId) it.copy(baseline = baseline) else it }
            }
        }
    }

    override fun onCleared() {
        ticketScope.cancel()
    }

    companion object {
        fun factory(context: Context, session: TicketSession): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SprintBoardViewModel(TaskRepository(app.aino.mobile.core.AppContainer.get(context).api), session) as T
            }
    }
}
