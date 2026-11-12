package app.aino.mobile.feature.tasks

/** Shared Plan context. The Tasks shell writes it; tab ViewModels only read it. */
data class PlanScope(
    val userId: Long? = null,
    val role: String = "employee",
    val teamName: String? = null,
    /** The caller's team, for team-aware permissions. */
    val teamId: Long? = null,
    val agileEnabled: Boolean = false,
    /** Null derives availability from the selected project's sprint list. */
    val sprintEnabled: Boolean? = null,
    val customFieldsEnabled: Boolean = false,
    /** Null is unresolved; -1 is the admin's Unassigned bucket. */
    val selectedTeamId: Long? = null,
    val teams: List<TeamOption> = emptyList(),
    val teamResolved: Boolean = false,
    val labels: List<TaskLabel> = emptyList(),
    val assignableUsers: List<AssignableUser> = emptyList(),
    val projects: List<ProjectOption> = emptyList(),
    /** Authenticated Plan always sets this; false is an unbound projection only. */
    val projectScoped: Boolean = false,
    val selectedProjectId: Long? = null,
    val myProjects: List<ProjectOption> = emptyList(),
    val sprints: List<AvailableSprint> = emptyList(),
    val currentSprintId: Long? = null,
    val currentSprintRow: AvailableSprint? = null,
    val agile: AgileConfig = AgileConfig(),
    val selectedSprintId: Long? = null,
    val filters: TaskFilters = TaskFilters(),
    val selectedEpicId: Long? = null,
) {
    val selectedProject: ProjectOption? get() = projects.firstOrNull { it.id == selectedProjectId }
    val projectType: String get() = selectedProject?.type ?: "scrum"
    val planTabs: List<String> get() = selectedProject?.planTabs ?: DEFAULT_SCRUM_PLAN_TABS
    val kanbanBacklogEnabled: Boolean get() = selectedProject?.kanbanBacklogEnabled == true
    val wipEnabled: Boolean get() = selectedProject?.wipEnabled == true
    val canChangeType: Boolean get() = selectedProject?.capabilities?.canChangeType == true

    internal fun contains(ticket: Task): Boolean =
        if (projectScoped) selectedProjectId != null && ticket.projectId == selectedProjectId else true

    val teamScope: TeamScope get() = TeamScope(projectId = selectedProjectId)
    val createProjects: List<ProjectOption> get() = myProjects.filter { !it.archived && it.capabilities?.canCreateTickets == true }
    val teamSprints: List<AvailableSprint> get() {
        val scoped = if (projectScoped) sprints.filter { it.projectId == selectedProjectId } else sprints
        val current = currentSprintRow?.takeIf { it.id == currentSprintId && (!projectScoped || it.projectId == selectedProjectId) }
        return if (current != null && scoped.none { it.id == current.id }) scoped + current else scoped
    }
    val currentSprint: AvailableSprint? get() = teamSprints.firstOrNull { it.id == selectedSprintId }
    val sprintsEnabled: Boolean get() = sprintEnabled ?: sprints.isNotEmpty()
    val visibleTabs: List<TaskTab> get() = planningTabs(
        scope = this,
        agileEnabled = agileEnabled,
        sprints = teamSprints,
        currentSprintId = currentSprintId,
    )
}
