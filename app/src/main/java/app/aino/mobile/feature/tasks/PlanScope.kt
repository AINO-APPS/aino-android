package app.aino.mobile.feature.tasks

/** Shared Plan context. The Tasks shell writes it; tab ViewModels only read it. */
data class PlanScope(
    val userId: Long? = null,
    val role: String = "employee",
    val teamName: String? = null,
    /** The caller's team, for team-aware permissions. */
    val teamId: Long? = null,
    val agileEnabled: Boolean = false,
    /** Null preserves the older server's sprint-list fallback. */
    val sprintEnabled: Boolean? = null,
    val customFieldsEnabled: Boolean = false,
    /** Null is unresolved; -1 is the admin's Unassigned bucket. */
    val selectedTeamId: Long? = null,
    val teams: List<TeamOption> = emptyList(),
    val teamResolved: Boolean = false,
    val labels: List<TaskLabel> = emptyList(),
    val assignableUsers: List<AssignableUser> = emptyList(),
    val projects: List<ProjectOption> = emptyList(),
    val sprints: List<AvailableSprint> = emptyList(),
    val currentSprintId: Long? = null,
    val currentSprintRow: AvailableSprint? = null,
    val agile: AgileConfig = AgileConfig(),
    val selectedSprintId: Long? = null,
    val filters: TaskFilters = TaskFilters(),
    val selectedEpicId: Long? = null,
) {
    val teamScope: TeamScope get() = scopeForTeam(selectedTeamId)
    val teamSprints: List<AvailableSprint> get() {
        val scoped = sprintsForTeam(sprints, selectedTeamId, showsTeamPicker(role))
        val current = currentSprintRow?.takeIf { it.id == currentSprintId }
        return if (current != null && scoped.none { it.id == current.id }) scoped + current else scoped
    }
    val currentSprint: AvailableSprint? get() = teamSprints.firstOrNull { it.id == selectedSprintId }
    val sprintsEnabled: Boolean get() = sprintEnabled ?: sprints.isNotEmpty()
}
