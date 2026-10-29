package app.aino.mobile.feature.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.DashboardTask
import app.aino.mobile.feature.home.MyWork
import app.aino.mobile.feature.home.MyWorkMode
import app.aino.mobile.feature.home.rowDayLabel

/**
 * The home "My Work" card: the user's tickets in the running sprint, or the
 * ones planned into this week (spec D1, D8-D11).
 *
 * Replaces the old `SprintProgressCard`, which showed the backlog whenever it
 * could not show a sprint — and, because it decoded the active-sprint envelope
 * wrongly, could never show one.
 */
@Composable
fun MyWorkCard(work: MyWork, onOpenAll: () -> Unit, onOpenTask: (Long) -> Unit) {
    val colors = LocalWebColors.current

    WebCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenAll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (work.mode == MyWorkMode.Sprint) HeroIcons.Bolt else HeroIcons.CalendarDays,
                null,
                Modifier.size(18.dp),
                tint = colors.primary,
            )
            Text(
                work.title,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (work.total > 0) {
                Text("${work.done} of ${work.total} done", color = colors.textSecondary, fontSize = 12.sp)
            }
            Text("›", color = colors.textMuted, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
        }

        if (work.isEmpty) {
            Text(
                when {
                    // An outage must not read as "you have nothing to do".
                    work.failed -> "Could not load your tickets."
                    work.mode == MyWorkMode.Sprint -> "No tickets assigned to you in this sprint."
                    else -> "Nothing planned for this week."
                },
                color = colors.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@WebCard
        }

        Column(Modifier.padding(top = 8.dp)) {
            work.groups.forEach { group ->
                if (group.label.isNotEmpty()) {
                    Text(
                        group.label,
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                group.tasks.forEach { task -> MyWorkRow(task, group.key, onOpenTask) }
            }
            if (work.hidden > 0) {
                Text(
                    "＋${work.hidden} more",
                    color = colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAll).padding(top = 8.dp),
                )
            }
        }
    }
}

/** Title, day (Overdue only), workflow-state label and priority — no avatar, no key. */
@Composable
private fun MyWorkRow(task: DashboardTask, groupKey: String, onOpenTask: (Long) -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The dot carries the workflow state (spec D9), not the priority — the
        // server sends no state colour, so the category picks one of the three
        // tones the Tasks board already uses.
        Box(
            Modifier.size(6.dp).background(
                when (task.workflowStateCategory) {
                    "in_progress" -> colors.warning
                    "in_review" -> colors.primary
                    else -> colors.textMuted
                },
                CircleShape,
            ),
        )
        Text(
            task.title,
            color = colors.text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Only Overdue rows need a day: elsewhere the group header names it.
        rowDayLabel(task, groupKey)?.let { day ->
            Text(day, color = colors.danger, fontSize = 11.sp, maxLines = 1)
        }
        Text(task.stateLabel, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1)
        task.priority?.let { priority ->
            Text(
                priority,
                color = if (priority == "high") colors.danger else colors.warning,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}
