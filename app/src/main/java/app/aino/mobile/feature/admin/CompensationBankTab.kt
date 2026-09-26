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
 * Bank Verifications tab (pending first, approve / reject), plus the
 * Android-only "All accounts" view of `GET /bank-details`. A card opens the
 * employee page (edit, penny-drop verify).
 */
@Composable
internal fun BankVerificationsTab(state: PayrollUiState, viewModel: PayrollViewModel, onOpenEmployee: (Long, String) -> Unit) {
    val colors = LocalWebColors.current
    var rejecting by remember { mutableStateOf<BankDetails?>(null) }
    AdminChipRow(listOf(false to "Verifications", true to "All accounts"), state.bankShowAll, viewModel::setBankShowAll)
    val load = if (state.bankShowAll) state.bankAccounts else state.bankVerifications
    AdminLoadState(load) { rows ->
        if (rows.isEmpty()) AdminEmpty("No bank details submitted yet.")
        rows.forEach { b ->
            AdminRowCard(onClick = { onOpenEmployee(b.userId, b.fullName.orEmpty()) }) {
                Text(b.fullName.orEmpty(), color = colors.text, fontWeight = FontWeight.SemiBold)
                b.email?.let { Text(it, color = colors.textMuted, fontSize = 0.78.rem) }
                if (!state.bankShowAll) AdminCell("Department", b.departmentName ?: "-")
                AdminCell("Account Holder", b.accountHolderName.orEmpty())
                AdminCell("Account Number", b.accountNumber.orEmpty())
                AdminCell("IFSC", b.ifscCode.orEmpty())
                AdminCell("Bank", b.bankName ?: "-")
                if (b.isVerified) {
                    AdminPill("Verified", colors.success)
                    AdminHint(b.verifiedAt?.let { "Verified ${shortDate(it)}" } ?: "\u2014")
                } else {
                    AdminPill("Pending", colors.warning)
                    AdminButtonRow {
                        AdminButton("Approve", { viewModel.approveBank(b.userId) }, enabled = !state.busy, small = true)
                        AdminButton("Reject", { rejecting = b }, style = AdminButtonStyle.Danger, enabled = !state.busy, small = true)
                    }
                }
            }
        }
    }
    rejecting?.let { b ->
        AdminConfirmDialog(
            "Reject this bank detail?", confirmText = "Reject",
            onConfirm = { rejecting = null; viewModel.rejectBank(b.userId) }, onDismiss = { rejecting = null },
        )
    }
}
