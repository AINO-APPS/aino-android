package app.aino.mobile.feature.organization

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private val WEEK_OPTIONS = (1..8).map { it to it.toString() }
private val MODE_OPTIONS = listOf(
    "manual" to "Manual (start/complete by hand)",
    "auto" to "Auto (start & rotate on schedule)",
)

/** `components/organization/Teams.tsx` (with the inline sprint-config editor). */
@Composable
internal fun TeamsTab(ui: OrganizationUiState, viewModel: OrganizationViewModel) {
    val colors = LocalWebColors.current
    val canManage = ui.isAdmin
    val section = ui.teams
    val departments = section.data?.departments.orEmpty()
    val deptOptions = departmentOptions(departments)
    val leadOptions = memberOptions("No Lead", ui.members.data.orEmpty())
    var showForm by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var departmentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var leadId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.notices[NoticeSlot.Teams]?.let { OrgNoticeBanner(it) }
        if (canManage) {
            ui.members.error?.let { OrgErrorText(it) }
            if (showForm) {
                OrgRowCard {
                    OrgTextField(name, { name = it }, placeholder = "Team name")
                    OrgPicker(deptOptions, departmentId, { departmentId = it })
                    OrgPicker(leadOptions, leadId, { leadId = it })
                    OrgButtonRow {
                        OrgButton(
                            "Add",
                            onClick = {
                                viewModel.createTeam(name, departmentId, leadId) {
                                    name = ""
                                    departmentId = null
                                    leadId = null
                                    showForm = false
                                }
                            },
                            enabled = name.isNotBlank() && !ui.busy,
                        )
                        OrgButton("Cancel", onClick = { showForm = false }, style = OrgButtonStyle.Cancel)
                    }
                }
            } else {
                OrgButton("+ Add Team", onClick = { showForm = true })
            }
        }

        section.error?.let { OrgErrorText(it) }
        val teams = section.data?.teams.orEmpty()
        when {
            section.initialLoading -> OrgLoading()
            teams.isEmpty() && section.data != null -> OrgEmpty("No teams yet")
            else -> teams.forEach { t ->
                val edit = ui.teamEdit?.takeIf { canManage && it.teamId == t.id }
                OrgRowCard {
                    if (edit != null) {
                        OrgCellLabel("Name")
                        OrgTextField(edit.name, { v -> viewModel.updateTeamEdit { it.copy(name = v) } }, placeholder = "")
                        OrgCellLabel("Department")
                        OrgPicker(deptOptions, edit.departmentId, { v -> viewModel.updateTeamEdit { it.copy(departmentId = v) } })
                        OrgCellLabel("Lead")
                        OrgPicker(leadOptions, edit.leadId, { v -> viewModel.updateTeamEdit { it.copy(leadId = v) } })
                    } else {
                        Text(t.name, color = colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.SemiBold)
                        OrgCell("Department", t.departmentName?.takeIf(String::isNotEmpty) ?: "—")
                        OrgCell("Lead", t.leadName?.takeIf(String::isNotEmpty) ?: "—")
                    }
                    if (ui.isAdmin) OrgCell("Members", t.memberCount?.toString().orEmpty())
                    OrgCell("Sprint Config", sprintConfigLabel(t.sprintDurationWeeks, t.sprintStartDate))
                    if (canManage) {
                        OrgButtonRow {
                            if (edit != null) {
                                OrgButton("Save", onClick = viewModel::saveTeamEdit, style = OrgButtonStyle.Accent, small = true, enabled = !ui.busy)
                                OrgButton("Cancel", onClick = viewModel::cancelTeamEdit, style = OrgButtonStyle.Secondary, small = true)
                            } else {
                                OrgButton("Edit", onClick = { viewModel.beginTeamEdit(t) }, style = OrgButtonStyle.Accent, small = true)
                                OrgButton("Delete", onClick = { deleteId = t.id }, style = OrgButtonStyle.Danger, small = true, enabled = !ui.busy)
                            }
                        }
                    }
                    if (edit != null) SprintConfigEditor(edit, ui.busy, viewModel)
                }
            }
        }
    }

    deleteId?.let { id ->
        OrgConfirmDialog(
            message = "Delete this team? Members will be unassigned.",
            onConfirm = {
                deleteId = null
                viewModel.deleteTeam(id)
            },
            onDismiss = { deleteId = null },
        )
    }
}

/** `.sprint-config-form` row shown under the team being edited. */
@Composable
private fun SprintConfigEditor(edit: TeamEdit, busy: Boolean, viewModel: OrganizationViewModel) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            OrgFieldLabel("🏃 Sprint Duration (weeks)")
            OrgPicker(WEEK_OPTIONS, edit.sprintDurationWeeks.takeIf { it in 1..8 } ?: 2, { v ->
                viewModel.updateTeamEdit { it.copy(sprintDurationWeeks = v) }
            })
            OrgHint("Length of each sprint (1-8 weeks)")
        }
        Column {
            OrgFieldLabel("Sprint Start Date", icon = Icons.Outlined.CalendarMonth)
            OrgDateField(edit.sprintStartDate, { v -> viewModel.updateTeamEdit { it.copy(sprintStartDate = v) } })
            OrgHint("First sprint's start date (sprints auto-calculated from this)")
        }
        Column {
            OrgFieldLabel("⚙️ Sprint Mode")
            OrgPicker(MODE_OPTIONS, edit.sprintMode.ifEmpty { "manual" }, { v -> viewModel.updateTeamEdit { it.copy(sprintMode = v) } })
            OrgHint("Auto mode automatically starts, completes, and rotates sprints on the configured cadence.")
        }
        val sprint = edit.activeSprint
        if (edit.sprintMode == "auto" && sprint != null) {
            Column {
                OrgFieldLabel("⏯️ Active Sprint — ${sprint.name.orEmpty()} (${if (edit.paused) "Paused" else sprint.status.orEmpty()})")
                OrgButton(
                    if (edit.paused) "Resume Sprint" else "Pause Sprint",
                    onClick = viewModel::toggleSprintPause,
                    style = if (edit.paused) OrgButtonStyle.Accent else OrgButtonStyle.Secondary,
                    small = true,
                    enabled = !busy,
                )
                OrgHint(
                    if (edit.paused) "Resuming extends the end date by the paused duration."
                    else "Pausing freezes the cadence clock until you resume.",
                )
            }
        }
    }
}
