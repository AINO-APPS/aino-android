package app.aino.mobile.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.designsystem.icons.HeroIcons

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.clip(shape).background(colors.cardBg).border(1.dp, colors.border, shape).padding(14.dp)) {
        Text(value.toString(), color = colors.text, fontSize = 1.5.rem, fontWeight = FontWeight.Bold)
        Text(label, color = colors.textSecondary, fontSize = 0.78.rem)
    }
}

/** `AdminHome.tsx` quick actions: `(label, web section key)`; department/team need an org. */
internal fun adminQuickActions(hasOrg: Boolean): List<Pair<String, String>> = buildList {
    add("Add people" to AdminSectionKeys.ADD)
    if (hasOrg) add("New department" to "departments")
    if (hasOrg) add("New team" to "teams")
    add("Lock pay period" to AdminSectionKeys.PAYROLL)
    add("Manage labels" to "labels")
    add("View audit logs" to AdminSectionKeys.AUDIT)
}

/** `AdminHome.tsx`: stats, attention, quick actions and the org setup checklist. */
@Composable
internal fun AdminHomeSection(state: AdminUiState, onOpenSection: (String) -> Unit) {
    val colors = LocalWebColors.current
    AdminLoadState(state.stats) { s ->
        val tiles = listOf(
            "Total users" to s.totalUsers, "Active users" to s.activeUsers, "Departments" to s.departments,
            "Teams" to s.teams, "Pending approvals" to s.pendingApprovals, "Clocked in today" to s.clockedInToday,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                }
            }
        }
    }
    val home = state.home.data
    if (home != null && home.pendingRoleRequests > 0) {
        AdminRowCard(onClick = { onOpenSection(AdminSectionKeys.ROLE_REQUESTS) }) {
            val n = home.pendingRoleRequests
            Text("$n role request${if (n == 1) "" else "s"} awaiting review", color = colors.warning, fontWeight = FontWeight.SemiBold)
        }
    }
    AdminTitle("Quick actions")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        adminQuickActions(state.orgId != null).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, key) ->
                    AdminButton(label, { onOpenSection(key) }, Modifier.weight(1f), style = AdminButtonStyle.Secondary)
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (state.orgId != null && home != null) {
        val done = home.checklist.count { it.third }
        AdminTitle("Setup checklist", "$done/${home.checklist.size} done")
        home.checklist.forEach { (label, target, isDone) ->
            AdminRowCard(onClick = { onOpenSection(target) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isDone) HeroIcons.CheckCircle else HeroIcons.Circle, null,
                        Modifier.size(18.dp), tint = if (isDone) colors.success else colors.textMuted,
                    )
                    Text(label, color = colors.text, fontSize = 0.88.rem, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    if (!isDone) Icon(HeroIcons.ArrowRight, null, Modifier.size(14.dp), tint = colors.textSecondary)
                }
            }
        }
        if (done == home.checklist.size) Text("Your organization is fully set up.", color = colors.success, fontSize = 0.8.rem)
    }
}
