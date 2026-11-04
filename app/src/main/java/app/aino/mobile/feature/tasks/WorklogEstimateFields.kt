package app.aino.mobile.feature.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun InlineEstimate(kind: EstimateKind, minutes: Int?, ui: TicketDetailUiState, viewModel: TicketDetailViewModel) {
    val draft = ui.estimateDraft?.takeIf { it.kind == kind }
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(kind.label)
        if (draft == null) {
            if (ui.canEditEstimate) TextButton(onClick = { viewModel.startEstimateEdit(kind) }, enabled = !ui.estimateBusy && !ui.worklogBusy) {
                Text(minutes?.let(::formatMinutes) ?: "Set estimate")
            } else Text(minutes?.let(::formatMinutes) ?: "—")
        } else {
            OutlinedTextField(draft.value, viewModel::updateEstimateDraft, label = { Text(kind.label) },
                supportingText = { Text(ui.estimateError ?: "Use w, d, h or m; 0m is zero, empty clears.") },
                isError = ui.estimateError != null, enabled = !ui.estimateBusy, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { viewModel.saveEstimate(draft.value) }, enabled = !ui.estimateBusy) { Text(if (ui.estimateBusy) "Saving…" else "Save") }
                TextButton(onClick = viewModel::dismissEstimate, enabled = !ui.estimateBusy) { Text("Cancel") }
            }
        }
    }
}

@Composable
internal fun RemainingEstimateChoice(mode: String, value: String, onMode: (String) -> Unit, onValue: (String) -> Unit, enabled: Boolean) {
    Column {
        FieldLabel("Remaining estimate")
        WebSelect(listOf("auto" to "Adjust automatically", "set" to "Set to", "leave" to "Leave unchanged"),
            mode, onMode, Modifier.fillMaxWidth(), enabled = enabled)
        if (mode == "set") OutlinedTextField(value, onValue, label = { Text("Set remaining estimate to") },
            placeholder = { Text("4h or 0m") }, supportingText = { Text("Use w, d, h or m. Empty clears the estimate.") },
            enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
