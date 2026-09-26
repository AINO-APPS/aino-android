package app.aino.mobile.feature.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import app.aino.mobile.core.common.formatRupees
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate

/** Employees tab: active records; the card opens the Android-only employee page, Edit opens the web modal. */
@Composable
internal fun CompensationEmployeesTab(state: PayrollUiState, viewModel: PayrollViewModel, onOpenEmployee: (Long, String) -> Unit) {
    val colors = LocalWebColors.current
    var form by remember { mutableStateOf<AssignForm?>(null) }
    // Phone layout: the web modal becomes an inline form card at the top of the tab.
    form?.let { current -> AssignFormCard(current, state, viewModel, onChange = { form = it }, onClose = { form = null }) }
    if (form == null) AdminButton("+ Assign Compensation", { form = newAssignForm(LocalDate.now().toString()) }, small = true)
    AdminLoadState(state.employees) { employees ->
        if (employees.isEmpty()) AdminEmpty("No compensation records. Assign salary to employees.")
        employees.forEach { emp ->
            AdminRowCard(onClick = { onOpenEmployee(emp.userId, emp.fullName.orEmpty()) }) {
                Text(emp.fullName.orEmpty(), color = colors.text, fontWeight = FontWeight.SemiBold)
                emp.email?.let { Text(it, color = colors.textMuted, fontSize = 0.78.rem) }
                AdminCell("Department", emp.departmentName ?: "-")
                AdminCell("Annual CTC", if ((emp.ctcAnnual ?: 0.0) > 0) formatRupees(emp.ctcAnnual) else "-")
                AdminCell("Base Salary (Monthly)", formatRupees(emp.baseSalary))
                AdminCell("Effective From", emp.effectiveFrom)
                AdminButton("Edit", { form = editAssignForm(emp, LocalDate.now().toString()) }, style = AdminButtonStyle.Secondary, small = true)
            }
        }
    }
}

/** The web Assign / Edit modal as a form card; a positive CTC auto-fills base salary and components. */
@Composable
internal fun AssignFormCard(
    form: AssignForm,
    state: PayrollUiState,
    viewModel: PayrollViewModel,
    onChange: (AssignForm) -> Unit,
    onClose: () -> Unit,
) {
    val ctc = state.ctc.data
    AdminRowCard {
        AdminTitle(form.title)
        if (form.isNew) {
            val options = listOf<Pair<Long?, String>>(null to "Select an employee") +
                state.assignableMembers.map { m -> m.id to "${m.fullName ?: m.name.orEmpty()} (${m.email.orEmpty()})" }
            AdminPicker("Employee", options, form.userId, { onChange(form.copy(userId = it)) })
        }
        AdminField(
            "Annual CTC (\u20B9)", form.ctcAnnual, { onChange(form.withCtc(it, ctc)) },
            placeholder = "e.g. 600000 \u2014 auto-fills fields below", keyboardType = KeyboardType.Decimal,
        )
        AdminDateField("Effective From", form.effectiveFrom, { onChange(form.copy(effectiveFrom = it)) })
        AdminField("Base Salary (Monthly \u20B9)", form.baseSalary, { onChange(form.copy(baseSalary = it)) }, keyboardType = KeyboardType.Decimal)
        val templates = state.templates.data.orEmpty()
        AdminPicker(
            "Template",
            listOf<Pair<Long?, String>>(null to "No template") + templates.map { it.id to it.name },
            form.templateId,
            { id -> onChange(form.withTemplate(templates.firstOrNull { it.id == id }, ctc)) },
        )
        if (form.components.isNotEmpty()) {
            AdminFieldLabel("Component Amounts (Monthly \u20B9)")
            form.components.forEach { (key, value) ->
                AdminField(
                    componentLabel(key), value, { onChange(form.copy(components = form.components + (key to it))) },
                    keyboardType = KeyboardType.Decimal,
                )
            }
        }
        AdminButtonRow {
            AdminButton(if (state.busy) "Saving..." else "Save", { viewModel.assign(form, onClose) }, enabled = form.canSave && !state.busy)
            AdminButton("Cancel", onClose, style = AdminButtonStyle.Cancel)
        }
    }
}
