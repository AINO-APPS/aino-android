package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

@Composable
internal fun SprintCompletionDialog(ui: SprintBoardUiState, sprint: AvailableSprint, viewModel: SprintBoardViewModel) {
    val colors = LocalWebColors.current
    val preview = ui.completionPreview
    val supportsNew = preview?.supportsNewSprint == true
    val next = ui.rolloverOptions.firstOrNull()
    val nextValue = if (supportsNew) "next" else next?.id?.toString()
    val defaultName = if (preview?.defaultTarget == "backlog") "Backlog" else next?.name
    AlertDialog(
        onDismissRequest = viewModel::cancelComplete,
        containerColor = colors.bgElevated,
        title = { Text("Complete ${sprint.name}", color = colors.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Where should unfinished work go?", color = colors.textSecondary)
                defaultName?.let { Text("Default: $it", color = colors.textMuted, fontSize = 0.78.rem) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompletionChoice(
                        next?.name ?: "Next planned sprint",
                        if (next == null) "No next planned sprint" else "Next planned sprint",
                        selected = nextValue != null && ui.rolloverTo == nextValue,
                        enabled = nextValue != null && !ui.lifecycleBusy,
                        onClick = { nextValue?.let(viewModel::setRollover) },
                    )
                    CompletionChoice(
                        "Create new sprint",
                        if (supportsNew) "Planned in this project, starting the day after completion" else "Requires a server update",
                        selected = ui.rolloverTo == "new",
                        enabled = supportsNew && !ui.lifecycleBusy,
                        onClick = { viewModel.setRollover("new") },
                    )
                    CompletionChoice(
                        "Backlog", "Return unfinished work to this project's backlog",
                        selected = ui.rolloverTo == "backlog",
                        enabled = preview != null && !ui.lifecycleBusy,
                        onClick = { viewModel.setRollover("backlog") },
                    )
                }
                when {
                    ui.completionPreviewLoading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp)
                        Text("Loading preview…", color = colors.textSecondary)
                    }
                    ui.completionPreviewError != null -> Column {
                        Text(ui.completionPreviewError, color = colors.danger)
                        TextButton(onClick = viewModel::retryCompletionPreview) { Text("Retry preview") }
                    }
                    preview != null -> {
                        Text(
                            "${preview.incompleteTasks} tickets move · ${formatPoints(preview.movingPoints)} ${ui.agile.unitLabel}",
                            color = colors.warning, fontWeight = FontWeight.SemiBold,
                        )
                        preview.newSprint?.takeIf { ui.rolloverTo == "new" }?.let {
                            Text(
                                listOfNotNull(it.name.takeIf(String::isNotBlank), it.startDate, it.endDate).joinToString(" · "),
                                color = colors.textSecondary, fontSize = 0.78.rem,
                            )
                        }
                        CompletionTicketGroup(
                            if (preview.movingParents != null) "Moving parents and standalone tickets" else "Moving tickets",
                            preview.movingParents ?: preview.moving.tickets,
                        )
                        preview.movingSubtasks?.let {
                            Text("$it subtasks move with their parents, regardless of state.", color = colors.textSecondary, fontSize = 0.78.rem)
                        }
                        Text("Staying in this sprint · ${preview.doneTasks}", color = colors.text, fontWeight = FontWeight.SemiBold)
                        CompletionTicketGroup(null, preview.staying.tickets)
                    }
                }
                ui.lifecycleError?.let { Text(it, color = colors.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::completeSprint, enabled = ui.canCompleteSprint) {
                Text(if (ui.lifecycleBusy) "Completing…" else "Complete sprint")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelComplete, enabled = !ui.lifecycleBusy) { Text("Cancel") }
        },
    )
}

@Composable
private fun CompletionChoice(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) colors.primary.copy(alpha = 0.08f) else colors.bg, shape)
            .border(1.dp, if (selected) colors.primary else colors.border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = if (enabled) colors.text else colors.textMuted, fontWeight = FontWeight.SemiBold, fontSize = 0.84.rem)
            Text(description, color = colors.textMuted, fontSize = 0.72.rem)
        }
    }
}

@Composable
private fun CompletionTicketGroup(title: String?, tickets: List<CompletionTicket>) {
    if (tickets.isEmpty()) return
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let { Text(it, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.78.rem) }
        tickets.forEach { ticket ->
            Text(
                listOfNotNull(ticket.issueKey, ticket.title.takeIf(String::isNotBlank) ?: "#${ticket.id}").joinToString(" · "),
                color = colors.textSecondary, fontSize = 0.78.rem,
            )
        }
    }
}
