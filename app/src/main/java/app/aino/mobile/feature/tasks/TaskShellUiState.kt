package app.aino.mobile.feature.tasks

data class TaskShellUiState(
    val planScope: PlanScope = PlanScope(),
    val tab: TaskTab = TaskTab.Backlog,
    val sheet: TaskSheet? = null,
    val refreshing: Boolean = false,
    val searchQuery: String = "",
    val searchResultIds: List<Long> = emptyList(),
    val searching: Boolean = false,
    val searchOpen: Boolean = false,
    val serviceDeskVersion: Int = 0,
    val preferences: TaskPreferences = TaskPreferences(),
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
}
