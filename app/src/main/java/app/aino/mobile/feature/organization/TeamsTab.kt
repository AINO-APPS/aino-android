package app.aino.mobile.feature.organization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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

/** Team ownership and membership administration. */
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
