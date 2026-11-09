package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.userFacingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

data class UpcomingTicketGroup(val key: String, val label: String, val tickets: List<Task>)

data class ProjectTicketUiState(
    val planScope: PlanScope = PlanScope(),
    val query: ProjectTicketQuery = ProjectTicketQuery(),
    val tickets: List<Task> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val today: LocalDate = LocalDate.now(),
) {
    val upcoming: List<UpcomingTicketGroup> get() {
        val open = tickets.filterNot(Task::isClosed)
        val overdue = open.filter {
            localDateOf(it.dueDate)?.isBefore(today) == true || localDateOf(it.startDate)?.isBefore(today) == true
        }.sortedWith(compareBy<Task> { localDateOf(it.startDate) ?: localDateOf(it.dueDate) }.thenBy { it.id })
        val remaining = open.filterNot { it in overdue }
        return buildList {
            if (overdue.isNotEmpty()) add(UpcomingTicketGroup("overdue", "Overdue", overdue))
            remaining.filter { localDateOf(it.startDate) != null }.groupBy { localDateOf(it.startDate)!! }
                .toSortedMap().forEach { (date, rows) ->
                    add(UpcomingTicketGroup("$date", if (date == today) "Today · $date" else "$date", rows))
                }
            val noStart = remaining.filter { localDateOf(it.startDate) == null }
            if (noStart.isNotEmpty()) add(UpcomingTicketGroup("no-start", "No start date", noStart))
        }
    }
}

/** Project collection membership, shared by List and Upcoming; never a placement container. */
class ProjectTicketViewModel(
    private val repository: TaskRepository,
    private val ticketSession: TicketSession,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(ticketSession.job))
    private val _ui = ticketSession.state(ProjectTicketUiState(planScope = ticketSession.planScope.value, today = today()))
    val ui: StateFlow<ProjectTicketUiState> = _ui.asStateFlow()
    private val generation = AtomicInteger()
    private var loadJob: Job? = null
    private var ids: List<Long> = emptyList()

    init {
        scope.launch {
            ticketSession.planScope.collect { context ->
                val previous = _ui.value.planScope
                val changed = context.selectedProjectId != previous.selectedProjectId ||
                    context.projectType != previous.projectType || context.projectScoped != previous.projectScoped
                val filtersChanged = context.filters != previous.filters
                if (changed) {
                    generation.incrementAndGet()
                    loadJob?.cancel()
                    ids = emptyList()
                    _ui.value = ProjectTicketUiState(planScope = context, today = today())
                } else _ui.update { it.copy(planScope = context) }
                if (changed || filtersChanged || (!_ui.value.loaded && !_ui.value.loading)) refresh()
            }
        }
        scope.launch {
            ticketSession.store.state.collect { snapshot ->
                _ui.update { current ->
                    current.copy(tickets = ids.mapNotNull(snapshot.tickets::get).filter(current.planScope::contains))
                }
            }
        }
        scope.launch {
            ticketSession.sync(repository).changes.collect {
                if (_ui.value.loaded) refresh()
            }
        }
        scope.launch {
            ticketSession.sync(repository).signals.collect {
                if (_ui.value.loaded) refresh()
            }
        }
    }

    fun setQuery(query: ProjectTicketQuery) {
        if (query == _ui.value.query) return
        _ui.update { it.copy(query = query) }
        refresh()
    }

    fun refresh() {
        val state = _ui.value
        val projectId = state.planScope.selectedProjectId ?: return
        if (!state.planScope.projectScoped || state.planScope.projectType != "task_list") return
        loadJob?.cancel()
        val request = generation.incrementAndGet()
        _ui.update { it.copy(loading = true, error = null, today = today()) }
        loadJob = scope.launch(Dispatchers.IO) {
            try {
                val tickets = linkedMapOf<Long, Task>()
                var offset = 0
                do {
                    val page = repository.loadProjectTickets(projectId, state.query, state.planScope.filters, offset)
                    page.tasks.filter { it.projectId == projectId }.forEach { tickets[it.id] = it }
                    check(!page.pagination.hasMore || page.tasks.isNotEmpty()) { "Project pagination did not advance" }
                    offset += 500
                } while (page.pagination.hasMore && generation.get() == request)
                synchronized(ticketSession.planScopeLock) {
                    val current = ticketSession.planScope.value
                    if (generation.get() == request && current.selectedProjectId == projectId &&
                        current.projectScoped && current.projectType == "task_list" && current.filters == state.planScope.filters
                    ) {
                        ids = tickets.keys.toList()
                        ticketSession.store.upsert(tickets.values.toList())
                        _ui.update {
                            it.copy(
                                tickets = ids.mapNotNull(ticketSession.store.state.value.tickets::get),
                                loaded = true, loading = false, error = null,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation.get() == request) _ui.update {
                    it.copy(
                        loading = false,
                        error = if (error is ApiError) userFacingMessage(error, "Could not load project tickets")
                        else error.message ?: "Could not load project tickets",
                    )
                }
            }
        }
    }

    override fun onCleared() = scope.cancel()

    companion object {
        fun factory(context: Context, session: TicketSession) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProjectTicketViewModel(TaskRepository(app.aino.mobile.core.AppContainer.get(context).api), session) as T
        }
    }
}
