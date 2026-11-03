package app.aino.mobile.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.ApiError
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TicketDetailUiState(
    val ticketId: Long,
    val detail: Task? = null,
    val detailLoading: Boolean = true,
    val detailEditing: Boolean = false,
    val detailStale: Boolean = false,
    val detailTab: DetailTab = DetailTab.Comments,
    val history: List<TaskHistoryEntry> = emptyList(),
    val detailComments: List<TaskComment> = emptyList(),
    val commentsLoading: Boolean = false,
    val recentlyUpdated: Set<Long> = emptySet(),
    val worklogs: List<TaskWorklog> = emptyList(),
    val worklogsLoading: Boolean = true,
    val worklogDraft: WorklogDraft? = null,
    val editingWorklogId: Long? = null,
    val worklogBusy: Boolean = false,
    val worklogDurationError: String? = null,
    val worklogError: String? = null,
    val worklogWarnings: List<String> = emptyList(),
    val estimateDraft: EstimateDraft? = null,
    val estimateBusy: Boolean = false,
    val estimateError: String? = null,
    val closed: Boolean = false,
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
    val planScope: PlanScope = PlanScope(),
    val sprintBoard: SprintBoardResponse? = null,
) {
    val canLogWork get() = !closed && detail?.capability(TaskCapability.LogWork)?.allowed == true
    val canLogWorkForOthers get() = !closed && detail?.capability(TaskCapability.LogWorkForOthers)?.allowed == true
    val canEditEstimate get() = !closed && detail?.capability(TaskCapability.EditEstimate)?.allowed == true
    val overEstimate get() = detail?.let { it.originalEstimateMinutes?.let { original -> it.timeSpentMinutes > original } } == true
    val agile get() = planScope.agile
    val agileEnabled get() = planScope.agileEnabled
    val customFieldsEnabled get() = planScope.customFieldsEnabled
    val selectedSprintId get() = planScope.selectedSprintId
    val sprints get() = planScope.sprints
    val labels get() = planScope.labels
    val projects get() = planScope.projects
    val assignableUsers get() = planScope.assignableUsers
    val userId get() = planScope.userId
}

