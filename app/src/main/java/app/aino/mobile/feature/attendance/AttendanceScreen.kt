package app.aino.mobile.feature.attendance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoPullToRefreshBox
import app.aino.mobile.core.designsystem.component.FirstLoadSpinner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HR_ROLES = setOf("hr_admin", "super_admin", "platform_admin")

private fun tabIcon(tab: AttendanceTab): ImageVector = when (tab) {
    AttendanceTab.Overview -> HeroIcons.CalendarDays
    AttendanceTab.Leaves -> HeroIcons.Sun
    AttendanceTab.Manual -> HeroIcons.ClipboardDocumentList
    AttendanceTab.Analytics -> HeroIcons.ChartBar
}

/**
 * Attendance page — mobile-first redesign (approved deviation from web parity,
 * Attendance only). A Today hero card with the clock controls sits above a
 * labelled scrollable tab row synced to a swipeable pager; each page owns its
 * lazy list and pull-to-refresh. The header collapses as a page scrolls and
 * returns on the first scroll back (enter-always). Deep links
 * (`attendance?tab=leaves|manual-entry|analytics`) select the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    viewModel: AttendanceViewModel,
    modifier: Modifier = Modifier,
    userRole: String = "employee",
    initialTab: AttendanceTab? = null,
    onLocationPermission: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialTab) { if (initialTab != null) viewModel.openTab(initialTab) }
    LaunchedEffect(userRole) { viewModel.setHrRole(userRole in HR_ROLES) }

    // ---- Pager <-> selected tab -------------------------------------------
    val pagerState = rememberPagerState(initialPage = ui.selectedTab.page) { AttendanceTab.entries.size }
    var animatingTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ui.selectedTab) {
        val target = ui.selectedTab.page
        if (pagerState.currentPage == target && !pagerState.isScrollInProgress) return@LaunchedEffect
        animatingTo = target
        try {
            pagerState.animateScrollToPage(target)
        } finally {
            if (animatingTo == target) animatingTo = null
        }
    }
    // Swipes select the tab once past halfway, so its first-visit load starts mid-swipe.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (animatingTo == null && page != viewModel.ui.value.selectedTab.page) {
                viewModel.selectTab(AttendanceTab.fromPage(page))
            }
        }
    }
    // Pages render once visited; before that a swipe shows a spinner, not empty data.
    var visited by rememberSaveable { mutableStateOf(ui.selectedTab.page.toString()) }
    LaunchedEffect(ui.selectedTab) {
        val page = ui.selectedTab.page.toString()
        if (page !in visited.split(',')) visited = "$visited,$page"
    }

    // ---- Success messages -> snackbar ---------------------------------------
    val snackbar = remember { SnackbarHostState() }
    val notice = ui.message ?: ui.leaveSuccess
    val haptics = app.aino.mobile.core.designsystem.rememberAinoHaptics()
    val verifyError = ui.verifySession?.submitError
    LaunchedEffect(verifyError) { if (verifyError != null) haptics.reject() }
    LaunchedEffect(notice) {
        if (notice != null) {
            haptics.confirm()
            scope.launch { snackbar.showSnackbar(notice) }
            viewModel.consumeMessage()
        }
    }

    // ---- Collapsing header (enter-always) -----------------------------------
    var headerHeight by remember { mutableIntStateOf(0) }
    val headerOffset = remember { mutableFloatStateOf(0f) }
    val collapse = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val old = headerOffset.floatValue
                val new = (old + available.y).coerceIn(-headerHeight.toFloat(), 0f)
                headerOffset.floatValue = new
                return Offset(0f, new - old)
            }
        }
    }

    val formSheetOpen = ui.sheet == AttendanceSheet.ManualEntry || ui.sheet == AttendanceSheet.Overtime
    val currentTab = AttendanceTab.fromPage(pagerState.currentPage)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                AnimatedVisibility(
                    visible = currentTab == AttendanceTab.Leaves &&
                        (ui.leavesSubTab == LeavesSubTab.MyLeaves || ui.leavesSubTab == LeavesSubTab.MyBalances),
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.openSheet(AttendanceSheet.ApplyLeave) },
                        icon = { Icon(HeroIcons.Plus, null, Modifier.size(18.dp)) },
                        text = { Text("Apply leave", fontWeight = FontWeight.SemiBold) },
                        containerColor = colors.primary,
                        contentColor = colors.onAccent,
                    )
                }
                AnimatedVisibility(
                    visible = currentTab == AttendanceTab.Manual,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) { RequestsFab(viewModel) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().nestedScroll(collapse)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val visible = (placeable.height + headerOffset.floatValue).roundToInt().coerceIn(0, placeable.height)
                        layout(placeable.width, visible) { placeable.place(0, visible - placeable.height) }
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged {
                            headerHeight = it.height
                            headerOffset.floatValue = headerOffset.floatValue.coerceAtLeast(-it.height.toFloat())
                        }
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Attendance",
                        color = colors.text,
                        fontSize = 1.4.rem,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    TodayHeroCard(
                        ui = ui,
                        onWorkMode = viewModel::setWorkMode,
                        onClock = { action -> haptics.tap(); viewModel.prepare(action, onLocationPermission) },
                        onBreak = { action -> haptics.toggle(); viewModel.breakAction(action) },
                        timer = timer,
                        onRequestModeChange = viewModel::openModeChange,
                    )
                    ui.modeChange?.let { draft ->
                        ModeChangeDialog(
                            draft = draft,
                            onReason = viewModel::updateModeChangeReason,
                            onSubmit = viewModel::submitModeChange,
                            onDismiss = viewModel::dismissModeChange,
                        )
                    }
                    AnimatedVisibility(visible = ui.error != null && !formSheetOpen) {
                        ErrorNotice(
                            ui.error.orEmpty(),
                            onRetry = { refreshPage(viewModel, ui.selectedTab) },
                            onDismiss = viewModel::clearNotice,
                        )
                    }
                }
            }

            PrimaryScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = colors.bg,
                contentColor = colors.primary,
                edgePadding = 8.dp,
                divider = { HorizontalDivider(color = colors.border) },
            ) {
                AttendanceTab.entries.forEach { tab ->
                    LeadingIconTab(
                        selected = pagerState.currentPage == tab.page,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                        icon = { Icon(tabIcon(tab), null, Modifier.size(18.dp)) },
                        selectedContentColor = colors.primary,
                        unselectedContentColor = colors.textSecondary,
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                // Keep-alive: every page stays composed, so scroll positions and filters survive tab switches.
                beyondViewportPageCount = AttendanceTab.entries.size - 1,
                key = { it },
            ) { page ->
                if (page.toString() !in visited.split(',')) {
                    FirstLoadSpinner()
                } else {
                    when (AttendanceTab.fromPage(page)) {
                        AttendanceTab.Overview -> AttendancePageList(loading = ui.loading, onRefresh = viewModel::refresh) {
                            overviewItems(ui, viewModel)
                        }
                        AttendanceTab.Leaves -> LeavesPage(ui, viewModel)
                        AttendanceTab.Manual -> RequestsPage(ui, viewModel)
                        AttendanceTab.Analytics -> InsightsPage(ui, viewModel)
                    }
                }
            }
        }
    }

    DayDetailSheet(ui, viewModel)
}

private fun refreshPage(viewModel: AttendanceViewModel, tab: AttendanceTab) {
    viewModel.clearNotice()
    when (tab) {
        AttendanceTab.Leaves -> viewModel.loadLeavesTab()
        AttendanceTab.Analytics -> viewModel.loadAnalytics()
        else -> viewModel.refresh()
    }
}

/** One pager page: pull-to-refresh (user pulls only) around its own lazy list. */
@Composable
internal fun AttendancePageList(loading: Boolean, onRefresh: () -> Unit, content: LazyListScope.() -> Unit) {
    AinoPullToRefreshBox(loading = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
