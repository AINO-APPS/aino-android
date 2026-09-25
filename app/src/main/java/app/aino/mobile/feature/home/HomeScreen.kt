package app.aino.mobile.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.common.TrackerStatus
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.component.DashboardSkeleton
import app.aino.mobile.feature.home.component.EventReminderToast
import app.aino.mobile.feature.home.component.GreetingBanner
import app.aino.mobile.feature.home.component.PendingApprovalsCard
import app.aino.mobile.feature.home.component.SprintProgressCard
import app.aino.mobile.feature.home.component.TasksSummaryCard
import app.aino.mobile.feature.home.component.TodayEventsCard
import app.aino.mobile.feature.home.component.WorkTimerCard

private val ROLE_LEVEL = mapOf(
    "employee" to 1, "team_lead" to 2, "manager" to 3,
    "hr_admin" to 4, "super_admin" to 5, "platform_admin" to 6,
)

/**
 * Dashboard (P2). Render order at 430px: greeting banner, WorkTimerCard,
 * TodayEventsCard, TasksSummary, SprintProgressCard, PendingApprovalsCard
 * (manager-gated), EventReminderToast. Uses web-parity tokens, no glass/cream.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    user: AinoUser,
    viewModel: DashboardViewModel,
    attendanceStatus: TrackerStatus?,
    attendanceLoading: Boolean,
    attendanceWorkMode: String,
    onAttendanceWorkMode: (String) -> Unit,
    onAttendanceAction: (String) -> Unit,
    onAttendanceBreak: (Boolean) -> Unit,
    onCalendar: () -> Unit,
    onTasks: () -> Unit,
    modifier: Modifier = Modifier,
    attendanceNotice: String? = null,
    attendanceNoticeIsError: Boolean = false,
    onDismissAttendanceNotice: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val snapshot = ui.snapshot
    val isManager = (ROLE_LEVEL[user.role] ?: 1) >= ROLE_LEVEL["team_lead"]!! || user.hasReports
    androidx.compose.runtime.LaunchedEffect(isManager) { viewModel.setManager(isManager) }

    val status = attendanceStatus ?: snapshot?.status
    val (reminders, dismissReminder) = rememberEventReminders(snapshot?.todayEvents.orEmpty())

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = ui.loading && snapshot != null,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize().background(colors.bg),
    ) {
        if (ui.loading && snapshot == null) {
            // First load with nothing cached ? skeleton (stale-while-revalidate).
            Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) { DashboardSkeleton() }
        } else {
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GreetingBanner(user.fullName, snapshot?.announcements.orEmpty())
                WorkTimerCard(
                    status,
                    attendanceLoading,
                    ui.floorSeconds,
                    ui.breakSeconds,
                    attendanceWorkMode,
                    onAttendanceWorkMode,
                    onAttendanceAction,
                    onAttendanceBreak,
                )
                TodayEventsCard(snapshot?.todayEvents.orEmpty(), snapshot?.tomorrowEvents.orEmpty(), onCalendar)
                TasksSummaryCard(snapshot?.tasks, onTasks)
                SprintProgressCard(snapshot?.sprint, snapshot?.sprintTasks.orEmpty(), snapshot?.backlogTasks.orEmpty(), onTasks)
                if (isManager) {
                    PendingApprovalsCard(snapshot?.approvals.orEmpty(), viewModel::approve, viewModel::reject)
                }
            }
        }
        // Reminder toasts overlay the bottom of the page.
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EventReminderToast(reminders, dismissReminder)
                // Clock-in/out/break results (web shows these as toasts).
                attendanceNotice?.let { notice ->
                    androidx.compose.runtime.LaunchedEffect(notice) {
                        kotlinx.coroutines.delay(if (attendanceNoticeIsError) 6_000 else 3_000)
                        onDismissAttendanceNotice()
                    }
                    app.aino.mobile.core.designsystem.AinoAlert(
                        notice,
                        if (attendanceNoticeIsError) app.aino.mobile.core.designsystem.AlertTone.Error else app.aino.mobile.core.designsystem.AlertTone.Success,
                        Modifier.clickable(onClick = onDismissAttendanceNotice),
                    )
                }
            }
        }
    }
}