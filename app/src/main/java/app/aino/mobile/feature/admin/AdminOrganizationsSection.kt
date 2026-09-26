package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** `admin/organizations` stores ISO day numbers (1 = Mon … 7 = Sun), unlike `/org/settings`. */
internal val ISO_WEEK_DAYS = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")

internal fun toggleDay(csv: String, day: Int): String {
    val set = csv.split(',').mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
    if (!set.remove(day)) set += day
    return set.sorted().joinToString(",")
}

/** Android-only page for `admin/organizations` CRUD (platform_admin). */
@Composable
internal fun AdminOrganizationsSection(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    var editing by remember { mutableStateOf<OrganizationDraft?>(null) }
    var deleting by remember { mutableStateOf<AdminOrganization?>(null) }
    if (!state.isPlatformAdmin) return AdminEmpty("Only platform admins can manage organizations")
    AdminButton("New organization", { editing = OrganizationDraft() }, small = true)
    AdminLoadState(state.organizations) { orgs ->
        if (orgs.isEmpty()) AdminEmpty("No organizations yet")
        orgs.forEach { o ->
            AdminRowCard {
                Text(o.name, color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(o.slug, o.timezone, o.members?.let { "$it members" }).joinToString(" · "),
                    color = colors.textSecondary, fontSize = 0.78.rem,
                )
                AdminButtonRow {
                    AdminButton("Edit", {
                        editing = OrganizationDraft(
                            o.id, o.name, (o.workHoursPerDay ?: 8).toString(), o.workDays ?: "1,2,3,4,5",
                            o.timezone ?: "UTC", (o.fiscalYearStart ?: 1).toString(),
                        )
                    }, style = AdminButtonStyle.Secondary, small = true)
                    AdminButton("Delete", { deleting = o }, style = AdminButtonStyle.Danger, small = true)
                }
            }
        }
    }
    editing?.let { OrganizationEditor(it, state.busy, viewModel) { editing = null } }
    deleting?.let { o ->
        AdminConfirmDialog(
            "Delete \"${o.name}\"? The server refuses while the organization still has members.",
            onConfirm = { deleting = null; viewModel.deleteOrganization(o.id) }, onDismiss = { deleting = null }, confirmText = "Delete",
        )
    }
}

@Composable
private fun OrganizationEditor(initial: OrganizationDraft, busy: Boolean, viewModel: AdminViewModel, close: () -> Unit) {
    var d by remember(initial) { mutableStateOf(initial) }
    AdminConfirmDialog(
        message = "", title = if (d.id == null) "New organization" else "Edit organization",
        onConfirm = { viewModel.saveOrganization(d, close) }, onDismiss = close,
        confirmText = "Save", danger = false, confirmEnabled = !busy && d.name.isNotBlank(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminField("Name", d.name, { d = d.copy(name = it) })
            AdminField("Work hours per day", d.workHoursPerDay, { d = d.copy(workHoursPerDay = it) }, keyboardType = KeyboardType.Number)
            AdminFieldLabel("Working days")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val on = d.workDays.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                ISO_WEEK_DAYS.forEach { (day, label) ->
                    AdminButton(label.take(2), { d = d.copy(workDays = toggleDay(d.workDays, day)) }, Modifier.weight(1f),
                        if (day in on) AdminButtonStyle.Primary else AdminButtonStyle.Secondary, small = true)
                }
            }
            AdminPicker("Timezone", (listOf(d.timezone) + COMMON_TIMEZONES).distinct().map { it to it }, d.timezone, { d = d.copy(timezone = it) })
            AdminPicker("Fiscal year starts", MONTHS.mapIndexed { i, m -> (i + 1).toString() to m }, d.fiscalYearStart, { d = d.copy(fiscalYearStart = it) })
        }
    }
}
