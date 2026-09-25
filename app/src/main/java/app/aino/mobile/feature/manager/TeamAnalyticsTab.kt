package app.aino.mobile.feature.manager

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.common.roleLabel
import java.time.LocalDate
import kotlin.math.roundToInt

private val RANGES = listOf("7" to "This Week", "30" to "This Month", "90" to "This Quarter", "custom" to "Custom Range")

/** `TeamAnalytics.tsx`. Sort/expand state lives in [ManagerViewModel]; filtering happens here. */
@Composable
internal fun TeamAnalyticsTab(ui: ManagerUiState, viewModel: ManagerViewModel, onSelectMember: (Long) -> Unit) {
    val colors = LocalWebColors.current
    val section = ui.teamAnalytics
    val data = section.data

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AnalyticsToolbar(ui, viewModel)
        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading || data == null -> ManagerLoading()
            else -> {
                SummaryGrid(data)
                val members = filterAndSort(data.members, ui)
                val rangeLabel = rangeLabel(ui)
                Text(
                    "Member Performance — $rangeLabel (${members.size})",
                    color = colors.text,
                    fontSize = 0.92.rem,
                    fontWeight = FontWeight.SemiBold,
                )
                if (members.isEmpty()) {
                    ManagerEmpty("No members match your filters")
                } else {
                    members.forEach { member ->
                        AnalyticsMemberCard(
                            member,
                            data.targetMinutes,
                            data.expectedWeekdays,
                            expanded = ui.analyticsExpandedId == member.id,
                            onToggleExpand = { viewModel.toggleAnalyticsExpanded(member.id) },
                            onSelectMember = onSelectMember,
                        )
                    }
                }
            }
        }
    }
}

private fun rangeLabel(ui: ManagerUiState): String = when (ui.analyticsRange) {
    "7" -> "This Week"
    "30" -> "This Month"
    "90" -> "This Quarter"
    else -> if (ui.analyticsCustomFrom.isNotEmpty() && ui.analyticsCustomTo.isNotEmpty()) {
        "${ui.analyticsCustomFrom} — ${ui.analyticsCustomTo}"
    } else {
        "Custom Range"
    }
}

private fun filterAndSort(members: List<AnalyticsMember>, ui: ManagerUiState): List<AnalyticsMember> {
    var list = members
    if (ui.analyticsSearch.isNotBlank()) {
        val q = ui.analyticsSearch.lowercase()
        list = list.filter {
            it.fullName?.lowercase()?.contains(q) == true ||
                it.email?.lowercase()?.contains(q) == true ||
                it.role?.lowercase()?.contains(q) == true
        }
    }
    if (ui.analyticsFilterDept.isNotEmpty()) list = list.filter { it.departmentName == ui.analyticsFilterDept }
    val comparator: Comparator<AnalyticsMember> = when (ui.analyticsSortBy) {
        "full_name" -> compareBy { it.fullName.orEmpty() }
        "avgFloorMinutes" -> compareBy { it.avgFloorMinutes }
        "tasksDone" -> compareBy { it.tasksDone }
        "targetMetPercent" -> compareBy { it.targetMetPercent }
        "punctualityPercent" -> compareBy { it.punctualityPercent }
        else -> compareBy { it.hours }
    }
    val sorted = list.sortedWith(comparator)
    return if (ui.analyticsSortAsc) sorted else sorted.reversed()
}

@Composable
private fun AnalyticsToolbar(ui: ManagerUiState, viewModel: ManagerViewModel) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AnalyticsRangeDropdown(ui.analyticsRange, viewModel::setAnalyticsRange)
        }
        if (ui.analyticsRange == "custom") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AnalyticsDateField(ui.analyticsCustomFrom) { viewModel.setAnalyticsCustomRange(it, ui.analyticsCustomTo) }
                Text("to", color = colors.textSecondary, fontSize = 0.8.rem)
                AnalyticsDateField(ui.analyticsCustomTo) { viewModel.setAnalyticsCustomRange(ui.analyticsCustomFrom, it) }
            }
        }
        OutlinedTextField(
            value = ui.analyticsSearch,
            onValueChange = viewModel::setAnalyticsSearch,
            placeholder = { Text("Search members...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val depts = ui.teamAnalytics.data?.members.orEmpty().mapNotNull { it.departmentName }.distinct().sorted()
        if (depts.isNotEmpty()) {
            AnalyticsDeptDropdown(ui.analyticsFilterDept, depts, viewModel::setAnalyticsFilterDept)
        }
    }
}

@Composable
private fun AnalyticsRangeDropdown(active: String, onSelect: (String) -> Unit) {
    val colors = LocalWebColors.current
    var expanded by remember { mutableStateOf(false) }
    val label = RANGES.firstOrNull { it.first == active }?.second ?: "This Week"
    Box {
        Row(
            Modifier
                .background(colors.inputBg, RoundedCornerShape(8.dp))
                .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(label, color = colors.text, fontSize = 0.85.rem) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RANGES.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun AnalyticsDeptDropdown(active: String, depts: List<String>, onSelect: (String) -> Unit) {
    val colors = LocalWebColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .background(colors.inputBg, RoundedCornerShape(8.dp))
                .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(active.ifEmpty { "All Departments" }, color = colors.text, fontSize = 0.85.rem) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All Departments") }, onClick = { onSelect(""); expanded = false })
            depts.forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { onSelect(d); expanded = false }) }
        }
    }
}

