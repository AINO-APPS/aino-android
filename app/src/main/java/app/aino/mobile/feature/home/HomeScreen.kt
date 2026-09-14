package app.aino.mobile.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.designsystem.theme.AinoDanger
import app.aino.mobile.core.designsystem.theme.AinoSuccess
import app.aino.mobile.core.designsystem.theme.AinoWarning
import app.aino.mobile.feature.attendance.AttendanceAction
import app.aino.mobile.feature.attendance.AttendanceViewModel
import app.aino.mobile.feature.attendance.WorkMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    user: AinoUser,
    viewModel: DashboardViewModel,
    attendance: AttendanceViewModel,
    onLocationPermission: () -> Unit,
    onBiometricRequired: () -> Unit,
    onCalendar: () -> Unit,
    onTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val attendanceUi by attendance.ui.collectAsStateWithLifecycle()
    AinoAtmosphere {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GreetingCard(user, ui.snapshot?.announcements.orEmpty())
            WorkTimerCard(attendanceUi, ui.floorSeconds, ui.breakSeconds, attendance, onLocationPermission, onBiometricRequired)
            if (ui.loading && ui.snapshot == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.primary)
            } else {
                ui.error?.let { AinoAlert(it, AlertTone.Error) }
                ui.snapshot?.let { snapshot ->
                    TodayEventsCard(snapshot.todayEvents, snapshot.tomorrowEvents, onCalendar)
                    snapshot.tasks?.takeIf { it.total > 0 }?.let { TasksPlannerCard(it, onTasks) }
                }
            }
            Text(
                "Refresh dashboard",
                Modifier.fillMaxWidth().clickable(onClick = viewModel::refresh).padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GreetingCard(user: AinoUser, announcements: List<DashboardAnnouncement>) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${greeting()}, ${user.fullName ?: user.username}!", style = MaterialTheme.typography.headlineMedium)
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            announcements.firstOrNull()?.let { announcement ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(8.dp)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (announcement.type == "quote") "“${announcement.message}”" else announcement.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = if (announcement.type == "quote") FontStyle.Italic else null,
                    )
                    if (announcements.size > 1) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            announcements.forEachIndexed { index, _ ->
                                Box(Modifier.size(6.dp).background(if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkTimerCard(
    ui: app.aino.mobile.feature.attendance.AttendanceUiState,
    floorSeconds: Long,
    breakSeconds: Long,
    viewModel: AttendanceViewModel,
    onLocationPermission: () -> Unit,
    onBiometricRequired: () -> Unit,
) {
    val status = ui.status
    val state = status?.state ?: "logged_out"
    val target = (status?.targetMinutes ?: 480).coerceAtLeast(1)
    val progress = ((status?.floorMinutes ?: 0).toFloat() / target).coerceIn(0f, 1f)
    val tint = when (state) { "on_floor" -> AinoSuccess; "on_break" -> AinoWarning; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Timer, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("WORK TIMER", Modifier.padding(start = 6.dp).weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Box(Modifier.size(8.dp).background(tint, CircleShape))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), color = tint, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), strokeWidth = 7.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatDuration(floorSeconds), color = tint, style = MaterialTheme.typography.titleMedium)
                        Text(statusLabel(state), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state != "logged_out") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            CompactStat("Work", formatDuration(floorSeconds), Modifier.weight(1f))
                            StatDivider()
                            CompactStat("Break", formatDuration(breakSeconds), Modifier.weight(1f))
                            StatDivider()
                            CompactStat("Remaining", formatMinutes((target * 60L - floorSeconds).coerceAtLeast(0)), Modifier.weight(1f))
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp), color = tint, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                            Text("${target / 60}hr target", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    when (state) {
                        "logged_out" -> {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(6.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                ModeButton("Office", Icons.Outlined.Apartment, ui.workMode == WorkMode.Office, Modifier.weight(1f)) { viewModel.setWorkMode(WorkMode.Office) }
                                ModeButton("Remote", Icons.Outlined.Home, ui.workMode == WorkMode.Remote, Modifier.weight(1f)) { viewModel.setWorkMode(WorkMode.Remote) }
                            }
                            TimerButton("Login", AinoSuccess, Icons.AutoMirrored.Outlined.Login, !ui.loading) { viewModel.prepare(AttendanceAction.ClockIn, onLocationPermission, onBiometricRequired) }
                        }
                        "on_floor" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimerButton("Break", AinoWarning, Icons.Outlined.Coffee, !ui.loading, Modifier.weight(1f)) { viewModel.breakAction(true) }
                            TimerButton("Logout", AinoDanger, Icons.AutoMirrored.Outlined.Logout, !ui.loading, Modifier.weight(1f)) { viewModel.prepare(AttendanceAction.ClockOut, onLocationPermission, onBiometricRequired) }
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimerButton("Resume", AinoSuccess, Icons.AutoMirrored.Outlined.Login, !ui.loading, Modifier.weight(1f)) { viewModel.breakAction(false) }
                            TimerButton("Logout", AinoDanger, Icons.AutoMirrored.Outlined.Logout, !ui.loading, Modifier.weight(1f)) { viewModel.prepare(AttendanceAction.ClockOut, onLocationPermission, onBiometricRequired) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatDivider() = Box(Modifier.size(width = 1.dp, height = 24.dp).background(MaterialTheme.colorScheme.outline))

@Composable
private fun ModeButton(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clickable(onClick = onClick).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(5.dp)).padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.padding(start = 5.dp), color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TimerButton(
    label: String,
    color: Color,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.4f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 11.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = Color.White)
        Text(label, Modifier.padding(start = 6.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TodayEventsCard(today: List<DashboardEvent>, tomorrow: List<DashboardEvent>, onOpen: () -> Unit) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(Icons.Outlined.CalendarMonth, "Today's Events", today.size.toString(), onOpen)
            if (today.isEmpty()) {
                Text("No events scheduled for today.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                today.take(4).forEach { EventRow(it) }
                if (today.size > 4) Text("+${today.size - 4} more", Modifier.clickable(onClick = onOpen), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (tomorrow.isNotEmpty()) {
                Text("TOMORROW", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                tomorrow.take(3).forEach { EventRow(it, muted = true) }
            }
        }
    }
}

@Composable
private fun EventRow(event: DashboardEvent, muted: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(event.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.55f else 1f))
            Text(
                if (event.allDay) "All day" else "${event.startTime.take(16).replace('T', ' ')} – ${event.endTime.take(16).replace('T', ' ')}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        event.meetingCode?.let { Text("Join", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun TasksPlannerCard(tasks: TaskSummary, onOpen: () -> Unit) {
    val done = tasks.done.toFloat() / tasks.total.coerceAtLeast(1)
    val progress = tasks.inProgress.toFloat() / tasks.total.coerceAtLeast(1)
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(Icons.Outlined.Checklist, "Today's Planner", null, onOpen)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlannerStat("Done", tasks.done, AinoSuccess, Modifier.weight(1f)); StatDivider()
                PlannerStat("In Progress", tasks.inProgress, MaterialTheme.colorScheme.primary, Modifier.weight(1f)); StatDivider()
                PlannerStat("In Review", tasks.inReview, AinoWarning, Modifier.weight(1f)); StatDivider()
                PlannerStat("Pending", tasks.pending, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), CircleShape)) {
                if (done > 0) Box(Modifier.weight(done).fillMaxSize().background(AinoSuccess))
                if (progress > 0) Box(Modifier.weight(progress).fillMaxSize().background(MaterialTheme.colorScheme.primary))
                val rest = (1f - done - progress).coerceAtLeast(0.001f)
                Box(Modifier.weight(rest).fillMaxSize())
            }
            tasks.activeTasks.firstOrNull()?.let { task ->
                Row(Modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (task.status == "in_progress") "DOING:" else if (task.status == "in_review") "REVIEW:" else "NEXT:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(task.title, Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    task.priority?.let { Text(it, Modifier.background(AinoWarning.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), color = AinoWarning, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String, badge: String?, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        badge?.let { Text(it, Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
        Text("›", Modifier.padding(start = 8.dp).clickable(onClick = onOpen), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PlannerStat(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), color = color, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified, style = MaterialTheme.typography.labelMedium)
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 0..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    else -> "Good Evening"
}

private fun statusLabel(state: String): String = when (state) {
    "on_floor" -> "Working"
    "on_break" -> "On break"
    else -> "Logged out"
}

private fun formatMinutes(seconds: Long): String = "%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60)