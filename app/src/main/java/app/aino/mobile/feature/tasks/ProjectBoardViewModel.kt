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
import java.util.concurrent.atomic.AtomicInteger

data class ProjectBoardUiState(
    val planScope: PlanScope = PlanScope(),
    val tickets: Map<Long, Task> = emptyMap(),
    val boardTicketIds: List<Long> = emptyList(),
    val backlogTicketIds: List<Long> = emptyList(),
    val board: ProjectBoardResponse? = null,
    val backlog: ProjectBoardResponse? = null,
    val boardLoading: Boolean = false,
    val backlogLoading: Boolean = false,
    val backlogLoaded: Boolean = false,
    val boardError: String? = null,
    val backlogError: String? = null,
) {
    val serverProject: ProjectOption?
        get() = board?.project ?: backlog?.project ?: planScope.selectedProject
    val columns: List<ProjectBoardColumn>
        get() = board?.columns.orEmpty().filter { it.state != null && it.state.key != CANCELLED_STATE }
    val boardTasks: List<Task>
        get() = boardTicketIds.mapNotNull(tickets::get).filter(::isVisibleProjectTicket)
    val backlogTasks: List<Task>
        get() = backlogTicketIds.mapNotNull(tickets::get).filter(::isVisibleProjectTicket)

    fun tasksForColumn(column: ProjectBoardColumn): List<Task> =
        boardTasks.filter { task ->
            val matchesColumn = task.workflowStateId == column.stateId ||
                (task.workflowStateId == null && task.workflowStateKey == column.state?.key)
            matchesColumn && matchesProjectFilters(task)
        }

    val filteredBacklogTasks: List<Task>
        get() = backlogTasks.filter(::matchesProjectFilters)

    private fun isVisibleProjectTicket(task: Task): Boolean =
        planScope.contains(task) && !task.isCancelled

    private fun matchesProjectFilters(task: Task): Boolean =
        matchesFilters(task, planScope.filters, planScope.userId, includeStatus = true) &&
            (planScope.selectedEpicId == null || task.parentTaskId == planScope.selectedEpicId)
}

