package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** New / Edit project modal (`ProjectForm`). */
data class ProjectFormState(
    val editing: Project? = null,
    val name: String = editing?.name.orEmpty(),
    val key: String = editing?.key.orEmpty(),
    val description: String = editing?.description.orEmpty(),
    val color: String = editing?.color ?: PROJECT_COLORS.first(),
    val leadId: Long? = editing?.leadUserId,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editing != null
    val keyValid: Boolean get() = isEdit || isValidProjectKey(key)
}

/** `ProjectTasksPanel`. */
data class ProjectTasksState(
    val limit: Int = 25,
    val offset: Int = 0,
    val tasks: List<ProjectTask> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

data class ProjectsUiState(
    val role: String = "",
    val includeArchived: Boolean = false,
    val limit: Int = 12,
    val offset: Int = 0,
    val projects: List<Project> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val users: List<AssignableUser> = emptyList(),
    val selectedId: Long? = null,
    val panel: ProjectTasksState = ProjectTasksState(),
    val form: ProjectFormState? = null,
    val confirm: ConfirmRequest? = null,
) {
    val canEdit: Boolean get() = canEditProjects(role)
    val canDelete: Boolean get() = canDeleteProjects(role)
}

sealed interface AdminEvent {
    data class Toast(val text: String) : AdminEvent

    /** Data the Tasks page caches (projects, labels, agile config) changed. */
    data object Changed : AdminEvent
}

/** `pages/Projects.tsx` state and actions. */
class ProjectsViewModel(private val repository: ProjectsRepository, role: String) : ViewModel() {
    private val _ui = MutableStateFlow(ProjectsUiState(role = role))
    val ui: StateFlow<ProjectsUiState> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<AdminEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AdminEvent> = _events
    private var listGeneration = 0
    private var panelGeneration = 0

    init {
        loadProjects()
        loadUsers()
    }

    fun setRole(role: String) = _ui.update { it.copy(role = role) }

    /** Pull-to-refresh / resume: the grid, the lead picker and an open tasks panel. */
    fun refresh(pull: Boolean = false) {
        if (pull) _ui.update { it.copy(refreshing = true) }
        loadProjects()
        loadUsers()
        if (_ui.value.selectedId != null) loadPanel()
    }

    /** Filter or page-size changes reset to page 1. */
    fun setIncludeArchived(on: Boolean) {
        _ui.update { it.copy(includeArchived = on, offset = 0) }
        loadProjects()
    }

    fun setPage(offset: Int) {
        _ui.update { it.copy(offset = offset) }
        loadProjects()
    }

    fun setLimit(limit: Int) {
        _ui.update { it.copy(limit = limit, offset = 0) }
        loadProjects()
    }

    fun toggleSelected(project: Project) {
        val next = if (_ui.value.selectedId == project.id) null else project.id
        _ui.update { it.copy(selectedId = next, panel = it.panel.copy(offset = 0, tasks = emptyList(), total = 0, loading = true, error = null)) }
        if (next != null) loadPanel()
    }

    fun closePanel() = _ui.update { it.copy(selectedId = null) }

    fun setPanelPage(offset: Int) {
        _ui.update { it.copy(panel = it.panel.copy(offset = offset)) }
        loadPanel()
    }

    fun setPanelLimit(limit: Int) {
        _ui.update { it.copy(panel = it.panel.copy(limit = limit, offset = 0)) }
        loadPanel()
    }

    // ── Form ─────────────────────────────────────────────────────────────
    fun openCreate() = _ui.update { it.copy(form = ProjectFormState()) }

    fun openEdit(project: Project) = _ui.update { it.copy(form = ProjectFormState(editing = project)) }

    fun closeForm() = _ui.update { it.copy(form = null) }

    fun editForm(transform: (ProjectFormState) -> ProjectFormState) = _ui.update { s -> s.copy(form = s.form?.let(transform)) }

    fun submitForm() {
        val form = _ui.value.form ?: return
        if (form.saving) return
        if (form.name.isBlank()) return toast("Name is required")
        if (!form.isEdit && !form.keyValid) {
            return toast("Key must be 2–10 uppercase letters/digits/underscores, starting with a letter.")
        }
        val payload = ProjectPayload(
            name = form.name.trim(),
            description = form.description.trim().ifEmpty { null },
            color = form.color,
            leadUserId = form.leadId,
            key = if (form.isEdit) null else form.key.trim().uppercase(),
        )
        editForm { it.copy(saving = true) }
        io {
            val result = runCatching {
                val editing = form.editing
                if (editing != null) repository.updateProject(editing.id, payload) else repository.createProject(payload)
            }
            result.onSuccess {
                toast(if (form.isEdit) "Project updated" else "Project created")
                _ui.update { it.copy(form = null) }
                changed()
                loadProjects()
            }.onFailure { error ->
                editForm { it.copy(saving = false) }
                toast(error.message ?: "Save failed")
            }
        }
    }

    // ── Card actions ─────────────────────────────────────────────────────
    fun archive(project: Project) = io {
        runCatching { repository.archiveProject(project.id, !project.isArchived) }
            .onSuccess {
                toast(if (project.isArchived) "Project unarchived" else "Project archived")
                changed()
                loadProjects()
            }
            .onFailure { toast(it.message ?: "Failed") }
    }

    fun requestDelete(project: Project) = confirm("Delete project \"${project.name}\" permanently? This cannot be undone.") {
        io {
            runCatching { repository.deleteProject(project.id) }
                .onSuccess { deleted(project, "Project deleted") }
                .onFailure { error ->
                    if (error is ProjectNotEmptyException) {
                        confirm(forceDeleteMessage(project, error.taskCount)) { forceDelete(project, error.taskCount) }
                    } else {
                        toast(error.message ?: "Failed to delete")
                    }
                }
        }
    }

    private fun forceDelete(project: Project, taskCount: Int?) = io {
        runCatching { repository.deleteProject(project.id, force = true) }
            .onSuccess { res ->
                val detached = res.detachedTasks.takeIf { it != 0 } ?: taskCount?.takeIf { it != 0 }
                deleted(project, "Project deleted (detached ${detached ?: "?"} task${plural(detached)})")
            }
            .onFailure { toast(it.message ?: "Failed to force-delete") }
    }

    private fun deleted(project: Project, message: String) {
        toast(message)
        _ui.update { if (it.selectedId == project.id) it.copy(selectedId = null) else it }
        changed()
        loadProjects()
    }

    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    private fun confirm(message: String, action: () -> Unit) =
        _ui.update { it.copy(confirm = ConfirmRequest("", message, "OK", action = action)) }

    // ── Loading ──────────────────────────────────────────────────────────
    private fun loadProjects() {
        val generation = ++listGeneration
        val s = _ui.value
        io {
            val result = runCatching { repository.loadProjects(s.includeArchived, s.limit, s.offset) }
            if (generation != listGeneration) return@io
            _ui.update { state ->
                result.fold(
                    { page -> state.copy(projects = page.projects, total = page.total, loading = false, refreshing = false, error = null) },
                    { error -> state.copy(loading = false, refreshing = false, error = error.message ?: "Failed to list projects") },
                )
            }
        }
    }

    private fun loadUsers() = io {
        runCatching(repository::loadAssignableUsers).onSuccess { users -> _ui.update { it.copy(users = users) } }
    }

    private fun loadPanel() {
        val id = _ui.value.selectedId ?: return
        val generation = ++panelGeneration
        val panel = _ui.value.panel
        _ui.update { it.copy(panel = it.panel.copy(loading = it.panel.tasks.isEmpty(), error = null)) }
        io {
            val result = runCatching { repository.loadProjectTasks(id, panel.limit, panel.offset) }
            if (generation != panelGeneration) return@io
            _ui.update { state ->
                result.fold(
                    { page -> state.copy(panel = state.panel.copy(tasks = page.tasks, total = page.total, loading = false)) },
                    { error -> state.copy(panel = state.panel.copy(loading = false, error = error.message ?: "Failed to list project tasks")) },
                )
            }
        }
    }

    private fun toast(text: String) {
        _events.tryEmit(AdminEvent.Toast(text))
    }

    private fun changed() {
        _events.tryEmit(AdminEvent.Changed)
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    companion object {
        fun factory(context: Context, role: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return ProjectsViewModel(ProjectsRepository(container.api), role) as T
            }
        }
    }
}
