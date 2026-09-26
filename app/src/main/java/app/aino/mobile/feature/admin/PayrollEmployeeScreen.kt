package app.aino.mobile.feature.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.common.formatRupees
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * Android-only employee payroll page for the endpoints the web never calls:
 * compensation history (`GET employees/:userId`), in-place record correction
 * (`PUT employees/:userId/:id`), and the admin bank-detail calls
 * (`GET|POST bank-details/:userId`, `…/verify`).
 */
@Composable
fun PayrollEmployeeScreen(viewModel: PayrollViewModel, userId: Long, name: String, onBack: () -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    var editing by remember { mutableStateOf<Pair<Long, RecordEdit>?>(null) }
    LaunchedEffect(userId) { viewModel.openEmployee(userId, name) }
    val page = state.employeePage?.takeIf { it.userId == userId }
    AinoFullPage(title = name.ifBlank { "Employee" }, onBack = onBack) {
        state.notice?.let { AdminNoticeBanner(it) }
        if (page == null) {
            AdminLoading()
            return@AinoFullPage
        }
        EmployeeBankCard(page.bank, state, viewModel, userId, name)
        AdminTitle("Compensation history")
        AdminLoadState(page.history) { rows ->
            if (rows.isEmpty()) AdminEmpty("No compensation records.")
            rows.forEach { r ->
                AdminRowCard {
                    Text("${r.effectiveFrom} \u2192 ${r.effectiveTo ?: "current"}", color = colors.text, fontWeight = FontWeight.SemiBold)
                    AdminCell("Annual CTC", if ((r.ctcAnnual ?: 0.0) > 0) formatRupees(r.ctcAnnual) else "-")
                    AdminCell("Base Salary", formatRupees(r.baseSalary))
                    AdminCell("Template", r.templateName ?: "-")
                    AdminCell("Currency", r.currency ?: "INR")
                    AdminCell("Frequency", r.paymentFrequency ?: "monthly")
                    r.bankAccount?.takeIf { it.isNotBlank() }?.let { AdminCell("Bank account", it) }
                    r.notes?.takeIf { it.isNotBlank() }?.let { AdminCell("Notes", it) }
                    r.componentAmounts.forEach { (k, v) -> AdminCell(componentLabel(k), formatRupees(v)) }
                    val edit = editing?.takeIf { it.first == r.id }?.second
                    if (edit == null) {
                        AdminButton("Correct record", { editing = r.id to recordEditFor(r) }, style = AdminButtonStyle.Secondary, small = true)
                    } else {
                        RecordEditForm(edit) { editing = r.id to it }
                        AdminButtonRow {
                            AdminButton("Save", { viewModel.updateRecord(userId, r.id, edit) { editing = null } }, enabled = !state.busy, small = true)
                            AdminButton("Cancel", { editing = null }, style = AdminButtonStyle.Cancel, small = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordEditForm(edit: RecordEdit, onChange: (RecordEdit) -> Unit) {
    AdminHint("Fixes this record in place. To change pay from a new date, use Assign Compensation instead.")
    AdminField("Base Salary (Monthly \u20B9)", edit.baseSalary, { onChange(edit.copy(baseSalary = it)) }, keyboardType = KeyboardType.Decimal)
    AdminField("Annual CTC (\u20B9)", edit.ctcAnnual, { onChange(edit.copy(ctcAnnual = it)) }, keyboardType = KeyboardType.Decimal)
    AdminField("Currency", edit.currency, { onChange(edit.copy(currency = it)) }, placeholder = "INR")
    AdminPicker(
        "Payment frequency",
        listOf("monthly" to "Monthly", "biweekly" to "Biweekly", "weekly" to "Weekly"),
        edit.paymentFrequency.ifBlank { "monthly" },
        { onChange(edit.copy(paymentFrequency = it)) },
    )
    AdminField("Bank account", edit.bankAccount, { onChange(edit.copy(bankAccount = it)) })
    AdminField("Notes", edit.notes, { onChange(edit.copy(notes = it)) }, singleLine = false)
}

/** `MySalarySlips` bank form fields; IFSC is upper-cased as typed. */
@Composable
internal fun BankFormFields(form: BankForm, onChange: (BankForm) -> Unit) {
    AdminField("Account Holder Name *", form.accountHolderName, { onChange(form.copy(accountHolderName = it)) }, placeholder = "Full name as per bank")
    AdminField("Account Number *", form.accountNumber, { onChange(form.copy(accountNumber = it)) }, placeholder = "Enter account number", keyboardType = KeyboardType.Number)
    AdminField("IFSC Code *", form.ifscCode, { onChange(form.copy(ifscCode = it.uppercase())) }, placeholder = "e.g. SBIN0001234")
    AdminField("Bank Name", form.bankName, { onChange(form.copy(bankName = it)) }, placeholder = "e.g. State Bank of India")
    AdminPicker("Account Type", ACCOUNT_TYPES, form.accountType, { onChange(form.copy(accountType = it)) })
}
