package app.aino.mobile.feature.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.common.formatDuration
import app.aino.mobile.core.designsystem.theme.AinoDanger
import app.aino.mobile.core.designsystem.theme.AinoSuccess
import app.aino.mobile.core.designsystem.theme.AinoWarning
import app.aino.mobile.core.designsystem.theme.AinoBlue
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun AttendanceScreen(
    viewModel: AttendanceViewModel,
    onLocationPermission: () -> Unit,
    onBiometricRequired: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(AttendancePage.Overview) }
    AinoAtmosphere {
        Column(Modifier.fillMaxSize()) {
            AttendanceTabs(selectedTab) { tab ->
                selectedTab = tab
                when (tab) {
                    AttendancePage.Overview -> viewModel.selectTab(AttendanceTab.Overview)
                    AttendancePage.Manual -> viewModel.selectTab(AttendanceTab.Manual)
                    else -> Unit
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            ui.error?.let { AinoAlert(it, AlertTone.Error) }
            ui.message?.let { AinoAlert(it, AlertTone.Success) }
                when (selectedTab) {
                    AttendancePage.Overview -> AttendanceOverview(ui, viewModel)
                    AttendancePage.Manual -> ManualAttendance(ui, viewModel)
                    AttendancePage.Leaves -> AttendanceLeaves(ui)
                    AttendancePage.Analytics -> AttendanceAnalytics(ui)
                }
            }
        }
    }
}

private enum class AttendancePage(val label: String) { Overview("Overview"), Leaves("Leaves"), Manual("Manual"), Analytics("Analytics") }

