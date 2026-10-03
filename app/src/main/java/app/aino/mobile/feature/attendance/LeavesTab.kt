package app.aino.mobile.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.common.LeaveTypeMeta
import app.aino.mobile.core.common.getLeaveType
import app.aino.mobile.core.designsystem.component.FirstLoadSpinner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LEAVE_STATUS_FILTERS = listOf(
    "all" to "All",
    "pending" to "Pending",
    "approved" to "Approved",
    "rejected" to "Rejected",
    "withdraw_pending" to "Withdrawal pending",
)

private fun LeavesSubTab.shortLabel(): String = when (this) {
    LeavesSubTab.MyLeaves -> "My leaves"
    LeavesSubTab.MyBalances -> "Balances"
    LeavesSubTab.Policies -> "Policies"
    LeavesSubTab.AllBalances -> "All"
}

/**
 * Leaves page (mobile-first): balance rings, month + chip filters, history
 * cards with withdraw confirmation, and the apply form in a bottom sheet
 * (opened by the page FAB). HR read-only panels sit behind segmented sub-tabs.
 */
@Composable
fun LeavesPage(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    var statusFilter by rememberSaveable { mutableStateOf("all") }
    var typeFilter by rememberSaveable { mutableStateOf("all") }
    val loading = when (ui.leavesSubTab) {
        LeavesSubTab.MyBalances -> ui.myBalancesLoading
        LeavesSubTab.AllBalances -> ui.allBalancesLoading
        else -> ui.leavesLoading
    }
    AttendancePageList(loading = loading, onRefresh = { viewModel.selectLeavesSubTab(ui.leavesSubTab) }) {
        item(key = "subtabs") { LeavesSubTabs(ui, viewModel) }
        if (ui.sheet == null) ui.leaveError?.let { item(key = "error") { ErrorNotice(it, onDismiss = { viewModel.updateLeaveForm() }) } }
        when (ui.leavesSubTab) {
            LeavesSubTab.MyLeaves -> myLeavesItems(ui, viewModel, statusFilter, typeFilter, { statusFilter = it }, { typeFilter = it })
            LeavesSubTab.MyBalances -> myBalancesItems(ui, viewModel)
            LeavesSubTab.Policies -> policiesItems(ui)
            LeavesSubTab.AllBalances -> allBalancesItems(ui, viewModel)
        }
    }
    WithdrawDialog(ui, viewModel)
    if (ui.sheet == AttendanceSheet.ApplyLeave) ApplyLeaveSheet(ui, viewModel)
}

@Composable
private fun LeavesSubTabs(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val visible = LeavesSubTab.entries.filter { !it.hrOnly || ui.isHr }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        visible.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = ui.leavesSubTab == tab,
                onClick = { viewModel.selectLeavesSubTab(tab) },
                shape = SegmentedButtonDefaults.itemShape(index, visible.size),
                colors = segmentedColors(),
                icon = {},
            ) { Text(tab.shortLabel(), fontSize = 0.8.rem, maxLines = 1) }
        }
    }
}

private fun LazyListScope.myLeavesItems(
    ui: AttendanceUiState,
    viewModel: AttendanceViewModel,
    statusFilter: String,
    typeFilter: String,
    onStatus: (String) -> Unit,
    onType: (String) -> Unit,
) {
    if (ui.leaveBalances.isNotEmpty()) {
        item(key = "balances") {
            val typeMeta = buildLeaveTypeMeta(ui.leavePolicies)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(ui.leaveBalances) { balance ->
                    BalanceRingCard(balance, typeMeta[balance.leaveType] ?: getLeaveType(balance.leaveType))
                }
            }
        }
    }
    val personal = ui.monthLeaves.filterNot(::isPublicHolidayLeave)
    item(key = "month") {
        val colors = LocalWebColors.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                label = ui.leavesMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                onPrev = { viewModel.changeLeavesMonth(-1) },
                onNext = { viewModel.changeLeavesMonth(1) },
                prevDescription = "Previous month",
                nextDescription = "Next month",
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${personal.size} request${if (personal.size == 1) "" else "s"} · ${formatDays(totalLeaveDays(personal))} days",
                color = colors.textMuted,
                fontSize = 0.78.rem,
            )
        }
    }
    item(key = "status-filters") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LEAVE_STATUS_FILTERS, key = { it.first }) { (value, label) ->
                val count = if (value == "all") personal.size else personal.count { it.status == value }
                AttendanceFilterChip(
                    "$label${if (count > 0) " $count" else ""}",
                    statusFilter == value,
                    { onStatus(value) },
                    tint = if (value == "all") LocalWebColors.current.primary else leaveStatusMeta(value).color,
                )
            }
        }
    }
    val typeOptions = buildLeaveTypeOptions(ui.leavePolicies)
    if (typeOptions.isNotEmpty()) {
        item(key = "type-filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") { AttendanceFilterChip("All types", typeFilter == "all", { onType("all") }) }
                items(typeOptions) { type ->
                    AttendanceFilterChip(type.label, typeFilter == type.value, { onType(type.value) }, icon = type.icon, tint = type.color)
                }
            }
        }
    }
    val filtered = filterLeaves(ui.monthLeaves, statusFilter, typeFilter)
    when {
        ui.leavesLoading && filtered.isEmpty() -> item(key = "loading") { FirstLoadSpinner(Modifier.height(120.dp)) }
        filtered.isEmpty() -> item(key = "empty") {
            EmptyState(
                HeroIcons.Sun,
                if (personal.isEmpty()) "No leaves this month" else "Nothing matches these filters",
                if (personal.isEmpty()) "Tap Apply leave to request time off." else "Try another status or type.",
            )
        }
        else -> {
            val typeMeta = buildLeaveTypeMeta(ui.leavePolicies)
            items(filtered, key = { "leave-${it.id}-${it.date}" }) { leave -> LeaveHistoryCard(leave, typeMeta, viewModel) }
        }
    }
}

