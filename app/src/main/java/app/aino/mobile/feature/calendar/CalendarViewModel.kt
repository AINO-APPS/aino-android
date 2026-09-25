package app.aino.mobile.feature.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PickerKind { Required, Optional }

data class EventEditor(
    /** null = "create", otherwise the event being edited. */
    val eventId: Long? = null,
    val form: EventForm,
    val meetingCode: String? = null,
    val meetingCreatedBy: Long? = null,
    val addMeeting: Boolean = false,
    val required: List<ParticipantUser> = emptyList(),
    val optional: List<ParticipantUser> = emptyList(),
    val settings: MeetingSettings = MeetingSettings(),
    val conflicts: Map<Long, ParticipantConflict> = emptyMap(),
    val meetingParticipants: List<MeetingParticipant> = emptyList(),
    val queries: Map<PickerKind, String> = emptyMap(),
    val results: Map<PickerKind, List<ParticipantUser>> = emptyMap(),
    val searching: Set<PickerKind> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null,
) {
    val creating: Boolean get() = eventId == null
    val hasMeeting: Boolean get() = !creating && meetingCode != null
}

data class CalendarUiState(
    val view: CalendarView = CalendarView.Week,
    val baseDate: LocalDate = LocalDate.now(),
    val events: List<CalendarEvent> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val tasks: List<CalendarTask> = emptyList(),
    val editor: EventEditor? = null,
)