@Composable
private fun AttendanceTabs(selected: AttendancePage, onSelect: (AttendancePage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        AttendancePage.entries.forEach { tab ->
            val icon = when (tab) {
                AttendancePage.Overview -> Icons.Outlined.CalendarMonth
                AttendancePage.Manual -> Icons.Outlined.EditCalendar
                AttendancePage.Leaves -> Icons.Outlined.BeachAccess
                AttendancePage.Analytics -> Icons.Outlined.BarChart
            }
            Row(
                Modifier.weight(1f).clickable { onSelect(tab) }
                    .background(if (selected == tab) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(vertical = 9.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(15.dp), tint = if (selected == tab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tab.label, Modifier.padding(start = 4.dp), color = if (selected == tab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ManualAttendance(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EditCalendar, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (ui.manualEditMode) "Edit time entry" else "Manual time entry", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
            }
            Text("Add a missed work day. Your manager may need to approve it.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            AttendanceField("Date (YYYY-MM-DD)", ui.manualDate, { viewModel.updateManualForm(date = it) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttendanceField("Clock in", ui.manualClockIn, { viewModel.updateManualForm(clockIn = it) }, Modifier.weight(1f))
                AttendanceField("Clock out", ui.manualClockOut, { viewModel.updateManualForm(clockOut = it) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkMode.entries.forEach { mode ->
                    Button(
                        onClick = { viewModel.updateManualForm(mode = mode) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.manualMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (ui.manualMode == mode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(mode.name.take(6).lowercase().replaceFirstChar(Char::uppercase), maxLines = 1) }
                }
            }
            if (ui.checkingManualDate) Text("Checking selected date…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.manualBreaks.isNotEmpty()) {
                Text("Breaks", style = MaterialTheme.typography.titleMedium)
                ui.manualBreaks.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AttendanceField("Start", item.start, { viewModel.updateManualBreak(index, start = it) }, Modifier.weight(1f))
                        AttendanceField("End", item.end, { viewModel.updateManualBreak(index, end = it) }, Modifier.weight(1f))
                        Text("Remove", Modifier.clickable { viewModel.removeManualBreak(index) }, color = AinoDanger, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Button(
                onClick = viewModel::addManualBreak,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                enabled = ui.manualBreaks.size < 20,
            ) { Text("Add break", color = MaterialTheme.colorScheme.onSurface) }
            AinoPrimaryButton(if (ui.manualEditMode) "Submit entry update" else "Submit manual entry", viewModel::submitManualEntry, Modifier.fillMaxWidth(), !ui.loading && !ui.checkingManualDate)
        }
    }

    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MoreTime, null, tint = MaterialTheme.colorScheme.primary)
                Text("Overtime request", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
            }
            AttendanceField("Date (YYYY-MM-DD)", ui.overtimeDate, { viewModel.updateOvertimeForm(date = it) })
            AttendanceField("Extra hours", ui.overtimeHours, { viewModel.updateOvertimeForm(hours = it) }, keyboardType = KeyboardType.Decimal)
            AttendanceField("Reason", ui.overtimeReason, { viewModel.updateOvertimeForm(reason = it) }, singleLine = false)
            AinoPrimaryButton("Submit overtime request", viewModel::submitOvertime, Modifier.fillMaxWidth(), !ui.loading)
        }
    }

    RequestSection("Manual entry requests", ui.manualRequests.map { request ->
        RequestDisplay(
            id = request.requestId,
            date = request.metadata?.date,
            detail = listOfNotNull(request.metadata?.clockIn, request.metadata?.clockOut).joinToString(" → "),
            status = request.approvalStatus,
            reason = request.rejectReason,
        )
    })
    RequestSection("Overtime requests", ui.overtimeRequests.map { request ->
        RequestDisplay(
            id = request.id,
            date = request.metadata?.date,
            detail = request.metadata?.hours?.let { "$it hours" }.orEmpty(),
            status = request.status,
            reason = request.rejectReason ?: request.reason,
        )
    })
}

@Composable
private fun AttendanceLeaves(ui: AttendanceUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Leaves", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text("Approved and pending time off", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        AinoBadge("${ui.leaves.size} days", AlertTone.Info)
    }
    if (ui.leaves.isEmpty()) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.BeachAccess, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No leave days this month", fontWeight = FontWeight.SemiBold)
                Text("Leave requests will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        ui.leaves.toSortedMap().forEach { (date, leave) ->
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), fontWeight = FontWeight.Bold)
                        Text("${leave.leaveType.replace('_', ' ').replaceFirstChar(Char::uppercase)} · ${leave.duration.replaceFirstChar(Char::uppercase)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        leave.reason?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    }
                    AinoBadge(leave.status.replaceFirstChar(Char::uppercase), requestTone(leave.status))
                }
            }
        }
    }
}

@Composable
private fun AttendanceAnalytics(ui: AttendanceUiState) {
    val days = ui.history.toSortedMap().values.toList()
    val worked = days.sumOf { it.floorMinutes }
    val breaks = days.sumOf { it.breakMinutes }
    val present = days.count { it.floorMinutes > 0 || it.entries.isNotEmpty() }
    val average = if (present == 0) 0 else worked / present
    Text("Attendance Analytics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    Text("This month", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AnalyticsStat("Work", formatDuration(worked * 60L), AinoBlue, Modifier.weight(1f))
        AnalyticsStat("Break", formatDuration(breaks * 60L), AinoWarning, Modifier.weight(1f))
        AnalyticsStat("Average", formatDuration(average * 60L), AinoSuccess, Modifier.weight(1f))
    }
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Work & break trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (days.isEmpty()) Text("No analytics available for this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            days.takeLast(10).forEach { day ->
                val total = maxOf(day.floorMinutes + day.breakMinutes, 1)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day.date.takeLast(5), Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth(day.floorMinutes.toFloat() / total).height(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        if (day.breakMinutes > 0) Box(Modifier.fillMaxWidth(day.breakMinutes.toFloat() / total).height(4.dp).background(AinoWarning, CircleShape))
                    }
                    Text(formatDuration(day.floorMinutes * 60L), Modifier.width(58.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    AinoGlassCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, color = color, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private data class RequestDisplay(val id: Long, val date: String?, val detail: String, val status: String, val reason: String?)

@Composable
private fun RequestSection(title: String, requests: List<RequestDisplay>) {
    AinoSectionHeader(title)
    if (requests.isEmpty()) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Text("No requests yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        requests.forEach { request ->
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(request.date?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("EEE, MMM d")) }.getOrDefault(it) } ?: "—", fontWeight = FontWeight.SemiBold)
                        if (request.detail.isNotBlank()) Text(request.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        request.reason?.let { Text(it, color = if (request.status == "rejected") AinoDanger else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    }
                    AinoBadge(request.status, requestTone(request.status))
                }
            }
        }
    }
}

@Composable
private fun AttendanceField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    )
}

private fun requestTone(status: String): AlertTone = when (status) {
    "approved" -> AlertTone.Success
    "rejected" -> AlertTone.Error
    else -> AlertTone.Warning
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AttendanceOverview(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val month = ui.month
    val today = LocalDate.now()
    val policy = ui.policy ?: AttendancePolicy()
    val workDays = workDaySet(policy.workDays)
    val minimumMinutes = ((policy.minHoursPresent ?: (policy.workHoursPerDay / 2.0)) * 60).toInt()
    val grid = monthGrid(month)
    val monthDays = (1..month.lengthOfMonth()).map(month::atDay)
    val stats = monthDays.filter { it <= today }.groupingBy {
        attendanceKind(it, today, ui.history[it], workDays, minimumMinutes, ui.leaves[it], ui.holidays[it])
    }.eachCount()

    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalendarNavButton(Icons.Outlined.ChevronLeft) { viewModel.changeMonth(-1) }
                    Text("Today", Modifier.clickable { viewModel.currentMonth() }.padding(horizontal = 9.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    CalendarNavButton(Icons.Outlined.ChevronRight) { viewModel.changeMonth(1) }
                }
            }
            AttendanceLegend()
            Row(Modifier.fillMaxWidth()) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                    Text(day, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                grid.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            CalendarDay(
                                date = date,
                                inMonth = YearMonth.from(date) == month,
                                selected = date == ui.selectedDate,
                                today = date == today,
                                kind = attendanceKind(date, today, ui.history[date], workDays, minimumMinutes, ui.leaves[date], ui.holidays[date]),
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.selectDate(date) },
                            )
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 8.dp) / 2
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            MonthStat("Present", stats[AttendanceDayKind.Present] ?: 0, AinoSuccess, Modifier.width(cardWidth))
            MonthStat("Absent", stats[AttendanceDayKind.Absent] ?: 0, AinoDanger, Modifier.width(cardWidth))
            MonthStat("Leave", stats[AttendanceDayKind.Leave] ?: 0, AinoBlue, Modifier.width(cardWidth))
            MonthStat("Days off", (stats[AttendanceDayKind.Weekend] ?: 0) + (stats[AttendanceDayKind.Holiday] ?: 0), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.width(cardWidth))
        }
    }
    SelectedDayDetail(ui.selectedDate, ui.history[ui.selectedDate], ui.leaves[ui.selectedDate], ui.holidays[ui.selectedDate])
}

