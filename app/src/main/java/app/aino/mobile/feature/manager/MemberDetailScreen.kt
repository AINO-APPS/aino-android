package app.aino.mobile.feature.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.common.roleLabel
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `EmployeeDashboard.tsx` — the member-detail sub-screen, navigated to via
 * `manager/member/{userId}` rather than the web's inline `selectedMember`
 * swap. Web has 4 sub-tabs (Overview/Leaves/Requests/Hours); a 5th "Tasks"
 * tab is added here to surface `GET /manager/member/:userId/tasks`, which
 * the web UI itself never renders even though the endpoint exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(viewModel: ManagerViewModel, userId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.memberDetail.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    LaunchedEffect(userId) { viewModel.openMemberDetail(userId) }
    val detail = state ?: return

    PullToRefreshBox(
        isRefreshing = detail.refreshing,
        onRefresh = viewModel::refreshMemberDetail,
        modifier = modifier.fillMaxSize().background(colors.bg),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                }
                Spacer(Modifier.width(4.dp))
                Text("Back", color = colors.text, fontSize = 0.9.rem)
            }
            Spacer(Modifier.height(8.dp))

            val user = detail.displayUser
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(user?.fullName, user?.avatar, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(user?.fullName.orEmpty(), color = colors.text, fontSize = 1.15.rem, fontWeight = FontWeight.Bold)
                    val meta = listOfNotNull(
                        user?.role?.let(::roleLabel),
                        user?.email,
                        user?.departmentName,
                        user?.teamName,
                    ).joinToString(" · ")
                    Text(meta, color = colors.textSecondary, fontSize = 0.8.rem)
                }
            }
            Spacer(Modifier.height(20.dp))

            MemberTabStrip(detail.tab, viewModel::selectMemberTab)
            Spacer(Modifier.height(16.dp))

            when (detail.tab) {
                MemberTab.Overview -> MemberOverviewTab(detail.overview)
                MemberTab.Leaves -> MemberLeavesTab(detail.leaves)
                MemberTab.Requests -> MemberRequestsSubTab(detail.requests)
                MemberTab.Hours -> MemberHoursSubTab(detail.hours)
                MemberTab.Tasks -> MemberTasksSubTab(detail.tasks)
            }
        }
    }
}

