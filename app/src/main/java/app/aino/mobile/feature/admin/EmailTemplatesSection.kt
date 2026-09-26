package app.aino.mobile.feature.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** `EmailTemplatesSection.tsx`: template picker (the web's left list), editor and live preview. */
@Composable
internal fun EmailTemplatesTab(viewModel: BrandingViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    state.notice?.let { AdminNoticeBanner(it) }
    val load = state.templates
    load.error?.let { AdminError(it, Modifier.padding(bottom = 8.dp)) }
    val list = load.data
    when {
        list == null -> if (load.loading) AdminLoading()
        list.isEmpty() -> AdminEmpty("No email templates available.")
        else -> {
            AdminPicker(
                "Template",
                list.map { it.templateKey to emailTemplateOptionLabel(it) },
                state.selectedKey ?: list.first().templateKey,
                viewModel::selectTemplate,
            )
            val template = state.selected
            val draft = state.templateDraft
            if (template != null && draft != null && draft.key == template.templateKey) {
                TemplateEditor(state, template, draft, viewModel)
            }
        }
    }
}

@Composable
private fun TemplateEditor(state: BrandingUiState, template: EmailTemplate, draft: TemplateDraft, viewModel: BrandingViewModel) {
    val colors = LocalWebColors.current
    val canEdit = state.canEdit
    val dirty = draft.dirty(template)
    var confirmRevert by remember(template.templateKey) { mutableStateOf(false) }
    AdminRowCard {
        AdminTitle(emailTemplateLabel(template.templateKey))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Template key: ", color = colors.textMuted, fontSize = 0.78.rem)
            Text(template.templateKey, color = colors.textSecondary, fontSize = 0.78.rem, fontFamily = FontFamily.Monospace)
            if (template.isOverridden) Text(" \u00b7 Customised", color = colors.primary, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold)
        }
        AdminSwitchRow("Send this email", draft.enabled, { on -> viewModel.editTemplate { it.copy(enabled = on) } }, enabled = canEdit)
        AdminField("Subject", draft.subject, { v -> viewModel.editTemplate { it.copy(subject = v) } }, enabled = canEdit)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AdminFieldLabel("Body (HTML)") }
            Text(
                "Insert built-in template",
                color = if (canEdit) colors.primary else colors.textMuted,
                fontSize = 0.78.rem,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = canEdit, onClick = viewModel::insertBuiltin),
            )
        }
        AdminTextInput(draft.body, { v -> viewModel.editTemplate { it.copy(body = v) } }, "", singleLine = false, enabled = canEdit)
        AdminHint(
            "Tokens: {{accent}} = your accent color. Recipient name, dates, task titles and other dynamic values " +
                "are inserted automatically \u2014 see the preview below.",
        )
        AdminButtonRow {
            AdminButton(if (state.saving) "Saving\u2026" else "Save changes", viewModel::saveTemplate, enabled = canEdit && dirty && !state.saving)
            if (template.isOverridden) {
                AdminButton(
                    if (state.reverting) "Reverting\u2026" else "Revert to built-in",
                    { confirmRevert = true },
                    style = AdminButtonStyle.Secondary,
                    enabled = canEdit && !state.reverting,
                )
            }
        }
        if (dirty) Text("Unsaved changes", color = colors.warning, fontSize = 0.78.rem)
        if (!canEdit) AdminHint("You don't have permission to edit email templates. Contact your HR admin or super admin.")
    }
    EmailPreviewCard(draft.subject, state.preview?.html.orEmpty(), state.previewLoading)
    if (confirmRevert) {
        AdminConfirmDialog(
            "Revert to the built-in template? Your custom subject and body will be deleted. This cannot be undone.",
            onConfirm = { confirmRevert = false; viewModel.revertTemplate() },
            onDismiss = { confirmRevert = false },
            title = "Revert to built-in template",
            confirmText = "Revert",
        )
    }
}
