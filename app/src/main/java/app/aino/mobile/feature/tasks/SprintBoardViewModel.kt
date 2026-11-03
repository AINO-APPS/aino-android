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
    val baseline: BaselineResponse? = null,
) {
    val selectedSprintId get() = planScope.selectedSprintId
    val currentSprint get() = planScope.currentSprint
    val role get() = planScope.role
    val agile get() = planScope.agile
    val selectedTeamId get() = planScope.selectedTeamId
    internal fun containsBoardTicket(ticket: Task): Boolean =
        ticket.container == "sprint" && ticket.sprintId == selectedSprintId && ticket.workflowStateKey != "cancelled"

    private fun inScope(ticket: Task): Boolean = when {
        !showsTeamPicker(role) -> planScope.teamId == null || ticket.teamId == planScope.teamId
        selectedTeamId == -1L -> ticket.teamId == null
        selectedTeamId != null -> ticket.teamId == selectedTeamId
        else -> true
    }

    private val boardTickets get() = boardTicketIds.mapNotNull(tickets::get)
        .filter { inScope(it) && containsBoardTicket(it) }
    val tasks get() = boardTickets.filter {
        matchesFilters(it, planScope.filters, planScope.userId, includeStatus = true) &&
            (planScope.selectedEpicId == null || it.parentTaskId == planScope.selectedEpicId)
    }
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
    private data class CachedBoard(val board: SprintBoardResponse?, val ids: List<Long>, val stats: SprintStats?, val baseline: BaselineResponse?)
    private val boards = mutableMapOf<Pair<Long?, Long?>, CachedBoard>()

    init {
        ticketScope.launch {
            ticketSession.planScope.collect { scope ->
                synchronized(ticketSession.planScopeLock) {
                    if (scope != ticketSession.planScope.value) return@synchronized
                    val previous = _ui.value
                    val changed = scope.selectedTeamId != previous.selectedTeamId || scope.selectedSprintId != previous.selectedSprintId
                    if (changed) {
                        sprintGeneration.incrementAndGet()
                        sprintJob?.cancel()
                        boards[previous.selectedTeamId to previous.selectedSprintId] =
                            CachedBoard(previous.board, previous.boardTicketIds, previous.sprintStats, previous.baseline)
                        val cached = boards[scope.selectedTeamId to scope.selectedSprintId]
                        _ui.update {
                            it.copy(
                                planScope = scope, board = cached?.board, boardTicketIds = cached?.ids.orEmpty(),
                                sprintStats = cached?.stats, baseline = cached?.baseline, completionPreview = null,
                                completing = false, lifecycleBusy = false, lifecycleError = null, error = null, confirm = null,
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
        val generation = sprintGeneration.incrementAndGet()
        sprintJob?.cancel()
        if (sprintId == null) {
            _ui.update { it.copy(boardTicketIds = emptyList(), board = null, sprintStats = null, sprintLoading = false) }
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
                    notify("Moved to ${state.name.ifEmpty { state.key }}", "Undo") { revertMove(previous, state) }
                },
                onFailure = {
                    if (ticketSession.store.restoreIfCurrent(optimistic, previous)) ticketSession.planRevision.incrementAndGet()
                    fail(failure(it, "Failed to move item"))
                },
            )
        }
    }

    private fun revertMove(previous: Task, from: WorkflowState) {
        val current = _ui.value.tasks.firstOrNull { it.id == previous.id } ?: previous
        applyTask(previous)
        io {
            runCatching { repository.updateStatus(previous.id, previous.workflowStateKey.orEmpty()) }.fold(
                onSuccess = { applyTask(it); loadSprint() },
                onFailure = { applyTask(current.copy(workflowStateKey = from.key)); fail(failure(it, "Failed to undo move")) },
            )
        }
    }

    private fun lifecycle(fallback: String, call: suspend (Long) -> Unit, done: String, rollover: Boolean = false) {
        val sprint = _ui.value.currentSprint ?: return
        val teamId = _ui.value.selectedTeamId
        if (_ui.value.lifecycleBusy) return
        val ids = ticketSession.store.state.value.tickets.values
            .filter { it.container == "sprint" && it.sprintId == sprint.id && it.workflowStateKey != "cancelled" }
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
        _ui.update { it.copy(completing = true, rolloverTo = "backlog", completionPreview = null, rolloverOptions = emptyList()) }
        io {
            val options = runCatching {
                repository.loadTeamSprints(sprint.teamId.takeIf { showsTeamPicker(_ui.value.role) }).sprints
            }.getOrDefault(emptyList()).filter { it.id != sprint.id && it.status != "completed" }
            _ui.update { if (it.selectedSprintId == sprint.id && it.completing) it.copy(rolloverOptions = options) else it }
            loadCompletionPreview(sprint.id)
        }
    }

    fun cancelComplete() = _ui.update { it.copy(completing = false, completionPreview = null) }
    fun setRollover(value: String) = _ui.update { it.copy(rolloverTo = value) }
    fun completeSprint() {
        val rollover = _ui.value.rolloverTo
        lifecycle("Failed to complete sprint", { repository.completeSprint(it, rollover) }, "Sprint completed", rollover = true)
    }

    fun loadCompletionPreview(sprintId: Long) {
        io {
            runCatching { repository.loadCompletionPreview(sprintId) }.onSuccess { preview ->
                _ui.update { if (it.selectedSprintId == sprintId && it.completing) it.copy(completionPreview = preview) else it }
            }
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
