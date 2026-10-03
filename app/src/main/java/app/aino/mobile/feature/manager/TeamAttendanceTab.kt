package app.aino.mobile.feature.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.common.roleLabel
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Roster filters; [status] is the `team-attendance` status value (null = everyone). */
internal enum class TeamFilter(val status: String?, val label: String) {
    All(null, "All"),
    Working("working", "Working"),
    Away("away", "Away"),
    NotStarted("not_started", "Not started"),
    OnLeave("on_leave", "On leave"),
}

internal fun teamCounts(members: List<TeamAttendanceMember>): Map<TeamFilter, Int> =
    TeamFilter.entries.associateWith { filter -> filterTeam(members, filter).size }

internal fun filterTeam(members: List<TeamAttendanceMember>, filter: TeamFilter): List<TeamAttendanceMember> =
    if (filter.status == null) members else members.filter { it.status == filter.status }

/** Moves the roster date by [days], never past [today] (future rosters are empty). */
internal fun shiftTeamDate(date: String, days: Long, today: LocalDate = LocalDate.now()): String {
    val base = runCatching { LocalDate.parse(date) }.getOrDefault(today)
    return minOf(base.plusDays(days), today).toString()
}

/** "3.5h" from the server's decimal hours, else from floor minutes; null when nothing was worked. */
internal fun teamHoursLabel(member: TeamAttendanceMember): String? {
    val hours = member.hoursToday ?: member.floorMinutes.takeIf { it > 0 }?.let { it / 60.0 }
    if (hours == null || hours <= 0.0) return null
    val rounded = Math.round(hours * 10) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}h" else "${rounded}h"
}

private fun statusColor(status: String, colors: WebColors): Color = when (status) {
    "working" -> colors.success
    "away" -> colors.warning
    "on_leave" -> colors.danger
    else -> colors.textMuted
}

/**
 * Team Attendance (manager): date stepper + picker, status filter chips with
 * counts, and member cards (avatar status dot, hours, work mode, current task
 * or leave). Data and refresh stay on [ManagerViewModel] (`setAttendanceDate`
 * reloads; pull-to-refresh and realtime refresh the section).
 */
@Composable
internal fun TeamAttendanceTab(ui: ManagerUiState, viewModel: ManagerViewModel, onSelectMember: (Long) -> Unit) {
    val colors = LocalWebColors.current
    val section = ui.attendance
    val members = section.data.orEmpty()
    var filterName by rememberSaveable { mutableStateOf(TeamFilter.All.name) }
    val filter = TeamFilter.valueOf(filterName)
    val counts = teamCounts(members)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TeamDateSelector(ui.attendanceDate, viewModel::setAttendanceDate)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamFilter.entries.forEach { option ->
                val tint = option.status?.let { statusColor(it, colors) } ?: colors.primary
                FilterChip(
                    selected = filter == option,
                    onClick = { filterName = option.name },
                    label = { Text("${option.label} ${counts[option] ?: 0}", maxLines = 1) },
                    leadingIcon = option.status?.let { { Box(Modifier.size(8.dp).background(tint, CircleShape)) } },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = colors.textSecondary,
                        selectedContainerColor = tint.copy(alpha = 0.16f),
                        selectedLabelColor = if (option.status == null) colors.primary else colors.text,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == option,
                        borderColor = colors.border,
                        selectedBorderColor = tint.copy(alpha = 0.5f),
                    ),
                )
            }
        }

        section.error?.let { ManagerErrorText(it) }
        val shown = filterTeam(members, filter)
        when {
            section.initialLoading -> ManagerLoading()
            members.isEmpty() && section.data != null ->
                ManagerEmpty("No team members found. Make sure you are part of an organization.")
            shown.isEmpty() -> ManagerEmpty("Nobody is ${filter.label.lowercase()} on this day.")
            filter == TeamFilter.All -> {
                val groups = TeamFilter.entries.drop(1)
                groups.forEach { group ->
                    val groupMembers = filterTeam(members, group)
                    if (groupMembers.isNotEmpty()) TeamGroup(group.label, groupMembers.size, statusColor(group.status.orEmpty(), colors), groupMembers, onSelectMember)
                }
                val others = members.filter { m -> groups.none { it.status == m.status } }
                if (others.isNotEmpty()) TeamGroup("Other", others.size, colors.textMuted, others, onSelectMember)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { TeamMemberCard(it, onSelectMember) }
            }
        }
    }
}

