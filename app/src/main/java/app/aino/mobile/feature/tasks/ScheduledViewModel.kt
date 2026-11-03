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

/** Activity-scoped Scheduled state, reading shared context from the session. */
class ScheduledViewModel(
    private val repository: TaskRepository,
    private val ticketSession: TicketSession,
) : ViewModel() {
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(ticketSession.job))
    private val _ui = ticketSession.state(ScheduledUiState(planScope = ticketSession.planScope.value))
    val ui: StateFlow<ScheduledUiState> = _ui.asStateFlow()
    private val listActions = TicketListActions(repository, ticketSession, ticketScope,
        confirm = { request -> _ui.update { it.copy(confirm = request) } },
        fail = { error, message -> fail(failure(error, message)) },
    )
    fun requestDelete(task: Task) = listActions.requestDelete(task)
    private var scheduledJob: Job? = null
    private var errorJob: Job? = null
    private var carriedOn: String? = null
    private val scheduledGen = java.util.concurrent.atomic.AtomicInteger()
    private data class CachedScheduled(val ids: List<Long>, val loaded: Boolean)
    private val teamViews = mutableMapOf<TeamScope, CachedScheduled>()

    init {
        ticketScope.launch {
            ticketSession.planScope.collect { scope ->
                synchronized(ticketSession.planScopeLock) {
                    if (scope != ticketSession.planScope.value) return@synchronized
                    val previous = _ui.value
                    if (previous.userId != null && previous.userId != scope.userId) {
                        scheduledGen.incrementAndGet()
                        scheduledJob?.cancel()
                        teamViews.clear()
                        _ui.update { ScheduledUiState(planScope = scope) }
                    } else if (previous.teamScope != scope.teamScope) {
                        scheduledGen.incrementAndGet()
                        scheduledJob?.cancel()
                        teamViews[previous.teamScope] = CachedScheduled(previous.scheduledTicketIds, previous.scheduledLoaded)
                        val cached = teamViews[scope.teamScope]
                        _ui.update {
                            it.copy(
                                planScope = scope, tickets = ticketSession.store.state.value.tickets,
                                scheduledTicketIds = cached?.ids.orEmpty(), scheduledLoaded = cached?.loaded ?: false,
                                scheduledLoading = false, overlays = ScheduleOverlays(), error = null, confirm = null,
                            )
                        }
                    } else {
                        if (previous.filters != scope.filters) {
                            scheduledGen.incrementAndGet()
                            scheduledJob?.cancel()
                        }
                        _ui.update { it.copy(planScope = scope) }
                    }
                }
            }
        }
        ticketScope.launch {
            ticketSession.store.state.collect { snapshot ->
                _ui.update { current ->
                    val changed = snapshot.tickets.values.filter { it !== current.tickets[it.id] }
                    val retained = current.scheduledTicketIds.filter(snapshot.tickets::containsKey)
                    val joined = if (current.scheduledLoaded) changed.filter(current::containsScheduledTicket).map { it.id }
                    else emptyList()
                    current.copy(
                        tickets = snapshot.tickets,
                        recentlyUpdated = snapshot.recentlyChanged,
                        scheduledTicketIds = (retained + joined).distinct(),
                    )
                }
            }
        }
        ticketScope.launch {
            ticketSession.scheduledRequests.collect { request ->
                when (request) {
                    ScheduledRequest.CarryForward -> carryForward()
                    ScheduledRequest.Load -> loadScheduled()
                    ScheduledRequest.ReloadLoaded -> if (_ui.value.scheduledLoaded) loadScheduled()
                    is ScheduledRequest.Show -> showScheduled(request.date)
                }
            }
        }
        ticketSession.onClose { synchronized(ticketSession.planScopeLock) { teamViews.clear() } }
    }

    private fun io(block: suspend () -> Unit) = ticketScope.launch(Dispatchers.IO) { block() }
    private fun notify(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) =
        ticketSession.publishNotice(TaskNotice(text, actionLabel, action))

    private fun fail(message: String) {
        _ui.update { it.copy(error = message) }
        errorJob?.cancel()
        errorJob = ticketScope.launch {
            delay(5_000)
            _ui.update { if (it.error == message) it.copy(error = null) else it }
        }
    }

    private fun failure(e: Throwable, fallback: String): String {
        val message = if (e is ApiError) userFacingMessage(e, fallback)
        else e.message?.takeIf { it.isNotBlank() && it != "Task action failed" && !it.startsWith("HTTP ") } ?: fallback
        return capabilityFailureCopy(message) ?: message
    }

    private fun applyTicket(ticket: Task) {
        ticketSession.planRevision.incrementAndGet()
        ticketSession.store.upsert(ticket)
        ticketSession.store.highlight(ticket.id)
    }

    fun clearError() = _ui.update { it.copy(error = null) }
    fun acceptConfirm() {
        val request = _ui.value.confirm ?: return
        dismissConfirm()
        request.action()
    }

    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    fun carryForward() {
        val today = java.time.LocalDate.now().toString()
        if (carriedOn == today) return
        carriedOn = today
        io {
            runCatching(repository::carryForward).fold(
                onSuccess = { result ->
                    if (result.carried > 0) {
                        notify("${result.carried} incomplete item${if (result.carried > 1) "s" else ""} from yesterday carried forward")
                        loadScheduled()
                    }
                },
                onFailure = {
                    if (isTransientFailure(it)) carriedOn = null else fail("Failed to carry forward tasks")
                },
            )
        }
    }

    fun showScheduled(date: String?) {
        _ui.update { it.copy(scheduledFocus = date) }
        ticketSession.requestShell(ShellRequest.ShowScheduled)
        loadScheduled()
    }

    fun consumeScheduledFocus() = _ui.update { it.copy(scheduledFocus = null) }

    fun loadScheduled() {
        scheduledJob?.cancel()
        _ui.update { it.copy(scheduledLoading = !it.scheduledLoaded, window = scheduledWindow()) }
        val gen = scheduledGen.incrementAndGet()
        val state = _ui.value
        scheduledJob = io {
            runCatching { repository.loadScheduleOverlays(state.window.first.toString(), state.window.second.toString()) }
                .onSuccess { overlays ->
                    if (gen == scheduledGen.get()) _ui.update { it.copy(overlays = overlays) }
                }
            runCatching { repository.loadScheduled(state.filters, state.teamScope) }.fold(
                onSuccess = { list ->
                    synchronized(ticketSession.planScopeLock) {
                        if (gen != scheduledGen.get() || state.teamScope != ticketSession.planScope.value.teamScope ||
                            state.userId != ticketSession.planScope.value.userId ||
                            state.filters != ticketSession.planScope.value.filters
                        ) return@fold
                        ticketSession.store.upsert(list)
                        _ui.update {
                            it.copy(
                                tickets = ticketSession.store.state.value.tickets,
                                scheduledTicketIds = list.map { ticket -> ticket.id }.distinct(),
                                scheduledLoading = false, scheduledLoaded = true,
                            )
                        }
                    }
                },
                onFailure = {
                    if (gen != scheduledGen.get()) return@fold
                    val hadData = _ui.value.scheduledLoaded
                    _ui.update { it.copy(scheduledLoading = false) }
                    val message = "Failed to load scheduled tasks"
                    if (hadData && isTransientFailure(it)) {
                        _ui.update { current -> if (current.error == message) current.copy(error = null) else current }
                    } else fail(message)
                },
            )
        }
    }

    fun loadOverlays(from: String, to: String) {
        io {
            runCatching { repository.loadScheduleOverlays(from, to) }.onSuccess { overlays ->
                _ui.update { it.copy(overlays = overlays) }
            }
        }
    }

    fun toggleOverlayType(type: String) {
        _ui.update {
            it.copy(overlayTypes = if (type in it.overlayTypes) it.overlayTypes - type else it.overlayTypes + type)
        }
    }

    fun schedule(taskId: Long, date: String) {
        if (date.isEmpty()) return
        val decision = ticketSession.store.state.value.tickets[taskId]?.capability(TaskCapability.Schedule)
            ?: TaskCapabilityDecision()
        if (!decision.allowed) return notify(decision.reasonCopy())
        io {
            runCatching { repository.scheduleTask(taskId, date) }.fold(
                onSuccess = { saved ->
                    applyTicket(saved)
                    ticketSession.requestShell(ShellRequest.ReloadScheduledIfVisible)
                    notify("Scheduled for ${formatDate(date)}", "View") { showScheduled(date) }
                },
                onFailure = { fail(failure(it, "Failed to schedule task")) },
            )
        }
    }

    fun unschedule(taskId: Long, title: String?, ask: Boolean = true) {
        val ticket = ticketSession.store.state.value.tickets[taskId]
        val decision = ticket?.capability(TaskCapability.Schedule) ?: TaskCapabilityDecision()
        if (!decision.allowed) return notify(decision.reasonCopy())
        val previousDate = ticket?.plannedDate
        val run = {
            io {
                runCatching { repository.unscheduleTask(taskId) }.fold(
                    onSuccess = { saved ->
                        applyTicket(saved)
                        val back = previousDate?.take(10)
                        if (back != null) notify("Moved to backlog", "Undo") { schedule(taskId, back) }
                        else notify("Moved to backlog")
                    },
                    onFailure = { fail("Failed to move task to backlog") },
                )
            }
            Unit
        }
        if (ask) _ui.update {
            it.copy(confirm = ConfirmRequest(
                "Move to Backlog",
                "Move \"${title ?: "this task"}\" to backlog? It will be removed from the planner.",
                "Move to Backlog", action = run,
            ))
        } else run()
    }

    override fun onCleared() { ticketScope.cancel() }

    companion object {
        fun factory(context: Context, ticketSession: TicketSession): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val container = app.aino.mobile.core.AppContainer.get(context)
                    return ScheduledViewModel(TaskRepository(container.api), ticketSession) as T
                }
            }
    }
}
