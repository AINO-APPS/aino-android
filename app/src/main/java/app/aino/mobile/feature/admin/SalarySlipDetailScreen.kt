package app.aino.mobile.feature.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.common.formatIndianNumber
import app.aino.mobile.core.common.formatRupees
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.media.openDownloadedBytes
import kotlinx.coroutines.launch

/** Opens a slip's PDF under the web's download name. */
@Composable
internal fun rememberSlipPdfOpener(viewModel: PayrollViewModel): (SalarySlip) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { slip ->
        viewModel.downloadPdf(slip.id) { bytes ->
            scope.launch { openDownloadedBytes(context, bytes, adminSlipFileName(slip.fullName, slip.slipMonth), "application/pdf") }
        }
    }
}

/** The web Payment cell: status label (+ UTR), or an em dash with no disbursement. */
internal fun paymentCell(disb: Disbursement?): String {
    if (disb == null) return "\u2014"
    val parts = listOfNotNull(disbursementLabel(disb.status), disb.utr?.let { "UTR: $it" })
    return parts.joinToString(" \u00B7 ").ifEmpty { "\u2014" }
}

/**
 * Android-only page for `GET /salary-slips/:id` (the web never opens a single
 * slip): the breakdown the PDF prints, plus publish, PDF and the single-slip
 * payout (`POST /disburse/:slipId`) for a published slip not yet paid.
 */
@Composable
fun SalarySlipDetailScreen(viewModel: PayrollViewModel, slipId: Long, onBack: () -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val openPdf = rememberSlipPdfOpener(viewModel)
    var confirmPay by remember { mutableStateOf(false) }
    LaunchedEffect(slipId) { viewModel.openSlip(slipId) }
    AinoFullPage(title = "Salary Slip", onBack = onBack) {
        state.notice?.let { AdminNoticeBanner(it) }
        AdminLoadState(state.slipPage) { slip ->
            // Disbursements are loaded per period; only trust them for this slip's period.
            val payoutsKnown = slip.payPeriodId != null && slip.payPeriodId == state.selectedPeriodId && state.disbursements.data != null
            val disb = if (payoutsKnown) state.disbursementFor(slip.id) else null
            AdminRowCard {
                Text(slip.fullName.orEmpty(), color = colors.text, fontWeight = FontWeight.Bold)
                AdminPill(slip.status, payrollStatusColor(slip.status))
                AdminCell("Month", slip.slipMonth)
                slip.email?.let { AdminCell("Email", it) }
                AdminCell("Department", slip.departmentName ?: "-")
                AdminCell("Team", slip.teamName ?: "-")
                slip.publishedAt?.let { AdminCell("Published", shortDateTime(it)) }
                if (payoutsKnown) AdminCell("Payment", paymentCell(disb))
            }
            AdminRowCard {
                AdminTitle("Attendance")
                AdminCell("Days worked", formatIndianNumber(slip.daysWorked ?: 0.0))
                AdminCell("Days absent", formatIndianNumber(slip.daysAbsent ?: 0.0))
                AdminCell("Leave days", formatIndianNumber(slip.leaveDays ?: 0.0))
                AdminCell("Overtime hours", formatIndianNumber(slip.overtimeHours ?: 0.0))
            }
            AdminRowCard {
                AdminTitle("Earnings")
                slip.earningAmounts.forEach { (k, v) -> AdminCell(componentLabel(k), formatRupees(v)) }
                AdminCell("Gross", formatRupees(slip.grossEarnings))
            }
            AdminRowCard {
                AdminTitle("Deductions")
                if (slip.deductionAmounts.isEmpty()) AdminCell("\u2014", formatRupees(0.0))
                slip.deductionAmounts.forEach { (k, v) -> AdminCell(componentLabel(k), formatRupees(v)) }
                AdminCell("Total", formatRupees(slip.totalDeductions))
            }
            AdminRowCard { AdminCell("Net Pay", formatRupees(slip.netPay)) }
            AdminButtonRow {
                if (slip.status == "draft") AdminButton("Publish", { viewModel.publish(slip.id) }, enabled = !state.busy, small = true)
                AdminButton("PDF", { openPdf(slip) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
                if (slip.status == "published" && payoutsKnown && disb == null) {
                    AdminButton("Disburse", { confirmPay = true }, enabled = !state.busy, small = true)
                }
                if (disb?.status == "failed") AdminButton("Retry", { viewModel.retry(disb.id) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
            }
        }
    }
    if (confirmPay) {
        AdminConfirmDialog(
            "Initiate a bank transfer for this salary slip?", title = "Confirm Disbursement", confirmText = "Disburse", danger = false,
            onConfirm = { confirmPay = false; viewModel.disburseOne(slipId) }, onDismiss = { confirmPay = false },
        )
    }
}
