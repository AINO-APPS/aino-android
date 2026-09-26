package app.aino.mobile.feature.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * An employee's bank details on the Android-only page: view (masked), add or
 * replace on their behalf (`POST bank-details/:userId`, which also registers
 * the payee with Razorpay when payouts are set up), penny-drop verify when a
 * fund account exists, and approve / reject like the Bank Verifications tab.
 */
@Composable
internal fun EmployeeBankCard(bank: Load<Saved<BankDetails>>, state: PayrollUiState, viewModel: PayrollViewModel, userId: Long, name: String) {
    val colors = LocalWebColors.current
    var form by remember { mutableStateOf<BankForm?>(null) }
    var rejecting by remember { mutableStateOf(false) }
    AdminRowCard {
        AdminTitle("Bank Details")
        AdminLoadState(bank) { saved ->
            val b = saved.value
            val current = form
            when {
                current != null -> {
                    BankFormFields(current) { form = it }
                    AdminButtonRow {
                        AdminButton(
                            if (state.busy) "Saving..." else "Save Bank Details",
                            { viewModel.saveEmployeeBank(userId, current) { form = null } }, enabled = !state.busy, small = true,
                        )
                        AdminButton("Cancel", { form = null }, style = AdminButtonStyle.Cancel, small = true)
                    }
                }
                b == null -> {
                    AdminHint("No bank details saved for this employee.")
                    AdminButton("Add bank details", { form = BankForm(accountHolderName = name) }, small = true)
                }
                else -> {
                    AdminCell("Account Holder", b.accountHolderName ?: "-")
                    AdminCell("Account Number", b.accountNumber.orEmpty())
                    AdminCell("IFSC Code", b.ifscCode.orEmpty())
                    AdminCell("Bank Name", b.bankName ?: "-")
                    AdminCell("Account Type", b.accountType ?: "savings")
                    Text(
                        if (b.isVerified) "\u2713 Verified" else "Pending Verification",
                        color = if (b.isVerified) colors.success else colors.warning, fontSize = 0.85.rem, fontWeight = FontWeight.Medium,
                    )
                    AdminButtonRow {
                        AdminButton(
                            "Edit",
                            { form = BankForm(b.accountHolderName.orEmpty(), "", b.ifscCode.orEmpty(), b.bankName.orEmpty(), b.accountType ?: "savings") },
                            style = AdminButtonStyle.Secondary, small = true,
                        )
                        if (!b.isVerified) {
                            AdminButton("Approve", { viewModel.approveBank(userId) }, enabled = !state.busy, small = true)
                            AdminButton("Reject", { rejecting = true }, style = AdminButtonStyle.Danger, enabled = !state.busy, small = true)
                        }
                    }
                    if (b.razorpayFundAccountId != null) {
                        AdminButton("Verify (penny drop)", { viewModel.verifyBank(userId) }, style = AdminButtonStyle.Secondary, enabled = !state.busy, small = true)
                    }
                }
            }
        }
    }
    if (rejecting) {
        AdminConfirmDialog(
            "Reject this bank detail?", confirmText = "Reject",
            onConfirm = { rejecting = false; viewModel.rejectBank(userId) }, onDismiss = { rejecting = false },
        )
    }
}
