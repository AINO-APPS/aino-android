package app.aino.mobile.feature.attendance

import app.aino.mobile.core.common.getLeaveType
import app.aino.mobile.core.common.LeaveTypeMeta
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.format.DateTimeFormatter

private fun subTabIcon(tab: LeavesSubTab): ImageVector = when (tab) {
    LeavesSubTab.MyLeaves -> Icons.Outlined.Send
    LeavesSubTab.MyBalances -> Icons.Outlined.BarChart
    LeavesSubTab.Policies -> Icons.Outlined.Assignment
    LeavesSubTab.AllBalances -> Icons.Outlined.Groups
}

/**
 * Leaves tab (P3.3) — port of `pages/Leaves.tsx`. Policy CRUD and balance
 * editing stay on the web (Phase 10); the HR panels here are read-only views
 * of the same GET endpoints.
 */
@Composable
fun LeavesTab(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val visibleTabs = LeavesSubTab.entries.filter { !it.hrOnly || ui.isHr }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Leave Management", color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.25.rem)
                Text(
                    "Request time off, configure policies, manage balances and public holidays",
                    color = colors.textMuted,
                    fontSize = 0.85.rem,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (ui.leavesSubTab == LeavesSubTab.MyLeaves) {
                MonthPicker(
                    ui.leavesMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    { viewModel.changeLeavesMonth(-1) },
                    { viewModel.changeLeavesMonth(1) },
                )
            }
        }

        Row(
            Modifier
                .background(colors.surface, RoundedCornerShape(10.dp))
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visibleTabs.forEach { tab ->
                val active = ui.leavesSubTab == tab
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp))
                        .background(if (active) colors.primary else Color.Transparent)
                        .clickable { viewModel.selectLeavesSubTab(tab) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(subTabIcon(tab), null, Modifier.size(14.dp), tint = if (active) colors.onAccent else colors.textSecondary)
                    Text(
                        "  " + tab.label,
                        color = if (active) colors.onAccent else colors.textSecondary,
                        fontSize = 0.8.rem,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        ui.leaveError?.let { WebErrorBanner(it) }
        ui.leaveSuccess?.let { WebSuccessBanner(it) }

        when (ui.leavesSubTab) {
            LeavesSubTab.MyLeaves -> MyLeavesPanel(ui, viewModel)
            LeavesSubTab.MyBalances -> MyBalancesPanel(ui, viewModel)
            LeavesSubTab.Policies -> PoliciesPanel(ui)
            LeavesSubTab.AllBalances -> AllBalancesPanel(ui, viewModel)
        }
    }

    WithdrawDialog(ui, viewModel)
}

/** ConfirmDialog port: exact copy from Leaves.tsx. */
@Composable
private fun WithdrawDialog(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val candidate = ui.withdrawCandidate ?: return
    AlertDialog(
        onDismissRequest = { viewModel.confirmWithdraw(null) },
        title = { Text("Withdraw Leave Request") },
        text = {
            Text(
                "Withdraw your ${candidate.leaveType} leave for ${fmtDate(candidate.date)}?" +
                    if (candidate.status == "approved") " This requires manager approval." else "",
            )
        },
        confirmButton = { TextButton(onClick = viewModel::withdrawLeave) { Text("Withdraw") } },
        dismissButton = { TextButton(onClick = { viewModel.confirmWithdraw(null) }) { Text("Cancel") } },
    )
}

/** Month picker row standing in for `<input type="month">`. */
@Composable
private fun MonthPicker(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PickerArrow(Icons.Outlined.ChevronLeft, onPrev)
        Text(label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem, modifier = Modifier.padding(horizontal = 6.dp))
        PickerArrow(Icons.Outlined.ChevronRight, onNext)
    }
}

@Composable
private fun PickerArrow(icon: ImageVector, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(7.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = colors.text)
    }
}

/** My Leaves = balance cards + request form + history (`.layout` stacked on mobile). */
@Composable
private fun MyLeavesPanel(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LeaveBalanceCards(ui.leaveBalances)
        LeaveRequestFormCard(ui, viewModel)
        LeaveHistorySection(ui, viewModel)
    }
}

