package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TaskUiState(
    val loading: Boolean = false,
    val tab: TaskTab = TaskTab.Today,
    val date: String = LocalDate.now().toString(),
    val filters: TaskFilters = TaskFilters(),
    val tasks: List<Task> = emptyList(),
    val stats: TaskStats = TaskStats(),
    val backlog: List<Task> = emptyList(),
    val backlogSummary: BacklogSummary = BacklogSummary(),
    val backlogHasMore: Boolean = false,
    val labels: List<TaskLabel> = emptyList(),
    val assignableUsers: List<AssignableUser> = emptyList(),
    val detail: TaskDetail? = null,
    val detailLoading: Boolean = false,
    val composerOpen: Boolean = false,
    val composerTitle: String = "",
    val composerDescription: String = "",
    val composerPriority: String = "medium",
    val composerDueDate: String = "",
    val composerAssignee: Long? = null,
    val commentDraft: String = "",
    val error: String? = null,
    val message: String? = null,
) {
    val visibleTasks: List<Task> get() = if (tab == TaskTab.Today) tasks else backlog
}

class TaskViewModel(private val repository: TaskRepository) : ViewModel() {
    private val _ui = MutableStateFlow(TaskUiState())
    val ui: StateFlow<TaskUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val current = _ui.value
            runCatching {
                // Metadata failures must not hide the task list: an org with no
                // labels, or a role without the assignable-users endpoint, still
                // needs a working planner.
                val labels = runCatching(repository::loadLabels).getOrDefault(current.labels)
                val users = runCatching(repository::loadAssignableUsers).getOrDefault(current.assignableUsers)
                val today = repository.loadToday(current.date, current.filters)
                val backlog = runCatching { repository.loadBacklog(current.filters) }.getOrNull()
                Loaded(labels, users, today, backlog)
            }.fold(
                onSuccess = { loaded ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        labels = loaded.labels,
                        assignableUsers = loaded.users,
                        tasks = loaded.today.tasks.sortedBy(::taskSortKey),
                        stats = loaded.today.stats,
                        backlog = loaded.backlog?.tasks?.sortedBy(::taskSortKey) ?: _ui.value.backlog,
                        backlogSummary = loaded.backlog?.summary ?: _ui.value.backlogSummary,
                        backlogHasMore = loaded.backlog?.pagination?.hasMore ?: false,
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not load tasks") },
            )
        }
    }

    fun selectTab(tab: TaskTab) { _ui.value = _ui.value.copy(tab = tab, error = null, message = null) }

    fun changeDate(delta: Long) {
        val next = LocalDate.parse(_ui.value.date).plusDays(delta)
        _ui.value = _ui.value.copy(date = next.toString())
        refresh()
    }

    fun today() {
        _ui.value = _ui.value.copy(date = LocalDate.now().toString())
        refresh()
    }

    fun setFilters(
        assigneeMine: Boolean? = null,
        priority: String? = null,
        status: String? = null,
        search: String? = null,
        clearPriority: Boolean = false,
        clearStatus: Boolean = false,
    ) {
        val current = _ui.value.filters
        _ui.value = _ui.value.copy(
            filters = current.copy(
                assigneeMine = assigneeMine ?: current.assigneeMine,
                priority = if (clearPriority) null else priority ?: current.priority,
                status = if (clearStatus) null else status ?: current.status,
                search = search ?: current.search,
            ),
        )
        // Typing must not fire a request per keystroke; the search box commits
        // explicitly through applySearch().
        if (search == null) refresh()
    }

    fun applySearch() = refresh()

    fun advanceStatus(task: Task) = setStatus(task, nextTaskStatus(task.status))

    fun setStatus(task: Task, status: String) {
        // Optimistic: the row flips immediately, then the server's enriched row
        // replaces it. Any failure (WIP limit, lost access) restores the original
        // row rather than leaving a lie on screen.
        applyTask(task.copy(status = status))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.updateStatus(task.id, status) }.fold(
                onSuccess = { applyTask(it) },
                onFailure = {
                    applyTask(task)
                    _ui.value = _ui.value.copy(error = it.message ?: "Could not update the task status")
                },
            )
        }
    }

    private fun applyTask(task: Task) {
        val tasks = _ui.value.tasks.map { if (it.id == task.id) task else it }.sortedBy(::taskSortKey)
        val backlog = _ui.value.backlog.map { if (it.id == task.id) task else it }.sortedBy(::taskSortKey)
        val detail = _ui.value.detail
        _ui.value = _ui.value.copy(
            tasks = tasks,
            backlog = backlog,
            stats = recomputeStats(tasks),
            detail = if (detail?.id == task.id) detail.copy(status = task.status) else detail,
        )
    }

    fun openComposer() { _ui.value = _ui.value.copy(composerOpen = true, error = null, message = null) }

    fun closeComposer() {
        _ui.value = _ui.value.copy(
            composerOpen = false,
            composerTitle = "",
            composerDescription = "",
            composerPriority = "medium",
            composerDueDate = "",
            composerAssignee = null,
        )
    }

    fun updateComposer(
        title: String? = null,
        description: String? = null,
        priority: String? = null,
        dueDate: String? = null,
        assignee: Long? = null,
        clearAssignee: Boolean = false,
    ) {
        _ui.value = _ui.value.copy(
            composerTitle = title ?: _ui.value.composerTitle,
            composerDescription = description ?: _ui.value.composerDescription,
            composerPriority = priority ?: _ui.value.composerPriority,
            composerDueDate = dueDate ?: _ui.value.composerDueDate,
            composerAssignee = if (clearAssignee) null else assignee ?: _ui.value.composerAssignee,
            error = null,
        )
    }

    fun submitTask() {
        val current = _ui.value
        val backlogMode = current.tab == TaskTab.Backlog
        val date = if (backlogMode) null else current.date
        validateTask(current.composerTitle, current.composerDescription, date, current.composerDueDate)?.let {
            _ui.value = current.copy(error = it)
            return
        }
        _ui.value = current.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            val payload = CreateTaskPayload(
                title = current.composerTitle.trim(),
                description = current.composerDescription.trim().ifBlank { null },
                priority = current.composerPriority,
                date = date,
                dueDate = current.composerDueDate.trim().ifBlank { null },
                assignedTo = current.composerAssignee,
            )
            runCatching {
                if (backlogMode) repository.createBacklogTask(payload) else repository.createTask(payload)
            }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(loading = false, message = "Task created")
                    closeComposer()
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not create the task") },
            )
        }
    }

    fun deleteTask(task: Task) {
        _ui.value = _ui.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteTask(task.id) }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(loading = false, detail = null, message = it.message.ifBlank { "Task deleted" })
                    refresh()
                },
                // A 403 here is the server's creator-only delete rule, not a bug.
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not delete the task") },
            )
        }
    }

    fun carryForward() {
        _ui.value = _ui.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(repository::carryForward).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(loading = false, message = it.message.ifBlank { "Carried ${it.carried} tasks" })
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not carry tasks forward") },
            )
        }
    }

    fun openDetail(task: Task) {
        _ui.value = _ui.value.copy(detailLoading = true, detail = null, commentDraft = "", error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.loadDetail(task.id) }.fold(
                onSuccess = { _ui.value = _ui.value.copy(detailLoading = false, detail = it) },
                onFailure = { _ui.value = _ui.value.copy(detailLoading = false, error = it.message ?: "Could not open the task") },
            )
        }
    }

    fun closeDetail() { _ui.value = _ui.value.copy(detail = null, commentDraft = "") }

    fun updateCommentDraft(value: String) { _ui.value = _ui.value.copy(commentDraft = value, error = null) }

    fun submitComment() {
        val detail = _ui.value.detail ?: return
        val draft = _ui.value.commentDraft
        validateComment(draft)?.let {
            _ui.value = _ui.value.copy(error = it)
            return
        }
        _ui.value = _ui.value.copy(detailLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.addComment(detail.id, draft.trim()) }.fold(
                onSuccess = { comment ->
                    val open = _ui.value.detail
                    _ui.value = _ui.value.copy(
                        detailLoading = false,
                        commentDraft = "",
                        detail = if (open?.id == detail.id) open.copy(comments = open.comments + comment) else open,
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(detailLoading = false, error = it.message ?: "Could not add the comment") },
            )
        }
    }

    fun clearNotices() { _ui.value = _ui.value.copy(error = null, message = null) }

    private data class Loaded(
        val labels: List<TaskLabel>,
        val users: List<AssignableUser>,
        val today: TaskListResponse,
        val backlog: BacklogResponse?,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens)
                return TaskViewModel(TaskRepository(api)) as T
            }
        }
    }
}
