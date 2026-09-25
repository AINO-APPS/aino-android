package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** AgileSettings tabs; Custom Fields is P10.5 and not shown until it lands. */
enum class AgileTab(val label: String) { General("General"), Types("Work Item Types"), Workflow("Workflow"), Labels("Labels") }

/** A `useAutoDismiss` success / error line in the Labels tab. */
data class LabelNotice(val ok: Boolean, val text: String)

data class AgileSettingsUiState(
    val permsLoading: Boolean = true,
    val perms: AgilePermissions = AgilePermissions(),
    val permsError: String? = null,
    val tab: AgileTab = AgileTab.General,
    /** The page-level `setError` banner shared by every tab. */
    val error: String = "",
    val refreshing: Boolean = false,
    // General
    val settings: AgileSettingsRecord? = null,
    val settingsError: String? = null,
    val valuesText: String = "",
    val saving: Boolean = false,
    // Types
    val types: List<AdminWorkItemType>? = null,
    val typesError: String? = null,
    val addingType: Boolean = false,
    val typeForm: NewWorkItemType = NewWorkItemType(),
    // Workflow
    val states: List<AdminWorkflowState>? = null,
    val statesError: String? = null,
    val addingState: Boolean = false,
    val stateForm: NewWorkflowState = NewWorkflowState(),
    // Labels
    val labels: List<ManagedLabel>? = null,
    val labelsError: String? = null,
    val labelNotice: LabelNotice? = null,
    val labelBusy: Boolean = false,
    val confirm: ConfirmRequest? = null,
) {
    val canEdit: Boolean get() = perms.canEdit
}

/** `pages/AgileSettings.tsx` state and actions; each tab loads when first opened, like the web's per-tab queries. */
class AgileSettingsViewModel(private val repository: AgileSettingsRepository) : ViewModel() {
    private val _ui = MutableStateFlow(AgileSettingsUiState())
    val ui: StateFlow<AgileSettingsUiState> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<AdminEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AdminEvent> = _events
    private var noticeJob: Job? = null

    init {
        loadPermissions()
        loadTab(AgileTab.General)
    }

    fun selectTab(tab: AgileTab) {
        _ui.update { it.copy(tab = tab) }
        val s = _ui.value
        val loaded = when (tab) {
            AgileTab.General -> s.settings != null
            AgileTab.Types -> s.types != null
            AgileTab.Workflow -> s.states != null
            AgileTab.Labels -> s.labels != null
        }
        if (!loaded) loadTab(tab)
    }

    /** Pull-to-refresh / resume: permissions plus the visible tab. */
    fun refresh(pull: Boolean = false) {
        if (pull) _ui.update { it.copy(refreshing = true) }
        loadPermissions()
        loadTab(_ui.value.tab)
    }

    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    // ── General ──────────────────────────────────────────────────────────
    fun setEstimationType(type: String) = _ui.update { s ->
        val next = s.settings?.withEstimationType(type) ?: return@update s
        s.copy(settings = next, valuesText = next.valuesText())
    }

    /**
     * The values input keeps the raw text while typing (the web re-joins on
     * every keystroke, which swallows a trailing comma) and parses it into the
     * draft on each change.
     */
    fun setValuesText(text: String) = _ui.update { s ->
        s.copy(valuesText = text, settings = s.settings?.copy(estimationValues = parseEstimationValues(text)))
    }

    fun editSettings(transform: (AgileSettingsRecord) -> AgileSettingsRecord) =
        _ui.update { s -> s.copy(settings = s.settings?.let(transform)) }

    fun saveSettings() {
        val s = _ui.value
        val settings = s.settings ?: return
        if (!s.canEdit || s.saving) return
        _ui.update { it.copy(saving = true, error = "") }
        io {
            runCatching { repository.saveSettings(settings) }
                .onSuccess { saved ->
                    _ui.update { it.copy(saving = false, settings = saved, valuesText = saved.valuesText()) }
                    changed()
                }
                .onFailure { e -> _ui.update { it.copy(saving = false, error = e.message ?: "Failed to save") } }
        }
    }

    // ── Work item types ──────────────────────────────────────────────────
    fun toggleAddingType() = _ui.update { it.copy(addingType = !it.addingType) }

    fun editTypeForm(transform: (NewWorkItemType) -> NewWorkItemType) = _ui.update { it.copy(typeForm = transform(it.typeForm)) }

