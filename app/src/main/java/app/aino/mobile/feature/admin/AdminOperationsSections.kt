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

/** `RoleRequests.tsx` action visibility for one pending request. */
internal fun canApproveRoleRequest(r: RoleRequest, role: String): Boolean =
    r.chain.any { it.first == role && it.second == "pending" } || role == "platform_admin"

internal fun canRejectRoleRequest(r: RoleRequest, role: String): Boolean =
    r.chain.any { it.first == role } || role == "super_admin" || role == "platform_admin"

@Composable
internal fun AdminRoleRequestsSection(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    var rejecting by remember { mutableStateOf<Long?>(null) }
    AdminChipRow(ROLE_REQUEST_STATUSES, state.roleRequestStatus) { viewModel.setRoleRequestStatus(it) }
    AdminLoadState(state.roleRequests) { rows ->
        if (rows.isEmpty()) AdminEmpty("No role change requests")
        rows.forEach { r ->
            AdminRowCard {
                Text(r.targetName ?: r.targetUsername ?: "User #${r.targetUserId}", color = colors.text, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdminRolePill(r.shownFrom)
                    Text("\u2192", color = colors.textSecondary)
                    AdminRolePill(r.shownTo)
                }
                AdminCell("Requested by", r.requesterName ?: "\u2014")
                AdminCell("Created", shortDateTime(r.createdAt))
                r.reason?.takeIf { it.isNotBlank() }?.let { AdminCell("Reason", it) }
                if (r.chain.isNotEmpty()) AdminCell("Approvals", r.chain.joinToString(" · ") { (who, st) -> "${AdminCatalog.roleLabel(who)}: $st" })
                AdminCell("Status", r.status.replaceFirstChar(Char::uppercase))
                if (r.status == "rejected" && !r.rejectReason.isNullOrBlank()) AdminCell("Rejected", r.rejectReason)
                if (r.status == "pending") {
                    AdminButtonRow {
                        if (canApproveRoleRequest(r, state.role)) AdminButton("Approve", { viewModel.approveRoleRequest(r.id) }, enabled = !state.busy, small = true)
                        if (canRejectRoleRequest(r, state.role)) AdminButton("Reject", { rejecting = r.id }, style = AdminButtonStyle.Danger, enabled = !state.busy, small = true)
                        AdminButton("Cancel", { viewModel.cancelRoleRequest(r.id) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
                    }
                }
            }
        }
    }
    rejecting?.let { id ->
        var reason by remember(id) { mutableStateOf("") }
        AdminConfirmDialog(
            "Why is this request being rejected?", title = "Reject request",
            onConfirm = { rejecting = null; viewModel.rejectRoleRequest(id, reason) }, onDismiss = { rejecting = null },
            confirmText = "Reject", confirmEnabled = reason.isNotBlank(),
        ) { AdminTextInput(reason, { reason = it }, "Reason") }
    }
}

/** `PayPeriods.tsx`: lock a period (label + dates) and unlock existing ones. */
@Composable
internal fun AdminPayrollSection(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    var label by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var unlocking by remember { mutableStateOf<PayPeriod?>(null) }
    AdminRowCard {
        AdminTitle("Lock pay period", "Time entries inside a locked period can no longer be edited.")
        AdminField("Label", label, { label = it }, placeholder = "e.g. March 2026")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminDateField("Start", start, { start = it }, Modifier.weight(1f))
            AdminDateField("End", end, { end = it }, Modifier.weight(1f))
        }
        AdminButton("Lock period", {
            viewModel.lockPayPeriod(label, start, end) { label = ""; start = ""; end = "" }
        }, enabled = !state.busy, small = true)
    }
    AdminLoadState(state.payPeriods) { rows ->
        if (rows.isEmpty()) AdminEmpty("No locked pay periods")
        rows.forEach { p ->
            AdminRowCard {
                Text(p.label, color = colors.text, fontWeight = FontWeight.SemiBold)
                AdminCell("Period", "${shortDate(p.startDate)} \u2013 ${shortDate(p.endDate)}")
                AdminCell("Locked by", p.lockedByName ?: "\u2014")
                AdminCell("Locked at", shortDateTime(p.lockedAt))
                AdminButton("Unlock", { unlocking = p }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
            }
        }
    }
    unlocking?.let { p ->
        AdminConfirmDialog(
            "Unlock \"${p.label}\"? Time entries in this range become editable again.",
            onConfirm = { unlocking = null; viewModel.unlockPayPeriod(p.id) }, onDismiss = { unlocking = null }, confirmText = "Unlock",
        )
    }
}
