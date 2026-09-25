package app.aino.mobile.feature.manager

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.common.roleLabel
import java.time.LocalDate

private data class AttendanceGroup(val status: String, val label: String)

private val ATTENDANCE_GROUPS = listOf(
    AttendanceGroup("working", "\uD83D\uDFE2 Working"),
    AttendanceGroup("away", "\uD83D\uDFE1 Away"),
    AttendanceGroup("not_started", "\u26AA Not Started"),
    AttendanceGroup("on_leave", "\uD83D\uDD34 On Leave"),
)

/** `TeamAttendance.tsx`. */
@Composable
internal fun TeamAttendanceTab(ui: ManagerUiState, viewModel: ManagerViewModel, onSelectMember: (Long) -> Unit) {
    val colors = LocalWebColors.current
    val section = ui.attendance
    val members = section.data.orEmpty()
    val grouped = ATTENDANCE_GROUPS.associate { it.status to members.filter { m -> m.status == it.status } }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AttendanceDateField(ui.attendanceDate, viewModel::setAttendanceDate)

        val counts = ATTENDANCE_GROUPS.map { grouped[it.status]?.size ?: 0 }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AttendanceStatCard("Working", counts[0], colors.success, Modifier.weight(1f))
            AttendanceStatCard("Away", counts[1], colors.warning, Modifier.weight(1f))
            AttendanceStatCard("Not Started", counts[2], colors.textSecondary, Modifier.weight(1f))
            AttendanceStatCard("On Leave", counts[3], colors.danger, Modifier.weight(1f))
        }

        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading -> ManagerLoading()
            members.isEmpty() && section.data != null ->
                ManagerEmpty("No team members found. Make sure you are part of an organization.")
            else -> ATTENDANCE_GROUPS.forEach { group ->
                val groupMembers = grouped[group.status].orEmpty()
                if (groupMembers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group.label, color = colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.SemiBold)
                        groupMembers.forEach { member -> AttendanceMemberCard(member, onSelectMember) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceStatCard(label: String, count: Int, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .background(colors.cardBg, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count", color = tint, fontSize = 1.15.rem, fontWeight = FontWeight.Bold)
        Text(label, color = colors.textMuted, fontSize = 0.68.rem, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun AttendanceMemberCard(member: TeamAttendanceMember, onSelect: (Long) -> Unit) {
    val colors = LocalWebColors.current
    ManagerRowCard(onClick = { onSelect(member.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(member.fullName, member.avatar, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(member.fullName.orEmpty(), color = colors.text, fontSize = 0.92.rem, fontWeight = FontWeight.SemiBold)
                Text(member.role?.let(::roleLabel) ?: member.role.orEmpty(), color = colors.textSecondary, fontSize = 0.75.rem)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            member.hoursToday?.let {
                Icon(Icons.Outlined.AccessTime, null, Modifier.width(13.dp).height(13.dp), tint = colors.textSecondary)
                Text(" ${it}h", color = colors.textSecondary, fontSize = 0.78.rem)
                Spacer(Modifier.width(10.dp))
            }
            member.workMode?.let { WorkModeLabel(it) }
        }
        member.currentTask?.let { Text("• $it", color = colors.primary, fontSize = 0.78.rem) }
        member.leaveType?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeaveIconFor(it)
                Spacer(Modifier.width(4.dp))
                Text(it, color = colors.textSecondary, fontSize = 0.78.rem)
            }
        }
    }
}

@Composable
private fun AttendanceDateField(value: String, onChange: (String) -> Unit) {
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
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.width(16.dp).height(16.dp), tint = colors.textSecondary)
        Spacer(Modifier.width(8.dp))
        Text(value, color = colors.text, fontSize = 0.88.rem)
    }
}
