package app.aino.mobile.feature.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private val APPROVAL_FILTERS = listOf("pending" to "Pending", "approved" to "Approved", "rejected" to "Rejected", "" to "All")

/** `ApprovalsTab.tsx`. */
@Composable
internal fun ApprovalsTab(ui: ManagerUiState, viewModel: ManagerViewModel) {
    val colors = LocalWebColors.current
    val section = ui.approvals
    val rows = section.data.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ApprovalsFilterDropdown(ui.approvalsFilter, viewModel::setApprovalsFilter)
            if (ui.approvalsFilter == "pending" && ui.selectedApprovalIds.isNotEmpty()) {
                Spacer(Modifier.width(1.dp))
                ManagerSmallButton("Approve (${ui.selectedApprovalIds.size})", colors.success, enabled = !ui.busy, onClick = viewModel::bulkApprove)
                ManagerSmallButton("Reject (${ui.selectedApprovalIds.size})", colors.danger, enabled = !ui.busy, onClick = viewModel::bulkReject)
            }
        }

        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading -> ManagerLoading()
            rows.isEmpty() -> ManagerEmpty("No ${ui.approvalsFilter.ifEmpty { "" }} requests".trim())
            else -> {
                if (ui.approvalsFilter == "pending") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = ui.selectedApprovalIds.size == rows.size && rows.isNotEmpty(),
                            onCheckedChange = { viewModel.toggleApprovalSelectAll() },
                            colors = CheckboxDefaults.colors(checkedColor = colors.primary),
                        )
                        Text("Select all", color = colors.textSecondary, fontSize = 0.8.rem)
                    }
                }
                rows.forEach { row ->
                    ApprovalRowCard(row, ui, viewModel)
                }
            }
        }
    }

    if (ui.rejectTargetId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelReject,
            title = { Text("Reject Request") },
            text = {
                Column {
                    Text("Reason (optional)", color = colors.textSecondary, fontSize = 0.8.rem)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ui.rejectReason,
                        onValueChange = viewModel::updateRejectReason,
                        placeholder = { Text("Provide a reason...") },
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReject, enabled = !ui.busy) {
                    Text(if (ui.busy) "Rejecting…" else "Reject", color = colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelReject, enabled = !ui.busy) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApprovalRowCard(row: ApprovalRow, ui: ManagerUiState, viewModel: ManagerViewModel) {
    val colors = LocalWebColors.current
    ManagerRowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ui.approvalsFilter == "pending") {
                Checkbox(
                    checked = ui.selectedApprovalIds.contains(row.id),
                    onCheckedChange = { viewModel.toggleApprovalSelect(row.id) },
                    colors = CheckboxDefaults.colors(checkedColor = colors.primary),
                )
                Spacer(Modifier.width(4.dp))
            }
            UserAvatar(row.requesterName, row.requesterAvatar, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(row.requesterName.orEmpty(), color = colors.text, fontSize = 0.88.rem, fontWeight = FontWeight.SemiBold)
                Text(row.type?.replace("_", " ").orEmpty(), color = colors.textSecondary, fontSize = 0.72.rem)
            }
            ApprovalBadge(row.status)
        }
        RequestDetails(row)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(formatApprovalDate(row.createdAt), color = colors.textMuted, fontSize = 0.72.rem)
            if (ui.approvalsFilter == "pending") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManagerSmallButton("✓", colors.success, enabled = !ui.busy, onClick = { viewModel.approve(row.id) })
                    ManagerSmallButton("✗", colors.danger, enabled = !ui.busy, onClick = { viewModel.openReject(row.id) })
                }
            }
        }
    }
}


@Composable
private fun ApprovalsFilterDropdown(active: String, onSelect: (String) -> Unit) {
    val colors = LocalWebColors.current
    var expanded by remember { mutableStateOf(false) }
    val label = APPROVAL_FILTERS.firstOrNull { it.first == active }?.second ?: "All"
    Box {
        Row(
            Modifier
                .background(colors.inputBg, RoundedCornerShape(8.dp))
                .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.text, fontSize = 0.85.rem)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            APPROVAL_FILTERS.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun ManagerSmallButton(label: String, tint: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = tint, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold)
    }
}
