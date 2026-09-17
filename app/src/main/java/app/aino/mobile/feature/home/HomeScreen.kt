package app.aino.mobile.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.common.TrackerStatus
import app.aino.mobile.core.common.formatDuration
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.designsystem.theme.AinoDanger
import app.aino.mobile.core.designsystem.theme.AinoSuccess
import app.aino.mobile.core.designsystem.theme.AinoWarning
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DashboardCream = Color(0xFFF6F0E4)
private val DashboardCard = Color(0xFFFFFBF2)
private val DashboardInk = Color(0xFF15273B)
private val DashboardMuted = Color(0xFF65717C)
private val DashboardBlue = Color(0xFF79A8D8)
private val DashboardBlueDark = Color(0xFF4D7EAF)
private val DashboardOrange = Color(0xFFF29A49)
private val DashboardLine = Color(0xFFE7DECF)

@Composable
fun HomeScreen(
    user: AinoUser,
    viewModel: DashboardViewModel,
    attendanceStatus: TrackerStatus?,
    attendanceLoading: Boolean,
    attendanceWorkMode: String,
    onAttendanceWorkMode: (String) -> Unit,
    onAttendanceAction: (String) -> Unit,
    onAttendanceBreak: (Boolean) -> Unit,
    onCalendar: () -> Unit,
    onTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pageBackground = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.background else DashboardCream
    Box(Modifier.fillMaxSize().background(pageBackground)) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GreetingCard(user, ui.snapshot?.announcements.orEmpty())
            WorkTimerCard(
                attendanceStatus,
                attendanceLoading,
                ui.floorSeconds,
                ui.breakSeconds,
                onAttendanceAction,
                onAttendanceBreak,
            )
            DashboardDateStrip(LocalDate.now())
            WorkLocationCard(
                workMode = attendanceWorkMode,
                enabled = (attendanceStatus?.state ?: "logged_out") == "logged_out" && !attendanceLoading,
                onWorkMode = onAttendanceWorkMode,
            )
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
    val dark = isSystemInDarkTheme()
    val container = if (dark) MaterialTheme.colorScheme.surfaceContainerLow else DashboardCard
    val ink = if (dark) MaterialTheme.colorScheme.onSurface else DashboardInk
    val muted = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else DashboardMuted
    val displayName = (user.fullName ?: user.username).substringBefore(' ').ifBlank { user.username }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = container,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(190.dp).padding(start = 20.dp, top = 22.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1.05f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${greeting()},", color = ink, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("$displayName!", color = ink, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(
                    announcements.firstOrNull()?.message?.take(110)
                        ?: "Make today count, stay productive & connected.",
                    color = muted,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                )
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                    color = DashboardBlueDark,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            DashboardIllustration(Modifier.weight(.95f).fillMaxSize())
        }
    }
}

/**
 * Original AINO dashboard illustration, drawn entirely with Compose primitives.
 * It follows the reference's friendly blue/orange editorial direction without
 * copying an external asset into the repository.
 */
