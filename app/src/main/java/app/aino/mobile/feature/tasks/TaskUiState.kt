package app.aino.mobile.feature.tasks

/** The web `showConfirm(title, message, onConfirm, { confirmText, isDanger })`. */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmText: String,
    val danger: Boolean = false,
    val action: () -> Unit,
)

/** New Backlog Ticket form (`useBacklog` fields). */
data class BacklogDraft(
    val title: String = "",
    val description: String = "",
    val priority: String = "medium",
    val assignedTo: Long? = null,
    val dueDate: String = "",
    val labels: List<Long> = emptyList(),
    val sprintId: Long? = null,
    val storyPoints: String? = null,
    val workItemTypeId: Long? = null,
    val projectId: Long? = null,
    /** Set by a board column's "+": the ticket starts in that workflow state. */
    val workflowStateId: Long? = null,
)

/** One-shot snackbar; [action] runs when its button is tapped (e.g. Undo). */
data class TaskNotice(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val openTicketId: Long? = null,
)

/** Shared bottom sheets on the Tasks page. */
enum class TaskSheet { Filters, CreateTicket, Import, SprintPicker }

data class TaskUiState(
    val planScope: PlanScope = PlanScope(),
    // Page.
    val tab: TaskTab = TaskTab.Backlog,
    /** The Backlog is showing the Plan split view (backlog beside a sprint). */
    val planOpen: Boolean = false,
    val sheet: TaskSheet? = null,
    val refreshing: Boolean = false,
    /** Ids patched by a realtime event a moment ago (cards flash briefly). */
    val recentlyUpdated: Set<Long> = emptySet(),
    val tickets: Map<Long, Task> = emptyMap(),
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
    // Global search.
    val searchQuery: String = "",
    val searchResultIds: List<Long> = emptyList(),
    val searching: Boolean = false,
    val searchOpen: Boolean = false,
    val importTaskId: Long? = null,
    val importAssignedTo: Long? = null,
    val importDueDate: String = "",
    // Backlog.
    /** Loaded, unfiltered Plan ids, including completed work. */
    val planTicketIds: List<Long> = emptyList(),
    val planLoaded: Boolean = false,
    val planBaseline: TicketPlanBaseline? = null,
    val planOffset: Int = 0,
    val planHasMore: Boolean = false,
    val quickFilters: BacklogQuickFilters = BacklogQuickFilters(),
    val backlogPageSize: Int = 25,
    val backlogSort: BacklogSort = BacklogSort.Priority,
    val backlogLoading: Boolean = false,
    val backlogLoadingMore: Boolean = false,
    val draft: BacklogDraft = BacklogDraft(),
    val creating: Boolean = false,
    /** Bumped on refresh while the Service Desk tab is visible; the tab reloads on change. */
    val serviceDeskVersion: Int = 0,
    // ── Next-generation surfaces ─────────────────────────────────────────
    /** Per-user Tasks preferences, shared with web so both clients agree. */
    val preferences: TaskPreferences = TaskPreferences(),
    /** User expansion choice is preserved while selection temporarily collapses it. */
    val completedOpen: Boolean = true,
    val completedLoading: Boolean = false,
    /** True once "View all completed" has dropped the recent-window filter. */
    val completedShowingAll: Boolean = false,
    /** Multi-select for bulk actions. */
    val selectedIds: Set<Long> = emptySet(),
    /** Epics with their rollups, for the epic filter. */
    val epics: List<EpicSummary> = emptyList(),
    /** "Updated by <name>" flashes keyed by task id, from realtime deltas. */
    val attribution: Map<Long, String> = emptyMap(),
) {
    val userId get() = planScope.userId
    val role get() = planScope.role
    val teamName get() = planScope.teamName
    val teamId get() = planScope.teamId
    val agileEnabled get() = planScope.agileEnabled
    val sprintEnabled get() = planScope.sprintEnabled
    val customFieldsEnabled get() = planScope.customFieldsEnabled
    val selectedTeamId get() = planScope.selectedTeamId
    val teams get() = planScope.teams
    val teamResolved get() = planScope.teamResolved
    val labels get() = planScope.labels
    val assignableUsers get() = planScope.assignableUsers
    val projects get() = planScope.projects
    val sprints get() = planScope.sprints
    val currentSprintId get() = planScope.currentSprintId
    val currentSprintRow get() = planScope.currentSprintRow
    val agile get() = planScope.agile
    val selectedSprintId get() = planScope.selectedSprintId
    val filters get() = planScope.filters
    val selectedEpicId get() = planScope.selectedEpicId


    internal fun inScope(ticket: Task): Boolean = planScope.contains(ticket)

    private fun resolve(ids: List<Long>, belongs: (Task) -> Boolean): List<Task> =
        ids.mapNotNull(tickets::get).filter { inScope(it) && belongs(it) }

    internal fun containsPlanTicket(ticket: Task): Boolean =
        ticket.container == "backlog" ||
            (ticket.container == "sprint" && ticket.sprintId != null && sprints.none { it.id == ticket.sprintId })

    val planTasks: List<Task> get() = resolve(planTicketIds, ::containsPlanTicket)
    private val filteredPlan: List<Task> get() = planTasks.filter {
        containsPlanTicket(it) && matchesPlanFilters(it, filters, quickFilters, userId) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val backlog: List<Task> get() = filteredPlan.filterNot { it.isClosed }
    val completed: List<Task> get() = filteredPlan.filter { it.isClosed }
    internal val planFiltersActive: Boolean get() = filters != TaskFilters() || !quickFilters.isAll || selectedEpicId != null
    val backlogTotal: Int get() = readPlanTotals().total
    val completedCount: Int get() = readPlanTotals().completed
    val backlogSummary: BacklogSummary get() = readPlanTotals().summary
    val currentSprint: AvailableSprint? get() = planScope.currentSprint
    val sortedBacklog: List<Task> get() = sortBacklog(backlog, backlogSort)
    /** Display sections for the backlog list, or `null` for a flat list. */
    val backlogGroups: List<BacklogGroup>? get() = strandedSprintGroups(sortedBacklog, sprints)
    /** The resolved answer: the server's flag, or the sprint list when it is unknown. */
    val sprintsEnabled: Boolean get() = planScope.sprintsEnabled
    /** The sprint rows on screen belong to the selected team only. */
    val teamSprints: List<AvailableSprint> get() = planScope.teamSprints
    val sprintTabVisible: Boolean get() = TaskTab.Sprint in visibleTabs
    val visibleTabs: List<TaskTab> get() = planScope.visibleTabs
    /** The query scope for backlog and sprint loads. */
    val teamScope: TeamScope get() = planScope.teamScope
    /**
     * The heading's team. The admin's pick wins; otherwise the user's own team,
     * then the sprint's team for an admin browsing one.
     */
    val teamTitle: String
        get() = if (planScope.projectScoped) projects.firstOrNull { it.id == planScope.selectedProjectId }?.name ?: "Plan"
            else teams.firstOrNull { it.id == selectedTeamId }?.name
            ?: if (selectedTeamId == -1L) "Unassigned" else null
            ?: teamName
            ?: currentSprint?.teamName
            ?: "Plan"
    val filterCount: Int get() = filterCount(filters, tab) + if (tab == TaskTab.Backlog) quickFilters.count + (if (selectedEpicId != null) 1 else 0) else 0
    /** Sprint planning is not narrowed by Backlog-only quick chips. */
    val planningBacklog: List<Task> get() = (if (planLoaded) planTasks else backlog).filter {
        it.container == "backlog" && !it.isClosed &&
            matchesFilters(it, filters, userId, includeStatus = false) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val importable: List<Task> get() = planningBacklog
    val backlogHasMore: Boolean get() = planHasMore
    val searchResults: List<Task> get() = searchResultIds.mapNotNull(tickets::get)
    /** Compact cards keep key, title and points; comfortable adds the rest. */
    val compact: Boolean get() = preferences.isCompact
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val completedExpanded: Boolean get() = completedOpen && !hasSelection
    /** Selection mode swaps the "New ticket" FAB for the bulk action bar so the two never overlap. */
    val showNewTicketFab: Boolean get() = tab == TaskTab.Backlog && !searchOpen && !hasSelection &&
        (!planScope.projectScoped || planScope.createProjects.isNotEmpty())
    /** The epic filter narrows the backlog and board to one epic's children. */
    val epicFiltered: List<Task> get() = selectedEpicId?.let { epic ->
        backlog.filter { it.parentTaskId == epic }
    } ?: backlog
}

/**
 * One projection for loading, quick/advanced filters, mutations and realtime.
 *
 * Only sprint-less tickets are backlog. A sprint row that reaches us anyway — a
 * stale page, or a task whose sprint was deleted server-side — is kept so it
 * cannot vanish, but it is rendered in a separate "in a sprint" group rather
 * than mixed into the backlog. A task whose sprint is still open is excluded:
 * it belongs on that sprint's board and nowhere else.
 */
internal fun TaskUiState.projectPlan(): TaskUiState {
    return copy(selectedIds = selectedIds.intersect(backlog.map { it.id }.toSet()))
}
