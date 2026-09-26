package app.aino.mobile.feature.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private enum class AddMode(val label: String) { Single("One person"), Paste("Paste a list") }

/** `AddPeopleWizard.tsx`: one person (`POST admin/users`) or a pasted list (`POST admin/users/import`). */
@Composable
internal fun AdminAddPeopleSection(state: AdminUiState, viewModel: AdminViewModel, onOpenUser: (Long) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(AddMode.Single) }
    AdminChipRow(AddMode.entries.map { it to it.label }, mode) { mode = it; viewModel.clearResults() }
    when (mode) {
        AddMode.Single -> AddSingle(state, viewModel, onOpenUser)
        AddMode.Paste -> AddPaste(state, viewModel)
    }
}

@Composable
private fun AddSingle(state: AdminUiState, viewModel: AdminViewModel, onOpenUser: (Long) -> Unit) {
    val colors = LocalWebColors.current
    var draft by androidx.compose.runtime.remember { mutableStateOf(NewUserDraft(orgId = state.orgId)) }
    AdminField("Full name *", draft.fullName, { draft = draft.copy(fullName = it) })
    AdminField("Username *", draft.username, { draft = draft.copy(username = it) })
    AdminField("Email *", draft.email, { draft = draft.copy(email = it) }, keyboardType = KeyboardType.Email)
    AdminField("Password", draft.password, { draft = draft.copy(password = it) }, password = true, hint = "Leave blank to generate one")
    AdminPicker("Role", assignableRoles(state.role).map { it to AdminCatalog.roleLabel(it) }, draft.role, { draft = draft.copy(role = it) })
    if (state.isPlatformAdmin) {
        AdminPicker("Organization", noneOptions(state.organizations.data, { it.id }, { it.name }), draft.orgId, {
            draft = draft.copy(orgId = it, departmentId = null, teamId = null, managerId = null)
            viewModel.loadPickers(it, force = true)
        })
    }
    AdminPicker("Department", noneOptions(state.departments.data, { it.id }, { it.name }), draft.departmentId, { draft = draft.copy(departmentId = it, teamId = null) })
    AdminPicker("Team", noneOptions(teamsFor(state.teams.data, draft.departmentId), { it.id }, { it.name }), draft.teamId, { draft = draft.copy(teamId = it) })
    AdminPicker("Manager", noneOptions(state.members.data, { it.id }, { it.fullName }), draft.managerId, { draft = draft.copy(managerId = it) })
    val valid = draft.fullName.isNotBlank() && draft.username.isNotBlank() && draft.email.isNotBlank() && (draft.password.isEmpty() || draft.password.length >= 8)
    AdminButton("Create user", { viewModel.createUser(draft) { draft = NewUserDraft(orgId = state.orgId) } }, enabled = valid && !state.busy)
    state.createdUser?.let { created ->
        AdminRowCard(onClick = { onOpenUser(created.id) }) {
            Text("User created", color = colors.success, fontWeight = FontWeight.SemiBold)
            created.initialPassword?.let { AdminCell("Initial password", it) }
            Text("Share the password securely. Tap to open the user.", color = colors.textSecondary, fontSize = 0.78.rem)
        }
    }
}

@Composable
private fun AddPaste(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    var text by rememberSaveable { mutableStateOf("") }
    var defaultRole by rememberSaveable { mutableStateOf("employee") }
    AdminHint("Paste rows from a spreadsheet: full_name, email, username, role, department_name, team_name. A header row is detected automatically; username defaults to the email's local part.")
    AdminField("People", text, { text = it }, placeholder = "Jane Doe, jane@acme.com", singleLine = false)
    AdminPicker("Default role", AdminCatalog.INVITE_ROLES.map { it to AdminCatalog.roleLabel(it) }, defaultRole, { defaultRole = it })
    val parsed = parsePastedPeople(text, defaultRole)
    if (text.isNotBlank()) {
        Text("${parsed.rows.size} ready · ${parsed.errors.size} with errors", color = colors.textSecondary, fontSize = 0.8.rem)
        parsed.errors.take(20).forEach { Text("Line ${it.line}: ${it.errors}", color = colors.danger, fontSize = 0.78.rem) }
    }
    AdminButton(
        "Import ${parsed.rows.size} ${if (parsed.rows.size == 1) "person" else "people"}",
        { viewModel.importUsers(parsed.rows, state.orgId) },
        enabled = parsed.rows.isNotEmpty() && !state.busy,
    )
    state.importResult?.let { r ->
        AdminRowCard {
            Text("${r.imported} imported, ${r.failed.size} failed", color = if (r.failed.isEmpty()) colors.success else colors.warning, fontWeight = FontWeight.SemiBold)
            r.failed.forEach { Text("Row ${it.row ?: "?"}: ${it.error}", color = colors.danger, fontSize = 0.78.rem) }
            r.details.filter { it.initialPassword != null }.forEach { AdminCell(it.username.orEmpty(), it.initialPassword.orEmpty()) }
        }
    }
}
