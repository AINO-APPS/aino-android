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
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * `CompensationSetup.tsx`: Templates · Employees · CTC Settings · Bank
 * Verifications (the pending count rides on the last tab label).
 */
@Composable
internal fun CompensationSection(state: PayrollUiState, viewModel: PayrollViewModel, onOpenEmployee: (Long, String) -> Unit) {
    val tabs = CompTab.entries.map { tab ->
        tab to if (tab == CompTab.Bank && state.pendingBankCount > 0) "${tab.label} (${state.pendingBankCount})" else tab.label
    }
    AdminChipRow(tabs, state.compTab, viewModel::selectCompTab)
    when (state.compTab) {
        CompTab.Templates -> TemplatesTab(state, viewModel)
        CompTab.Employees -> CompensationEmployeesTab(state, viewModel, onOpenEmployee)
        CompTab.Ctc -> CtcSettingsTab(state, viewModel)
        CompTab.Bank -> BankVerificationsTab(state, viewModel, onOpenEmployee)
    }
}

@Composable
private fun TemplatesTab(state: PayrollUiState, viewModel: PayrollViewModel) {
    val colors = LocalWebColors.current
    var form by remember { mutableStateOf(TemplateForm()) }
    var deleting by remember { mutableStateOf<CompensationTemplate?>(null) }
    AdminRowCard {
        AdminTitle(if (form.editingId != null) "Edit Template" else "Create Template")
        AdminField("", form.name, { form = form.copy(name = it) }, placeholder = "Template Name")
        AdminField("", form.description, { form = form.copy(description = it) }, placeholder = "Description (optional)")
        AdminSwitchRow("Default", form.isDefault, { form = form.copy(isDefault = it) })
        Text("Components", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem)
        form.components.forEachIndexed { idx, comp ->
            fun update(next: CompComponent) {
                form = form.copy(components = form.components.mapIndexed { i, c -> if (i == idx) next else c })
            }
            AdminRowCard {
                AdminTextInput(comp.key, { update(comp.copy(key = it)) }, "Key (e.g. hra)")
                AdminTextInput(comp.label, { update(comp.copy(label = it)) }, "Label")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminPicker(
                        "", listOf("earning" to "Earning", "deduction" to "Deduction"), comp.type,
                        { update(comp.copy(type = it)) }, Modifier.weight(1f),
                    )
                    AdminButton(
                        "Remove", { form = form.copy(components = form.components.filterIndexed { i, _ -> i != idx }) },
                        style = AdminButtonStyle.Cancel, small = true,
                    )
                }
            }
        }
        AdminButton("+ Add Component", { form = form.copy(components = form.components + BLANK_COMPONENT) }, style = AdminButtonStyle.Secondary, small = true)
        AdminButtonRow {
            AdminButton(
                if (form.editingId != null) "Update" else "Create",
                { viewModel.saveTemplate(form) { form = TemplateForm() } },
                enabled = form.canSave && !state.busy,
            )
            if (form.editingId != null) AdminButton("Cancel", { form = TemplateForm() }, style = AdminButtonStyle.Cancel)
        }
    }
    AdminLoadState(state.templates) { templates ->
        if (templates.isEmpty()) AdminEmpty("No templates yet")
        templates.forEach { t ->
            AdminRowCard {
                Text(t.name, color = colors.text, fontWeight = FontWeight.SemiBold)
                t.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.textMuted, fontSize = 0.8.rem) }
                AdminCell("Components", "${t.components.orEmpty().size} items")
                AdminCell("Default", if (t.isDefault) "Yes" else "-")
                AdminButtonRow {
                    AdminButton("Edit", { form = templateFormFor(t) }, style = AdminButtonStyle.Secondary, small = true)
                    AdminButton("Delete", { deleting = t }, style = AdminButtonStyle.Danger, enabled = !state.busy, small = true)
                }
            }
        }
    }
    deleting?.let { t ->
        AdminConfirmDialog(
            "Delete this template?", confirmText = "Delete",
            onConfirm = { deleting = null; viewModel.deleteTemplate(t.id) }, onDismiss = { deleting = null },
        )
    }
}