/** `LeaveBalanceCards` port: horizontal row of balance cards. */
@Composable
private fun LeaveBalanceCards(balances: List<LeaveBalance>) {
    if (balances.isEmpty()) return
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        balances.forEach { balance ->
            val type = getLeaveType(balance.leaveType)
            val barColor = when {
                balance.usedPercent >= 80 -> Color(0xFFEF4444)
                balance.usedPercent >= 50 -> Color(0xFFF59E0B)
                else -> type.color
            }
            Column(
                Modifier
                    .width(190.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(type.bg, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                        Icon(type.icon, null, Modifier.size(18.dp), tint = type.color)
                    }
                    Text(
                        type.label,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 0.85.rem,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(formatDays(balance.available), color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.3.rem)
                    Text(
                        " of ${formatDays(balance.total)} available",
                        color = colors.textMuted,
                        fontSize = 0.75.rem,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp)
                        .background(colors.surfaceHover, RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(balance.usedPercent / 100f).height(5.dp)
                            .background(barColor, RoundedCornerShape(3.dp)),
                    )
                }
                Text(
                    "${formatDays(balance.used)} used · ${formatDays(balance.carriedForward)} carried forward",
                    color = colors.textMuted,
                    fontSize = 0.72.rem,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** `LeaveRequestForm` port: single/range toggle, type chips, duration, reason. */
@Composable
private fun LeaveRequestFormCard(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val rangeDays = viewModel.leaveRequestDates()

    AttendanceCard {
        Text("New Leave Request", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SegmentedRow(
                listOf("Single Day", "Date Range"),
                if (ui.leaveIsRange) 1 else 0,
                { viewModel.updateLeaveForm(isRange = it == 1) },
            )

            if (!ui.leaveIsRange) {
                Column {
                    WebFieldLabel("Date")
                    WebDateField(ui.leaveDate, { viewModel.updateLeaveForm(date = it) }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        WebFieldLabel("From")
                        WebDateField(ui.leaveFrom, { from ->
                            // Keep the range coherent: moving From past To drags To with it.
                            viewModel.updateLeaveForm(from = from, to = if (ui.leaveTo.isNotBlank() && from > ui.leaveTo) from else null)
                        }, Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    Text("→", color = colors.textMuted, modifier = Modifier.padding(top = 18.dp))
                    Column(Modifier.weight(1f)) {
                        WebFieldLabel("To")
                        WebDateField(ui.leaveTo, { viewModel.updateLeaveForm(to = it) }, Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
                Row(
                    Modifier.clickable { viewModel.updateLeaveForm(skipWeekends = !ui.leaveSkipWeekends) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = ui.leaveSkipWeekends,
                        onCheckedChange = { viewModel.updateLeaveForm(skipWeekends = it) },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = colors.primary),
                    )
                    Text("Skip weekends", color = colors.text, fontSize = 0.85.rem)
                }
                if (rangeDays.isNotEmpty()) {
                    Row(
                        Modifier.background(colors.primaryGlow, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(rangeDays.size.toString(), color = colors.primary, fontWeight = FontWeight.ExtraBold, fontSize = 1.rem)
                        Text(
                            " working day${if (rangeDays.size != 1) "s" else ""} selected",
                            color = colors.textSecondary,
                            fontSize = 0.82.rem,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            LeaveTypeAndSubmit(ui, viewModel, rangeDays.size)
        }
    }
}

@Composable
private fun LeaveTypeAndSubmit(ui: AttendanceUiState, viewModel: AttendanceViewModel, rangeCount: Int) {
    val colors = LocalWebColors.current
    val typeOptions = buildLeaveTypeOptions(ui.leavePolicies).filter { it.value != "holiday" }

    Column {
        WebFieldLabel("Leave Type")
        if (typeOptions.isEmpty()) {
            Text(
                "No leave types configured. Ask your HR admin to add leave policies.",
                color = colors.textMuted,
                fontSize = 0.82.rem,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                typeOptions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { type ->
                            val active = ui.leaveType == type.value
                            Row(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (active) type.bg else colors.surface)
                                    .border(1.dp, if (active) type.color else colors.border, RoundedCornerShape(10.dp))
                                    .clickable { viewModel.updateLeaveForm(type = type.value) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(type.icon, null, Modifier.size(16.dp), tint = type.color)
                                Text(
                                    "  " + type.label,
                                    color = if (active) type.color else colors.textSecondary,
                                    fontSize = 0.8.rem,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    Column {
        WebFieldLabel("Duration")
        SegmentedRow(
            listOf("Full Day", "Half Day", "Quarter"),
            LEAVE_DURATIONS.indexOf(ui.leaveDuration).coerceAtLeast(0),
            { viewModel.updateLeaveForm(duration = LEAVE_DURATIONS[it]) },
            Modifier.padding(top = 6.dp),
        )
    }

    Column {
        Row {
            WebFieldLabel("Reason ")
            Text("(optional)", color = colors.textMuted, fontSize = 0.75.rem)
        }
        WebTextInput(
            ui.leaveReason,
            { viewModel.updateLeaveForm(reason = it) },
            Modifier.fillMaxWidth().padding(top = 6.dp),
            placeholder = "Briefly describe your reason…",
            singleLine = false,
            minLines = 3,
        )
    }

    WebPrimaryButton(
        when {
            ui.leaveSubmitting -> "Submitting…"
            ui.leaveIsRange -> "Submit ${if (rangeCount > 0) "$rangeCount " else ""}Request${if (rangeCount != 1) "s" else ""}"
            else -> "Submit Request"
        },
        enabled = !ui.leaveSubmitting && typeOptions.isNotEmpty(),
        onClick = viewModel::applyLeave,
    )
}

/** `LeaveHistory` port: summary strip, status/type filters, leave list. */
@Composable
private fun LeaveHistorySection(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val personal = ui.monthLeaves.filterNot(::isPublicHolidayLeave)
    val (filterStatus, setFilterStatus) = androidx.compose.runtime.remember(ui.leavesMonth) {
        androidx.compose.runtime.mutableStateOf("all")
    }
    val (filterType, setFilterType) = androidx.compose.runtime.remember(ui.leavesMonth) {
        androidx.compose.runtime.mutableStateOf("all")
    }
    val typeMeta = buildLeaveTypeMeta(ui.leavePolicies)
    val typeOptions = buildLeaveTypeOptions(ui.leavePolicies)
    val filtered = personal.filter {
        (filterStatus == "all" || it.status == filterStatus) && (filterType == "all" || it.leaveType == filterType)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Summary stats strip (personal leaves only).
        Row(
            Modifier.fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(10.dp))
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem(personal.size.toString(), "Requests", colors.text)
            StatItem(formatDays(totalLeaveDays(personal)), "Total Days", colors.text)
            StatItem(personal.count { it.status == "approved" }.toString(), "Approved", Color(0xFF10B981))
            StatItem(personal.count { it.status == "pending" }.toString(), "Pending", Color(0xFFF59E0B))
            StatItem(personal.count { it.status == "rejected" }.toString(), "Rejected", Color(0xFFEF4444))
        }

        // Filters (web uses selects; chips are the mobile equivalent).
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", "pending" to "Pending", "approved" to "Approved", "rejected" to "Rejected", "withdraw_pending" to "Withdrawal Pending")
                .forEach { (value, label) ->
                    WebFilterChip(label, filterStatus == value) { setFilterStatus(value) }
                }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WebFilterChip("All Types", filterType == "all") { setFilterType("all") }
            typeOptions.forEach { type ->
                WebFilterChip(type.label, filterType == type.value) { setFilterType(type.value) }
            }
        }

        AttendanceCard {
            Text("Leave History", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    ui.leavesLoading -> Text("Loading…", color = colors.textMuted, fontSize = 0.85.rem)
                    filtered.isEmpty() -> Text(
                        "No leave records found for the selected filters.",
                        color = colors.textMuted,
                        fontSize = 0.85.rem,
                    )
                    else -> filtered.forEach { leave -> LeaveRow(leave, typeMeta, viewModel) }
                }
            }
        }
    }
}

@Composable
private fun StatItem(value: String, label: String, color: Color) {
    val colors = LocalWebColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 1.05.rem)
        Text(label, color = colors.textMuted, fontSize = 0.68.rem)
    }
}

@Composable
private fun LeaveRow(leave: LeaveOverlay, typeMeta: Map<String, LeaveTypeMeta>, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val type = typeMeta[leave.leaveType] ?: getLeaveType(leave.leaveType)
    val status = leaveStatusMeta(leave.status)
    val canWithdraw = canWithdrawLeave(leave)

    Row(
        Modifier.fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).background(type.bg, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(type.icon, null, Modifier.size(16.dp), tint = type.color)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(type.label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem)
                Badge(durationLabel(leave.duration), colors.surfaceHover, colors.textSecondary)
                Badge(status.label, status.bg, status.color)
            }
            Text(fmtDate(leave.date), color = colors.textSecondary, fontSize = 0.78.rem, modifier = Modifier.padding(top = 3.dp))
            leave.reason?.takeIf(String::isNotBlank)?.let {
                Text("\"$it\"", color = colors.textMuted, fontSize = 0.78.rem, modifier = Modifier.padding(top = 2.dp))
            }
            leave.rejectReason?.takeIf(String::isNotBlank)?.let {
                Text("Rejection reason: $it", color = Color(0xFFEF4444), fontSize = 0.78.rem, modifier = Modifier.padding(top = 2.dp))
            }
            if (leave.status == "approved" && !leave.approvedByName.isNullOrBlank()) {
                Text("Approved by ${leave.approvedByName}", color = colors.textMuted, fontSize = 0.75.rem, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (canWithdraw) {
            Text(
                "Withdraw",
                color = Color(0xFFEF4444),
                fontSize = 0.78.rem,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, Color(0x40EF4444), RoundedCornerShape(7.dp))
                    .clickable { viewModel.confirmWithdraw(leave) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Badge(label: String, bg: Color, fg: Color) {
    Text(
        label,
        color = fg,
        fontSize = 0.68.rem,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** `MyBalances` port: year selector + detail cards (available/used/total). */
@Composable
private fun MyBalancesPanel(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My Leave Balances", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
                Text("Your available leave days for the selected year", color = colors.textMuted, fontSize = 0.8.rem)
            }
            MonthPicker(ui.balancesYear.toString(), { viewModel.changeBalancesYear(-1) }, { viewModel.changeBalancesYear(1) })
        }

        when {
            ui.myBalancesLoading -> Text("Loading…", color = colors.textMuted, fontSize = 0.85.rem)
            ui.myBalances.isEmpty() -> AttendanceCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.BarChart, null, Modifier.size(32.dp), tint = colors.textMuted)
                    Text("No balances found", color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "Contact HR to set up leave policies for your account",
                        color = colors.textMuted,
                        fontSize = 0.8.rem,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            else -> ui.myBalances.forEach { balance ->
                val meta = getLeaveType(balance.leaveType)
                val barColor = when {
                    balance.usedPercent >= 80 -> Color(0xFFEF4444)
                    balance.usedPercent >= 50 -> Color(0xFFF59E0B)
                    else -> meta.color
                }
                AttendanceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(meta.bg, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                            Icon(meta.icon, null, Modifier.size(18.dp), tint = meta.color)
                        }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(meta.label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem)
                            Text("Year ${formatDays(balance.year)}", color = colors.textMuted, fontSize = 0.75.rem)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatItem(formatDays(balance.available), "available", meta.color)
                        StatItem(formatDays(balance.used), "used", colors.text)
                        StatItem(formatDays(balance.total), "total", colors.text)
                    }
                    Box(
                        Modifier.padding(top = 10.dp).fillMaxWidth().height(5.dp)
                            .background(colors.surfaceHover, RoundedCornerShape(3.dp)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(balance.usedPercent / 100f).height(5.dp)
                                .background(barColor, RoundedCornerShape(3.dp)),
                        )
                    }
                    if (balance.carriedForward > 0) {
                        Text(
                            "${formatDays(balance.carriedForward)} carried forward",
                            color = colors.textMuted,
                            fontSize = 0.75.rem,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** `PoliciesTab` (HR) — read-only policy catalogue + public holiday list. */
@Composable
private fun PoliciesPanel(ui: AttendanceUiState) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AttendanceCard {
            Text("Leave Policies", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
            Text(
                "Configured by HR. Editing is available on the web app.",
                color = colors.textMuted,
                fontSize = 0.78.rem,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ui.leavePolicies.isEmpty()) {
                    Text("No leave policies configured yet.", color = colors.textMuted, fontSize = 0.85.rem)
                }
                ui.leavePolicies.forEach { policy ->
                    val meta = buildLeaveTypeMeta(listOf(policy))[policy.leaveType] ?: getLeaveType(policy.leaveType)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(10.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(34.dp).background(meta.bg, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                            Icon(meta.icon, null, Modifier.size(16.dp), tint = meta.color)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(policy.name?.takeIf(String::isNotBlank) ?: meta.label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem)
                            Text(policy.leaveType, color = colors.textMuted, fontSize = 0.72.rem)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${formatDays(policy.annualQuota)} days/yr", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.8.rem)
                            val flags = buildList {
                                if (policy.halfDayAllowed) add("Half")
                                if (policy.quarterDayAllowed) add("Quarter")
                            }
                            if (flags.isNotEmpty()) {
                                Text(flags.joinToString(" · "), color = colors.textMuted, fontSize = 0.7.rem)
                            }
                        }
                    }
                }
            }
        }

        AttendanceCard {
            Text("Public Holidays ${ui.balancesYear}", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.policiesHolidays.isEmpty()) {
                    Text("No public holidays configured for this year.", color = colors.textMuted, fontSize = 0.85.rem)
                }
                ui.policiesHolidays.sortedBy { it.date }.forEach { holiday ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(10.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(holiday.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem)
                            Text(fmtDate(holiday.date), color = colors.textMuted, fontSize = 0.75.rem)
                        }
                        if (holiday.isOptional) {
                            Badge("Optional", Color(0x1F0EA5E9), Color(0xFF0EA5E9))
                        }
                    }
                }
            }
        }
    }
}

/** `AllBalances` (HR) — searchable per-member balance list (read-only). */
@Composable
private fun AllBalancesPanel(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val query = ui.allBalancesQuery.trim().lowercase()
    val rows = ui.allBalances.filter { query.isEmpty() || it.fullName?.lowercase()?.contains(query) == true }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WebTextInput(
            ui.allBalancesQuery,
            viewModel::setAllBalancesQuery,
            Modifier.fillMaxWidth(),
            placeholder = "Search by employee name…",
        )
        AttendanceCard {
            Text("All Balances", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    ui.allBalancesLoading -> Text("Loading…", color = colors.textMuted, fontSize = 0.85.rem)
                    rows.isEmpty() -> Text("No balances found.", color = colors.textMuted, fontSize = 0.85.rem)
                    else -> rows.forEach { row ->
                        val meta = getLeaveType(row.leaveType)
                        Row(
                            Modifier.fillMaxWidth()
                                .background(colors.surface, RoundedCornerShape(10.dp))
                                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.fullName ?: row.username ?: "—", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem)
                                Text(meta.label, color = colors.textMuted, fontSize = 0.75.rem)
                            }
                            Text(
                                "${formatDays(row.used)} / ${formatDays(row.quota + row.carriedForward)} used",
                                color = colors.textSecondary,
                                fontSize = 0.8.rem,
                            )
                        }
                    }
                }
            }
        }
    }
}
