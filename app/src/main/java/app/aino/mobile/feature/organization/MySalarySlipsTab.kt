package app.aino.mobile.feature.organization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.common.formatRupees
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.media.openDownloadedBytes
import kotlinx.coroutines.launch

/**
 * `pages/attendance/MySalarySlips.tsx` (Organization → Salary Slips): bank
 * details (view / add / edit — saving resets verification) and the user's
 * published slips with payout status and a PDF download.
 */
@Composable
internal fun MySalarySlipsTab(ui: OrganizationUiState, viewModel: OrganizationViewModel) {
    val colors = LocalWebColors.current
    val section = ui.salary
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My Salary Slips", color = colors.text, fontSize = 1.1.rem, fontWeight = FontWeight.Bold)
        ui.notices[NoticeSlot.Salary]?.let { OrgNoticeBanner(it) }
        section.error?.let { OrgErrorText(it) }
        val data = section.data
        when {
            section.initialLoading -> OrgLoading()
            data != null -> {
                BankDetailsCard(data.bank, ui.busy, viewModel)
                if (data.slips.isEmpty()) OrgEmpty("No salary slips available yet.")
                data.slips.forEach { slip -> MySlipCard(slip, ui.busy, viewModel) }
            }
        }
    }
}

@Composable
private fun BankDetailsCard(bank: MyBankDetails?, busy: Boolean, viewModel: OrganizationViewModel) {
    val colors = LocalWebColors.current
    var editing by remember { mutableStateOf(false) }
    var form by remember(bank) { mutableStateOf(myBankFormFor(bank)) }
    OrgRowCard {
        OrgFieldLabel("Bank Details", Icons.Outlined.AccountBalance)
        if (bank != null && !editing) {
            OrgCell("Account Holder", bank.accountHolderName?.ifEmpty { null } ?: "-")
            OrgCell("Account Number", bank.accountNumber.orEmpty())
            OrgCell("IFSC Code", bank.ifscCode.orEmpty())
            OrgCell("Bank Name", bank.bankName?.ifEmpty { null } ?: "-")
            Text(
                if (bank.isVerified) "\u2713 Verified" else "Pending Verification",
                color = if (bank.isVerified) colors.success else colors.warning,
                fontSize = 0.85.rem, fontWeight = FontWeight.Medium,
            )
            OrgButton("Edit", onClick = { form = myBankFormFor(bank); editing = true }, style = OrgButtonStyle.Secondary, small = true)
        } else {
            if (bank == null) OrgHint("Add your bank details to receive salary payouts directly to your account.")
            OrgCellLabel("Account Holder Name *")
            OrgTextField(form.accountHolderName, { form = form.copy(accountHolderName = it) }, placeholder = "Full name as per bank")
            OrgCellLabel("Account Number *")
            OrgTextField(form.accountNumber, { form = form.copy(accountNumber = it) }, placeholder = "Enter account number", keyboardType = KeyboardType.Number)
            OrgCellLabel("IFSC Code *")
            OrgTextField(form.ifscCode, { form = form.copy(ifscCode = it.uppercase()) }, placeholder = "e.g. SBIN0001234")
            OrgCellLabel("Bank Name")
            OrgTextField(form.bankName, { form = form.copy(bankName = it) }, placeholder = "e.g. State Bank of India")
            OrgCellLabel("Account Type")
            OrgPicker(listOf("savings" to "Savings", "current" to "Current"), form.accountType, { form = form.copy(accountType = it) })
            OrgButtonRow {
                OrgButton(
                    if (busy) "Saving..." else "Save Bank Details",
                    onClick = { viewModel.saveMyBank(form) { editing = false; form = MyBankForm() } },
                    enabled = !busy,
                )
                if (editing) OrgButton("Cancel", onClick = { editing = false }, style = OrgButtonStyle.Cancel)
            }
        }
    }
}

@Composable
private fun MySlipCard(slip: MySlip, busy: Boolean, viewModel: OrganizationViewModel) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OrgRowCard {
        Text(slip.slipMonth, color = colors.text, fontWeight = FontWeight.SemiBold)
        OrgCell("Gross", formatRupees(slip.grossEarnings))
        OrgCell("Deductions", formatRupees(slip.totalDeductions))
        OrgCell("Net Pay", formatRupees(slip.netPay))
        OrgCell("Payment", mySlipPaymentLabel(slip.disbursementStatus) + (slip.utr?.let { " \u00B7 UTR: $it" } ?: ""))
        OrgButton(
            "PDF",
            onClick = {
                viewModel.downloadMySlip(slip.id) { bytes ->
                    scope.launch { openDownloadedBytes(context, bytes, mySlipFileName(slip.slipMonth), "application/pdf") }
                }
            },
            style = OrgButtonStyle.Secondary, small = true, enabled = !busy,
        )
    }
}