/** Balance card: ring = used / total, available days prominent in the centre. */
@Composable
private fun BalanceRingCard(balance: LeaveBalance, type: LeaveTypeMeta) {
    val colors = LocalWebColors.current
    val ringColor = when {
        balance.usedPercent >= 80 -> SwatchAbsent
        balance.usedPercent >= 50 -> SwatchLeave
        else -> type.color
    }
    AttendanceCard(
        Modifier.width(156.dp).clearAndSetSemantics {
            contentDescription = "${type.label}: ${formatDays(balance.available)} of ${formatDays(balance.total)} days available, ${formatDays(balance.used)} used"
        },
        contentPadding = 14.dp,
    ) {
        Box(Modifier.size(72.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { balance.usedPercent / 100f },
                modifier = Modifier.size(72.dp),
                color = ringColor,
                trackColor = colors.surfaceHover,
                strokeWidth = 6.dp,
                gapSize = 0.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatDays(balance.available), color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.15.rem)
                Text("left", color = colors.textMuted, fontSize = 0.65.rem)
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(type.icon, null, Modifier.size(14.dp), tint = type.color)
            Text(" ${type.label}", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.8.rem, maxLines = 1)
        }
        Text(
            "${formatDays(balance.used)} used of ${formatDays(balance.total)}",
            color = colors.textMuted,
            fontSize = 0.7.rem,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun LeaveHistoryCard(leave: LeaveOverlay, typeMeta: Map<String, LeaveTypeMeta>, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val type = typeMeta[leave.leaveType] ?: getLeaveType(leave.leaveType)
    val status = leaveStatusMeta(leave.status)
    AttendanceCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(38.dp).background(type.bg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(type.icon, null, Modifier.size(18.dp), tint = type.color)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(type.label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem)
                Text(fmtDate(leave.date), color = colors.textSecondary, fontSize = 0.8.rem, modifier = Modifier.padding(top = 2.dp))
            }
            StatusPill(status.label, status.color, dot = true)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(durationLabel(leave.duration), colors.textSecondary)
            if (leave.status == "approved" && !leave.approvedByName.isNullOrBlank()) {
                Text("by ${leave.approvedByName}", color = colors.textMuted, fontSize = 0.75.rem)
            }
            Spacer(Modifier.weight(1f))
            if (canWithdrawLeave(leave)) {
                TextButton(onClick = { viewModel.confirmWithdraw(leave) }) {
                    Text(if (leave.status == "approved") "Withdraw" else "Cancel", color = colors.danger, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        leave.reason?.takeIf(String::isNotBlank)?.let {
            Text("“$it”", color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(top = 4.dp))
        }
        leave.rejectReason?.takeIf(String::isNotBlank)?.let {
            Text("Rejected: $it", color = colors.danger, fontSize = 0.8.rem, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Withdraw / cancel confirmation. */
@Composable
private fun WithdrawDialog(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val candidate = ui.withdrawCandidate ?: return
    val colors = LocalWebColors.current
    val approved = candidate.status == "approved"
    AlertDialog(
        onDismissRequest = { viewModel.confirmWithdraw(null) },
        containerColor = colors.bgElevated,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
        icon = { Icon(HeroIcons.ExclamationTriangle, null, tint = colors.warning) },
        title = { Text(if (approved) "Withdraw leave?" else "Cancel leave request?") },
        text = {
            Text(
                "Your ${candidate.leaveType.replace('_', ' ')} leave on ${fmtDate(candidate.date)} will be " +
                    if (approved) "withdrawn. Your manager needs to approve this." else "cancelled.",
            )
        },
        confirmButton = {
            TextButton(onClick = viewModel::withdrawLeave) {
                Text(if (approved) "Withdraw" else "Cancel request", color = colors.danger, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.confirmWithdraw(null) }) { Text("Keep") } },
    )
}

/** Apply-leave form (single day / range, type, duration, reason) with the VM's validation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApplyLeaveSheet(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    val rangeDays = viewModel.leaveRequestDates()
    val typeOptions = buildLeaveTypeOptions(ui.leavePolicies).filter { it.value != "holiday" }

    FormSheet("Apply leave", onDismiss = viewModel::closeSheet) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Single day", "Date range").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = ui.leaveIsRange == (index == 1),
                    onClick = { viewModel.updateLeaveForm(isRange = index == 1) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    colors = segmentedColors(),
                ) { Text(label) }
            }
        }
        if (!ui.leaveIsRange) {
            DateField("Date", ui.leaveDate, { viewModel.updateLeaveForm(date = it) })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("From", ui.leaveFrom, { from ->
                    // Keep the range coherent: moving From past To drags To with it.
                    viewModel.updateLeaveForm(from = from, to = if (ui.leaveTo.isNotBlank() && from > ui.leaveTo) from else null)
                }, Modifier.weight(1f))
                DateField("To", ui.leaveTo, { viewModel.updateLeaveForm(to = it) }, Modifier.weight(1f))
            }
            SwitchRow("Skip weekends", ui.leaveSkipWeekends) { viewModel.updateLeaveForm(skipWeekends = it) }
            if (rangeDays.isNotEmpty()) {
                StatusPill("${rangeDays.size} working day${if (rangeDays.size != 1) "s" else ""} selected", colors.primary, icon = HeroIcons.CalendarDays)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Leave type")
            if (typeOptions.isEmpty()) {
                Text("No leave types configured yet. Ask HR to add leave policies.", color = colors.textMuted, fontSize = 0.82.rem)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    typeOptions.forEach { type ->
                        AttendanceFilterChip(type.label, ui.leaveType == type.value, { viewModel.updateLeaveForm(type = type.value) }, icon = type.icon, tint = type.color)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Duration")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Full day", "Half day", "Quarter").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = ui.leaveDuration == LEAVE_DURATIONS[index],
                        onClick = { viewModel.updateLeaveForm(duration = LEAVE_DURATIONS[index]) },
                        shape = SegmentedButtonDefaults.itemShape(index, LEAVE_DURATIONS.size),
                        colors = segmentedColors(),
                        icon = {},
                    ) { Text(label, maxLines = 1) }
                }
            }
        }

        TextInput("Reason (optional)", ui.leaveReason, { viewModel.updateLeaveForm(reason = it) }, placeholder = "A short note for your manager", singleLine = false, minLines = 3)

        ui.leaveError?.let { ErrorNotice(it) }

        Button(
            onClick = viewModel::applyLeave,
            enabled = !ui.leaveSubmitting && typeOptions.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onAccent),
        ) {
            if (ui.leaveSubmitting) {
                CircularProgressIndicator(Modifier.size(16.dp), color = colors.onAccent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when {
                    ui.leaveSubmitting -> "Submitting…"
                    ui.leaveIsRange && rangeDays.size > 1 -> "Submit ${rangeDays.size} requests"
                    else -> "Submit request"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Label + Material3 switch; the whole row toggles and reads as a switch to TalkBack. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.text, fontSize = 0.9.rem, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = colors.onAccent,
                uncheckedTrackColor = colors.surfaceHover,
                uncheckedBorderColor = colors.border,
                uncheckedThumbColor = colors.textSecondary,
            ),
        )
    }
}

private fun LazyListScope.myBalancesItems(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    item(key = "year") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Leave balances", Modifier.weight(1f))
            Stepper(ui.balancesYear.toString(), { viewModel.changeBalancesYear(-1) }, { viewModel.changeBalancesYear(1) }, "Previous year", "Next year")
        }
    }
    when {
        ui.myBalancesLoading && ui.myBalances.isEmpty() -> item(key = "loading") { FirstLoadSpinner(Modifier.height(120.dp)) }
        ui.myBalances.isEmpty() -> item(key = "empty") {
            EmptyState(HeroIcons.ChartBar, "No balances found", "Contact HR to set up leave policies for your account.")
        }
        else -> items(ui.myBalances) { balance ->
            val colors = LocalWebColors.current
            val meta = buildLeaveTypeMeta(ui.leavePolicies)[balance.leaveType] ?: getLeaveType(balance.leaveType)
            AttendanceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { balance.usedPercent / 100f },
                            modifier = Modifier.size(56.dp),
                            color = meta.color,
                            trackColor = colors.surfaceHover,
                            strokeWidth = 5.dp,
                            gapSize = 0.dp,
                        )
                        Icon(meta.icon, null, Modifier.size(18.dp), tint = meta.color)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(meta.label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.92.rem)
                        Text(
                            "${formatDays(balance.used)} used · ${formatDays(balance.total)} total" +
                                if (balance.carriedForward > 0) " · ${formatDays(balance.carriedForward)} carried" else "",
                            color = colors.textMuted,
                            fontSize = 0.76.rem,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatDays(balance.available), color = meta.color, fontWeight = FontWeight.ExtraBold, fontSize = 1.3.rem)
                        Text("available", color = colors.textMuted, fontSize = 0.68.rem)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.policiesItems(ui: AttendanceUiState) {
    item(key = "policies-header") {
        Column {
            SectionHeader("Leave policies")
            Text("Read-only here — edit policies on the web app.", color = LocalWebColors.current.textMuted, fontSize = 0.78.rem)
        }
    }
    if (ui.leavePolicies.isEmpty()) {
        item(key = "policies-empty") { EmptyState(HeroIcons.ClipboardDocumentList, "No leave policies yet") }
    }
    items(ui.leavePolicies) { policy ->
        val colors = LocalWebColors.current
        val meta = buildLeaveTypeMeta(listOf(policy))[policy.leaveType] ?: getLeaveType(policy.leaveType)
        AttendanceCard(contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(meta.bg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(meta.icon, null, Modifier.size(16.dp), tint = meta.color)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(policy.name?.takeIf(String::isNotBlank) ?: meta.label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.88.rem)
                    val flags = buildList {
                        if (policy.halfDayAllowed) add("Half day")
                        if (policy.quarterDayAllowed) add("Quarter day")
                    }
                    Text(flags.joinToString(" · ").ifBlank { "Full days only" }, color = colors.textMuted, fontSize = 0.74.rem)
                }
                Text("${formatDays(policy.annualQuota)} d/yr", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 0.85.rem)
            }
        }
    }
    item(key = "holidays-header") { SectionHeader("Public holidays ${ui.balancesYear}", Modifier.padding(top = 8.dp)) }
    if (ui.policiesHolidays.isEmpty()) {
        item(key = "holidays-empty") { EmptyState(HeroIcons.Sun, "No public holidays this year") }
    }
    items(ui.policiesHolidays.sortedBy { it.date }) { holiday ->
        val colors = LocalWebColors.current
        AttendanceCard(contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(holiday.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.88.rem)
                    Text(fmtDate(holiday.date), color = colors.textMuted, fontSize = 0.76.rem)
                }
                if (holiday.isOptional) StatusPill("Optional", Color(0xFF0EA5E9))
            }
        }
    }
}

private fun LazyListScope.allBalancesItems(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    item(key = "search") {
        TextInput("Search employees", ui.allBalancesQuery, viewModel::setAllBalancesQuery, placeholder = "Name", leadingIcon = HeroIcons.MagnifyingGlass)
    }
    val query = ui.allBalancesQuery.trim().lowercase()
    val rows = ui.allBalances.filter { query.isEmpty() || it.fullName?.lowercase()?.contains(query) == true }
    when {
        ui.allBalancesLoading && rows.isEmpty() -> item(key = "loading") { FirstLoadSpinner(Modifier.height(120.dp)) }
        rows.isEmpty() -> item(key = "empty") { EmptyState(HeroIcons.UserGroup, "No balances found") }
        else -> itemsIndexed(rows, key = { index, row -> "all-$index-${row.userId}-${row.leaveType}" }) { _, row ->
            val colors = LocalWebColors.current
            val meta = getLeaveType(row.leaveType)
            val total = row.quota + row.carriedForward
            AttendanceCard(contentPadding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.fullName ?: row.username ?: "—", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.88.rem)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(meta.icon, null, Modifier.size(12.dp), tint = meta.color)
                            Text(" ${meta.label}", color = colors.textMuted, fontSize = 0.76.rem)
                        }
                    }
                    Text(
                        "${formatDays(row.used)} / ${formatDays(total)}",
                        color = colors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 0.82.rem,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}
