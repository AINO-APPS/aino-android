package app.aino.mobile.feature.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** `(null, "None")` + rows, the web's `<option value="">None</option>` pickers. */
internal fun <T> noneOptions(rows: List<T>?, id: (T) -> Long, label: (T) -> String): List<Pair<Long?, String>> =
    listOf<Pair<Long?, String>>(null to "None") + rows.orEmpty().map { id(it) to label(it) }

/** Teams under [departmentId] (unscoped teams always show). */
internal fun teamsFor(teams: List<PickerTeam>?, departmentId: Long?): List<PickerTeam> =
    teams.orEmpty().filter { departmentId == null || it.departmentId == null || it.departmentId == departmentId }

/** Role change: super+ apply immediately; hr_admin submits a request (reason optional). */
@Composable
internal fun AdminUserRoleCard(state: AdminUiState, user: AdminUser, viewModel: AdminViewModel) {
    var role by remember(user.id, user.role) { mutableStateOf(user.role) }
    var reason by remember(user.id) { mutableStateOf("") }
    val immediate = state.isSuperOrAbove
    AdminRowCard {
        AdminTitle("Role", if (immediate) "Changes apply immediately." else "Changes are submitted for approval.")
        val roles = (assignableRoles(state.role) + user.role).distinct()
        AdminPicker("", roles.map { it to AdminCatalog.roleLabel(it) }, role, { role = it })
        if (!immediate) AdminField("Reason", reason, { reason = it }, placeholder = "Why is this change needed?")
        AdminButton(
            if (immediate) "Update role" else "Request change",
            { viewModel.changeRole(user.id, role, reason) },
            enabled = !state.busy && role != user.role, small = true,
        )
    }
}

/** `PUT admin/users/:id/assignment`; platform admins may also move the user between orgs. */
@Composable
internal fun AdminUserAssignmentCard(state: AdminUiState, user: AdminUser, viewModel: AdminViewModel) {
    var orgId by remember(user.id, user.orgId) { mutableStateOf(user.orgId) }
    var departmentId by remember(user.id, user.departmentId) { mutableStateOf(user.departmentId) }
    var teamId by remember(user.id, user.teamId) { mutableStateOf(user.teamId) }
    var managerId by remember(user.id, user.managerId) { mutableStateOf(user.managerId) }
    val managers = state.members.data.orEmpty().filter { it.id != user.id }
    AdminRowCard {
        AdminTitle("Assignment")
        if (state.isPlatformAdmin) {
            AdminPicker("Organization", noneOptions(state.organizations.data, { it.id }, { it.name }), orgId, {
                orgId = it; departmentId = null; teamId = null; managerId = null
                viewModel.loadPickers(it, force = true)
            })
        }
        AdminPicker("Department", noneOptions(state.departments.data, { it.id }, { it.name }), departmentId, { departmentId = it; teamId = null })
        AdminPicker("Team", noneOptions(teamsFor(state.teams.data, departmentId), { it.id }, { it.name }), teamId, { teamId = it })
        AdminPicker("Manager", noneOptions(managers, { it.id }, { "${it.fullName} (${AdminCatalog.roleLabel(it.role)})" }), managerId, { managerId = it })
        val changed = orgId != user.orgId || departmentId != user.departmentId || teamId != user.teamId || managerId != user.managerId
        AdminButton("Save assignment", { viewModel.updateAssignment(user.id, orgId, departmentId, teamId, managerId) }, enabled = !state.busy && changed, small = true)
    }
}

@Composable
internal fun AdminUserDialogs(dialog: UserDialog, user: AdminUser, viewModel: AdminViewModel, onDeleted: () -> Unit, close: () -> Unit) {
    when (dialog) {
        UserDialog.None -> Unit
        UserDialog.ToggleActive -> AdminConfirmDialog(
            "${if (user.isActive) "Deactivate" else "Activate"} ${user.fullName}?",
            onConfirm = { close(); viewModel.toggleActive(user.id) }, onDismiss = close,
            confirmText = if (user.isActive) "Deactivate" else "Activate", danger = user.isActive,
        )
        UserDialog.ResetPassword -> {
            var password by remember { mutableStateOf("") }
            AdminConfirmDialog(
                "Set a new password for ${user.fullName}.", title = "Reset password",
                onConfirm = { close(); viewModel.resetPassword(user.id, password) }, onDismiss = close,
                confirmText = "Reset", danger = false, confirmEnabled = password.length >= 8,
            ) { AdminField("New password", password, { password = it }, password = true, hint = "Minimum 8 characters") }
        }
        UserDialog.ResetFace -> AdminConfirmDialog(
            "Clear ${user.fullName}'s face enrollment? They will need to enroll again.",
            onConfirm = { close(); viewModel.resetFaceEnrollment(user.id) }, onDismiss = close, confirmText = "Reset",
        )
        UserDialog.RemoveFromOrg -> AdminConfirmDialog(
            "Remove ${user.fullName} from the organization?",
            onConfirm = { close(); viewModel.removeFromOrg(user.id) }, onDismiss = close, confirmText = "Remove",
        )
        UserDialog.Delete -> {
            var typed by remember { mutableStateOf("") }
            AdminConfirmDialog(
                "Permanently delete ${user.fullName}? This cannot be undone. Type the username to confirm.", title = "Delete user",
                onConfirm = { close(); viewModel.deleteUser(user.id, onDeleted) }, onDismiss = close,
                confirmText = "Delete", confirmEnabled = typed == user.username,
            ) { AdminTextInput(typed, { typed = it }, user.username) }
        }
    }
}

/** `POST org/invite` (Android-only action): add an org-less user to the caller's org. */
@Composable
internal fun AdminUserInviteCard(state: AdminUiState, user: AdminUser, viewModel: AdminViewModel) {
    var role by remember(user.id) { mutableStateOf("employee") }
    var departmentId by remember(user.id) { mutableStateOf<Long?>(null) }
    var teamId by remember(user.id) { mutableStateOf<Long?>(null) }
    AdminRowCard {
        AdminTitle("Add to organization", "This user is not in an organization yet.")
        AdminPicker("Role", AdminCatalog.INVITE_ROLES.map { it to AdminCatalog.roleLabel(it) }, role, { role = it })
        AdminPicker("Department", noneOptions(state.departments.data, { it.id }, { it.name }), departmentId, { departmentId = it; teamId = null })
        AdminPicker("Team", noneOptions(teamsFor(state.teams.data, departmentId), { it.id }, { it.name }), teamId, { teamId = it })
        AdminButton("Add to organization", { viewModel.inviteToOrg(user.id, role, departmentId, teamId) }, enabled = !state.busy, small = true)
    }
}
