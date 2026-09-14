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
import app.aino.mobile.feature.home.formatDuration
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
    val status = ui.status
    AinoAtmosphere {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AinoSectionHeader("Attendance", "Secure clocking with tenant policy enforcement")
            AttendanceTabs(ui.selectedTab, viewModel::selectTab)
            ui.error?.let { AinoAlert(it, AlertTone.Error) }
            ui.message?.let { AinoAlert(it, AlertTone.Success) }
            if (ui.selectedTab == AttendanceTab.Today) {
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AinoBadge(statusLabel(status?.state), statusTone(status?.state))
                        AinoBadge(ui.workMode.name, if (ui.workMode == WorkMode.Remote) AlertTone.Warning else AlertTone.Info)
                    }
                    val floorSeconds = (status?.floorMinutes ?: 0) * 60L
                    val breakSeconds = (status?.breakMinutes ?: 0) * 60L
                    Text(formatDuration(floorSeconds), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text("Work today · Break ${formatDuration(breakSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ui.policy?.verificationEnabled == true) {
                        AinoAlert("Attendance verification is enabled for this workspace.", AlertTone.Info)
                        ui.locationProof?.let { proof ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                                Text("Precise location ±${proof.accuracyMeters.toInt()} m", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (status?.state == "logged_out") {
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Work mode", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorkMode.entries.forEach { mode ->
                                Button(
                                    onClick = { viewModel.setWorkMode(mode) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (ui.workMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (ui.workMode == mode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase), maxLines = 1) }
                            }
                        }
                        AinoPrimaryButton(
                            if (ui.loading) "Preparing…" else "Clock in",
                            { viewModel.prepare(AttendanceAction.ClockIn, onLocationPermission, onBiometricRequired) },
                            Modifier.fillMaxWidth(),
                            !ui.loading && status.isWeekend.not(),
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.padding(end = 8.dp), tint = Color.White) },
                        )
                    }
                }
            } else {
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (status?.state == "on_floor") {
                            AinoPrimaryButton(
                                "Start break",
                                { viewModel.breakAction(start = true) },
                                Modifier.fillMaxWidth(),
                                !ui.loading,
                                leadingIcon = { Icon(Icons.Outlined.Coffee, null, Modifier.padding(end = 8.dp), tint = Color.White) },
                            )
                        } else if (status?.state == "on_break") {
                            AinoPrimaryButton("Resume work", { viewModel.breakAction(start = false) }, Modifier.fillMaxWidth(), !ui.loading)
                        }
                        AinoPrimaryButton(
                            if (ui.loading) "Preparing…" else "Clock out",
                            { viewModel.prepare(AttendanceAction.ClockOut, onLocationPermission, onBiometricRequired) },
                            Modifier.fillMaxWidth(),
                            !ui.loading,
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.padding(end = 8.dp), tint = Color.White) },
                        )
                    }
                }
            }
            AinoPrimaryButton("Refresh attendance", viewModel::refresh, Modifier.fillMaxWidth(), !ui.loading, leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.padding(end = 8.dp), tint = Color.White) })
            if (ui.policy?.verificationEnabled == true && ui.workMode == WorkMode.Remote) {
                AinoAlert("Remote verified clock-in requires face matching. Password and fingerprint alone cannot satisfy this policy.", AlertTone.Warning)
            } else if (ui.policy?.verificationEnabled == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Office actions require precise location and strong biometric confirmation.", style = MaterialTheme.typography.bodySmall)
                }
            }
            } else {
                if (ui.selectedTab == AttendanceTab.Overview) AttendanceOverview(ui, viewModel)
                else ManualAttendance(ui, viewModel)
            }
        }
    }
}

@Composable
private fun AttendanceTabs(selected: AttendanceTab, onSelect: (AttendanceTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        AttendanceTab.entries.forEach { tab ->
            Row(
                Modifier.weight(1f).clickable { onSelect(tab) }
                    .background(if (selected == tab) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp), tint = if (selected == tab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tab.name, Modifier.padding(start = 6.dp), color = if (selected == tab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
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
                Text("Manual time entry", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
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
            AinoPrimaryButton("Submit manual entry", viewModel::submitManualEntry, Modifier.fillMaxWidth(), !ui.loading)
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