@Composable
private fun AnalyticsDateField(value: String, onChange: (String) -> Unit) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    Row(
        Modifier
            .background(colors.inputBg, RoundedCornerShape(8.dp))
            .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
            .clickable {
                val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toString()) },
                    initial.year,
                    initial.monthValue - 1,
                    initial.dayOfMonth,
                ).show()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) { Text(value.ifEmpty { "Select date" }, color = colors.text, fontSize = 0.8.rem) }
}

@Composable
private fun SummaryGrid(data: TeamAnalyticsResponse) {
    val avgHours = "%.1f".format(data.avgHours)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCard(Icons.Outlined.Groups, "${data.totalMembers}", "Team Members", Modifier.weight(1f))
            SummaryCard(Icons.Outlined.Timer, "${avgHours}h", "Avg Hours/Day", Modifier.weight(1f))
            SummaryCard(Icons.Outlined.Checklist, "${data.totalTasksDone}", "Planner Completed", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCard(Icons.Outlined.GpsFixed, "${data.avgTargetMet}%", "Avg Target Met", Modifier.weight(1f))
            SummaryCard(Icons.Outlined.AlarmOn, "${data.avgPunctuality}%", "Avg Punctuality", Modifier.weight(1f))
            SummaryCard(Icons.Outlined.Checklist, "${data.pendingApprovals}", "Pending Approvals", Modifier.weight(1f), amber = true)
        }
    }
}

@Composable
private fun SummaryCard(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier = Modifier, amber: Boolean = false) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .background(colors.cardBg, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.width(20.dp).height(20.dp), tint = colors.primary)
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (amber) colors.warning else colors.text, fontSize = 1.05.rem, fontWeight = FontWeight.Bold)
        Text(label, color = colors.textMuted, fontSize = 0.66.rem, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun AnalyticsMemberCard(
    member: AnalyticsMember,
    targetMinutes: Int,
    expectedWeekdays: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectMember: (Long) -> Unit,
) {
    val colors = LocalWebColors.current
    ManagerRowCard(onClick = onToggleExpand) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.aino.mobile.core.designsystem.component.UserAvatar(member.fullName, member.avatar, 34.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(member.fullName.orEmpty(), color = colors.text, fontSize = 0.88.rem, fontWeight = FontWeight.SemiBold)
                val meta = listOfNotNull(member.role?.let(::roleLabel), member.departmentName, member.teamName).joinToString(" · ")
                Text(meta, color = colors.textSecondary, fontSize = 0.7.rem)
            }
            TodayStatusBadge(member.todayStatus, member.todayHoursMin)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            AnalyticsStat("Total", "%.1fh".format(member.hours))
            AnalyticsStat("Avg/Day", formatMin(member.avgFloorMinutes))
            AnalyticsStat("Planner", "${member.tasksDone}/${member.tasksTotal}")
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Target", color = colors.textMuted, fontSize = 0.68.rem, modifier = Modifier.width(56.dp))
                PercentBar(member.targetMetPercent)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Punctual", color = colors.textMuted, fontSize = 0.68.rem, modifier = Modifier.width(56.dp))
                PercentBar(member.punctualityPercent, blue = true)
            }
        }
        MiniTrend(member.trend, targetMinutes, Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "View full profile →",
                color = colors.primary,
                fontSize = 0.78.rem,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onSelectMember(member.id) },
            )
        }
        if (expanded) {
            AnalyticsExpandedDetails(member, targetMinutes, expectedWeekdays)
        }
    }
}

@Composable
private fun AnalyticsStat(label: String, value: String) {
    val colors = LocalWebColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.text, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold)
        Text(label, color = colors.textMuted, fontSize = 0.62.rem)
    }
}

/** `MemberExpandedCard.tsx`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalyticsExpandedDetails(member: AnalyticsMember, targetMinutes: Int, expectedWeekdays: Int) {
    val colors = LocalWebColors.current
    val utilization = if (expectedWeekdays > 0) {
        ((member.hours / (expectedWeekdays * (targetMinutes / 60.0))) * 100).roundToInt()
    } else {
        0
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExpandedStat("Email", member.email ?: "—")
        ExpandedStat("Department", member.departmentName ?: "—")
        ExpandedStat("Team", member.teamName ?: "—")
        ExpandedStat("Days Worked", "${member.daysWorked}")
        ExpandedStat("Target Met", "${member.targetMetDays}/${member.daysWorked} days")
        ExpandedStat("Avg Break", formatMin(member.avgBreakMinutes))
        ExpandedStat("Utilization", "$utilization%")
        ExpandedStat("Current Streak", "${member.streak} day${if (member.streak != 1) "s" else ""} \uD83D\uDD25")
        ExpandedStat("Task Completion", "${member.taskCompletionRate}% (${member.tasksDone}/${member.tasksTotal})")
        ExpandedStat("Leaves", "${member.leaves} total" + if (member.leavesByType.isNotEmpty()) " (${member.leavesByType.entries.joinToString(", ") { "${it.key}: ${it.value}" }})" else "")
        Column {
            Text("Work Mode", color = colors.textMuted, fontSize = 0.66.rem)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Business, null, Modifier.width(13.dp).height(13.dp), tint = colors.textSecondary)
                Text(" ${member.officeDays} · ", color = colors.text, fontSize = 0.78.rem)
                Icon(Icons.Outlined.Home, null, Modifier.width(13.dp).height(13.dp), tint = colors.textSecondary)
                Text(" ${member.remoteDays}", color = colors.text, fontSize = 0.78.rem)
            }
        }
    }
}

@Composable
private fun ExpandedStat(label: String, value: String) {
    val colors = LocalWebColors.current
    Column {
        Text(label, color = colors.textMuted, fontSize = 0.66.rem)
        Text(value, color = colors.text, fontSize = 0.78.rem, fontWeight = FontWeight.Medium)
    }
}