@Composable
private fun DashboardIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        val w = size.width
        val h = size.height
        // Soft ground and cloud.
        drawOval(Color(0xFFDDEAF4), topLeft = Offset(w * .08f, h * .79f), size = Size(w * .84f, h * .12f))
        drawOval(Color.White.copy(alpha = .9f), topLeft = Offset(w * .48f, h * .04f), size = Size(w * .37f, h * .17f))
        drawOval(Color.White.copy(alpha = .9f), topLeft = Offset(w * .68f, h * .02f), size = Size(w * .18f, h * .14f))

        // Notice board.
        drawRoundRect(
            color = DashboardBlue,
            topLeft = Offset(w * .03f, h * .12f),
            size = Size(w * .49f, h * .52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
        )
        drawRoundRect(
            color = Color(0xFFEDF5FA),
            topLeft = Offset(w * .08f, h * .18f),
            size = Size(w * .38f, h * .38f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f),
        )
        drawLine(DashboardBlueDark, Offset(w * .03f, h * .64f), Offset(w * .03f, h * .86f), strokeWidth = 5f)
        drawLine(DashboardBlueDark, Offset(w * .52f, h * .64f), Offset(w * .52f, h * .86f), strokeWidth = 5f)
        drawCircle(DashboardOrange, radius = w * .045f, center = Offset(w * .28f, h * .34f))
        drawLine(DashboardBlueDark, Offset(w * .15f, h * .46f), Offset(w * .40f, h * .46f), strokeWidth = 4f)

        // Person: head/hair, shirt, trousers and raised arm.
        drawCircle(Color(0xFFF5C8A7), radius = w * .075f, center = Offset(w * .68f, h * .33f))
        val hair = Path().apply {
            moveTo(w * .61f, h * .29f)
            cubicTo(w * .62f, h * .18f, w * .77f, h * .18f, w * .77f, h * .31f)
            lineTo(w * .71f, h * .26f)
            lineTo(w * .64f, h * .29f)
            close()
        }
        drawPath(hair, DashboardBlueDark)
        drawRoundRect(DashboardOrange, Offset(w * .57f, h * .40f), Size(w * .27f, h * .25f), androidx.compose.ui.geometry.CornerRadius(18f, 18f))
        drawLine(Color(0xFFF5C8A7), Offset(w * .61f, h * .45f), Offset(w * .43f, h * .30f), strokeWidth = 14f)
        drawLine(DashboardBlueDark, Offset(w * .64f, h * .64f), Offset(w * .60f, h * .84f), strokeWidth = 17f)
        drawLine(DashboardBlueDark, Offset(w * .77f, h * .64f), Offset(w * .82f, h * .84f), strokeWidth = 17f)
        drawLine(DashboardInk, Offset(w * .60f, h * .84f), Offset(w * .50f, h * .84f), strokeWidth = 9f)
        drawLine(DashboardInk, Offset(w * .82f, h * .84f), Offset(w * .91f, h * .84f), strokeWidth = 9f)

        // Plant and tiny blue bird for character.
        drawLine(DashboardBlueDark, Offset(w * .91f, h * .70f), Offset(w * .91f, h * .85f), strokeWidth = 4f)
        drawOval(Color(0xFF6E9EC4), Offset(w * .83f, h * .66f), Size(w * .10f, h * .08f))
        drawOval(Color(0xFF6E9EC4), Offset(w * .90f, h * .61f), Size(w * .08f, h * .11f))
        drawOval(DashboardBlueDark, Offset(w * .20f, h * .82f), Size(w * .14f, h * .07f))
        drawCircle(DashboardBlueDark, radius = w * .035f, center = Offset(w * .34f, h * .82f))
        drawLine(Color.White, Offset(w * .32f, h * .81f), Offset(w * .33f, h * .81f), strokeWidth = 3f)
    }
}

