package app.aino.mobile.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

@Composable
internal fun AddSubtaskDialog(ui: TicketDetailUiState, viewModel: TicketDetailViewModel) {
    val title = ui.subtaskTitle ?: return
    val colors = LocalWebColors.current
    val decision = ui.detail?.capability(TaskCapability.AddSubtask) ?: TaskCapabilityDecision()
    AlertDialog(
        onDismissRequest = viewModel::dismissAddSubtask,
        title = { Text("Add subtask") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Follows the parent's project and sprint.")
                WebTextField(title, viewModel::setSubtaskTitle, "Subtask title", maxLength = 200,
                    modifier = Modifier.semantics { contentDescription = "Subtask title" })
                if (!decision.allowed) Text(decision.reasonCopy(), color = colors.danger)
                ui.subtaskError?.let { Text(it, color = colors.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::createSubtask, enabled = title.isNotBlank() && decision.allowed && !ui.subtaskBusy) {
                Text(if (ui.subtaskBusy) "Creating…" else "Create subtask", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissAddSubtask, enabled = !ui.subtaskBusy) { Text("Cancel") }
        },
    )
}
