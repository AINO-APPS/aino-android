package app.aino.mobile.feature.tasks

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** Server-computed time spent and capability-driven worklog actions. */
@Composable
internal fun TaskTimePanel(task: Task, ui: TicketDetailUiState, viewModel: TicketDetailViewModel) {
    val colors = LocalWebColors.current
    val estimated = task.estimatedMinutes ?: 0
    val logged = task.timeSpentMinutes
    var deleting by remember(task.id) { mutableStateOf<TaskWorklog?>(null) }

    val percent = if (estimated > 0) ((logged.toFloat() / estimated) * 100).toInt() else 0
    val over = estimated > 0 && logged > estimated

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HeroIcons.Clock, null, Modifier.size(18.dp), tint = colors.textMuted)
            Spacer(Modifier.width(12.dp))
            Text("Time spent", color = colors.textSecondary, fontSize = 0.84.rem, modifier = Modifier.weight(1f).semantics { heading() })
            Text(
                if (estimated > 0) "${formatMinutes(logged)} / ${formatMinutes(estimated)}" else formatMinutes(logged),
                color = if (over) colors.danger else colors.textSecondary,
                fontSize = 0.8.rem,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (estimated > 0) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.border)) {
                Box(
                    Modifier
                        .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (over) colors.danger else colors.success),
                )
            }
            if (over) {
                Text(
                    "${formatMinutes(logged - estimated)} over estimate",
                    color = colors.danger,
                    fontSize = 0.72.rem,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (ui.canLogWork) {
            PillButton("Log work", viewModel::startLogWork, primary = true, enabled = !ui.worklogBusy)
        }
        if (ui.worklogsLoading) Text("Loading worklogs…", color = colors.textMuted, fontSize = 0.8.rem)
        else if (ui.worklogs.isEmpty()) Text("No work logged yet", color = colors.textMuted, fontSize = 0.8.rem)
        ui.worklogs.forEach { row ->
            HorizontalDivider(color = colors.border)
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.author?.display() ?: "Unknown author", color = colors.text, fontWeight = FontWeight.SemiBold,
                        fontSize = 0.84.rem, modifier = Modifier.weight(1f))
                    Text(formatMinutes(row.minutes), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.84.rem)
                }
                Text(listOfNotNull(row.dayWorked, row.startTime?.take(5)).joinToString(" · "), color = colors.textMuted, fontSize = 0.76.rem)
                row.comment?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.textSecondary, fontSize = 0.84.rem) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.canEdit) TextButton(onClick = { viewModel.startEditWorklog(row.id) }, enabled = !ui.worklogBusy) {
                        Text("Edit", color = colors.primary)
                    }
                    if (row.canDelete) TextButton(onClick = { deleting = row }, enabled = !ui.worklogBusy) {
                        Text("Delete", color = colors.danger)
                    }
                }
            }
        }
    }
    ui.worklogDraft?.let { WorklogSheet(ui, it, viewModel) }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete worklog?") },
            text = { Text("Remove ${formatMinutes(row.minutes)} logged on ${row.dayWorked}?") },
            confirmButton = { TextButton(onClick = { viewModel.deleteWorklog(row.id); deleting = null }) { Text("Delete", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WorklogSheet(ui: TicketDetailUiState, draft: WorklogDraft, viewModel: TicketDetailViewModel) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    TaskSheetScaffold(if (ui.editingWorklogId == null) "Log work" else "Edit worklog", viewModel::dismissWorklog, HeroIcons.Clock) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel("Day worked")
            WebDateField(draft.dayWorked, { viewModel.updateWorklogDraft(draft.copy(dayWorked = it)) }, clearable = false)
            OutlinedTextField(
                value = draft.timeSpent,
                onValueChange = { viewModel.updateWorklogDraft(draft.copy(timeSpent = it)) },
                label = { Text("Time spent") },
                placeholder = { Text("2h 30m") },
                supportingText = { Text(ui.worklogDurationError ?: "Use w, d, h and m. A day follows your organization's working hours.") },
                isError = ui.worklogDurationError != null,
                enabled = !ui.worklogBusy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel("Start time (optional)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val time = runCatching { java.time.LocalTime.parse(draft.startTime) }.getOrDefault(java.time.LocalTime.of(9, 0))
                    TimePickerDialog(context, { _, h, m ->
                        viewModel.updateWorklogDraft(draft.copy(startTime = java.time.LocalTime.of(h, m).toString()))
                    }, time.hour, time.minute, true).show()
                }, enabled = !ui.worklogBusy) { Text(draft.startTime.ifBlank { "Choose start time" }, color = colors.primary) }
                if (draft.startTime.isNotBlank()) TextButton(onClick = { viewModel.updateWorklogDraft(draft.copy(startTime = "")) }, enabled = !ui.worklogBusy) {
                    Text("Clear", color = colors.textMuted)
                }
            }
            OutlinedTextField(
                value = draft.comment,
                onValueChange = { viewModel.updateWorklogDraft(draft.copy(comment = it.take(10_000))) },
                label = { Text("Comment (optional)") },
                minLines = 2,
                enabled = !ui.worklogBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            ui.worklogError?.let { Text(it, color = colors.danger, fontSize = 0.84.rem) }
            PillButton(if (ui.worklogBusy) "Saving…" else "Save work", { viewModel.saveWorklog(draft) },
                primary = true, enabled = !ui.worklogBusy && draft.dayWorked.isNotBlank() && draft.timeSpent.isNotBlank())
        }
    }
}

/** Minutes as `1h 25m`, or `25m` under an hour. */
internal fun formatMinutes(minutes: Int): String {
    if (minutes <= 0) return "0m"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}