@Composable
private fun CalendarNavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AttendanceLegend() {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendDot(AinoSuccess, "Present")
        LegendDot(AinoDanger, "Absent")
        LegendDot(AinoBlue, "Leave")
        LegendDot(AinoWarning, "In progress")
        LegendDot(MaterialTheme.colorScheme.onSurfaceVariant, "Day off")
    }
}

@Composable
private fun RowScope.LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, Modifier.padding(start = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    kind: AttendanceDayKind,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val dot = when (kind) {
        AttendanceDayKind.Present -> AinoSuccess
        AttendanceDayKind.Leave -> AinoBlue
        AttendanceDayKind.LeavePending -> AinoWarning
        AttendanceDayKind.Holiday -> MaterialTheme.colorScheme.onSurfaceVariant
        AttendanceDayKind.Absent -> AinoDanger
        AttendanceDayKind.InProgress -> AinoWarning
        AttendanceDayKind.Weekend -> MaterialTheme.colorScheme.onSurfaceVariant
        AttendanceDayKind.Future -> Color.Transparent
    }
    Column(
        modifier.aspectRatio(1f).padding(2.dp).clickable(onClick = onClick)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    today -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(date.dayOfMonth.toString(), color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = if (inMonth) 1f else 0.35f), fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal)
        Box(Modifier.padding(top = 3.dp).size(6.dp).background(dot, CircleShape))
    }
}

@Composable
private fun MonthStat(label: String, value: Int, color: Color, modifier: Modifier) {
    AinoGlassCard(modifier) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), color = color, style = MaterialTheme.typography.titleLarge)
            Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SelectedDayDetail(date: LocalDate, day: AttendanceDay?, leave: LeaveOverlay?, holiday: HolidayOverlay?) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), style = MaterialTheme.typography.titleMedium)
            if (day != null && (day.floorMinutes > 0 || day.entries.isNotEmpty())) {
                DetailRow("Work", formatDuration(day.floorMinutes * 60L))
                DetailRow("Break", formatDuration(day.breakMinutes * 60L))
                DetailRow("Total", formatDuration(day.totalMinutes * 60L))
                DetailRow("Mode", day.workMode.replaceFirstChar(Char::uppercase))
            } else if (leave != null) {
                DetailRow("Status", "${leave.leaveType.replaceFirstChar(Char::uppercase)} leave")
                DetailRow("Approval", leave.status.replaceFirstChar(Char::uppercase))
                DetailRow("Duration", leave.duration.replaceFirstChar(Char::uppercase))
                leave.reason?.let { DetailRow("Reason", it) }
            } else if (holiday != null) {
                DetailRow("Holiday", holiday.name)
                if (holiday.isOptional) DetailRow("Type", "Optional observance")
            } else {
                Text("No recorded attendance", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun statusLabel(state: String?): String = when (state) {
    "on_floor" -> "Working"
    "on_break" -> "On break"
    else -> "Logged out"
}

private fun statusTone(state: String?): AlertTone = when (state) {
    "on_floor" -> AlertTone.Success
    "on_break" -> AlertTone.Warning
    else -> AlertTone.Info
}