package app.aino.mobile.feature.organization

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * Organization page — port of `client/src/pages/Organization.tsx`: the org
 * title, the tab strip (My Department · My Team · Org Chart · Task Labels for
 * managers) and the no-org states. Drawn inside the app shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationScreen(
    viewModel: OrganizationViewModel,
    userRole: String,
    userId: Long,
    onOrgCreated: (orgId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    LaunchedEffect(userRole, userId) { viewModel.bind(userRole, userId) }

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
            val noOrg = ui.orgLoaded && ui.org == null && ui.orgError == null
            when {
                !ui.orgLoaded -> OrgLoading()
                noOrg && userRole == "super_admin" -> CreateOrgView(ui, viewModel, onOrgCreated)
                // Admins (platform_admin / hr_admin) fall through to the tabs, which show empty states.
                noOrg && !isOrgAdmin(userRole) -> {
                    PageTitle("Organization")
                    Text(
                        "You are not assigned to any organization yet. Please contact your administrator.",
                        color = colors.textSecondary,
                        fontSize = 0.9.rem,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                else -> {
                    PageTitle(ui.org?.name?.takeIf(String::isNotEmpty) ?: "Organization")
                    ui.orgError?.let {
                        Spacer(Modifier.height(12.dp))
                        OrgErrorText(it)
                    }
                    Spacer(Modifier.height(20.dp))
                    OrgTabStrip(ui.tabs, ui.tab, viewModel::selectTab)
                    Spacer(Modifier.height(20.dp))
                    when (ui.tab) {
                        OrgTab.Departments -> DepartmentsTab(ui, viewModel)
                        OrgTab.Teams -> TeamsTab(ui, viewModel)
                        OrgTab.Chart -> OrgChartTab(ui.chart)
                        OrgTab.Labels -> if (canManageLabels(ui.role)) TaskLabelsTab(ui, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(text, color = LocalWebColors.current.text, fontSize = 1.4.rem, fontWeight = FontWeight.ExtraBold)
}

private fun tabIcon(tab: OrgTab): ImageVector = when (tab) {
    OrgTab.Departments -> Icons.Outlined.Business
    OrgTab.Teams -> Icons.Outlined.Group
    OrgTab.Chart -> Icons.Outlined.AccountTree
    OrgTab.Labels -> Icons.Outlined.LocalOffer
}

/** `.orgTabs` at phone width: a two-per-row grid of bordered tabs. */
@Composable
private fun OrgTabStrip(tabs: List<OrgTab>, active: OrgTab, onSelect: (OrgTab) -> Unit) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.4.dp)) {
        tabs.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
                row.forEach { tab ->
                    val selected = tab == active
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(if (selected) colors.surfaceHover else Color.Transparent)
                            .border(1.dp, if (selected) colors.accent else colors.border, shape)
                            .clickable { onSelect(tab) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (selected) colors.accent else colors.textSecondary
                        Icon(tabIcon(tab), null, Modifier.size(14.dp), tint = tint)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tab.label,
                            color = tint,
                            fontSize = 0.9.rem,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** `CreateOrgView` for a super_admin with no organization. */
@Composable
private fun CreateOrgView(ui: OrganizationUiState, viewModel: OrganizationViewModel, onOrgCreated: (Long) -> Unit) {
    val colors = LocalWebColors.current
    var name by rememberSaveable { mutableStateOf("") }
    PageTitle("Create Organization")
    Text(
        "You're not part of any organization yet. Create one to enable enterprise features.",
        color = colors.textSecondary,
        fontSize = 0.9.rem,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
    ui.createOrgError?.let {
        OrgErrorText(it)
        Spacer(Modifier.height(12.dp))
    }
    Column(Modifier.fillMaxWidth()) {
        OrgFieldLabel("Organization Name")
        OrgTextField(name, { name = it }, placeholder = "e.g. Acme Corp")
        Spacer(Modifier.height(16.dp))
        OrgButton(
            "Create Organization",
            onClick = { viewModel.createOrg(name, onOrgCreated) },
            enabled = name.isNotBlank() && !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