    fun submitType() {
        val form = _ui.value.typeForm
        if (form.name.isBlank()) return // `required`
        io {
            runCatching { repository.createWorkItemType(form) }
                .onSuccess {
                    _ui.update { it.copy(typeForm = NewWorkItemType(), addingType = false) }
                    loadTypes()
                    changed()
                }
                .onFailure { e -> setError(e.message ?: "Failed to create") }
        }
    }

    /** Row edits: `{ name }`, `{ color }`, `{ is_default }`, `{ is_epic }`, `{ is_active }`. */
    fun updateType(id: Long, patch: JsonObject) {
        _ui.update { s -> s.copy(types = s.types?.map { if (it.id == id) it.patched(patch) else it }) }
        io {
            runCatching { repository.updateWorkItemType(id, patch) }
                .onFailure { e -> setError(e.message ?: "Failed to update") }
            loadTypes()
            changed()
        }
    }

    fun deleteType(type: AdminWorkItemType) = confirm("Delete \"${type.name}\"? This will fail if any tasks still use it.") {
        io {
            runCatching { repository.deleteWorkItemType(type.id) }
                .onSuccess { loadTypes(); changed() }
                .onFailure { e -> setError(e.message ?: "Failed to delete") }
        }
    }

    // ── Workflow states ──────────────────────────────────────────────────
    fun toggleAddingState() = _ui.update { it.copy(addingState = !it.addingState) }

    fun editStateForm(transform: (NewWorkflowState) -> NewWorkflowState) = _ui.update { it.copy(stateForm = transform(it.stateForm)) }

    fun submitState() {
        val form = _ui.value.stateForm
        if (form.name.isBlank()) return
        io {
            runCatching { repository.createWorkflowState(form) }
                .onSuccess {
                    _ui.update { it.copy(stateForm = NewWorkflowState(), addingState = false) }
                    loadStates()
                    changed()
                }
                .onFailure { e -> setError(e.message ?: "Failed to create") }
        }
    }

    fun updateState(id: Long, patch: JsonObject) {
        _ui.update { s -> s.copy(states = s.states?.map { if (it.id == id) it.patched(patch) else it }) }
        io {
            runCatching { repository.updateWorkflowState(id, patch) }
                .onFailure { e -> setError(e.message ?: "Failed to update") }
            loadStates()
            changed()
        }
    }

    fun deleteState(state: AdminWorkflowState) = confirm("Delete state \"${state.name}\"? This will fail if any tasks are still in it.") {
        io {
            runCatching { repository.deleteWorkflowState(state.id) }
                .onSuccess { loadStates(); changed() }
                .onFailure { e -> setError(e.message ?: "Failed to delete") }
        }
    }

    // ── Labels ───────────────────────────────────────────────────────────
    fun createLabel(name: String, color: String, onDone: () -> Unit) {
        if (name.isBlank()) return
        labelMutation(
            action = { repository.createLabel(name, color) },
            onSuccess = { onDone(); notice(true, "Label created") },
            failure = { e -> e.message ?: "Failed to create label" },
        )
    }

    fun updateLabel(id: Long, name: String, color: String, onDone: () -> Unit) {
        if (name.isBlank()) return
        labelMutation(
            action = { repository.updateLabel(id, name, color) },
            onSuccess = { onDone(); notice(true, "Label updated") },
            failure = { e -> e.message ?: "Failed to update label" },
        )
    }

    fun deleteLabel(id: Long) = labelMutation(
        action = { repository.deleteLabel(id) },
        onSuccess = { notice(true, "Label deleted") },
        failure = { "Failed to delete label" },
    )

    fun requestDeleteLabel(id: Long) = _ui.update {
        it.copy(
            confirm = ConfirmRequest(
                "Delete Label",
                "Delete this label? It will be removed from all tasks. This cannot be undone.",
                "Delete",
                danger = true,
            ) { deleteLabel(id) },
        )
    }

    private fun labelMutation(action: () -> Any, onSuccess: () -> Unit, failure: (Throwable) -> String) {
        if (_ui.value.labelBusy) return
        _ui.update { it.copy(labelBusy = true) }
        io {
            val result = runCatching(action)
            _ui.update { it.copy(labelBusy = false) }
            viewModelScope.launch(Dispatchers.Main) {
                result.fold({ onSuccess(); loadLabels(); changed() }, { notice(false, failure(it)) })
            }
        }
    }