@Composable
private fun DashboardDateStrip(today: LocalDate) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) MaterialTheme.colorScheme.onSurface else DashboardInk
    val muted = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else DashboardMuted
    val dates = dashboardWeek(today)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        dates.forEach { date ->
            val selected = date == today
            Column(
                Modifier.width(42.dp)
                    .background(if (selected) DashboardBlue else Color.Transparent, RoundedCornerShape(16.dp))
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEE")).uppercase().take(3),
                    color = if (selected) Color.White else muted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    date.dayOfMonth.toString(),
                    color = if (selected) Color.White else ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun WorkTimerCard(
    status: TrackerStatus?,
    loading: Boolean,
    floorSeconds: Long,
    breakSeconds: Long,
    onAction: (String) -> Unit,
    onBreak: (Boolean) -> Unit,
) {
    val state = status?.state ?: "logged_out"
    val target = (status?.targetMinutes ?: 480).coerceAtLeast(1)
    val progress = ((status?.floorMinutes ?: 0).toFloat() / target).coerceIn(0f, 1f)
    val dark = isSystemInDarkTheme()
    val container = if (dark) MaterialTheme.colorScheme.surfaceContainerLow else DashboardCard
    val ink = if (dark) MaterialTheme.colorScheme.onSurface else DashboardInk
    val muted = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else DashboardMuted
    val timer = if (state == "on_break") breakSeconds else floorSeconds

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = container,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state == "logged_out") "Clock In & Work Status" else statusLabel(state),
                        color = ink,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        when (state) {
                            "on_floor" -> "You’re currently on the floor"
                            "on_break" -> "Break timer is running"
                            else -> "Clock in to begin your workday"
                        },
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(Modifier.size(48.dp).background(DashboardOrange, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Timer, null, Modifier.size(24.dp), tint = Color.White)
                }
            }

            Text(
                formatDuration(timer),
                Modifier.fillMaxWidth(),
                color = ink,
                textAlign = TextAlign.Center,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            if (state != "logged_out") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CompactStat("Work", formatDuration(floorSeconds), Modifier.weight(1f))
                    StatDivider()
                    CompactStat("Break", formatDuration(breakSeconds), Modifier.weight(1f))
                    StatDivider()
                    CompactStat("Remaining", formatMinutes((target * 60L - floorSeconds).coerceAtLeast(0)), Modifier.weight(1f))
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = DashboardBlueDark,
                    trackColor = DashboardLine,
                )
            }

            when (state) {
                "logged_out" -> {
                    TimerButton("Clock In", DashboardBlueDark, Icons.AutoMirrored.Outlined.Login, !loading) { onAction("clock_in") }
                }
                "on_floor" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimerButton("Take Break", DashboardOrange, Icons.Outlined.Coffee, !loading, Modifier.weight(1f)) { onBreak(true) }
                    TimerButton("Clock Out", AinoDanger, Icons.AutoMirrored.Outlined.Logout, !loading, Modifier.weight(1f)) { onAction("clock_out") }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimerButton("Resume", DashboardBlueDark, Icons.AutoMirrored.Outlined.Login, !loading, Modifier.weight(1f)) { onBreak(false) }
                    TimerButton("Clock Out", AinoDanger, Icons.AutoMirrored.Outlined.Logout, !loading, Modifier.weight(1f)) { onAction("clock_out") }
                }
            }
        }
    }
}

@Composable
private fun WorkLocationCard(
    workMode: String,
    enabled: Boolean,
    onWorkMode: (String) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (dark) MaterialTheme.colorScheme.surfaceContainerLow else DashboardCard,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(46.dp).background(DashboardBlue, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Apartment, null, Modifier.size(22.dp), tint = Color.White)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Work Location", color = if (dark) MaterialTheme.colorScheme.onSurface else DashboardInk, fontWeight = FontWeight.Bold)
                Text(
                    when (workMode) {
                        "remote" -> "Working remotely"
                        "hybrid" -> "Hybrid workday"
                        else -> "Office"
                    },
                    color = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else DashboardMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (enabled) {
                Row(
                    Modifier.background(if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else DashboardCream, RoundedCornerShape(12.dp)).padding(3.dp),
                ) {
                    ModeButton("Office", Icons.Outlined.Apartment, workMode == "office", Modifier.width(72.dp)) { onWorkMode("office") }
                    ModeButton("Remote", Icons.Outlined.Home, workMode == "remote", Modifier.width(76.dp)) { onWorkMode("remote") }
                }
            } else {
                Text("›", color = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else DashboardMuted, style = MaterialTheme.typography.titleLarge)
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

internal fun dashboardGreeting(hour: Int): String = when (hour) {
    in 0..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    else -> "Good Evening"
}

internal fun dashboardWeek(today: LocalDate): List<LocalDate> =
    List(7) { today.minusDays(6).plusDays(it.toLong()) }

private fun greeting(): String = dashboardGreeting(LocalTime.now().hour)

private fun statusLabel(state: String): String = when (state) {
    "on_floor" -> "Working"
    "on_break" -> "On break"
    else -> "Logged out"
}

private fun formatMinutes(seconds: Long): String = "%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60)