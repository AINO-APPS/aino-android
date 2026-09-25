package app.aino.mobile.feature.organization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** `components/organization/Departments.tsx`. */
@Composable
internal fun DepartmentsTab(ui: OrganizationUiState, viewModel: OrganizationViewModel) {
    val canManage = ui.isAdmin
    val headOptions = memberOptions("No Head", ui.members.data.orEmpty())
    var showForm by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var headId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    var editHeadId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.notices[NoticeSlot.Departments]?.let { OrgNoticeBanner(it) }
        if (canManage) {
            ui.members.error?.let { OrgErrorText(it) }
            if (showForm) {
                OrgRowCard {
                    OrgTextField(name, { name = it }, placeholder = "Department name")
                    OrgPicker(headOptions, headId, { headId = it })
                    OrgButtonRow {
                        OrgButton(
                            "Add",
                            onClick = {
                                viewModel.createDepartment(name, headId) {
                                    name = ""
                                    headId = null
                                    showForm = false
                                }
                            },
                            enabled = name.isNotBlank() && !ui.busy,
                        )
                        OrgButton("Cancel", onClick = { showForm = false }, style = OrgButtonStyle.Cancel)
                    }
                }
            } else {
                OrgButton("+ Add Department", onClick = { showForm = true })
            }
        }

        val section = ui.departments
        section.error?.let { OrgErrorText(it) }
        val departments = section.data.orEmpty()
        when {
            section.initialLoading -> OrgLoading()
            departments.isEmpty() && section.data != null -> OrgEmpty("No departments yet")
            else -> departments.forEach { d ->
                val editing = editId == d.id
                OrgRowCard {
                    if (editing) {
                        OrgCellLabel("Name")
                        OrgTextField(editName, { editName = it }, placeholder = "")
                        OrgCellLabel("Head")
                        OrgPicker(headOptions, editHeadId, { editHeadId = it })
                    } else {
                        Text(d.name, color = LocalWebColors.current.text, fontSize = 0.95.rem, fontWeight = FontWeight.SemiBold)
                        OrgCell("Head", d.headName?.takeIf(String::isNotEmpty) ?: "—")
                    }
                    if (ui.isAdmin) OrgCell("Members", d.memberCount?.toString().orEmpty())
                    if (canManage) {
                        OrgButtonRow {
                            if (editing) {
                                OrgButton(
                                    "Save",
                                    onClick = { viewModel.updateDepartment(d.id, editName, editHeadId) { editId = null } },
                                    style = OrgButtonStyle.Accent,
                                    small = true,
                                    enabled = !ui.busy,
                                )
                                OrgButton("Cancel", onClick = { editId = null }, style = OrgButtonStyle.Secondary, small = true)
                            } else {
                                OrgButton(
                                    "Edit",
                                    onClick = {
                                        editId = d.id
                                        editName = d.name
                                        editHeadId = d.headId
                                    },
                                    style = OrgButtonStyle.Accent,
                                    small = true,
                                )
                                OrgButton(
                                    "Delete",
                                    onClick = { deleteId = d.id },
                                    style = OrgButtonStyle.Danger,
                                    small = true,
                                    enabled = !ui.busy,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteId?.let { id ->
        OrgConfirmDialog(
            message = "Delete this department? Members will be unassigned.",
            onConfirm = {
                deleteId = null
                viewModel.deleteDepartment(id)
            },
            onDismiss = { deleteId = null },
        )
    }
}
