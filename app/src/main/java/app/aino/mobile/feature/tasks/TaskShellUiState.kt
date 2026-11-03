package app.aino.mobile.feature.tasks

data class TaskShellUiState(
    val planScope: PlanScope = PlanScope(),
    val tab: TaskTab = TaskTab.Backlog,
    val sheet: TaskSheet? = null,
    val refreshing: Boolean = false,
    val recentlyUpdated: Set<Long> = emptySet(),
    val tickets: Map<Long, Task> = emptyMap(),
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
    val searchQuery: String = "",
    val searchResultIds: List<Long> = emptyList(),
    val searching: Boolean = false,
    val searchOpen: Boolean = false,
    val carriedCount: Int = 0,
    val scheduledTicketIds: List<Long> = emptyList(),
    val scheduledLoading: Boolean = false,
    val scheduledLoaded: Boolean = false,
    val scheduledFocus: String? = null,
    val inlineCommentTaskId: Long? = null,
    val serviceDeskVersion: Int = 0,
    val preferences: TaskPreferences = TaskPreferences(),
    val overlays: ScheduleOverlays = ScheduleOverlays(),
    val overlayTypes: Set<String> = setOf("leave", "holiday", "meeting"),
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
    val currentSprint get() = planScope.currentSprint
    val teamSprints get() = planScope.teamSprints
    val sprintsEnabled get() = planScope.sprintsEnabled
    val visibleTabs get() = planningTabs(agileEnabled, teamSprints, currentSprintId)
    val teamScope get() = planScope.teamScope
    internal fun inScope(ticket: Task): Boolean = when {
        !showsTeamPicker(role) -> teamId == null || ticket.teamId == teamId
        selectedTeamId == -1L -> ticket.teamId == null
        selectedTeamId != null -> ticket.teamId == selectedTeamId
        else -> true
    }
    internal fun containsScheduledTicket(ticket: Task): Boolean {
        val (from, to) = scheduledWindow()
        val plannedDay = localDateOf(ticket.plannedDate) ?: return false
        return inScope(ticket) && ticket.container == "scheduled" && ticket.workflowStateKey != "cancelled" &&
            !plannedDay.isBefore(from) && !plannedDay.isAfter(to) &&
            matchesFilters(ticket, filters, userId, includeStatus = true)
    }
    val scheduled get() = scheduledTicketIds.mapNotNull(tickets::get).filter(::containsScheduledTicket)
    val searchResults get() = searchResultIds.mapNotNull(tickets::get)
}

/** Composed screen values, not another Ticket owner or a ViewModel dependency. */
internal fun tasksScreenState(shell: TaskShellUiState, plan: PlanUiState): TaskUiState = TaskUiState(
    planScope = shell.planScope, tab = shell.tab, sheet = shell.sheet, refreshing = shell.refreshing,
    recentlyUpdated = shell.recentlyUpdated, tickets = shell.tickets,
    error = plan.error ?: shell.error, confirm = plan.confirm ?: shell.confirm,
    searchQuery = shell.searchQuery, searchResultIds = shell.searchResultIds,
    searching = shell.searching, searchOpen = shell.searchOpen, carriedCount = shell.carriedCount,
    scheduledTicketIds = shell.scheduledTicketIds, scheduledLoading = shell.scheduledLoading,
    scheduledLoaded = shell.scheduledLoaded, scheduledFocus = shell.scheduledFocus,
    inlineCommentTaskId = shell.inlineCommentTaskId, serviceDeskVersion = shell.serviceDeskVersion,
    preferences = shell.preferences, overlays = shell.overlays, overlayTypes = shell.overlayTypes,
    attribution = shell.attribution,
    planOpen = plan.planOpen, planTicketIds = plan.planTicketIds, planLoaded = plan.planLoaded,
    planBaseline = plan.planBaseline, planOffset = plan.planOffset, planHasMore = plan.planHasMore,
    quickFilters = plan.quickFilters, backlogPageSize = plan.backlogPageSize, backlogSort = plan.backlogSort,
    backlogLoading = plan.backlogLoading, backlogLoadingMore = plan.backlogLoadingMore,
    draft = plan.draft, creating = plan.creating, completedOpen = plan.completedOpen,
    completedLoading = plan.completedLoading, completedShowingAll = plan.completedShowingAll,
    selectedIds = plan.selectedIds, epics = plan.epics, importTaskId = plan.importTaskId,
    importAssignedTo = plan.importAssignedTo, importDueDate = plan.importDueDate,
)