@Composable
private fun MemberTabStrip(active: MemberTab, onSelect: (MemberTab) -> Unit) {
    val colors = LocalWebColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MemberTab.entries.forEach { tab ->
            val selected = tab == active
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .background(if (selected) colors.surfaceHover else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .border(1.dp, if (selected) colors.accent else colors.border, shape)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    tab.label,
                    color = if (selected) colors.accent else colors.textSecondary,
                    fontSize = 0.78.rem,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, color = LocalWebColors.current.text, fontSize = 0.92.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
}

// ── Overview ────────────────────────────────────────────────────────────────

@Composable
private fun MemberOverviewTab(section: Section<MemberOverviewResponse>) {
    val colors = LocalWebColors.current
    section.error?.let { ManagerErrorText(it) }
    val data = section.data
    if (section.initialLoading || data == null) {
        ManagerLoading()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewStat(Icons.Outlined.Schedule, "${data.todayHours}h", "Today's Hours", Modifier.weight(1f))
                OverviewStat(Icons.Outlined.Coffee, formatMin(data.todayBreakMin), "Today's Break", Modifier.weight(1f))
                OverviewStat(Icons.AutoMirrored.Outlined.Assignment, "${data.pendingRequests}", "Pending Requests", Modifier.weight(1f), amber = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewStat(Icons.Outlined.CalendarMonth, "${data.monthLeaves}", "Leaves This Month", Modifier.weight(1f))
                OverviewStat(Icons.Outlined.Checklist, "${data.todayTasks.size}", "Today's Planner", Modifier.weight(1f))
            }
        }

        if (data.weeklyTrend.isNotEmpty()) {
            Column {
                SectionHeading("Weekly Trend (Last 7 Days)")
                val maxMin = (data.weeklyTrend.maxOf { it.floorMinutes }).coerceAtLeast(480).coerceAtLeast(1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.weeklyTrend.forEach { day ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val barColor = when {
                                day.floorMinutes >= 480 -> colors.success
                                day.floorMinutes > 0 -> colors.warning
                                else -> colors.border
                            }
                            val h = (min(day.floorMinutes.toFloat() / maxMin, 1f) * 48f).coerceAtLeast(2f)
                            Box(Modifier.height(48.dp), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.width(14.dp).height(h.dp).background(barColor, RoundedCornerShape(3.dp)))
                            }
                            Text(day.dayLabel, color = colors.textMuted, fontSize = 0.62.rem)
                            Text(formatMin(day.floorMinutes), color = colors.textSecondary, fontSize = 0.6.rem, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        val stats = data.stats30d
        if (data.stats30d != Stats30d()) {
            Column {
                SectionHeading("30-Day Performance")
                val items = listOf(
                    "${stats.daysWorked}" to "Days Worked",
                    formatMin(stats.totalFloorMinutes) to "Total Work Time",
                    formatMin(stats.avgFloorMinutes) to "Avg Work/Day",
                    formatMin(stats.avgBreakMinutes) to "Avg Break/Day",
                    "${stats.targetMetPercent}%" to "Target Met",
                    "${stats.punctualityPercent}%" to "Punctuality",
                )
                PerfGrid(items)
            }
        }

        val taskStats = data.monthTaskStats
        Column {
            SectionHeading("Planner This Month")
            PerfGrid(
                listOf(
                    "${taskStats.total}" to "Total",
                    "${taskStats.done}" to "Done",
                    "${taskStats.inProgress}" to "In Progress",
                    "${taskStats.completionRate}%" to "Completion",
                ),
            )
        }

        if (data.leaveBalances.isNotEmpty()) {
            Column {
                SectionHeading("Leave Balances")
                data.leaveBalances.forEach { lb ->
                    val pct = if (lb.totalDays > 0) ((lb.used / lb.totalDays) * 100).roundToInt() else 0
                    val barColor = when {
                        pct >= 90 -> colors.danger
                        pct >= 60 -> colors.warning
                        else -> colors.success
                    }
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LeaveIconFor(lb.leaveType)
                                Spacer(Modifier.width(4.dp))
                                Text(lb.policyName ?: lb.leaveType.orEmpty(), color = colors.text, fontSize = 0.8.rem)
                            }
                            Text("${lb.used.fmt()}/${lb.totalDays.fmt()} used", color = colors.textMuted, fontSize = 0.72.rem)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).background(colors.border, RoundedCornerShape(3.dp))) {
                            Box(Modifier.fillMaxWidth(fraction = (pct.coerceIn(0, 100) / 100f)).height(6.dp).background(barColor, RoundedCornerShape(3.dp)))
                        }
                    }
                }
            }
        }

        if (data.todayTasks.isNotEmpty()) {
            Column {
                SectionHeading("Today's Planner")
                data.todayTasks.forEach { task ->
                    ManagerRowCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(task.title.orEmpty(), color = colors.text, fontSize = 0.85.rem, modifier = Modifier.weight(1f))
                            PriorityBadge(task.priority)
                            Spacer(Modifier.width(6.dp))
                            TaskStatusBadge(task.status)
                        }
                    }
                }
            }
        }

        if (data.recentLeaves.isNotEmpty()) {
            Column {
                SectionHeading("Recent Leaves")
                data.recentLeaves.forEach { leave -> LeaveRowCard(leave) }
            }
        }

        if (data.recentRequests.isNotEmpty()) {
            Column {
                SectionHeading("Recent Requests")
                data.recentRequests.forEach { row -> MemberRequestRowCard(row) }
            }
        }
    }
}

private fun Double.fmt(): String = if (this == this.toLong().toDouble()) this.toLong().toString() else "%.1f".format(this)

