package app.aino.mobile.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
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
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoMetric
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.designsystem.theme.AinoBlue
import app.aino.mobile.core.designsystem.theme.AinoCyan
import app.aino.mobile.core.designsystem.theme.AinoSuccess
import app.aino.mobile.core.designsystem.theme.AinoWarning
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(user: AinoUser, viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AinoAtmosphere {
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${greeting()}, ${user.fullName ?: user.username}", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (ui.loading && ui.snapshot == null) CircularProgressIndicator()
            ui.error?.let {
                AinoAlert(it, AlertTone.Error)
                AinoPrimaryButton("Retry", viewModel::refresh, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.padding(end = 8.dp)) })
            }
            ui.snapshot?.let { snapshot ->
                val status = snapshot.status
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            AinoBadge(statusLabel(status.state, status.workMode), statusTone(status.state))
                            AinoBadge(status.workMode, if (status.workMode == "remote") AlertTone.Warning else AlertTone.Info)
                        }
                        Text(
                            formatDuration(if (status.state == "on_break") ui.breakSeconds else ui.floorSeconds),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (status.state == "on_break") AinoWarning else AinoBlue,
                        )
                        Text(if (status.state == "on_break") "Current break" else "Today's focused work", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AinoMetric(Icons.Outlined.Timer, "Work", formatDuration(ui.floorSeconds), AinoBlue, Modifier.weight(1f))
                            AinoMetric(Icons.Outlined.Coffee, "Break", formatDuration(ui.breakSeconds), AinoWarning, Modifier.weight(1f))
                        }
                        val progress = if (status.targetMinutes > 0) {
                            (ui.floorSeconds / 60f / status.targetMinutes).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = if (progress >= 1f) AinoSuccess else AinoBlue,
                            trackColor = MaterialTheme.colorScheme.outline,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${(progress * 100).toInt()}% complete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${status.targetMinutes / 60}h target", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (status.autoLoggedOut) AinoAlert("Daily target reached — automatically clocked out.", AlertTone.Success)
                        if (status.isWeekend) AinoAlert("Weekend / non-working day", AlertTone.Info)
                    }
                }
                snapshot.tasks?.let { tasks ->
                    AinoGlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Tasks", style = MaterialTheme.typography.titleLarge)
                                AinoBadge("${tasks.done}/${tasks.total} done", AlertTone.Success)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AinoMetric(Icons.Outlined.TaskAlt, "Total", tasks.total.toString(), AinoCyan, Modifier.weight(1f))
                                AinoMetric(Icons.Outlined.CheckCircleOutline, "Done", tasks.done.toString(), AinoSuccess, Modifier.weight(1f))
                            }
                            tasks.activeTasks.take(3).forEach { task ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AccessTime, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(task.title, Modifier.padding(start = 9.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    task.priority?.let { AinoBadge(it, if (it == "high" || it == "urgent") AlertTone.Error else AlertTone.Info) }
                                }
                            }
                        }
                    }
                }
                AinoPrimaryButton(if (ui.loading) "Refreshing…" else "Refresh dashboard", viewModel::refresh, Modifier.fillMaxWidth(), !ui.loading, leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.padding(end = 8.dp), tint = androidx.compose.ui.graphics.Color.White) })
                Spacer(Modifier.height(8.dp))
                AinoAlert(
                    "Clock actions will be enabled with attendance verification in the next milestone.",
                    AlertTone.Info,
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

private fun statusTone(state: String): AlertTone = when (state) {
    "on_floor" -> AlertTone.Success
    "on_break" -> AlertTone.Warning
    else -> AlertTone.Info
}
