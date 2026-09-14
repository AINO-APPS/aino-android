package app.aino.mobile.feature.leaves

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.designsystem.theme.AinoBlue
import app.aino.mobile.core.designsystem.theme.AinoDanger
import app.aino.mobile.core.designsystem.theme.AinoSuccess
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun LeavesScreen(viewModel: LeaveViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AinoAtmosphere {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Leaves", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Box(
                    Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clickable(enabled = !ui.loading, onClick = viewModel::refresh),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Refresh, "Refresh", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            LeaveTabs(ui.tab, viewModel::selectTab)
            ui.error?.let { AinoAlert(it, AlertTone.Error) }
            ui.message?.let { AinoAlert(it, AlertTone.Success) }
            MonthBar(ui, viewModel)
            when (ui.tab) {
                LeaveTab.Apply -> {
                    LeaveBalances(ui)
                    ApplyForm(ui, viewModel)
                }
                LeaveTab.History -> LeaveHistory(ui, viewModel)
                LeaveTab.Calendar -> MonthSchedule(ui)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LeaveTabs(selected: LeaveTab, onSelect: (LeaveTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LeaveTab.entries.forEach { tab ->
            val icon = when (tab) {
                LeaveTab.Apply -> Icons.Outlined.Send
                LeaveTab.History -> Icons.Outlined.History
                LeaveTab.Calendar -> Icons.Outlined.CalendarMonth
            }
            Row(
                Modifier.weight(1f).clickable { onSelect(tab) }
                    .background(if (selected == tab) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (selected == tab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(icon, null, Modifier.size(16.dp), tint = tint)
                Text(tab.name, Modifier.padding(start = 6.dp), color = tint, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MonthBar(ui: LeaveUiState, viewModel: LeaveViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(ui.month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NavButton(Icons.Outlined.ChevronLeft) { viewModel.changeMonth(-1) }
            Text(
                "Today",
                Modifier.clickable(onClick = viewModel::currentMonth).padding(horizontal = 9.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            NavButton(Icons.Outlined.ChevronRight) { viewModel.changeMonth(1) }
        }
    }
}

@Composable
private fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LeaveBalances(ui: LeaveUiState) {
    if (ui.balances.isEmpty()) return
    AinoSectionHeader("Balances", "${ui.month.year} entitlement")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 8.dp) / 2
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            ui.balances.forEach { balance ->
                AinoGlassCard(Modifier.width(cardWidth)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth().height(3.dp).background(balance.accentColor()))
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(balance.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDays(balance.available), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = balance.accentColor())
                        Text(
                            "${formatDays(balance.used)} used · ${formatDays(balance.quota + balance.carriedForward)} total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ApplyForm(ui: LeaveUiState, viewModel: LeaveViewModel) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New Leave Request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (ui.policies.isEmpty()) {
                AinoAlert("No leave policies are configured for this workspace yet.", AlertTone.Info)
            } else {
                FormLabel("Leave Type")
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.policies.forEach { policy ->
                        Chip(policy.label(), ui.applyType == policy.leaveType) { viewModel.updateApplyForm(type = policy.leaveType) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LeaveField("From (YYYY-MM-DD)", ui.applyStart, { viewModel.updateApplyForm(start = it) }, Modifier.weight(1f))
                LeaveField("To (YYYY-MM-DD)", ui.applyEnd, { viewModel.updateApplyForm(end = it) }, Modifier.weight(1f))
            }
            FormLabel("Duration")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LEAVE_DURATIONS.forEach { duration ->
                    // Half and quarter days only appear when the selected policy
                    // permits them, matching the server's per-policy gate.
                    val allowed = when (duration) {
                        "half" -> ui.selectedPolicy?.halfDayAllowed ?: true
                        "quarter" -> ui.selectedPolicy?.quarterDayAllowed ?: true
                        else -> true
                    }
                    if (allowed) {
                        Chip(durationLabel(duration), ui.applyDuration == duration) { viewModel.updateApplyForm(duration = duration) }
                    }
                }
            }
            LeaveField("Reason (optional)", ui.applyReason, { viewModel.updateApplyForm(reason = it) }, singleLine = false)
            val dates = ui.requestedDates
            Text(
                if (dates.isEmpty()) "Choose a valid date range." else
                    "${dates.size} day(s) · ${formatDays(dates.size * durationDays(ui.applyDuration))} deducted",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            AinoPrimaryButton("Submit Request", viewModel::apply, Modifier.fillMaxWidth(), !ui.loading)
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun LeaveHistory(ui: LeaveUiState, viewModel: LeaveViewModel) {
    if (ui.leaves.isEmpty()) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (ui.loading) "Loading leaves…" else "No leaves this month.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        HistoryStat(ui.leaves.size.toString(), "REQUESTS")
        HistoryStat(formatDays(totalLeaveDays(ui.leaves)), "DAYS")
        HistoryStat(ui.leaves.count { it.status == "approved" }.toString(), "APPROVED")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.leaves.forEach { leave -> LeaveRow(leave, viewModel) }
    }
}

@Composable
private fun LeaveRow(leave: Leave, viewModel: LeaveViewModel) {
    val action = availableLeaveAction(leave)
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(34.dp).background(leaveTypeColor(leave.leaveType).copy(alpha = .14f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.BeachAccess, null, Modifier.size(18.dp), tint = leaveTypeColor(leave.leaveType)) }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        leave.leaveType.replace('_', ' ').replaceFirstChar(Char::uppercase),
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                    Text(
                        "${runCatching { LocalDate.parse(leave.date.take(10)).format(DateTimeFormatter.ofPattern("EEE, MMM d")) }.getOrDefault(leave.date)} · ${durationLabel(leave.duration)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AinoBadge(leaveStatusLabel(leave.status), leaveTone(leave.status))
            }
            leave.reason?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            leave.rejectReason?.takeIf(String::isNotBlank)?.let {
                Text(it, color = AinoDanger, style = MaterialTheme.typography.bodySmall)
            }
            // Only the action the server would accept is offered, so a tap can
            // never produce a guaranteed 400.
            if (action != LeaveAction.None) {
                Text(
                    if (action == LeaveAction.Cancel) "Cancel request" else "Request withdrawal",
                    Modifier.clickable { viewModel.act(leave) }
                        .background(AinoDanger.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = AinoDanger,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            }
        }
    }
}

@Composable
private fun HistoryStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text(label, fontSize = 9.sp, letterSpacing = .3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun leaveTone(status: String): AlertTone = when (status) {
    "approved" -> AlertTone.Success
    "rejected", "revoked" -> AlertTone.Error
    else -> AlertTone.Warning
}

@Composable
private fun MonthSchedule(ui: LeaveUiState) {
    val monthPrefix = ui.month.toString()
    CalendarGrid(ui)
    // The holiday route is year-scoped, so the month filter happens here.
    val holidays = ui.holidays.filter { it.date.take(7) == monthPrefix }.sortedBy { it.date }
    AinoSectionHeader("Holidays", "${holidays.size} this month")
    if (holidays.isEmpty()) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Text("No holidays this month.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            holidays.forEach { holiday ->
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CalendarMonth, null, tint = AinoBlue)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(holiday.name, fontWeight = FontWeight.SemiBold)
                            Text(holiday.date.take(10), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (holiday.isOptional) AinoBadge("Optional", AlertTone.Info)
                    }
                }
            }
        }
    }

    AinoSectionHeader("Events", "${ui.events.size} scheduled")
    if (ui.events.isEmpty()) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Text("No calendar events this month.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.events.sortedBy { it.startTime }.forEach { event ->
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Event, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(event.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (event.allDay) event.startTime.take(10) else event.startTime.take(16).replace('T', ' '),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        event.taskTitle?.let {
                            Text("Task: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    event.meetingCode?.let { AinoBadge(it, AlertTone.Info) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CalendarGrid(ui: LeaveUiState) {
    val first = ui.month.atDay(1)
    val gridStart = first.minusDays((first.dayOfWeek.value % 7).toLong())
    val days = (0L until 42L).map(gridStart::plusDays)
    val today = LocalDate.now()
    val weekdays = listOf("S", "M", "T", "W", "T", "F", "S")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { day ->
                Text(day, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cellWidth = maxWidth / 7
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 7) {
                days.forEach { date ->
                    val key = date.toString()
                    val eventColors = buildList {
                        if (ui.events.any { it.startTime.take(10) == key }) add(MaterialTheme.colorScheme.primary)
                        if (ui.holidays.any { it.date.take(10) == key }) add(AinoBlue)
                    }
                    Column(
                        Modifier.width(cellWidth).aspectRatio(1f).padding(2.dp)
                            .background(
                                if (date == today) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(date.dayOfMonth.toString(), fontSize = 14.sp,
                            fontWeight = if (date == today) FontWeight.Bold else FontWeight.Medium,
                            color = if (date.month == ui.month.month) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
                        Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            eventColors.take(3).forEach { color ->
                                Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaveField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    )
}

private fun LeaveBalance.accentColor(): Color = color.toComposeColorOrNull() ?: leaveTypeColor(leaveType)

private fun leaveTypeColor(type: String): Color = when (type.lowercase()) {
    "sick" -> Color(0xFFEF4444)
    "casual" -> Color(0xFFF59E0B)
    "earned", "annual" -> Color(0xFF10B981)
    "holiday" -> Color(0xFF8B5CF6)
    else -> AinoBlue
}

private fun String?.toComposeColorOrNull(): Color? = runCatching {
    val raw = this?.trim()?.removePrefix("#") ?: return null
    val value = raw.toLong(16)
    when (raw.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> null
    }
}.getOrNull()
