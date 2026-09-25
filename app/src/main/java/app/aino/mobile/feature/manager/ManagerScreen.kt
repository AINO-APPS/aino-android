package app.aino.mobile.feature.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * Manager Dashboard — port of `client/src/pages/manager/index.tsx`: the tab
 * strip (Team Attendance · Approvals · Analytics · My Requests) drawn inside
 * the app shell, plus the member-detail sub-screen navigated to separately
 * (`manager/member/{userId}`, unlike the web's inline `selectedMember` swap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerScreen(
    viewModel: ManagerViewModel,
    userRole: String,
    onOpenMember: (userId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    LaunchedEffect(userRole) { viewModel.bind(userRole) }

    PullToRefreshBox(
        isRefreshing = ui.refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize().background(colors.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 64.dp),
        ) {
            Text("Manager Dashboard", color = colors.text, fontSize = 1.4.rem, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(20.dp))
            ManagerTabStrip(ui.tab, viewModel::selectTab)
            Spacer(Modifier.height(20.dp))
            val onSelectMember: (Long) -> Unit = { id -> onOpenMember(id) }
            when (ui.tab) {
                ManagerTab.Attendance -> TeamAttendanceTab(ui, viewModel, onSelectMember)
                ManagerTab.Approvals -> ApprovalsTab(ui, viewModel)
                ManagerTab.Analytics -> TeamAnalyticsTab(ui, viewModel, onSelectMember)
                ManagerTab.Requests -> MyRequestsTab(ui)
            }
        }
    }
}

/** `.tabs` — a horizontal strip of bordered tab buttons. */
@Composable
private fun ManagerTabStrip(active: ManagerTab, onSelect: (ManagerTab) -> Unit) {
    val colors = LocalWebColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
        ManagerTab.entries.forEach { tab ->
            val selected = tab == active
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (selected) colors.surfaceHover else Color.Transparent)
                    .border(1.dp, if (selected) colors.accent else colors.border, shape)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (selected) colors.accent else colors.textSecondary
                Icon(tabIcon(tab), null, Modifier.width(14.dp).height(14.dp), tint = tint)
                Spacer(Modifier.width(4.dp))
                Text(
                    tab.label,
                    color = tint,
                    fontSize = 0.78.rem,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
