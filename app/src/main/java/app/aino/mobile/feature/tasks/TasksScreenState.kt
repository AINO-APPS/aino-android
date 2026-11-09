package app.aino.mobile.feature.tasks

/** Presentation values composed at the screen boundary, never retained by a ViewModel. */
internal fun tasksScreenState(shell: TaskShellUiState, plan: PlanUiState): TaskUiState = TaskUiState(
    planScope = shell.planScope, tab = shell.tab, sheet = shell.sheet, refreshing = shell.refreshing,
    recentlyUpdated = plan.recentlyUpdated, tickets = plan.tickets,
    error = plan.error, confirm = plan.confirm,
    searchQuery = shell.searchQuery, searchResultIds = shell.searchResultIds,
    searching = shell.searching, searchOpen = shell.searchOpen,
    serviceDeskVersion = shell.serviceDeskVersion,
    preferences = shell.preferences,
    planOpen = plan.planOpen, planTicketIds = plan.planTicketIds, planLoaded = plan.planLoaded,
    planBaseline = plan.planBaseline, planOffset = plan.planOffset, planHasMore = plan.planHasMore,
    quickFilters = plan.quickFilters, backlogPageSize = plan.backlogPageSize, backlogSort = plan.backlogSort,
    backlogLoading = plan.backlogLoading, backlogLoadingMore = plan.backlogLoadingMore,
    draft = plan.draft, creating = plan.creating, completedOpen = plan.completedOpen,
    completedLoading = plan.completedLoading, completedShowingAll = plan.completedShowingAll,
    selectedIds = plan.selectedIds, epics = plan.epics, importTaskId = plan.importTaskId,
    importAssignedTo = plan.importAssignedTo, importDueDate = plan.importDueDate,
)
