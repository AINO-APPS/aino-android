package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** `OrgRoleLabels.tsx` swatches. */
internal val ROLE_COLORS = listOf("#6366f1", "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#ec4899", "#8b5cf6", "#64748b")

/** `OrgRoleLabels.tsx`: relabel system roles, create/edit/delete custom roles. */
@Composable
internal fun AdminOrgRolesPanel(state: AdminUiState, roles: OrgRoles, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    var editing by remember { mutableStateOf<RoleDraft?>(null) }
    var deleting by remember { mutableStateOf<OrgRole?>(null) }
    val canEdit = state.isSuperOrAbove || state.role == "hr_admin"
    AdminHint("Rename roles to match your organization's language. Custom roles map onto one of the built-in permission levels.")
    if (canEdit) AdminButton("New custom role", { editing = RoleDraft() }, small = true)
    roles.roles.forEach { r ->
        AdminRowCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminColorDot(hexColor(r.color), selected = false) {}
                Column(Modifier.weight(1f)) {
                    Text(r.label.ifBlank { AdminCatalog.roleLabel(r.roleKey) }, color = colors.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        r.roleKey + (if (r.isSystem) " · built-in" else " · custom") + (if (r.customised) " · renamed" else "") +
                            (r.userCount?.let { " · $it user${if (it == 1) "" else "s"}" } ?: ""),
                        color = colors.textSecondary, fontSize = 0.75.rem,
                    )
                }
            }
            r.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.textSecondary, fontSize = 0.8.rem) }
            if (canEdit) {
                AdminButtonRow {
                    AdminButton("Edit", {
                        editing = RoleDraft(r.roleKey, r.label, r.description.orEmpty(), r.color ?: ROLE_COLORS.first(), r.permissionLevel, isNew = false)
                    }, style = AdminButtonStyle.Secondary, small = true)
                    if (!r.isSystem || r.customised) {
                        AdminButton(if (r.isSystem) "Reset" else "Delete", { deleting = r }, style = AdminButtonStyle.Danger, small = true)
                    }
                }
            }
        }
    }
    editing?.let { draft -> RoleEditor(draft, roles.roles.firstOrNull { it.roleKey == draft.roleKey }?.isSystem == true, state.busy, viewModel) { editing = null } }
    deleting?.let { r ->
        AdminConfirmDialog(
            if (r.isSystem) "Reset \"${r.label}\" to its default label?" else "Delete the custom role \"${r.label}\"? Users holding it must be reassigned first.",
            onConfirm = { deleting = null; viewModel.deleteOrgRole(r.roleKey) }, onDismiss = { deleting = null },
            confirmText = if (r.isSystem) "Reset" else "Delete",
        )
    }
}

@Composable
private fun RoleEditor(initial: RoleDraft, isSystem: Boolean, busy: Boolean, viewModel: AdminViewModel, close: () -> Unit) {
    var d by remember(initial) { mutableStateOf(initial) }
    AdminConfirmDialog(
        message = if (isSystem) "Built-in roles keep their permissions; only the label, description and color change." else "",
        title = if (d.isNew) "New custom role" else "Edit role",
        onConfirm = { viewModel.saveOrgRole(d, close) }, onDismiss = close,
        confirmText = "Save", danger = false, confirmEnabled = !busy && d.label.isNotBlank(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.isNew) AdminField("Key", d.roleKey, { d = d.copy(roleKey = it.lowercase()) }, placeholder = "e.g. contractor", hint = "Lowercase letters, numbers and underscores")
            AdminField("Label", d.label, { d = d.copy(label = it) })
            AdminField("Description", d.description, { d = d.copy(description = it) }, singleLine = false)
            AdminFieldLabel("Color")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ROLE_COLORS.forEach { c -> AdminColorDot(hexColor(c), d.color.equals(c, ignoreCase = true)) { d = d.copy(color = c) } }
            }
            if (!isSystem) {
                AdminPicker("Permission level", AdminCatalog.PERMISSION_LEVELS.map { it.first to it.second }, d.permissionLevel, { d = d.copy(permissionLevel = it) })
                AdminCatalog.PERMISSION_LEVELS.firstOrNull { it.first == d.permissionLevel }?.let { AdminHint(it.third) }
            }
        }
    }
}
