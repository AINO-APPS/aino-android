package app.aino.mobile.feature.tasks

data class TeamOption(val id: Long, val name: String)

/** Planning query ownership. The historical type name no longer admits team or unassigned scopes. */
data class TeamScope(val projectId: Long? = null)

/** The sprint "Plan sprint" opens: the server's current sprint, else the next planned one. */
fun planTargetId(sprints: List<AvailableSprint>, currentSprintId: Long?): Long? =
    currentSprintId?.takeIf { id -> sprints.any { it.id == id } }
        ?: sprints.firstOrNull { it.status == "planned" }?.id
