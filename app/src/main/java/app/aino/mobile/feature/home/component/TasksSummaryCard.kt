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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.TaskSummary
import kotlinx.coroutines.delay

/** `TasksSummary` port (P2.5): stat row, progress bar, rotating active task. */
@Composable
fun TasksSummaryCard(summary: TaskSummary?, onTasks: () -> Unit) {
    val colors = LocalWebColors.current
    if (summary == null || summary.total == 0) return

    val active = summary.activeTasks
    var slide by remember { mutableIntStateOf(0) }
    LaunchedEffect(active.size) {
        if (active.size <= 1) return@LaunchedEffect
        while (true) { delay(4_000); slide = (slide + 1) % active.size }
    }

    WebCard(Modifier.clickable(onClick = onTasks)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Checklist, null, Modifier.size(18.dp).padding(0.dp), tint = colors.primary)
            Text(" Today''s Planner", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Box(Modifier.weight(1f))
            Text("›", color = colors.textMuted, fontSize = 18.sp)
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(summary.done, "Done", colors.success)
            Stat(summary.inProgress, "In Progress", colors.primary)
            Stat(summary.inReview, "In Review", colors.warning)
            Stat(summary.pending, "Pending", colors.textSecondary)
        }

        // Stacked progress: done then in-progress.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).background(colors.surface, RoundedCornerShape(3.dp))) {
            if (summary.total > 0) {
                Box(Modifier.fillMaxWidth(summary.done.toFloat() / summary.total).height(6.dp).background(colors.success, RoundedCornerShape(3.dp)))
                Box(Modifier.fillMaxWidth(summary.inProgress.toFloat() / (summary.total - summary.done).coerceAtLeast(1)).height(6.dp).background(colors.primary, RoundedCornerShape(3.dp)))
            }
        }

        if (active.isNotEmpty()) {
            val task = active[slide % active.size]
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (task.status) { "in_progress" -> "Doing:"; "in_review" -> "Review:"; else -> "Next:" },
                    color = colors.textMuted, fontSize = 13.sp,
                )
                Text(task.title, color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                task.priority?.let { Text(it, color = colors.warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = color, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text(label, color = LocalWebColors.current.textMuted, fontSize = 11.sp)
    }
}
