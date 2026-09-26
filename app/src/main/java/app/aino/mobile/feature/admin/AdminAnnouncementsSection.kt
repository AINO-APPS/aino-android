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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

internal fun announcementColor(type: String): Color =
    Color(AdminCatalog.ANNOUNCEMENT_TYPES.firstOrNull { it.first == type }?.third ?: 0xFF3B82F6)

/** Android-only page for `admin/announcements` (super_admin+): post, edit, pause, delete. */
@Composable
internal fun AdminAnnouncementsSection(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    if (!state.isSuperOrAbove) return AdminEmpty("Only super admins can manage announcements")
    var editId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("info") }
    var duration by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<AdminAnnouncement?>(null) }
    val reset = { editId = null; message = ""; type = "info"; duration = "" }
    AdminRowCard {
        AdminTitle(if (editId == null) "New announcement" else "Edit announcement", "Shown as a banner to everyone in the organization.")
        AdminField("Message", message, { message = it }, singleLine = false)
        AdminChipRow(AdminCatalog.ANNOUNCEMENT_TYPES.map { it.first to it.second }, type) { type = it }
        AdminPicker("Duration", AdminCatalog.DURATIONS, duration, { duration = it })
        AdminButtonRow {
            AdminButton(if (editId == null) "Post" else "Save", { viewModel.saveAnnouncement(editId, message, type, duration, reset) }, enabled = !state.busy, small = true)
            if (editId != null) AdminButton("Cancel", reset, style = AdminButtonStyle.Cancel, small = true)
        }
    }
    AdminLoadState(state.announcements) { rows ->
        if (rows.isEmpty()) AdminEmpty("No announcements")
        rows.forEach { a ->
            AdminRowCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdminPill(a.type.replaceFirstChar(Char::uppercase), announcementColor(a.type))
                    AdminPill(if (a.isActive) "Active" else "Paused", if (a.isActive) colors.success else colors.textMuted)
                }
                Text(a.message, color = colors.text, fontSize = 0.9.rem)
                Text(
                    listOfNotNull(a.createdByName, a.orgName, shortDateTime(a.createdAt), a.expiresAt?.let { "expires ${shortDateTime(it)}" }).joinToString(" · "),
                    color = colors.textMuted, fontSize = 0.72.rem,
                )
                AdminButtonRow {
                    AdminButton("Edit", { editId = a.id; message = a.message; type = a.type; duration = "" }, style = AdminButtonStyle.Secondary, small = true)
                    AdminButton(if (a.isActive) "Pause" else "Activate", { viewModel.setAnnouncementActive(a.id, !a.isActive) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
                    AdminButton("Delete", { deleting = a }, style = AdminButtonStyle.Danger, small = true)
                }
            }
        }
    }
    deleting?.let { a ->
        AdminConfirmDialog(
            "Delete this announcement?",
            onConfirm = { deleting = null; viewModel.deleteAnnouncement(a.id) }, onDismiss = { deleting = null }, confirmText = "Delete",
        )
    }
}
