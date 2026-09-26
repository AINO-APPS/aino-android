package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Common IANA zones offered by `OrgSettings.tsx` (the current value is always kept). */
internal val COMMON_TIMEZONES = listOf(
    "UTC", "Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Asia/Tokyo", "Europe/London", "Europe/Berlin",
    "Europe/Paris", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
    "America/Sao_Paulo", "Australia/Sydney", "Africa/Johannesburg",
)

internal val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** `OrgSettingsPage.tsx`: General / Attendance / Roles & Labels / Branding / Email templates. */
@Composable
internal fun AdminOrgSettingsSection(state: AdminUiState, viewModel: AdminViewModel) {
    AdminChipRow(OrgSettingsTab.entries.map { it to it.label }, state.settingsTab) { viewModel.selectSettingsTab(it) }
    when (state.settingsTab) {
        OrgSettingsTab.General -> AdminLoadState(state.orgSettings) { GeneralSettingsForm(state, it, viewModel) }
        OrgSettingsTab.Attendance -> AdminLoadState(state.orgSettings) { AttendanceSettingsForm(state, it, viewModel) }
        OrgSettingsTab.Roles -> AdminLoadState(state.orgRoles) { AdminOrgRolesPanel(state, it, viewModel) }
        OrgSettingsTab.Branding, OrgSettingsTab.EmailTemplates -> {
            val context = LocalContext.current
            val branding = viewModel<BrandingViewModel>(factory = BrandingViewModel.factory(context.applicationContext))
            LaunchedEffect(state.role) { branding.bind(state.role) }
            val seenRefresh = remember { mutableIntStateOf(state.settingsRefresh) }
            LaunchedEffect(state.settingsTab, state.settingsRefresh) {
                val force = state.settingsRefresh != seenRefresh.intValue
                seenRefresh.intValue = state.settingsRefresh
                if (state.settingsTab == OrgSettingsTab.Branding) branding.loadBranding(force) else branding.loadTemplates(force)
            }
            if (state.settingsTab == OrgSettingsTab.Branding) BrandingTab(branding) else EmailTemplatesTab(branding)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralSettingsForm(state: AdminUiState, settings: OrgSettings, viewModel: AdminViewModel) {
    var d by remember(settings) { mutableStateOf(GeneralSettingsDraft.from(settings)) }
    val all = state.isSuperOrAbove
    AdminRowCard {
        AdminTitle("General", if (all) null else "Only super admins can change the name, hours and working days.")
        AdminField("Organization name", d.name, { d = d.copy(name = it) }, enabled = all)
        AdminField("Work hours per day", d.workHoursPerDay, { d = d.copy(workHoursPerDay = it) }, keyboardType = KeyboardType.Number, enabled = all)
        AdminFieldLabel("Working days")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminCatalog.WEEK_DAYS.forEach { (day, label) ->
                val on = day in d.workDays
                AdminButton(label, { if (all) d = d.copy(workDays = if (on) d.workDays - day else d.workDays + day) },
                    style = if (on) AdminButtonStyle.Primary else AdminButtonStyle.Secondary, enabled = all, small = true)
            }
        }
        val zones = (listOf(d.timezone) + COMMON_TIMEZONES).distinct()
        AdminPicker("Timezone", zones.map { it to it }, d.timezone, { d = d.copy(timezone = it) })
        AdminPicker("Fiscal year starts", MONTHS.mapIndexed { i, m -> (i + 1).toString() to m }, d.fiscalYearStart, { d = d.copy(fiscalYearStart = it) })
        AdminField("Minimum hours to count as present", d.minHoursPresent, { d = d.copy(minHoursPresent = it) }, keyboardType = KeyboardType.Decimal, hint = "Leave blank to use half the work day")
        AdminField("Office start time", d.officeStartTime, { d = d.copy(officeStartTime = it) }, placeholder = "HH:MM", hint = "Used to flag late arrivals")
        AdminSwitchRow("Allow biometric login", d.biometricLoginEnabled, { d = d.copy(biometricLoginEnabled = it) })
        AdminButton("Save settings", { viewModel.saveGeneralSettings(d) }, enabled = !state.busy)
    }
}

@Composable
private fun AttendanceSettingsForm(state: AdminUiState, settings: OrgSettings, viewModel: AdminViewModel) {
    var d by remember(settings) { mutableStateOf(AttendanceSettingsDraft.from(settings)) }
    AdminRowCard {
        AdminTitle("Office location", "Require clock-ins to happen near the office.")
        AdminSwitchRow("Verify location on clock-in", d.verifyOn, { d = d.copy(verifyOn = it) })
        AdminField("Address", d.address, { d = d.copy(address = it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminField("Latitude", d.latitude, { d = d.copy(latitude = it) }, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
            AdminField("Longitude", d.longitude, { d = d.copy(longitude = it) }, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
        }
        AdminField("Radius (metres)", d.radius, { d = d.copy(radius = it) }, keyboardType = KeyboardType.Number)
    }
    AdminRowCard {
        AdminTitle("Office Wi-Fi", "Require clock-ins on one of these access points.")
        AdminSwitchRow("Verify Wi-Fi on clock-in", d.wifiOn, { d = d.copy(wifiOn = it) })
        d.wifi.forEachIndexed { i, ap ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AdminTextInput(ap.bssid, { v -> d = d.copy(wifi = d.wifi.toMutableList().also { it[i] = ap.copy(bssid = v) }) }, "BSSID", Modifier.weight(1f))
                AdminTextInput(ap.label.orEmpty(), { v -> d = d.copy(wifi = d.wifi.toMutableList().also { it[i] = ap.copy(label = v.ifEmpty { null }) }) }, "Label", Modifier.weight(1f))
            }
            AdminButton("Remove", { d = d.copy(wifi = d.wifi.filterIndexed { j, _ -> j != i }) }, style = AdminButtonStyle.Cancel, small = true)
        }
        AdminButton("Add access point", { d = d.copy(wifi = d.wifi + WifiAp()) }, style = AdminButtonStyle.Secondary, small = true)
    }
    AdminButton("Save attendance settings", { viewModel.saveAttendanceSettings(d) }, enabled = !state.busy)
}
