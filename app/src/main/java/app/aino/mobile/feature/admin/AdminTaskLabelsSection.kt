package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** Android-only page for `admin/task-labels` CRUD. */
@Composable
internal fun AdminTaskLabelsSection(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    var editId by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(ROLE_COLORS[1]) }
    var deleting by remember { mutableStateOf<AdminTaskLabel?>(null) }
    val reset = { editId = null; name = ""; color = ROLE_COLORS[1] }
    AdminRowCard {
        AdminTitle(if (editId == null) "New label" else "Edit label")
        AdminField("Name", name, { name = it })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ROLE_COLORS.forEach { c -> AdminColorDot(hexColor(c), color.equals(c, true)) { color = c } }
        }
        AdminField("Hex color", color, { color = it }, placeholder = "#3b82f6")
        AdminButtonRow {
            AdminButton(if (editId == null) "Create label" else "Save", { viewModel.saveTaskLabel(editId, name, color, reset) }, enabled = !state.busy, small = true)
            if (editId != null) AdminButton("Cancel", reset, style = AdminButtonStyle.Cancel, small = true)
        }
    }
    AdminLoadState(state.taskLabels) { labels ->
        if (labels.isEmpty()) AdminEmpty("No labels yet")
        labels.forEach { l ->
            AdminRowCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminPill(l.name, hexColor(l.color))
                    Text(l.createdByUsername?.let { "by @$it" }.orEmpty(), color = colors.textMuted, fontSize = 0.75.rem, modifier = Modifier.weight(1f))
                    AdminButton("Edit", { editId = l.id; name = l.name; color = l.color ?: ROLE_COLORS[1] }, style = AdminButtonStyle.Secondary, small = true)
                    AdminButton("Delete", { deleting = l }, style = AdminButtonStyle.Danger, small = true)
                }
            }
        }
    }
    deleting?.let { l ->
        AdminConfirmDialog(
            "Delete the label \"${l.name}\"? It is removed from every task.",
            onConfirm = { deleting = null; viewModel.deleteTaskLabel(l.id) }, onDismiss = { deleting = null }, confirmText = "Delete",
        )
    }
}