    private fun notice(ok: Boolean, text: String) {
        _ui.update { it.copy(labelNotice = LabelNotice(ok, text)) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_DISMISS_MS)
            _ui.update { it.copy(labelNotice = null) }
        }
    }

    // ── Loading ──────────────────────────────────────────────────────────
    private fun loadPermissions() = io {
        val result = runCatching(repository::loadPermissions)
        _ui.update { s ->
            result.fold(
                { p -> s.copy(perms = p, permsLoading = false, permsError = null) },
                { e -> s.copy(permsLoading = false, permsError = e.message ?: "Failed to load permissions") },
            )
        }
    }

    private fun loadTab(tab: AgileTab) {
        when (tab) {
            AgileTab.General -> loadSettings()
            AgileTab.Types -> loadTypes()
            AgileTab.Workflow -> loadStates()
            AgileTab.Labels -> loadLabels()
        }
    }

    private fun loadSettings() = io {
        val result = runCatching(repository::loadSettings)
        _ui.update { s ->
            result.fold(
                { r -> s.copy(settings = r, valuesText = r.valuesText(), settingsError = null, refreshing = false) },
                { e -> s.copy(settingsError = e.message ?: "Failed to load settings", refreshing = false) },
            )
        }
    }

    private fun loadTypes() = io {
        val result = runCatching(repository::loadWorkItemTypes)
        _ui.update { s ->
            result.fold(
                { r -> s.copy(types = r, typesError = null, refreshing = false) },
                { e -> s.copy(typesError = e.message ?: "Failed to load work item types", refreshing = false) },
            )
        }
    }

    private fun loadStates() = io {
        val result = runCatching(repository::loadWorkflowStates)
        _ui.update { s ->
            result.fold(
                { r -> s.copy(states = r, statesError = null, refreshing = false) },
                { e -> s.copy(statesError = e.message ?: "Failed to load workflow states", refreshing = false) },
            )
        }
    }

    private fun loadLabels() = io {
        val result = runCatching(repository::loadManagedLabels)
        _ui.update { s ->
            result.fold(
                { r -> s.copy(labels = r, labelsError = null, refreshing = false) },
                { e -> s.copy(labels = s.labels ?: emptyList(), labelsError = e.message ?: "Failed to load labels", refreshing = false) },
            )
        }
    }

    private fun setError(text: String) = _ui.update { it.copy(error = text) }

    private fun confirm(message: String, action: () -> Unit) =
        _ui.update { it.copy(confirm = ConfirmRequest("", message, "OK", action = action)) }

    private fun changed() {
        _events.tryEmit(AdminEvent.Changed)
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    companion object {
        /** `useAutoDismiss` default delay. */
        private const val NOTICE_DISMISS_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return AgileSettingsViewModel(AgileSettingsRepository(container.api)) as T
            }
        }
    }
}

/** Optimistic local copy of a row patch until the reload lands. */
internal fun AdminWorkItemType.patched(patch: JsonObject): AdminWorkItemType {
    var t = this
    patch["name"]?.jsonPrimitive?.contentOrNull?.let { t = t.copy(name = it) }
    patch["color"]?.jsonPrimitive?.contentOrNull?.let { t = t.copy(color = it) }
    patch["is_default"]?.jsonPrimitive?.booleanOrNull?.let { t = t.copy(isDefault = it) }
    patch["is_epic"]?.jsonPrimitive?.booleanOrNull?.let { t = t.copy(isEpic = it) }
    patch["is_active"]?.jsonPrimitive?.booleanOrNull?.let { t = t.copy(isActive = it) }
    return t
}

internal fun AdminWorkflowState.patched(patch: JsonObject): AdminWorkflowState {
    var s = this
    patch["name"]?.jsonPrimitive?.contentOrNull?.let { s = s.copy(name = it) }
    patch["color"]?.jsonPrimitive?.contentOrNull?.let { s = s.copy(color = it) }
    patch["wip_limit"]?.let { s = s.copy(wipLimit = if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull) }
    patch["is_initial"]?.jsonPrimitive?.booleanOrNull?.let { s = s.copy(isInitial = it) }
    patch["is_terminal"]?.jsonPrimitive?.booleanOrNull?.let { s = s.copy(isTerminal = it) }
    patch["is_active"]?.jsonPrimitive?.booleanOrNull?.let { s = s.copy(isActive = it) }
    return s
}

/** `{ key: value }` patch body helper. */
internal fun patchOf(key: String, value: Any?): JsonObject = JsonObject(
    mapOf(
        key to when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        },
    ),
)
