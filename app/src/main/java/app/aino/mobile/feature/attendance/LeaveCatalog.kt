package app.aino.mobile.feature.attendance

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.ui.graphics.Color
import app.aino.mobile.core.common.DEFAULT_LEAVE_TYPE_MAP as DEFAULT_TYPE_MAP
import app.aino.mobile.core.common.LeaveTypeMeta

/**
 * Port of `client/src/constants/leaves.ts` (P3.3): leave-status metadata and
 * the org-policy merge on top of the built-in types in `core/common/LeaveTypes`
 * (custom types render alongside the built-ins).
 */
data class LeaveStatusMeta(val label: String, val color: Color, val bg: Color)

val LEAVE_STATUS_CONFIG: Map<String, LeaveStatusMeta> = mapOf(
    "pending" to LeaveStatusMeta("Pending", Color(0xFFF59E0B), Color(0x1FF59E0B)),
    "approved" to LeaveStatusMeta("Approved", Color(0xFF10B981), Color(0x1F10B981)),
    "rejected" to LeaveStatusMeta("Rejected", Color(0xFFEF4444), Color(0x1FEF4444)),
    "withdraw_pending" to LeaveStatusMeta("Withdrawal Pending", Color(0xFF0EA5E9), Color(0x1F0EA5E9)),
    "withdrawn" to LeaveStatusMeta("Withdrawn", Color(0xFF0EA5E9), Color(0x1F0EA5E9)),
)

fun leaveStatusMeta(status: String): LeaveStatusMeta =
    LEAVE_STATUS_CONFIG[status] ?: LEAVE_STATUS_CONFIG.getValue("pending")

/** 10%-alpha background derived from a policy hex colour (`hexToBg` port). */
fun hexToBackground(hex: String?): Color {
    val raw = hex?.removePrefix("#").orEmpty()
    val expanded = when {
        raw.matches(Regex("[0-9a-fA-F]{3}")) -> raw.map { "$it$it" }.joinToString("")
        raw.matches(Regex("[0-9a-fA-F]{6}")) -> raw
        else -> return Color(0x196366F1) // rgba(99,102,241,0.1)
    }
    val rgb = expanded.toLong(16).toInt()
    return Color(rgb or 0x19000000.toInt())
}

fun parseHexColor(hex: String?): Color? {
    val raw = hex?.removePrefix("#").orEmpty()
    val expanded = when {
        raw.matches(Regex("[0-9a-fA-F]{3}")) -> raw.map { "$it$it" }.joinToString("")
        raw.matches(Regex("[0-9a-fA-F]{6}")) -> raw
        else -> return null
    }
    return Color(expanded.toLong(16).toInt() or 0xFF000000.toInt())
}

private fun prettify(slug: String): String =
    slug.replace(Regex("[_-]+"), " ").split(' ')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/**
 * `buildLeaveTypeMeta` port: known types keep their built-in icon/colour (with
 * policy name/colour overrides); custom org types get the EditNote icon and
 * the policy colour (default #6366f1).
 */
fun buildLeaveTypeMeta(policies: List<LeavePolicy>): Map<String, LeaveTypeMeta> {
    val out = DEFAULT_TYPE_MAP.toMutableMap()
    for (policy in policies) {
        val base = DEFAULT_TYPE_MAP[policy.leaveType] ?: LeaveTypeMeta(
            value = policy.leaveType,
            label = prettify(policy.leaveType),
            icon = Icons.Outlined.EditNote,
            color = Color(0xFF6366F1),
            bg = Color(0x196366F1),
        )
        val color = parseHexColor(policy.color) ?: base.color
        out[policy.leaveType] = LeaveTypeMeta(
            value = policy.leaveType,
            label = policy.name?.takeIf(String::isNotBlank) ?: base.label,
            icon = base.icon,
            color = color,
            bg = if (policy.color != null) hexToBackground(policy.color) else base.bg,
        )
    }
    return out
}

/**
 * `buildLeaveTypeOptions` port: only org-configured types are pickable — there
 * are no built-in defaults for non-holiday types. Empty when the org has not
 * configured any leave policies yet.
 */
fun buildLeaveTypeOptions(policies: List<LeavePolicy>): List<LeaveTypeMeta> {
    if (policies.isEmpty()) return emptyList()
    val meta = buildLeaveTypeMeta(policies)
    return policies.mapNotNull { meta[it.leaveType] }
}
