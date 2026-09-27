package app.aino.mobile.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.designsystem.icons.HeroIcons

/** Page titles; web `SECTIONS` labels plus the Android-only pages. */
fun adminSectionTitle(key: String): String = when (key) {
    AdminSectionKeys.HOME -> "Home"
    AdminSectionKeys.USERS -> "Users"
    AdminSectionKeys.ADD -> "Add People"
    AdminSectionKeys.ROLE_REQUESTS -> "Role Requests"
    AdminSectionKeys.PAYROLL -> "Payroll Periods"
    AdminSectionKeys.AUDIT -> "Audit Logs"
    AdminSectionKeys.ORG_SETTINGS -> "Org Settings"
    AdminSectionKeys.ORGANIZATIONS -> "Organizations"
    AdminSectionKeys.TASK_LABELS -> "Task Labels"
    AdminSectionKeys.REGISTRATION -> "Registration & Invites"
    AdminSectionKeys.ANNOUNCEMENTS -> "Announcements"
    else -> "Admin"
}

/**
 * One admin section as a full-screen page (web `AdminPanel` right pane).
 * [onOpenSection] receives web section keys (`departments`, `agile`, ...) and
 * the shell decides the route; [onOpenUser] opens the user drawer page.
 */
@Composable
fun AdminSectionScreen(
    viewModel: AdminViewModel,
    sectionKey: String,
    onBack: () -> Unit,
    onOpenSection: (String) -> Unit,
    onOpenUser: (Long) -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(sectionKey) { viewModel.loadSection(sectionKey) }
    AinoFullPage(
        title = adminSectionTitle(sectionKey),
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.loadSection(sectionKey, force = true) }) {
                Icon(HeroIcons.ArrowPath, "Refresh", tint = LocalWebColors.current.text)
            }
        },
    ) {
        state.notice?.let { AdminNoticeBanner(it) }
        when (sectionKey) {
            AdminSectionKeys.HOME -> AdminHomeSection(state, onOpenSection)
            AdminSectionKeys.USERS -> AdminUsersSection(state, viewModel, onOpenUser)
            AdminSectionKeys.ADD -> AdminAddPeopleSection(state, viewModel, onOpenUser)
            AdminSectionKeys.ROLE_REQUESTS -> AdminRoleRequestsSection(state, viewModel)
            AdminSectionKeys.PAYROLL -> AdminPayrollSection(state, viewModel)
            AdminSectionKeys.AUDIT -> AdminAuditSection(state, viewModel)
            AdminSectionKeys.ORG_SETTINGS -> AdminOrgSettingsSection(state, viewModel)
            AdminSectionKeys.ORGANIZATIONS -> AdminOrganizationsSection(state, viewModel)
            AdminSectionKeys.TASK_LABELS -> AdminTaskLabelsSection(state, viewModel)
            AdminSectionKeys.REGISTRATION -> AdminRegistrationSection(state, viewModel)
            AdminSectionKeys.ANNOUNCEMENTS -> AdminAnnouncementsSection(state, viewModel)
            else -> AdminEmpty("This section is not available.")
        }
    }
}

/** Segmented filter (`.tabs` / status pills), horizontally scrollable on phones. */
@Composable
internal fun <K> AdminChipRow(options: List<Pair<K, String>>, selected: K, onSelect: (K) -> Unit) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            val shape = RoundedCornerShape(20.dp)
            Text(
                label,
                color = if (on) colors.onAccent else colors.textSecondary,
                fontSize = 0.8.rem,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(shape).background(if (on) colors.primary else colors.surfaceHover)
                    .border(1.dp, if (on) colors.primary else colors.border, shape)
                    .clickable { onSelect(key) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