/** One route, one Ticket id. All Ticket data is projected from the signed-in session. */
class TicketDetailViewModel(
    val ticketId: Long,
    private val repository: TaskRepository,
    private val ticketSession: TicketSession,
) : ViewModel(), TicketCommentActions {
    private val ticketScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(ticketSession.job))
    private val sync = ticketSession.sync(repository)
    private val _ui = ticketSession.state(TicketDetailUiState(ticketId, planScope = ticketSession.planScope.value))
    val ui: StateFlow<TicketDetailUiState> = _ui.asStateFlow()
    val notices = ticketSession.notices
    internal val repo get() = repository
    private var errorJob: Job? = null
    private val worklogLock = Any()
    private var worklogRevision = 0L
    private var worklogRead = 0L
    private var effortWriting = false

    private fun beginEffortWrite(): Boolean = synchronized(worklogLock) {
        if (effortWriting) false else {
            effortWriting = true
            worklogRevision++
            true
        }
    }

    private fun endEffortWrite() = synchronized(worklogLock) {
        effortWriting = false
        _ui.update { it.copy(estimateBusy = false, worklogBusy = false) }
    }

    private fun publishEffort(summary: WorklogTicketSummary) {
        ticketSession.store.update(ticketId) {
            it.copy(timeSpentMinutes = summary.timeSpentMinutes, loggedMinutes = summary.timeSpentMinutes,
                estimatedMinutes = summary.originalEstimateMinutes, originalEstimateMinutes = summary.originalEstimateMinutes,
                remainingEstimateMinutes = summary.remainingEstimateMinutes)
        }
    }

    init {
        ticketScope.launch {
            ticketSession.store.state.collect { snapshot ->
                _ui.update { state ->
                    // A Ticket cancelled while open closes; one opened already cancelled
                    // (from Completed) stays readable as history.
                    val stored = snapshot.tickets[ticketId].takeUnless { state.closed }
                    val ticket = stored?.takeUnless { it.isCancelled && state.detail?.isCancelled == false }
                    state.copy(
                        detail = ticket,
                        recentlyUpdated = snapshot.recentlyChanged,
                        detailStale = ticket != null && (state.detailStale ||
                            (state.detailEditing && state.detail != null && state.detail != ticket)),
                        history = if (ticket == null) emptyList() else state.history,
                        closed = state.closed || (state.detail != null && ticket == null),
                    )
                }
            }
        }
        ticketScope.launch {
            ticketSession.planScope.collect { scope -> _ui.update { it.copy(planScope = scope) } }
        }
        ticketScope.launch {
            ticketSession.commentThreads.collect { threads ->
                _ui.update { it.copy(
                    detailComments = threads[ticketId]?.items.orEmpty(),
                    commentsLoading = threads[ticketId]?.loading ?: false,
                ) }
            }
        }
        ticketScope.launch {
            sync.signals.collect {
                if (!_ui.value.detailEditing && !_ui.value.closed) refreshDetail()
            }
        }
        ticketScope.launch {
            sync.changes.collect { change ->
                when (change) {
                    is TicketSyncChange.Updated -> if (change.ticketId == ticketId) {
                        if (change.staleDetail) loadWorklogs()
                        if (_ui.value.detailEditing && change.staleDetail) {
                            _ui.update { it.copy(detailStale = true) }
                        } else {
                            if (change.staleDetail) ticketSession.setCommentThreadIfAbsent(
                                CommentThread(ticketId, change.comments, loading = false),
                            )
                            io { refreshHistory() }
                        }
                    }
                    is TicketSyncChange.Removed -> if (change.ticketId == ticketId) {
                        closeDetail()
                        if (change.reason == RemovalReason.Deleted) notify("This task was deleted")
                    }
                }
            }
        }
        refreshDetail()
    }

    override fun onCleared() = ticketScope.cancel()

    private fun io(block: suspend () -> Unit) = ticketScope.launch(Dispatchers.IO) { block() }
    private fun notify(text: String) = ticketSession.publishNotice(TaskNotice(text))
    private fun fail(error: Throwable, fallback: String) {
        val message = if (error is ApiError) userFacingMessage(error, fallback)
        else error.message?.takeIf { it.isNotBlank() && it != "Task action failed" && !it.startsWith("HTTP ") } ?: fallback
        _ui.update { it.copy(error = capabilityFailureCopy(message) ?: message) }
        errorJob?.cancel()
        errorJob = ticketScope.launch {
            delay(5_000)
            _ui.update { it.copy(error = null) }
        }
    }
    private fun save(ticket: Task, removalToken: Any? = null) {
        ticketSession.planRevision.incrementAndGet()
        ticketSession.store.upsert(ticket, removalToken)
    }
    private fun allowed(task: Task, capability: TaskCapability): Boolean {
        if (task.id != ticketId || _ui.value.closed) return false
        val decision = (ticketSession.store.state.value.tickets[ticketId] ?: task).capability(capability)
        if (!decision.allowed) notify(decision.reasonCopy())
        return decision.allowed
    }
    private suspend fun refreshHistory() {
        runCatching { repository.loadHistory(ticketId) }.onSuccess { history ->
            _ui.update { if (it.closed) it else it.copy(history = history) }
        }
    }

    fun closeDetail() = _ui.update {
        it.copy(closed = true, detail = null, detailEditing = false, detailStale = false,
            detailLoading = false, history = emptyList(), worklogs = emptyList(), worklogsLoading = false,
            worklogDraft = null, editingWorklogId = null, worklogBusy = false, worklogDurationError = null, worklogError = null,
            worklogWarnings = emptyList(), estimateDraft = null, estimateError = null, estimateBusy = false)
    }
    fun setDetailTab(tab: DetailTab) = _ui.update { it.copy(detailTab = tab) }
    fun startEdit() {
        val task = _ui.value.detail ?: return
        if (allowed(task, TaskCapability.Edit)) _ui.update { it.copy(detailEditing = true) }
    }
    fun cancelEdit() {
        val stale = _ui.value.detailStale
        _ui.update { it.copy(detailEditing = false) }
        if (stale) refreshDetail()
    }
    fun refreshDetail() {
        if (_ui.value.closed) return
        val removalToken = ticketSession.store.removalToken(ticketId)
        val worklogRevisionBefore = synchronized(worklogLock) { worklogRevision }
        val commentsBefore = ticketSession.commentThreads.value[ticketId]?.items.orEmpty()
        _ui.update { it.copy(detailLoading = true, detailStale = false) }
        io {
            runCatching { repository.loadDetail(ticketId) }.fold(
                onSuccess = { ticket ->
                    synchronized(worklogLock) {
                        val current = ticketSession.store.state.value.tickets[ticketId]
                        val fresh = if ((effortWriting || worklogRevisionBefore != worklogRevision) && current != null) ticket.copy(
                            timeSpentMinutes = current.timeSpentMinutes, loggedMinutes = current.loggedMinutes,
                            estimatedMinutes = current.estimatedMinutes, originalEstimateMinutes = current.originalEstimateMinutes,
                            remainingEstimateMinutes = current.remainingEstimateMinutes,
                        ) else ticket
                        save(fresh, removalToken)
                    }
                    ticketSession.reconcileCommentThread(ticketId, commentsBefore, ticket.comments)
                    ticketSession.store.update(ticketId) {
                        val items = ticketSession.commentThreads.value[ticketId]?.items.orEmpty()
                        if (items == ticket.comments) it else it.copy(commentCount = items.size)
                    }
                },
                onFailure = { error ->
                    if (ticketSession.commentThreads.value[ticketId] == null) {
                        val comments = runCatching { repository.loadComments(ticketId) }.getOrDefault(emptyList())
                        ticketSession.setCommentThreadIfAbsent(CommentThread(ticketId, comments, loading = false))
                    }
                    if (_ui.value.detail == null) fail(error, "Failed to load ticket")
                },
            )
            refreshHistory()
            val sprintId = _ui.value.detail?.sprintId
            if (sprintId != null && _ui.value.sprintBoard?.sprint?.id != sprintId) {
                runCatching { repository.loadSprintBoard(sprintId) }.onSuccess { board ->
                    _ui.update { it.copy(sprintBoard = board.copy(tickets = emptyList())) }
                }
            }
            synchronized(worklogLock) {
                if (worklogRevisionBefore == worklogRevision) loadWorklogs()
            }
            _ui.update { it.copy(detailLoading = false) }
        }
    }

    fun loadComments() {
        val cached = ticketSession.commentThreads.value[ticketId] ?: CommentThread(ticketId)
        ticketSession.setCommentThread(cached.copy(loading = true))
        io {
            runCatching { repository.loadComments(ticketId) }.fold(
                onSuccess = { fetched ->
                    val items = ticketSession.reconcileCommentThread(ticketId, cached.items, fetched)
                    ticketSession.store.update(ticketId) { it.copy(commentCount = items.size) }
                },
                onFailure = { error ->
                    ticketSession.updateCommentThread(ticketId) { it.copy(loading = false) }
                    fail(error, "Failed to load comments")
                },
            )
        }
    }

    fun saveEdit(draft: TaskEditDraft) {
        val task = _ui.value.detail ?: return
        if (draft.title.isBlank()) return
        val description = if (draft.description == stripHtml(draft.descriptionHtml)) draft.descriptionHtml else plainTextToHtml(draft.description)
        updateFields(task, buildJsonObject {
            put("title", draft.title.trim())
            put("description", description)
        }, "Saved") { _ui.update { it.copy(detailEditing = false, detailStale = false) } }
    }
    fun updateFields(task: Task, fields: JsonObject, done: String? = null, onSaved: () -> Unit = {}) {
        if (!allowed(task, TaskCapability.Edit)) return
        io {
            runCatching { repository.patchFields(ticketId, fields) }.fold(
                onSuccess = {
                    save(it)
                    withContext(Dispatchers.Main) { onSaved() }
                    done?.let(::notify)
                    refreshHistory()
                },
                onFailure = { fail(it, "Failed to update item") },
            )
        }
    }
    fun setPriority(task: Task, value: String) = updateFields(task, buildJsonObject { put("priority", value) })
    fun setAssignee(task: Task, userId: Long?) = updateFields(task, buildJsonObject { put("assigned_to", userId?.let(::JsonPrimitive) ?: JsonNull) })
    fun setDueDate(task: Task, date: String) = updateFields(task, buildJsonObject { put("due_date", date.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull) })
    fun setLabels(task: Task, ids: List<Long>) = updateFields(task, buildJsonObject { put("label_ids", JsonArray(ids.map(::JsonPrimitive))) })
    fun setStoryPoints(task: Task, value: String?) = updateFields(task, buildJsonObject { put("story_points", value?.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull) })
    fun setWorkItemType(task: Task, id: Long?) = updateFields(task, buildJsonObject { put("work_item_type_id", id?.let(::JsonPrimitive) ?: JsonNull) })
    fun setProject(task: Task, id: Long) = updateFields(task, buildJsonObject { put("project_id", id) })
    fun setStatus(task: Task, state: WorkflowState) {
        if (task.id != ticketId || task.workflowStateKey == state.key) return
        val accepting = if (task.sprintId != null && task.sprintId == _ui.value.selectedSprintId) {
            _ui.value.sprintBoard?.columns?.firstOrNull { it.stateId == state.id }?.accepting ?: false
        } else true
        val decision = task.dropTargetDecision(state.id, accepting)
        if (!decision.allowed) return notify(decision.reasonCopy())
        io {
            runCatching { repository.updateStatus(ticketId, state.key) }.fold(
                onSuccess = { save(it); notify("Status: ${state.name.ifEmpty { state.key }}"); refreshHistory() },
                onFailure = { fail(it, "Failed to update status") },
            )
        }
    }

    fun setBlocker(taskId: Long, blocked: Boolean, reason: String?, onDone: () -> Unit = {}, onError: (String) -> Unit = {}) =
        relationshipWrite(taskId, { repository.setBlocker(ticketId, blocked, reason) }, "Failed to update blocker", onDone, onError)

    fun setParent(taskId: Long, parentId: Long?, onDone: () -> Unit = {}, onError: (String) -> Unit = {}) =
        relationshipWrite(taskId, { repository.setParent(ticketId, parentId) }, "Failed to set parent", onDone, onError)

    fun saveCriteria(
        taskId: Long,
        criteria: List<CriterionPayload>,
        onDone: (List<AcceptanceCriterion>) -> Unit = {},
        onError: (String) -> Unit = {},
    ) = relationshipWrite(
        taskId,
        { repository.saveCriteria(ticketId, criteria) },
        "Failed to save",
        { onDone(_ui.value.detail?.acceptanceCriteria.orEmpty()) },
        onError,
    )

    fun addDependency(taskId: Long, otherId: Long, type: String, onDone: () -> Unit, onError: (String) -> Unit) =
        relationshipWrite(taskId, { repository.addDependency(ticketId, otherId, type) }, "Failed to add link", onDone, onError)

    fun removeDependency(taskId: Long, linkId: Long, onDone: () -> Unit, onError: (String) -> Unit) =
        relationshipWrite(taskId, { repository.removeDependency(ticketId, linkId) }, "Failed to remove link", onDone, onError)

    fun saveCustomFieldValues(taskId: Long, values: JsonObject, onDone: (JsonObject) -> Unit, onError: (String) -> Unit) {
        var saved = values
        relationshipWrite(taskId, { saved = repository.saveCustomFieldValues(ticketId, values) },
            "Failed to save custom fields", { onDone(saved) }, onError)
    }

    private fun relationshipWrite(taskId: Long, write: () -> Unit, fallback: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val task = _ui.value.detail
        if (task == null || taskId != ticketId || !allowed(task, TaskCapability.Edit)) {
            onError(task?.capability(TaskCapability.Edit)?.reasonCopy() ?: "Ticket unavailable")
            return
        }
        io {
            // These endpoints return relationship metadata, not a full Ticket.
            runCatching { write(); repository.loadDetail(ticketId) }.fold(
                onSuccess = {
                    save(it)
                    refreshHistory()
                    withContext(Dispatchers.Main) { onDone() }
                },
                onFailure = {
                    fail(it, fallback)
                    withContext(Dispatchers.Main) { onError(_ui.value.error ?: fallback) }
                },
            )
        }
    }

    fun schedule(taskId: Long, date: String, closeAfter: Boolean = false) {
        val task = _ui.value.detail ?: return
        if (taskId != ticketId || date.isEmpty() || !allowed(task, TaskCapability.Schedule)) return
        placement(
            { repository.scheduleTask(ticketId, date) },
            TaskNotice("Scheduled for ${formatDate(date)}", "View", scheduledDate = date),
            closeAfter,
        )
    }
    fun unschedule(taskId: Long, title: String?, closeAfter: Boolean = false, ask: Boolean = true) {
        val task = _ui.value.detail ?: return
        if (taskId != ticketId || !allowed(task, TaskCapability.Schedule)) return
        val previousDate = task.plannedDate?.take(10)
        val notice = if (previousDate == null) TaskNotice("Moved to backlog")
        else TaskNotice("Moved to backlog", "Undo", action = {
            ticketSession.launch {
                runCatching { withContext(Dispatchers.IO) { repository.scheduleTask(ticketId, previousDate) } }.fold(
                    onSuccess = { save(it) },
                    onFailure = { ticketSession.publishNotice(TaskNotice("Failed to undo move")) },
                )
            }
        })
        val action = { placement({ repository.unscheduleTask(ticketId) }, notice, closeAfter); Unit }
        if (ask) confirm("Move to Backlog", "Move \"${title ?: "this task"}\" to backlog? It will be removed from the planner.", "Move to Backlog", action = action)
        else action()
    }
    fun assignSprint(task: Task, sprintId: Long?) {
        if (!allowed(task, TaskCapability.PlaceInSprint)) return
        val name = _ui.value.sprints.firstOrNull { it.id == sprintId }?.name
        placement({ repository.assignSprint(ticketId, sprintId) }, TaskNotice(if (name != null) "Added to $name" else "Moved to backlog"))
    }
    private fun placement(write: () -> Task, notice: TaskNotice, closeAfter: Boolean = false) {
        io {
            runCatching(write).fold(
                onSuccess = {
                    save(it)
                    ticketSession.store.highlight(it.id)
                    ticketSession.publishNotice(notice)
                    refreshHistory()
                    if (closeAfter) closeDetail()
                },
                onFailure = { fail(it, "Failed to move ticket") },
            )
        }
    }
    fun requestDelete(task: Task) {
        if (!allowed(task, TaskCapability.Cancel)) return
        confirm("Cancel ticket", "Cancel \"${task.title}\"? The ticket and its history are kept, and it leaves the open work.", "Cancel ticket", true) {
            io {
                runCatching { repository.deleteTask(ticketId) }.fold(
                    onSuccess = { response ->
                        ticketSession.planRevision.incrementAndGet()
                        ticketSession.store.markCancelled(ticketId, response.task)
                        closeDetail()
                        notify("Cancelled ${task.displayKey}")
                    },
                    onFailure = { fail(it, "Failed to cancel ticket") },
                )
            }
        }
    }
    private fun confirm(title: String, message: String, confirmText: String, danger: Boolean = false, action: () -> Unit) {
        _ui.update { it.copy(confirm = ConfirmRequest(title, message, confirmText, danger, action)) }
    }
    fun acceptConfirm() {
        val request = _ui.value.confirm ?: return
        dismissConfirm()
        request.action()
    }
    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    override fun addComment(taskId: Long, text: String, file: CommentFile?, mentions: Map<String, Long>, onDone: () -> Unit) {
        if (taskId != ticketId || (text.isBlank() && file == null)) return
        validateComment(text).takeIf { file == null }?.let { _ui.update { state -> state.copy(error = it) }; return }
        io {
            val html = plainTextToHtml(text, mentions)
            runCatching {
                if (file != null) repository.addCommentWithFile(ticketId, html, file.name, file.mimeType, file.bytes)
                else repository.addComment(ticketId, html)
            }.fold(
                onSuccess = { comment ->
                    ticketSession.updateCommentThread(ticketId) { thread ->
                        thread.copy(items = if (thread.items.any { it.id == comment.id }) thread.items else thread.items + comment, loading = false)
                    }
                    ticketSession.store.update(ticketId) {
                        it.copy(commentCount = ticketSession.commentThreads.value[ticketId]?.items?.size ?: it.commentCount)
                    }
                    withContext(Dispatchers.Main) { onDone() }
                    refreshHistory()
                },
                onFailure = { fail(it, "Failed to add comment") },
            )
        }
    }
    override fun editComment(taskId: Long, commentId: Long, text: String, onDone: () -> Unit) {
        if (taskId != ticketId || text.isBlank()) return
        io {
            runCatching { repository.updateComment(ticketId, commentId, plainTextToHtml(text)) }.fold(
                onSuccess = { updated ->
                    ticketSession.updateCommentThread(ticketId) { thread ->
                        thread.copy(items = thread.items.map { old ->
                            if (old.id == commentId) updated.copy(username = updated.username ?: old.username,
                                fullName = updated.fullName ?: old.fullName, avatar = updated.avatar ?: old.avatar) else old
                        }, loading = false)
                    }
                    withContext(Dispatchers.Main) { onDone() }
                    refreshHistory()
                },
                onFailure = { fail(it, "Failed to update comment") },
            )
        }
    }
    override fun deleteComment(taskId: Long, commentId: Long) {
        if (taskId != ticketId) return
        confirm("Delete Comment", "Are you sure you want to delete this comment? This cannot be undone.", "Delete", true) {
            io {
                runCatching { repository.deleteComment(ticketId, commentId) }.fold(
                    onSuccess = {
                        ticketSession.updateCommentThread(ticketId) { thread -> thread.copy(items = thread.items.filterNot { it.id == commentId }, loading = false) }
                        ticketSession.store.update(ticketId) {
                            it.copy(commentCount = ticketSession.commentThreads.value[ticketId]?.items?.size ?: it.commentCount)
                        }
                        refreshHistory()
                    },
                    onFailure = { fail(it, "Failed to delete comment") },
                )
            }
        }
    }
    fun startEstimateEdit(kind: EstimateKind) {
        if (!_ui.value.canEditEstimate || _ui.value.estimateBusy || _ui.value.worklogBusy) return
        val task = _ui.value.detail ?: return
        val minutes = if (kind == EstimateKind.Original) task.originalEstimateMinutes else task.remainingEstimateMinutes
        _ui.update { it.copy(estimateDraft = EstimateDraft(kind, minutes?.let(::formatMinutes).orEmpty()), estimateError = null) }
    }

    fun updateEstimateDraft(value: String) {
        if (!_ui.value.estimateBusy) _ui.update { it.copy(estimateDraft = it.estimateDraft?.copy(value = value), estimateError = null) }
    }

    fun dismissEstimate() {
        if (!_ui.value.estimateBusy) _ui.update { it.copy(estimateDraft = null, estimateError = null) }
    }

    fun saveEstimate(value: String) {
        val task = _ui.value.detail ?: return
        val draft = _ui.value.estimateDraft ?: return
        if (_ui.value.estimateBusy || _ui.value.worklogBusy || !allowed(task, TaskCapability.EditEstimate) || !beginEffortWrite()) return
        _ui.update { it.copy(estimateBusy = true, estimateDraft = draft.copy(value = value), estimateError = null) }
        io {
            runCatching { repository.setEstimates(ticketId, buildJsonObject { put(draft.kind.field, value) }) }.fold(
                onSuccess = { result ->
                    synchronized(worklogLock) {
                        worklogRevision++
                        if (!_ui.value.closed) {
                            publishEffort(result.ticket)
                            _ui.update { it.copy(estimateDraft = null) }
                        }
                    }
                    refreshHistory()
                },
                onFailure = { error -> _ui.update { it.copy(estimateError = error.message ?: "Failed to save estimate") } },
            )
            endEffortWrite()
        }
    }

    fun startLogWork() {
        val task = _ui.value.detail ?: return
        if (!_ui.value.worklogBusy && !_ui.value.estimateBusy && allowed(task, TaskCapability.LogWork)) {
            _ui.update { it.copy(worklogDraft = WorklogDraft(), editingWorklogId = null, worklogDurationError = null, worklogError = null) }
        }
    }

    fun startEditWorklog(worklogId: Long) {
        if (_ui.value.closed || _ui.value.worklogBusy || _ui.value.estimateBusy) return
        val row = _ui.value.worklogs.firstOrNull { it.id == worklogId && it.canEdit } ?: return
        _ui.update { it.copy(editingWorklogId = row.id, worklogDurationError = null, worklogError = null, worklogDraft = WorklogDraft(
            row.dayWorked, "${row.minutes}m", row.startTime.orEmpty(), row.comment.orEmpty(), authorId = row.authorId,
        )) }
    }

    fun saveWorklog(draft: WorklogDraft) {
        val task = _ui.value.detail ?: return
        val editingId = _ui.value.editingWorklogId
        if (_ui.value.worklogBusy || _ui.value.estimateBusy) return
        if (editingId == null) {
            if (!allowed(task, TaskCapability.LogWork)) return
        } else if (_ui.value.closed || _ui.value.worklogs.none { it.id == editingId && it.canEdit }) return
        if (!beginEffortWrite()) return
        _ui.update { it.copy(worklogBusy = true, worklogDraft = draft, worklogDurationError = null, worklogError = null) }
        io {
            val payload = LogWorkPayload(draft.dayWorked, draft.timeSpent, draft.startTime.takeIf { it.isNotBlank() }, draft.comment.takeIf { it.isNotBlank() },
                authorId = draft.authorId.takeIf { _ui.value.canLogWorkForOthers &&
                    (editingId == null || it != _ui.value.worklogs.firstOrNull { row -> row.id == editingId }?.authorId) },
                remainingMode = draft.remainingMode, remainingEstimate = draft.remainingEstimate.takeIf { draft.remainingMode == "set" })
            runCatching {
                if (editingId == null) repository.logWork(ticketId, payload)
                else repository.editWorklog(ticketId, editingId, payload)
            }.fold(
                onSuccess = { result ->
                    synchronized(worklogLock) {
                        worklogRevision++
                        if (!_ui.value.closed) {
                            publishEffort(result.ticket)
                            _ui.update { it.copy(worklogs = (it.worklogs.filterNot { row -> row.id == result.worklog.id } + result.worklog).newestFirst(),
                                worklogDraft = null, editingWorklogId = null, worklogWarnings = result.warnings) }
                        }
                    }
                    refreshHistory()
                },
                onFailure = { error ->
                    _ui.update { it.copy(
                        worklogDurationError = if ((error as? TaskFailure)?.code == "INVALID_DURATION") error.message else null,
                        worklogError = if ((error as? TaskFailure)?.code == "INVALID_DURATION") null else error.message ?: "Failed to save worklog",
                    ) }
                },
            )
            endEffortWrite()
        }
    }

    fun deleteWorklog(worklogId: Long, remainingMode: String = "auto", remainingEstimate: String = "") {
        if (_ui.value.closed || _ui.value.worklogBusy || _ui.value.estimateBusy || _ui.value.worklogs.none { it.id == worklogId && it.canDelete } || !beginEffortWrite()) return
        _ui.update { it.copy(worklogBusy = true, worklogError = null) }
        io {
            runCatching { repository.deleteWorklog(ticketId, worklogId,
                RemainingEstimatePayload(remainingMode, remainingEstimate.takeIf { remainingMode == "set" })) }.fold(
                onSuccess = { result ->
                    synchronized(worklogLock) {
                        worklogRevision++
                        if (!_ui.value.closed) {
                            publishEffort(result.ticket)
                            _ui.update { it.copy(worklogs = it.worklogs.filterNot { row -> row.id == worklogId }) }
                        }
                    }
                    refreshHistory()
                },
                onFailure = { error -> _ui.update { it.copy(worklogError = error.message ?: "Failed to delete worklog") } },
            )
            endEffortWrite()
        }
    }

    fun loadWorklogs() {
        if (_ui.value.closed) return
        val (revision, read) = synchronized(worklogLock) { worklogRevision to ++worklogRead }
        _ui.update { it.copy(worklogsLoading = true) }
        io {
            runCatching { repository.loadWorklogs(ticketId) }.fold(
                onSuccess = { result ->
                    synchronized(worklogLock) {
                        if (!_ui.value.closed && !effortWriting && revision == worklogRevision && read == worklogRead) {
                            if (result.hasEstimates) publishEffort(WorklogTicketSummary(ticketId, result.timeSpentMinutes,
                                originalEstimateMinutes = result.originalEstimateMinutes, remainingEstimateMinutes = result.remainingEstimateMinutes))
                            else ticketSession.store.update(ticketId) {
                                it.copy(timeSpentMinutes = result.timeSpentMinutes, loggedMinutes = result.timeSpentMinutes)
                            }
                            _ui.update { it.copy(worklogs = result.worklogs.newestFirst()) }
                        }
                    }
                },
                onFailure = { fail(it, "Failed to load worklogs") },
            )
            synchronized(worklogLock) {
                if (read == worklogRead) _ui.update { it.copy(worklogsLoading = false) }
            }
        }
    }

    fun dismissWorklog() {
        if (!_ui.value.worklogBusy) _ui.update {
            it.copy(worklogDraft = null, editingWorklogId = null, worklogDurationError = null, worklogError = null)
        }
    }

    fun updateWorklogDraft(draft: WorklogDraft) {
        if (!_ui.value.worklogBusy) _ui.update { it.copy(worklogDraft = draft, worklogDurationError = null, worklogError = null) }
    }

    companion object {
        /** Key resolution is performed before entering the id-keyed route. */
        suspend fun resolveKey(repository: TaskRepository, session: TicketSession, key: String): Long =
            withContext(Dispatchers.IO) {
                repository.loadByKey(key).also { session.store.upsert(it) }.id
            }

        fun factory(context: Context, ticketId: Long, session: TicketSession): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TicketDetailViewModel(ticketId, TaskRepository(app.aino.mobile.core.AppContainer.get(context).api), session) as T
            }
    }
}
