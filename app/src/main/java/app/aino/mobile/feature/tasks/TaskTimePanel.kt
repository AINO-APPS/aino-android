package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
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

/**
 * Logged against estimated, with the timer startable from the task (D13).
 *
 * The elapsed time is attributed to this task and rolls into the timesheet, so
 * the row is the same number the person sees in their timesheet rather than a
 * separate counter.
 */
@Composable
internal fun TaskTimePanel(task: Task, ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val estimated = task.estimatedMinutes ?: 0
    val logged = task.loggedMinutes
    // Nothing to show and nothing to start against: stay out of the way.
    if (estimated == 0 && logged == 0 && !ui.timerRunning) return

    val percent = if (estimated > 0) ((logged.toFloat() / estimated) * 100).toInt() else 0
    val over = estimated > 0 && logged > estimated

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HeroIcons.Clock, null, Modifier.size(18.dp), tint = colors.textMuted)
            Spacer(Modifier.width(12.dp))
            Text("Time", color = colors.textSecondary, fontSize = 0.84.rem, modifier = Modifier.weight(1f))
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.timerRunning) {
                PillButton(
                    if (ui.timerBusy) "Stopping…" else "Stop timer",
                    { viewModel.stopTimer(task.id) },
                    Modifier.weight(1f),
                    enabled = !ui.timerBusy,
                )
            } else {
                PillButton(
                    if (ui.timerBusy) "Starting…" else "Start timer",
                    { viewModel.startTimer(task.id) },
                    Modifier.weight(1f),
                    primary = true,
                    enabled = !ui.timerBusy,
                )
            }
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
