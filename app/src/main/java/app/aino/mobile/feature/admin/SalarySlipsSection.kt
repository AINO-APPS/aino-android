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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.common.formatRupees
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * `SalarySlips.tsx`: pick a locked pay period, generate slips, publish drafts,
 * disburse, and per-slip publish / PDF / retry. Tapping a card opens the
 * Android-only slip page (earnings, deductions, attendance, single payout).
 */
@Composable
internal fun SalarySlipsSection(state: PayrollUiState, viewModel: PayrollViewModel, onOpenSlip: (Long) -> Unit) {
    val openPdf = rememberSlipPdfOpener(viewModel)
    var confirmPublish by remember { mutableStateOf(false) }
    var confirmDisburse by remember { mutableStateOf(false) }
    AdminLoadState(state.periods) { _ ->
        AdminRowCard {
            val options = listOf<Pair<Long?, String>>(null to "Select a locked pay period") +
                state.lockedPeriods.map { it.id to "${it.label} (${shortDate(it.startDate)} to ${shortDate(it.endDate)})" }
            AdminPicker("Pay Period", options, state.selectedPeriodId, { viewModel.dismissNotice(); viewModel.selectPeriod(it) })
            AdminButton(
                if (state.busy) "Working..." else "Generate Slips", viewModel::runPayroll,
                enabled = state.selectedPeriodId != null && !state.busy, small = true,
            )
            if (state.selectedPeriodId != null && state.slips.data.orEmpty().isNotEmpty()) {
                AdminButtonRow {
                    if (state.draftCount > 0) {
                        AdminButton("Publish All Drafts (${state.draftCount})", { confirmPublish = true }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
                    }
                    if (state.publishedCount > 0) {
                        AdminButton("Disburse All (${state.publishedCount})", { confirmDisburse = true }, enabled = !state.busy, small = true)
                    }
                }
            }
        }
        if (state.selectedPeriodId != null) {
            state.disbursements.error?.let { AdminError(it) }
            AdminLoadState(state.slips) { slips ->
                if (slips.isEmpty()) AdminEmpty("No salary slips for this period. Click \"Generate Slips\" to create them.")
                slips.forEach { slip -> SlipCard(slip, state, viewModel, onOpenSlip, openPdf) }
            }
        }
    }
    if (confirmPublish) {
        AdminConfirmDialog(
            "Publish all draft slips for this period?", title = "Confirm Publish", confirmText = "Publish", danger = false,
            onConfirm = { confirmPublish = false; viewModel.bulkPublish() }, onDismiss = { confirmPublish = false },
        )
    }
    if (confirmDisburse) {
        AdminConfirmDialog(
            "Initiate bank transfer for all published slips in this period?", title = "Confirm Disbursement", confirmText = "Disburse", danger = false,
            onConfirm = { confirmDisburse = false; viewModel.disburseAll() }, onDismiss = { confirmDisburse = false },
        )
    }
}

@Composable
private fun SlipCard(
    slip: SalarySlip,
    state: PayrollUiState,
    viewModel: PayrollViewModel,
    onOpenSlip: (Long) -> Unit,
    openPdf: (SalarySlip) -> Unit,
) {
    val colors = LocalWebColors.current
    val disb = state.disbursementFor(slip.id)
    AdminRowCard(onClick = { onOpenSlip(slip.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(slip.fullName.orEmpty(), color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AdminPill(slip.status, payrollStatusColor(slip.status))
        }
        slip.email?.let { Text(it, color = colors.textMuted, fontSize = 0.78.rem) }
        AdminCell("Department", slip.departmentName ?: "-")
        AdminCell("Gross", formatRupees(slip.grossEarnings))
        AdminCell("Deductions", formatRupees(slip.totalDeductions))
        AdminCell("Net Pay", formatRupees(slip.netPay))
        AdminCell("Payment", paymentCell(disb))
        AdminButtonRow {
            if (slip.status == "draft") AdminButton("Publish", { viewModel.publish(slip.id) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
            AdminButton("PDF", { openPdf(slip) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
            if (disb?.status == "failed") AdminButton("Retry", { viewModel.retry(disb.id) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
        }
    }
}