@Composable
private fun TeamGroup(label: String, count: Int, tint: Color, members: List<TeamAttendanceMember>, onSelect: (Long) -> Unit) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            Box(Modifier.size(8.dp).background(tint, CircleShape))
            Text("  $label", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.92.rem)
            Text("  $count", color = colors.textMuted, fontSize = 0.85.rem)
        }
        members.forEach { TeamMemberCard(it, onSelect) }
    }
}

@Composable
private fun TeamMemberCard(member: TeamAttendanceMember, onSelect: (Long) -> Unit) {
    val colors = LocalWebColors.current
    val tint = statusColor(member.status, colors)
    val statusLabel = TeamFilter.entries.firstOrNull { it.status == member.status }?.label ?: member.status.replace('_', ' ')
    val hours = teamHoursLabel(member)
    val remote = member.workMode == "remote"
    Card(
        onClick = { onSelect(member.id) },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            role = Role.Button
            onClick(label = "Open member details") { onSelect(member.id); true }
            contentDescription = listOfNotNull(
                member.fullName,
                statusLabel,
                hours?.let { "$it today" },
                member.workMode?.let { if (it == "remote") "Remote" else "Office" },
                member.currentTask?.let { "Working on $it" },
                member.leaveType?.let { "Leave: $it" },
            ).joinToString(", ")
        },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBg, contentColor = colors.text),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                UserAvatar(member.fullName, member.avatar, 44.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(14.dp)
                        .background(colors.bg, CircleShape)
                        .padding(2.dp)
                        .background(tint, CircleShape),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    member.fullName.orEmpty(),
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 0.92.rem,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(member.role?.let(::roleLabel) ?: member.role.orEmpty(), color = colors.textSecondary, fontSize = 0.76.rem)
                    if (member.workMode != null) {
                        Text("  ·  ", color = colors.textMuted, fontSize = 0.76.rem)
                        Icon(if (remote) HeroIcons.Home else HeroIcons.BuildingOffice, null, Modifier.size(12.dp), tint = colors.textSecondary)
                        Text(if (remote) " Remote" else " Office", color = colors.textSecondary, fontSize = 0.76.rem)
                    }
                }
                member.currentTask?.takeIf(String::isNotBlank)?.let {
                    Text(
                        "▸ $it",
                        color = colors.primary,
                        fontSize = 0.78.rem,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                member.leaveType?.takeIf(String::isNotBlank)?.let {
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        LeaveIconFor(it)
                        Text(" ${it.replace('_', ' ').replaceFirstChar(Char::uppercase)}", color = colors.textSecondary, fontSize = 0.78.rem)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(hours ?: "—", color = if (hours != null) colors.text else colors.textMuted, fontWeight = FontWeight.Bold, fontSize = 0.95.rem)
                Text(
                    statusLabel,
                    color = tint,
                    fontSize = 0.7.rem,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(tint.copy(alpha = 0.14f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

/** ‹ date › stepper; the label opens a date picker. Future days are not selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamDateSelector(value: String, onChange: (String) -> Unit) {
    val colors = LocalWebColors.current
    val today = LocalDate.now()
    val date = runCatching { LocalDate.parse(value) }.getOrDefault(today)
    var picking by remember { mutableStateOf(false) }
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY_LABEL)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(shiftTeamDate(value, -1, today)) }) {
            Icon(HeroIcons.ChevronLeft, "Previous day", Modifier.size(18.dp), tint = colors.text)
        }
        TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
            Icon(HeroIcons.CalendarDays, null, Modifier.size(16.dp), tint = colors.primary)
            Spacer(Modifier.width(8.dp))
            Text(label, color = colors.text, fontWeight = FontWeight.SemiBold)
            if (date != today && label != date.format(DAY_LABEL)) Text("  ${date.format(DAY_LABEL)}", color = colors.textMuted, fontSize = 0.78.rem)
        }
        if (date != today) {
            TextButton(onClick = { onChange(today.toString()) }) { Text("Today", color = colors.primary, fontWeight = FontWeight.SemiBold) }
        }
        IconButton(onClick = { onChange(shiftTeamDate(value, 1, today)) }, enabled = date < today) {
            Icon(HeroIcons.ChevronRight, "Next day", Modifier.size(18.dp), tint = if (date < today) colors.text else colors.textMuted)
        }
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
