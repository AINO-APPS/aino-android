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
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.ActiveSprint
import app.aino.mobile.feature.home.SprintTask

private val PRIORITY_ORDER = mapOf("urgent" to 0, "high" to 1, "medium" to 2, "low" to 3)

/** `SprintProgressCard` (P2.6): active sprint progress, or backlog fallback. */
@Composable
fun SprintProgressCard(sprint: ActiveSprint?, sprintTasks: List<SprintTask>, backlogTasks: List<SprintTask>, onOpen: () -> Unit) {
    val colors = LocalWebColors.current
    // Nothing to show and no backlog fallback ? hide the card entirely.
    if (sprint == null && backlogTasks.isEmpty()) return

    WebCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DirectionsRun, null, Modifier.size(18.dp), tint = colors.primary)
            Text(if (sprint != null) " " + sprint.name else " My Backlog", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }

        if (sprint != null) {
            val done = sprintTasks.count { it.status == "done" }
            val total = sprintTasks.size
            val pct = if (total > 0) done.toFloat() / total else 0f
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).background(colors.surface, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(pct).height(6.dp).background(colors.primary, RoundedCornerShape(3.dp)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$done / $total done", color = colors.textSecondary, fontSize = 12.sp)
                Text("${(pct * 100).toInt()}%", color = colors.textMuted, fontSize = 12.sp)
            }
        } else {
            val tasks = backlogTasks.filter { it.status != "done" }
                .sortedBy { PRIORITY_ORDER[it.priority] ?: 3 }.take(5)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(6.dp).background(colors.textMuted, RoundedCornerShape(3.dp)))
                        Text(task.title, color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        task.priority?.let { Text(it, color = colors.warning, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
