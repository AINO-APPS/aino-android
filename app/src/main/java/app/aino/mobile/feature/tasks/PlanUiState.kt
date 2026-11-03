package app.aino.mobile.feature.tasks

data class PlanUiState(
    val planScope: PlanScope = PlanScope(),
    val tickets: Map<Long, Task> = emptyMap(),
    val recentlyUpdated: Set<Long> = emptySet(),
    val planOpen: Boolean = false,
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
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
    val importTaskId: Long? = null,
    val importAssignedTo: Long? = null,
    val importDueDate: String = "",
    val completedOpen: Boolean = true,
    val completedLoading: Boolean = false,
    val completedShowingAll: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val epics: List<EpicSummary> = emptyList(),
) {
    val userId get() = planScope.userId
    val role get() = planScope.role
    val teamId get() = planScope.teamId
    val selectedTeamId get() = planScope.selectedTeamId
    val sprints get() = planScope.sprints
    val teamSprints get() = planScope.teamSprints
    val currentSprintId get() = planScope.currentSprintId
    val currentSprint get() = planScope.currentSprint
    val selectedSprintId get() = planScope.selectedSprintId
    val teamScope get() = planScope.teamScope
    val filters get() = planScope.filters
    val selectedEpicId get() = planScope.selectedEpicId
    val agile get() = planScope.agile
    internal fun inScope(ticket: Task): Boolean = planScope.contains(ticket)
    // Cancelled Tickets stay members so the Completed section can list them;
    // `backlog` (open work) excludes them through `isClosed`.
    internal fun containsPlanTicket(ticket: Task): Boolean =
        ticket.container == "backlog" ||
            (ticket.container == "sprint" && ticket.sprintId != null && sprints.none { it.id == ticket.sprintId })
    val planTasks get() = planTicketIds.mapNotNull(tickets::get).filter { inScope(it) && containsPlanTicket(it) }
    private val filteredPlan get() = planTasks.filter {
        matchesPlanFilters(it, filters, quickFilters, userId) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val backlog get() = filteredPlan.filterNot { it.isClosed }
    val completed get() = filteredPlan.filter { it.isClosed }
    internal val planFiltersActive get() = filters != TaskFilters() || !quickFilters.isAll || selectedEpicId != null
    val backlogTotal get() = readPlanTotals().total
    val completedCount get() = readPlanTotals().completed
    val backlogSummary get() = readPlanTotals().summary
    val sortedBacklog get() = sortBacklog(backlog, backlogSort)
    val backlogGroups get() = strandedSprintGroups(sortedBacklog, sprints)
    val planningBacklog get() = planTasks.filter {
        it.container == "backlog" && !it.isClosed &&
            matchesFilters(it, filters, userId, includeStatus = false) &&
            (selectedEpicId == null || it.parentTaskId == selectedEpicId)
    }
    val importable get() = planningBacklog
    val backlogHasMore get() = planHasMore
    val hasSelection get() = selectedIds.isNotEmpty()
    val completedExpanded get() = completedOpen && !hasSelection
}

internal fun PlanUiState.projectPlan(): PlanUiState =
    copy(selectedIds = selectedIds.intersect(backlog.map { it.id }.toSet()))
