package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * The collapsible Completed section at the foot of the backlog.
 *
 * Completed work is hidden by default (D16) but the count renders from the
 * first response, so discoverability does not suffer. Expanding issues one
 * lazy request for the recent window; "View all" drops the window entirely.
 *
 * Rows are deliberately quieter than open work: no swipe actions, no drag
 * handle, muted text — this is history, not a triage queue.
 */
@Composable
internal fun CompletedSection(
    ui: TaskUiState,
    viewModel: TaskViewModel,
    onOpen: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    val count = ui.completedCount
    // Nothing finished and nothing loaded: the section would be an empty shell.
    if (count == 0 && ui.completed.isEmpty() && !ui.completedLoading) return

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .clickable(
                    onClickLabel = if (ui.completedOpen) "Collapse completed" else "Expand completed",
                    onClick = viewModel::toggleCompletedSection,
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(if (ui.completedOpen) "Collapse completed" else "Expand completed") {
                            viewModel.toggleCompletedSection()
                            true
                        },
                    )
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HeroIcons.ChevronDown,
                null,
                Modifier.size(16.dp).rotate(if (ui.completedOpen) 0f else -90f),
                tint = colors.textMuted,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Completed",
                color = colors.textSecondary,
                fontSize = 0.72.rem,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.06.rem,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                color = colors.success,
                fontSize = 0.68.rem,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(colors.success.copy(alpha = 0.14f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            if (ui.completedLoading) {
                CircularProgressIndicator(Modifier.size(14.dp), color = colors.textMuted, strokeWidth = 1.5.dp)
            }
        }

        if (ui.completedOpen) {
            if (ui.completed.isEmpty() && !ui.completedLoading) {
                Text(
                    "Nothing finished in the last 30 days.",
                    color = colors.textMuted,
                    fontSize = 0.76.rem,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            ui.completed.forEach { task ->
                CompletedRow(task, ui, onOpen)
            }
            if (ui.completed.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "View all completed", onClick = { viewModel.loadCompleted(all = true) })
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "View all completed",
                        color = colors.primary,
                        fontSize = 0.78.rem,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** A finished task: muted, struck through, no actions. */
@Composable
private fun CompletedRow(task: Task, ui: TaskUiState, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val points = if (ui.agile.features.storyPoints) formatPoints(task.storyPoints) else ""
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${task.displayKey}", onClick = { onOpen(task) })
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HeroIcons.CheckCircle, "Completed", Modifier.size(15.dp), tint = colors.success)
        Spacer(Modifier.width(10.dp))
        Text(
            task.displayKey,
            color = colors.textMuted,
            fontSize = 0.7.rem,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            task.title,
            color = colors.textMuted,
            fontSize = 0.82.rem,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
            modifier = Modifier.weight(1f),
        )
        if (points.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(points, color = colors.textMuted, fontSize = 0.7.rem, maxLines = 1)
        }
    }
}
