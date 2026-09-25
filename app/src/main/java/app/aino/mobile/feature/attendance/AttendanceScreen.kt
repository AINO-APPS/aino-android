package app.aino.mobile.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private val HR_ROLES = setOf("hr_admin", "super_admin", "platform_admin")

/**
 * Attendance page shell (P3.1) — port of `client/src/pages/Attendance.tsx`:
 * title + subtitle, the four-tab strip (Overview · Leaves · Manual Entry ·
 * Analytics), and the active tab's body. Clock controls live on the Dashboard
 * WorkTimerCard, exactly like the web. At ≤480dp the strip collapses to
 * icons-only, equal-width tabs (`.tabs` @media 480px).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    viewModel: AttendanceViewModel,
    userRole: String = "employee",
    initialTab: AttendanceTab? = null,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current

    // Hash deep link (P3.8): `attendance?tab=leaves` selects the tab once.
    LaunchedEffect(initialTab) { if (initialTab != null) viewModel.openTab(initialTab) }
    LaunchedEffect(userRole) { viewModel.setHrRole(userRole in HR_ROLES) }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = ui.loading,
        onRefresh = {
            when (ui.selectedTab) {
                AttendanceTab.Leaves -> viewModel.loadLeavesTab()
                AttendanceTab.Analytics -> viewModel.loadAnalytics()
                else -> viewModel.refresh()
            }
        },
        modifier = modifier.fillMaxSize().background(colors.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 64.dp),
        ) {
            Text(
                "Attendance",
                color = colors.text,
                fontSize = 1.4.rem,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "Track your daily attendance, leaves, manual entries and analytics in one place",
                color = colors.textMuted,
                fontSize = 0.88.rem,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(20.dp))
            AttendanceTabStrip(ui.selectedTab, viewModel::selectTab)
            Spacer(Modifier.height(24.dp))
            ui.error?.let {
                WebErrorBanner(it)
                Spacer(Modifier.height(12.dp))
            }
            ui.message?.let {
                WebSuccessBanner(it)
                Spacer(Modifier.height(12.dp))
            }
            // Keep-alive (P3.8): tab state lives in the activity-scoped
            // ViewModel, so switching tabs never loses form input or scroll data.
            when (ui.selectedTab) {
                AttendanceTab.Overview -> OverviewCalendarTab(ui, viewModel)
                AttendanceTab.Leaves -> LeavesTab(ui, viewModel)
                AttendanceTab.Manual -> ManualEntryTab(ui, viewModel)
                AttendanceTab.Analytics -> AnalyticsTab(ui, viewModel)
            }
        }
    }
}

private fun tabIcon(tab: AttendanceTab): ImageVector = when (tab) {
    AttendanceTab.Overview -> Icons.Outlined.CalendarMonth
    AttendanceTab.Leaves -> Icons.Outlined.BeachAccess
    AttendanceTab.Manual -> Icons.Outlined.EditNote
    AttendanceTab.Analytics -> Icons.Outlined.BarChart
}

/** `.tabs` strip: surface container, 12px radius; active tab is primary-filled. */
@Composable
private fun AttendanceTabStrip(active: AttendanceTab, onSelect: (AttendanceTab) -> Unit) {
    val colors = LocalWebColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val iconOnly = maxWidth <= 480.dp
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(12.dp))
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AttendanceTab.entries.forEach { tab ->
                val selected = tab == active
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) colors.primary else Color.Transparent)
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        tabIcon(tab),
                        contentDescription = tab.label,
                        modifier = Modifier.size(16.dp),
                        tint = if (selected) colors.onAccent else colors.textSecondary,
                    )
                    if (!iconOnly) {
                        Text(
                            "  " + tab.label,
                            color = if (selected) colors.onAccent else colors.textSecondary,
                            fontSize = 0.82.rem,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