class CalendarViewModel(
    private val repository: CalendarRepository,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {
    private val _ui = MutableStateFlow(CalendarUiState())
    val ui: StateFlow<CalendarUiState> = _ui.asStateFlow()
    private var loadJob: Job? = null
    private var conflictJob: Job? = null
    private val searchJobs = mutableMapOf<PickerKind, Job>()
    var userId: Long? = null

    init {
        refresh()
        loadTasks()
    }

    /** `fetchEvents` for the visible range; also the realtime / resume refetch. */
    fun refresh() {
        val state = _ui.value
        val (from, to) = visibleRange(state.view, state.baseDate)
        loadJob?.cancel()
        _ui.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.events(toIso(from.atStartOfDay(), zone()), toIso(to.atStartOfDay(), zone())) }.fold(
                onSuccess = { events -> _ui.update { it.copy(loading = false, events = events, error = null) } },
                // The web swallows fetch errors; keep whatever is already on screen.
                onFailure = { _ui.update { it.copy(loading = false) } },
            )
        }
    }

    private fun loadTasks() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.linkableTasks(clock().toLocalDate().toString()) }
                .onSuccess { tasks -> _ui.update { it.copy(tasks = tasks) } }
        }
    }

    fun setView(view: CalendarView) { _ui.update { it.copy(view = view) }; refresh() }

    fun navigate(direction: Int) {
        _ui.update { it.copy(baseDate = navigate(it.view, it.baseDate, direction)) }
        refresh()
    }

    fun goToday() { _ui.update { it.copy(baseDate = clock().toLocalDate()) }; refresh() }

    /** Tapping a week-header day or a month cell opens that day. */
    fun openDay(day: LocalDate) { _ui.update { it.copy(baseDate = day, view = CalendarView.Day) }; refresh() }

    // ── Editor ──────────────────────────────────────────────────────────────

    /** "New Event" button: a past base date falls back to today. */
    fun newEvent() {
        val now = clock()
        val base = _ui.value.baseDate.let { if (it.isBefore(now.toLocalDate())) now.toLocalDate() else it }
        openCreate(base, null)
    }

    fun openCreate(day: LocalDate, hour: Int?) {
        val form = createForm(day, hour, clock()) ?: return
        _ui.update { it.copy(editor = EventEditor(form = form)) }
    }

    fun openEdit(event: CalendarEvent) {
        val editor = EventEditor(
            eventId = event.id,
            form = editForm(event, zone()),
            meetingCode = event.meetingCode,
            meetingCreatedBy = event.meetingCreatedBy,
        )
        _ui.update { it.copy(editor = editor) }
        val code = event.meetingCode ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.meeting(code) }.onSuccess { detail ->
                _ui.update { state -> state.copy(editor = state.editor?.takeIf { it.eventId == event.id }?.copy(meetingParticipants = detail.participants) ?: state.editor) }
            }
        }
    }

    fun closeEditor() {
        conflictJob?.cancel()
        searchJobs.values.forEach(Job::cancel)
        _ui.update { it.copy(editor = null) }
    }

    fun isOrganizer(editor: EventEditor): Boolean = editor.meetingCode == null || editor.meetingCreatedBy == userId

    fun updateForm(transform: (EventForm, LocalDateTime) -> EventForm) {
        val now = clock()
        editEditor { it.copy(form = transform(it.form, now), error = null) }
        scheduleConflictCheck()
    }

    fun toggleMeeting() {
        val now = clock()
        editEditor { editor ->
            val next = !editor.addMeeting
            editor.copy(addMeeting = next, form = if (next) editor.form.forMeeting(now) else editor.form)
        }
        scheduleConflictCheck()
    }

    fun updateMeetingSettings(settings: MeetingSettings) = editEditor { it.copy(settings = settings) }

    fun queryPeople(kind: PickerKind, query: String) {
        editEditor { it.copy(queries = it.queries + (kind to query)) }
        searchJobs.remove(kind)?.cancel()
        if (query.trim().length < 2) {
            editEditor { it.copy(results = it.results - kind, searching = it.searching - kind) }
            return
        }
        searchJobs[kind] = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            editEditor { it.copy(searching = it.searching + kind) }
            val found = runCatching { repository.searchPeople(query) }.getOrDefault(emptyList())
            editEditor { editor ->
                val excluded = (editor.required + editor.optional).map { it.id }.toSet()
                editor.copy(results = editor.results + (kind to found.filter { it.id !in excluded }), searching = editor.searching - kind)
            }
        }
    }

    fun addParticipant(kind: PickerKind, user: ParticipantUser) {
        editEditor {
            when (kind) {
                PickerKind.Required -> it.copy(required = it.required + user)
                PickerKind.Optional -> it.copy(optional = it.optional + user)
            }.copy(queries = it.queries - kind, results = it.results - kind)
        }
        scheduleConflictCheck()
    }

    fun removeParticipant(kind: PickerKind, id: Long) {
        editEditor {
            when (kind) {
                PickerKind.Required -> it.copy(required = it.required.filterNot { p -> p.id == id })
                PickerKind.Optional -> it.copy(optional = it.optional.filterNot { p -> p.id == id })
            }
        }
        scheduleConflictCheck()
    }

    /** 500 ms debounced `check-conflicts` while creating a meeting. */
    private fun scheduleConflictCheck() {
        val editor = _ui.value.editor ?: return
        conflictJob?.cancel()
        if (!editor.addMeeting || !editor.creating) return
        val people = editor.required + editor.optional
        if (people.isEmpty()) { editEditor { it.copy(conflicts = emptyMap()) }; return }
        conflictJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            val form = _ui.value.editor?.form ?: return@launch
            val found = runCatching {
                repository.conflicts(ConflictRequest(people.map { it.id }, toIso(form.start, zone()), toIso(form.end, zone()))).conflicts
            }.getOrDefault(emptyList())
            editEditor { it.copy(conflicts = found.associateBy(ParticipantConflict::userId)) }
        }
    }

    fun save() {
        val editor = _ui.value.editor ?: return
        val form = editor.form
        if (form.title.isBlank() || editor.saving) return
        val now = clock()
        if (editor.creating && createBlocked(form, now)) {
            editEditor { it.copy(error = "Cannot create events in the past") }
            return
        }
        editEditor { it.copy(saving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val z = zone()
                val duration = java.time.Duration.between(form.start, form.end)
                if (editor.creating) {
                    for (start in occurrenceStarts(form, now)) {
                        val end = start.plus(duration)
                        // One meeting per generated occurrence, like the web.
                        val meetingId = if (editor.addMeeting) {
                            repository.createMeeting(
                                CreateMeetingRequest(
                                    title = form.title.trim(),
                                    description = form.description.ifBlank { null },
                                    requiredParticipantIds = editor.required.map { it.id },
                                    optionalParticipantIds = editor.optional.map { it.id },
                                    settings = editor.settings,
                                    startTime = toIso(start, z),
                                    endTime = toIso(end, z),
                                ),
                            ).id
                        } else null
                        repository.create(eventPayload(form, start, end, z, meetingId))
                    }
                } else {
                    repository.update(editor.eventId!!, eventPayload(form, form.start, form.end, z))
                }
            }.fold(
                onSuccess = { closeEditor(); refresh() },
                onFailure = { error -> editEditor { it.copy(saving = false, error = error.message) } },
            )
        }
    }

    /** "Delete" / "Cancel Event" (a meeting event cancels the meeting for everyone). */
    fun delete() {
        val editor = _ui.value.editor ?: return
        val id = editor.eventId ?: return
        editEditor { it.copy(saving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.delete(id) }.fold(
                onSuccess = { closeEditor(); refresh() },
                onFailure = { error -> editEditor { it.copy(saving = false, error = error.message) } },
            )
        }
    }

    private fun editEditor(transform: (EventEditor) -> EventEditor) =
        _ui.update { state -> state.copy(editor = state.editor?.let(transform)) }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CalendarViewModel(CalendarRepository(app.aino.mobile.core.AppContainer.get(context).api)) as T
        }
    }
}
