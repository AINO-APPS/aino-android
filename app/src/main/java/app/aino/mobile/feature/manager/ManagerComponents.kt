package app.aino.mobile.feature.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.common.getLeaveType

/** `pages/manager/constants.ts` `formatMin`. */
fun formatMin(totalMin: Int): String {
    if (totalMin <= 0) return "0h 0m"
    val h = totalMin / 60
    val mins = totalMin % 60
    return "${h}h ${mins}m"
}

private val DATE_FMT = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT)

/** `new Date(iso).toLocaleDateString()` — a short localized date. */
internal fun formatApprovalDate(iso: String): String = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DATE_FMT)
}.getOrDefault(iso)

/** `LEAVE_ICONS` — reuses the attendance feature's leave-type catalogue (identical icon set). */
@Composable
internal fun LeaveIconFor(type: String?, size: Dp = 13.dp) {
    val meta = type?.let(::getLeaveType) ?: return
    Icon(meta.icon, null, Modifier.width(size).height(size), tint = LocalWebColors.current.textSecondary)
}

/** `ApprovalBadge.tsx`. */
@Composable
internal fun ApprovalBadge(status: String?) {
    val colors = LocalWebColors.current
    val tint = when (status) {
        "pending" -> colors.warning
        "approved" -> colors.success
        "rejected" -> colors.danger
        else -> colors.textSecondary
    }
    Text(
        status.orEmpty(),
        color = tint,
        fontSize = 0.75.rem,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** `StatusBadge.tsx` (planner task status). */
@Composable
internal fun TaskStatusBadge(status: String?) {
    val colors = LocalWebColors.current
    val tint = when (status) {
        "done" -> colors.success
        "in_progress" -> colors.warning
        "in_review" -> colors.primary
        else -> colors.textSecondary
    }
    Text(
        status?.replace("_", " ").orEmpty(),
        color = tint,
        fontSize = 0.68.rem,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** `PriorityBadge.tsx`. */
@Composable
internal fun PriorityBadge(priority: String?) {
    val colors = LocalWebColors.current
    val tint = when (priority) {
        "high" -> colors.danger
        "medium" -> colors.warning
        "low" -> colors.success
        else -> colors.textSecondary
    }
    Text(
        priority.orEmpty(),
        color = tint,
        fontSize = 0.68.rem,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private val TODAY_STATUS_CONFIG: Map<String, Triple<String, Color, String>> = mapOf(
    "working" to Triple("Working", Color(0xFF4DAA57), "\uD83D\uDFE2"),
    "on_break" to Triple("Break", Color(0xFFCB912F), "\uD83D\uDFE1"),
    "on_leave" to Triple("On Leave", Color(0xFFE03E3E), "\uD83D\uDD34"),
    "left" to Triple("Left", Color(0xFF9CA3AF), "\u26AA"),
    "absent" to Triple("Absent", Color(0xFF6B7280), "\u26AB"),
)

/** `TodayStatusBadge.tsx`. */
@Composable
internal fun TodayStatusBadge(status: String?, minutes: Int) {
    val (label, tint, icon) = TODAY_STATUS_CONFIG[status] ?: TODAY_STATUS_CONFIG.getValue("absent")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$icon $label",
            color = tint,
            fontSize = 0.75.rem,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(tint.copy(alpha = 0.15f), RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (minutes > 0) {
            Spacer(Modifier.width(6.dp))
            Text(formatMin(minutes), color = LocalWebColors.current.textMuted, fontSize = 0.72.rem)
        }
    }
}

/** `PercentBar.tsx`. `color == "blue"` uses the punctuality palette. */
@Composable
internal fun PercentBar(value: Int, blue: Boolean = false, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    val bg = if (blue) {
        when {
            value >= 80 -> colors.primary
            value >= 50 -> colors.primaryLight
            else -> colors.textSecondary
        }
    } else {
        when {
            value >= 80 -> colors.success
            value >= 50 -> colors.warning
            else -> colors.danger
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .background(colors.border, RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = (value.coerceIn(0, 100) / 100f))
                    .height(6.dp)
                    .background(bg, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$value%", color = colors.textSecondary, fontSize = 0.72.rem, modifier = Modifier.width(34.dp))
    }
}

/** `MiniTrend.tsx`: a sparkline of daily floor minutes vs. the org target. */
@Composable
internal fun MiniTrend(data: List<Int>, target: Int, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    if (data.isEmpty()) {
        Text("—", color = colors.textMuted, fontSize = 0.75.rem, modifier = modifier)
        return
    }
    val max = (data.maxOrNull() ?: 0).coerceAtLeast(target.takeIf { it > 0 } ?: 480).coerceAtLeast(1)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        data.forEach { value ->
            val heightDp = ((value.toFloat() / max) * 28f).coerceAtLeast(2f)
            val barColor = when {
                value >= target -> colors.success
                value > 0 -> colors.warning
                else -> colors.border
            }
            Box(Modifier.width(5.dp).height(heightDp.dp).background(barColor, RoundedCornerShape(1.dp)))
        }
    }
}

/** `RequestDetails.tsx`: a one-line, type-specific summary of the request's `metadata`. */
@Composable
internal fun RequestDetails(row: ApprovalRow) {
    val colors = LocalWebColors.current
    val meta = row.metadata
    if (meta == null) {
        Text("—", color = colors.textSecondary, fontSize = 0.82.rem)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (row.type) {
            "leave" -> {
                LeaveIconFor(meta.leaveType)
                Spacer(Modifier.width(4.dp))
                val durationSuffix = if (!meta.duration.isNullOrEmpty() && meta.duration != "full") " (${meta.duration})" else ""
                Text("${meta.leaveType} • ${meta.date}$durationSuffix", color = colors.text, fontSize = 0.82.rem)
            }
            "leave_withdraw" -> {
                Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(13.dp), tint = colors.textSecondary)
                Spacer(Modifier.width(4.dp))
                Text("Withdraw", color = colors.text, fontSize = 0.82.rem)
                Spacer(Modifier.width(4.dp))
                LeaveIconFor(meta.leaveType)
                Spacer(Modifier.width(4.dp))
                val prevSuffix = meta.previousStatus?.let { " (was $it)" }.orEmpty()
                Text("${meta.leaveType} • ${meta.date}$prevSuffix", color = colors.text, fontSize = 0.82.rem)
            }
            "manual_entry" -> {
                Icon(Icons.Outlined.EditNote, null, Modifier.size(13.dp), tint = colors.textSecondary)
                Spacer(Modifier.width(4.dp))
                val clockOutSuffix = meta.clockOut?.let { " → $it" }.orEmpty()
                val modeSuffix = meta.workMode?.let { " ($it)" }.orEmpty()
                Text("${meta.date} • ${meta.clockIn}$clockOutSuffix$modeSuffix", color = colors.text, fontSize = 0.82.rem)
            }
            "overtime" -> {
                Icon(Icons.Outlined.AccessTime, null, Modifier.size(13.dp), tint = colors.textSecondary)
                Spacer(Modifier.width(4.dp))
                Text("${meta.date} • ${meta.hours}h", color = colors.text, fontSize = 0.82.rem)
            }
            else -> Text("—", color = colors.textSecondary, fontSize = 0.82.rem)
        }
    }
}

/** Work-mode icon + label (`House`/`Building2` pairs used across the tabs). */
@Composable
internal fun WorkModeLabel(workMode: String?) {
    val colors = LocalWebColors.current
    val remote = workMode == "remote"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (remote) Icons.Outlined.Home else Icons.Outlined.Business, null, Modifier.size(13.dp), tint = colors.textSecondary)
        Spacer(Modifier.width(4.dp))
        Text(if (remote) "Remote" else "Office", color = colors.textSecondary, fontSize = 0.82.rem)
    }
}

@Composable
internal fun ManagerLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.width(28.dp).height(28.dp), color = LocalWebColors.current.primary, strokeWidth = 2.5.dp)
    }
}

@Composable
internal fun ManagerEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalWebColors.current.textSecondary,
        fontSize = 0.88.rem,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
    )
}

@Composable
internal fun ManagerErrorText(text: String, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    Text(
        text,
        color = colors.danger,
        fontSize = 0.85.rem,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, colors.danger.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** A table row rendered as a card (`.table` rows on a phone). */
@Composable
internal fun ManagerRowCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    var base = modifier
        .fillMaxWidth()
        .background(colors.cardBg, RoundedCornerShape(12.dp))
        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
    if (onClick != null) base = base.clickable(onClick = onClick)
    Column(base.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
internal fun ManagerCellLabel(label: String) {
    Text(label, color = LocalWebColors.current.textMuted, fontSize = 0.72.rem)
}

/** Icon mapping helper for the tab strip. */
internal fun tabIcon(tab: ManagerTab): ImageVector = when (tab) {
    ManagerTab.Attendance -> Icons.Outlined.Schedule
    ManagerTab.Approvals -> Icons.AutoMirrored.Outlined.FactCheck
    ManagerTab.Analytics -> Icons.Outlined.Insights
    ManagerTab.Requests -> Icons.AutoMirrored.Outlined.Assignment
}