@Composable
private fun OverviewStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier = Modifier, amber: Boolean = false) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .background(colors.cardBg, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.width(18.dp).height(18.dp), tint = colors.primary)
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (amber) colors.warning else colors.text, fontSize = 1.0.rem, fontWeight = FontWeight.Bold)
        Text(label, color = colors.textMuted, fontSize = 0.62.rem, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PerfGrid(items: List<Pair<String, String>>) {
    val colors = LocalWebColors.current
    val rows = items.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(colors.cardBg, RoundedCornerShape(10.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(value, color = colors.text, fontSize = 0.92.rem, fontWeight = FontWeight.Bold)
                        Text(label, color = colors.textMuted, fontSize = 0.64.rem, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

// ── Leaves ──────────────────────────────────────────────────────────────────

@Composable
private fun MemberLeavesTab(section: Section<List<MemberLeaveRow>>) {
    val rows = section.data.orEmpty()
    Column {
        SectionHeading("Leave History")
        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading -> ManagerLoading()
            rows.isEmpty() -> ManagerEmpty("No leaves found")
            else -> rows.forEach { leave -> LeaveRowCard(leave) }
        }
    }
}

@Composable
private fun LeaveRowCard(leave: MemberLeaveRow) {
    val colors = LocalWebColors.current
    ManagerRowCard {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatShortDate(leave.date), color = colors.text, fontSize = 0.8.rem)
            ApprovalBadge(leave.status)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeaveIconFor(leave.leaveType, 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(leave.leaveType.orEmpty(), color = colors.text, fontSize = 0.8.rem)
            Spacer(Modifier.width(8.dp))
            Text(leave.duration ?: "full", color = colors.textSecondary, fontSize = 0.74.rem)
        }
        Text(leave.reason ?: "—", color = colors.textMuted, fontSize = 0.74.rem)
    }
}

// ── Requests ────────────────────────────────────────────────────────────────

@Composable
private fun MemberRequestsSubTab(section: Section<List<ApprovalRow>>) {
    val rows = section.data.orEmpty()
    Column {
        SectionHeading("Approval Requests")
        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading -> ManagerLoading()
            rows.isEmpty() -> ManagerEmpty("No requests found")
            else -> rows.forEach { row -> MemberRequestRowCard(row) }
        }
    }
}

@Composable
private fun MemberRequestRowCard(row: ApprovalRow) {
    val colors = LocalWebColors.current
    ManagerRowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.type?.replace("_", " ").orEmpty(), color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            ApprovalBadge(row.status)
        }
        RequestDetails(row)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(formatApprovalDate(row.createdAt), color = colors.textMuted, fontSize = 0.72.rem)
            Text(row.approverName ?: "—", color = colors.textSecondary, fontSize = 0.75.rem)
        }
    }
}

// ── Hours ───────────────────────────────────────────────────────────────────

@Composable
private fun MemberHoursSubTab(section: Section<List<MemberHourRow>>) {
    val colors = LocalWebColors.current
    val rows = section.data.orEmpty()
    val totalHours = rows.sumOf { it.floorMinutes } / 60.0
    Column {
        SectionHeading("Hours (Last 30 Days) — Total: ${"%.1f".format(totalHours)}h")
        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading -> ManagerLoading()
            rows.isEmpty() -> ManagerEmpty("No data found")
            else -> rows.forEach { row ->
                ManagerRowCard {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatShortDate(row.date), color = colors.text, fontSize = 0.8.rem)
                        WorkModeLabel(row.workMode)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text("Work", color = colors.textMuted, fontSize = 0.62.rem)
                            Text(formatMin(row.floorMinutes), color = colors.text, fontSize = 0.8.rem)
                        }
                        Column {
                            Text("Break", color = colors.textMuted, fontSize = 0.62.rem)
                            Text(formatMin(row.breakMinutes), color = colors.text, fontSize = 0.8.rem)
                        }
                    }
                }
            }
        }
    }
}

// ── Tasks (Android-only 5th tab; see file doc-comment) ──────────────────────

@Composable
private fun MemberTasksSubTab(section: Section<List<MemberTaskRow>>) {
    val colors = LocalWebColors.current
    val rows = section.data.orEmpty()
    Column {
        SectionHeading("Planner")
        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading -> ManagerLoading()
            rows.isEmpty() -> ManagerEmpty("No tasks found")
            else -> rows.forEach { task ->
                ManagerRowCard {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title.orEmpty(), color = colors.text, fontSize = 0.85.rem, modifier = Modifier.weight(1f))
                        task.date?.let { Text(formatShortDate(it), color = colors.textMuted, fontSize = 0.7.rem) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PriorityBadge(task.priority)
                        TaskStatusBadge(task.status)
                    }
                }
            }
        }
    }
}

private fun formatShortDate(dateStr: String): String = runCatching {
    val date = java.time.LocalDate.parse(dateStr)
    date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
}.getOrDefault(dateStr)
