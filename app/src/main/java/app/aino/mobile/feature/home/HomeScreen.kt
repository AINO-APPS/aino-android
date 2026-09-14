package app.aino.mobile.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.auth.AinoUser
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(user: AinoUser, viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "${greeting()}, ${user.fullName ?: user.username}", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ui.loading && ui.snapshot == null) CircularProgressIndicator()
            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::refresh) { Text("Retry") }
            }
            ui.snapshot?.let { snapshot ->
                val status = snapshot.status
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(statusLabel(status.state, status.workMode), style = MaterialTheme.typography.titleLarge)
                        Text("Work ${formatDuration(ui.floorSeconds)} · Break ${formatDuration(ui.breakSeconds)}")
                        val progress = if (status.targetMinutes > 0) {
                            (ui.floorSeconds / 60f / status.targetMinutes).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}% of ${status.targetMinutes / 60}h target")
                        if (status.autoLoggedOut) Text("Daily target reached — automatically clocked out.")
                        if (status.isWeekend) Text("Weekend / non-working day")
                    }
                }
                snapshot.tasks?.let { tasks ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tasks", style = MaterialTheme.typography.titleLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total ${tasks.total}")
                                Text("Done ${tasks.done}")
                                Text("Active ${tasks.inProgress + tasks.inReview}")
                            }
                            tasks.activeTasks.take(3).forEach { task ->
                                Text("• ${task.title}${task.priority?.let { " · $it" } ?: ""}")
                            }
                        }
                    }
                }
                Button(onClick = viewModel::refresh, enabled = !ui.loading) {
                    Text(if (ui.loading) "Refreshing…" else "Refresh")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Clock actions will be enabled with attendance verification in the next milestone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun statusLabel(state: String, workMode: String): String = when (state) {
    "on_floor" -> if (workMode == "remote") "Working remotely" else "Working"
    "on_break" -> "On break"
    else -> "Logged out"
}
