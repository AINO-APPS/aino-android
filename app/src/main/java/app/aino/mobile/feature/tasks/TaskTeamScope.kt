package app.aino.mobile.feature.tasks

// ── Team scope ──────────────────────────────────────────────────────────

/** Roles that may browse any team's Tasks (super admin and above). */
private val TEAM_PICKER_ROLES = setOf("super_admin", "platform_admin")

/** A row of `GET /org/teams` as the Tasks picker needs it. */
data class TeamOption(val id: Long, val name: String)

/**
 * The team scope sent with backlog, scheduled and sprint queries.
 *
 * [unassigned] is the admin bucket for legacy tickets with no owning team.
 */
data class TeamScope(val teamId: Long? = null, val unassigned: Boolean = false)

fun showsTeamPicker(role: String?): Boolean = role in TEAM_PICKER_ROLES

/**
 * Which team the picker starts on: the remembered choice, else the admin's own
 * team, else the first team in the org.
 */
fun defaultTeamId(remembered: Long?, ownTeamId: Long?, teams: List<TeamOption>): Long? {
    val known = teams.map { it.id }.toSet()
    return when {
        remembered != null && remembered in known -> remembered
        ownTeamId != null && ownTeamId in known -> ownTeamId
        else -> teams.firstOrNull()?.id
    }
}

/** `-1` is the Unassigned bucket, matching the picker's sentinel row. */
fun scopeForTeam(teamId: Long?): TeamScope = when (teamId) {
    null -> TeamScope()
    -1L -> TeamScope(unassigned = true)
    else -> TeamScope(teamId = teamId)
}

/** The sprint "Plan sprint" opens: the running one, else the next planned one. */
fun planTargetId(sprints: List<AvailableSprint>): Long? =
    sprints.firstOrNull { it.status == "active" }?.id
        ?: sprints.firstOrNull { it.status == "planned" }?.id

/**
 * The sprint rows of the team on screen.
 *
 * Without the picker the user only has their own team's sprints, so the list is
 * returned as-is (an older server may omit `team_id`). With the picker, a row
 * whose team is unknown is kept — dropping it would hide a sprint whose server
 * does not report the owner.
 */
fun sprintsForTeam(
    sprints: List<AvailableSprint>,
    selectedTeamId: Long?,
    pickerVisible: Boolean,
): List<AvailableSprint> {
    if (!pickerVisible || selectedTeamId == null || selectedTeamId == -1L) {
        return if (pickerVisible) emptyList() else sprints
    }
    return sprints.filter { it.teamId == null || it.teamId == selectedTeamId }
}
