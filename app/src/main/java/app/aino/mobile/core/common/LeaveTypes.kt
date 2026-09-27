package app.aino.mobile.core.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.aino.mobile.core.designsystem.icons.HeroIcons

/**
 * Built-in leave types from `client/src/constants/leaves.ts`, shared by
 * Attendance (which merges org policies on top) and My Team.
 */
data class LeaveTypeMeta(
    val value: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val bg: Color,
)

private val SickLeave = LeaveTypeMeta("sick", "Sick Leave", HeroIcons.FaceFrown, Color(0xFFEF4444), Color(0x19EF4444))
private val HolidayLeave = LeaveTypeMeta("holiday", "Holiday", HeroIcons.Sun, Color(0xFFF59E0B), Color(0x19F59E0B))
private val PlannedLeave = LeaveTypeMeta("planned", "Planned Leave", HeroIcons.CalendarDays, Color(0xFF0EA5E9), Color(0x190EA5E9))
private val PersonalLeave = LeaveTypeMeta("personal", "Personal", HeroIcons.User, Color(0xFF10B981), Color(0x1910B981))
private val OtherLeave = LeaveTypeMeta("other", "Other", HeroIcons.PencilSquare, Color(0xFF0EA5E9), Color(0x190EA5E9))

val DEFAULT_LEAVE_TYPES: List<LeaveTypeMeta> = listOf(SickLeave, HolidayLeave, PlannedLeave, PersonalLeave, OtherLeave)

val DEFAULT_LEAVE_TYPE_MAP: Map<String, LeaveTypeMeta> = DEFAULT_LEAVE_TYPES.associateBy { it.value }

/** Returns the built-in config for a value, defaulting to 'other'. */
fun getLeaveType(value: String): LeaveTypeMeta = DEFAULT_LEAVE_TYPE_MAP[value] ?: OtherLeave