/** Owns project-board membership while Ticket content remains in the session store. */
class ProjectBoardViewModel(
    private val repository: TaskRepository,
    private val ticketSession: TicketSession,
) : ViewModel() {
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(ticketSession.job))
    private val _ui = ticketSession.state(ProjectBoardUiState(planScope = ticketSession.planScope.value))
    val ui: StateFlow<ProjectBoardUiState> = _ui.asStateFlow()
    private val projectGeneration = AtomicInteger()
    private val boardGeneration = AtomicInteger()
    private val backlogGeneration = AtomicInteger()
    private var boardJob: Job? = null
    private var backlogJob: Job? = null

    init {
        ticketScope.launch {
            ticketSession.planScope.collect { scope ->
                val previous = _ui.value.planScope
                val projectChanged = scope.selectedProjectId != previous.selectedProjectId ||
                    scope.projectType != previous.projectType || scope.projectScoped != previous.projectScoped
                val metadataChanged = scope.selectedProject != previous.selectedProject
                _ui.update { it.copy(planScope = scope) }
                if (projectChanged) {
                    projectGeneration.incrementAndGet()
                    boardJob?.cancel()
                    backlogJob?.cancel()
                    _ui.update {
                        it.copy(
                            tickets = emptyMap(),
                            boardTicketIds = emptyList(),
                            backlogTicketIds = emptyList(),
                            board = null,
                            backlog = null,
                            boardLoading = false,
                            backlogLoading = false,
                            backlogLoaded = false,
                            boardError = null,
                            backlogError = null,
                        )
                    }
                    if (scope.projectScoped && scope.projectType in setOf("kanban", "task_list")) {
                        scope.selectedProjectId?.let(::loadBoard)
                    }
                } else if (metadataChanged && scope.projectScoped && scope.projectType in setOf("kanban", "task_list")) {
                    scope.selectedProjectId?.let { loadBoard(it, force = true) }
                }
            }
        }
        ticketScope.launch {
            ticketSession.store.state.collect { snapshot ->
                _ui.update { it.copy(tickets = snapshot.tickets) }
            }
        }
        ticketScope.launch {
            ticketSession.sync(repository).changes.collect { change ->
                if (change is TicketSyncChange.Updated && change.refreshBoard && _ui.value.board != null) {
                    refresh()
                }
            }
        }
    }

    fun onTabSelected(tab: TaskTab) {
        when (tab) {
            TaskTab.KanbanBoard -> ensureBoardLoaded()
            TaskTab.Backlog -> if (_ui.value.planScope.projectType == "kanban") loadBacklog()
            else -> Unit
        }
    }

    fun ensureBoardLoaded() {
        val state = _ui.value
        if (state.planScope.projectType !in setOf("kanban", "task_list") || !state.planScope.projectScoped) return
        val projectId = state.planScope.selectedProjectId ?: return
        if (state.board?.project?.id != projectId && !state.boardLoading) loadBoard(projectId)
    }

    fun loadBacklog(force: Boolean = false) {
        val state = _ui.value
        val projectId = state.planScope.selectedProjectId ?: return
        if (!state.planScope.projectScoped || state.planScope.projectType != "kanban") return
        val project = state.serverProject ?: state.planScope.selectedProject ?: return
        if (!project.kanbanBacklogEnabled || "backlog" !in project.planTabs) return
        if (!force && (state.backlogLoaded || state.backlogLoading)) return

        val projectRevision = projectGeneration.get()
        val request = backlogGeneration.incrementAndGet()
        _ui.update { it.copy(backlogLoading = true, backlogError = null) }
        backlogJob?.cancel()
        backlogJob = io {
            try {
                val response = repository.loadProjectBacklog(projectId)
                if (isCurrent(projectId, projectRevision) && backlogGeneration.get() == request) {
                    ticketSession.store.upsert(response.tickets)
                    _ui.update {
                        it.copy(
                            backlog = response,
                            backlogTicketIds = response.tickets.map(Task::id),
                            backlogLoading = false,
                            backlogLoaded = true,
                            backlogError = null,
                            tickets = ticketSession.store.state.value.tickets,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(projectId, projectRevision) && backlogGeneration.get() == request) {
                    _ui.update { it.copy(backlogLoading = false, backlogError = failure(error, "Could not load the Kanban backlog")) }
                }
            }
        }
    }

    fun refresh() {
        val state = _ui.value
        val projectId = state.planScope.selectedProjectId ?: return
        if (!state.planScope.projectScoped || state.planScope.projectType !in setOf("kanban", "task_list")) return
        loadBoard(projectId, force = true)
        if (state.backlogLoaded) loadBacklog(force = true)
    }

    private fun loadBoard(projectId: Long, force: Boolean = false) {
        val state = _ui.value
        if (!force && (state.board?.project?.id == projectId || state.boardLoading)) return
        val projectRevision = projectGeneration.get()
        val request = boardGeneration.incrementAndGet()
        _ui.update { it.copy(boardLoading = true, boardError = null) }
        boardJob?.cancel()
        boardJob = io {
            try {
                val response = repository.loadProjectBoard(projectId)
                if (isCurrent(projectId, projectRevision) && boardGeneration.get() == request) {
                    ticketSession.store.upsert(response.tickets)
                    _ui.update {
                        it.copy(
                            board = response,
                            boardTicketIds = response.tickets.map(Task::id),
                            boardLoading = false,
                            boardError = null,
                            tickets = ticketSession.store.state.value.tickets,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(projectId, projectRevision) && boardGeneration.get() == request) {
                    _ui.update { it.copy(boardLoading = false, boardError = failure(error, "Could not load the Kanban board")) }
                }
            }
        }
    }

    private fun isCurrent(projectId: Long, revision: Int): Boolean =
        projectGeneration.get() == revision &&
            _ui.value.planScope.selectedProjectId == projectId &&
            _ui.value.planScope.projectScoped &&
            _ui.value.planScope.projectType in setOf("kanban", "task_list")

    private fun io(block: suspend () -> Unit) = ticketScope.launch(Dispatchers.IO) { block() }

    private fun failure(error: Throwable, fallback: String): String =
        if (error is ApiError) userFacingMessage(error, fallback) else error.message ?: fallback

    override fun onCleared() {
        ticketScope.cancel()
    }

    companion object {
        fun factory(context: Context, session: TicketSession) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ProjectBoardViewModel::class.java)) {
                    return ProjectBoardViewModel(
                        TaskRepository(app.aino.mobile.core.AppContainer.get(context).api),
                        session,
                    ) as T
                }
                error("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
