package app.aino.mobile.feature.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoPullToRefreshBox
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.launch

internal fun tabIcon(tab: TaskTab): ImageVector = when (tab) {
    TaskTab.Sprint -> HeroIcons.RocketLaunch
    TaskTab.Backlog -> HeroIcons.ArchiveBox
    TaskTab.Scheduled -> HeroIcons.CalendarDays
    TaskTab.ServiceDesk -> HeroIcons.Lifebuoy
}

internal fun tabLabel(tab: TaskTab): String = when (tab) {
    TaskTab.Sprint -> "Sprint"
    TaskTab.Backlog -> "Backlog"
    TaskTab.Scheduled -> "Scheduled"
    TaskTab.ServiceDesk -> "Service Desk"
}

/**
 * Tasks page — mobile-first: a compact header (title, search, filters,
 * overflow), tabs (Sprint · Backlog · Service Desk) synced to a swipeable
 * pager, then the tab body with its own scroll state and pull-to-refresh.
 * Search opens a full-page overlay; filters, create, import and the sprint
 * picker are bottom sheets; results and Undo arrive as snackbars. Deep links
 * (`tasks?tab=&sprint_id=&task=`) select the tab/sprint/task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: TaskViewModel,
    onOpenDetail: () -> Unit,
    onOpenInsights: () -> Unit,
    serviceDesk: @Composable () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val open: (Task) -> Unit = { task ->
        viewModel.openDetail(task)
        onOpenDetail()
    }

    // ---- Pager <-> tab ----------------------------------------------------
    val tabs = ui.visibleTabs
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(ui.tab).coerceAtLeast(0)) { tabs.size }
    var animatingTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ui.tab, tabs) {
        // A deep-linked Sprint tab may precede the sprint list; wait rather than snapping to page 0.
        val target = tabs.indexOf(ui.tab).takeIf { it >= 0 } ?: return@LaunchedEffect
        if (pagerState.currentPage == target && !pagerState.isScrollInProgress) return@LaunchedEffect
        animatingTo = target
        try { pagerState.animateScrollToPage(target) } finally { if (animatingTo == target) animatingTo = null }
    }
    LaunchedEffect(pagerState, tabs) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val tab = tabs.getOrNull(page) ?: return@collect
            val current = viewModel.ui.value.tab
            if (animatingTo == null && current in tabs && tab != current) viewModel.selectTab(tab)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(
                visible = ui.tab == TaskTab.Backlog && !ui.searchOpen,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.openSheet(TaskSheet.CreateTicket) },
                    icon = { Icon(HeroIcons.Plus, null, Modifier.size(18.dp)) },
                    text = { Text("New ticket", fontWeight = FontWeight.SemiBold) },
                    containerColor = colors.primary,
                    contentColor = colors.onAccent,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TasksHeader(ui, viewModel, onOpenInsights)
            if (tabs.size > 1) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex),
                    containerColor = colors.bg,
                    edgePadding = 8.dp,
                    contentColor = colors.primary,
                    divider = { HorizontalDivider(color = colors.border) },
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val badge = when (tab) { TaskTab.Backlog -> ui.backlogTotal.takeIf { it > 0 }; TaskTab.Scheduled -> ui.scheduledOverdue.takeIf { it > 0 }; else -> null }
                        LeadingIconTab(
                            selected = pagerState.currentPage == index,
                            onClick = { viewModel.selectTab(tab) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tabLabel(tab), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (badge != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (badge > 999) "999+" else "$badge",
                                            fontSize = 0.66.rem,
                                            fontWeight = FontWeight.Bold,
                                            color = if (tab == TaskTab.Scheduled) colors.danger else androidx.compose.ui.graphics.Color.Unspecified,
                                            modifier = Modifier.background((if (tab == TaskTab.Scheduled) colors.danger else colors.primary).copy(alpha = 0.16f), CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            },
                            icon = { Icon(tabIcon(tab), null, Modifier.size(18.dp)) },
                            selectedContentColor = colors.primary,
                            unselectedContentColor = colors.textSecondary,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 0,
                    key = { tabs.getOrNull(it)?.key ?: it },
                    userScrollEnabled = true,
                ) { page ->
                    when (tabs.getOrNull(page)) {
                        TaskTab.Sprint -> SprintBoard(ui, viewModel, open)
                        TaskTab.Backlog -> BacklogPage(ui, viewModel, open)
                        TaskTab.Scheduled -> ScheduledPage(ui, viewModel, open)
                        TaskTab.ServiceDesk -> serviceDesk()
                        null -> Unit
                    }
                }
                if (ui.searchOpen) SearchOverlay(ui, viewModel, open)
            }
        }
    }

    when (ui.sheet) {
        TaskSheet.Filters -> FilterSheet(ui, viewModel)
        TaskSheet.CreateTicket -> CreateTicketSheet(ui, viewModel)
        TaskSheet.Import -> ImportSheet(ui, viewModel)
        TaskSheet.SprintPicker -> SprintPickerSheet(ui, viewModel)
        null -> Unit
    }
    ui.inlineComments?.let { thread ->
        val task = (ui.tasks + ui.backlog).firstOrNull { it.id == thread.taskId }
        TaskSheetScaffold("Comments", viewModel::closeComments, HeroIcons.ChatBubbleOvalLeft) {
            task?.let {
                Text(it.title, color = colors.textSecondary, fontSize = 0.84.rem, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
            }
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                CommentSection(
                    taskId = thread.taskId,
                    comments = thread.items,
                    loading = thread.loading,
                    currentUserId = ui.userId,
                    users = ui.assignableUsers,
                    viewModel = viewModel,
                    placeholder = "Write a comment…",
                )
            }
        }
    }
    ui.confirm?.let { TaskConfirmDialog(it, viewModel::acceptConfirm, viewModel::dismissConfirm) }
}

// ── Header ───────────────────────────────────────────────────────────────

@Composable
private fun TasksHeader(ui: TaskUiState, viewModel: TaskViewModel, onOpenInsights: () -> Unit) {
    val colors = LocalWebColors.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tasks", color = colors.text, fontSize = 1.4.rem, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
            Text(headerSubtitle(ui), color = colors.textMuted, fontSize = 0.78.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIconButton(HeroIcons.MagnifyingGlass, "Search tasks", viewModel::openSearch)
        if (ui.tab != TaskTab.ServiceDesk) {
            Spacer(Modifier.width(8.dp))
            RoundIconButton(HeroIcons.Bars3BottomLeft, "Filters", { viewModel.openSheet(TaskSheet.Filters) }, badge = ui.filterCount, active = ui.filterCount > 0)
        }
        Spacer(Modifier.width(8.dp))
        Box {
            RoundIconButton(HeroIcons.EllipsisVertical, "More options", { menu = true })
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(colors.bgElevated)) {
                if (ui.agileEnabled) {
                    MenuItem(HeroIcons.ChartBar, "Sprint insights") { menu = false; onOpenInsights() }
                }
                if (ui.agileEnabled && ui.tab == TaskTab.Sprint && ui.selectedSprintId != null) {
                    MenuItem(HeroIcons.ArchiveBoxArrowDown, "Import from backlog") { menu = false; viewModel.openSheet(TaskSheet.Import) }
                }
                MenuItem(HeroIcons.ArrowPath, "Refresh") { menu = false; viewModel.refresh(pull = true) }
            }
        }
    }
}

@Composable
internal fun MenuItem(icon: ImageVector, text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    val tint = when {
        !enabled -> colors.textMuted
        danger -> colors.danger
        else -> colors.text
    }
    androidx.compose.material3.DropdownMenuItem(
        enabled = enabled,
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = tint) },
        text = { Text(text, color = tint) },
        onClick = onClick,
    )
}

private fun headerSubtitle(ui: TaskUiState): String = when (ui.tab) {
    TaskTab.Sprint -> ui.currentSprint?.let { sp ->
        val team = ui.teamName?.let { "$it · " }.orEmpty()
        team + sp.name + if (sp.status == "active") " · ${sprintDaysLeft(sp.endDate)}d left" else " · ${sp.status.replaceFirstChar(Char::uppercase)}"
    } ?: "Sprint board"
    TaskTab.Backlog -> {
        val total = ui.backlogTotal.takeIf { it > 0 } ?: ui.backlog.size
        if (total > 0) "$total unscheduled ticket${if (total == 1) "" else "s"}" else "Unscheduled work waiting to be planned"
    }
    TaskTab.Scheduled -> {
        val overdue = ui.scheduledOverdue
        val upcoming = ui.scheduled.count { t -> localDateOf(t.date)?.isBefore(java.time.LocalDate.now()) == false }
        listOfNotNull("$upcoming upcoming", overdue.takeIf { it > 0 }?.let { "$it overdue" }).joinToString(" · ")
    }
    TaskTab.ServiceDesk -> "Report bugs, request features, or raise access issues"
}

/** Removable chips for each active filter, plus "Clear all". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActiveFilterChips(ui: TaskUiState, viewModel: TaskViewModel, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    val f = ui.filters
    val chips = buildList<Pair<String, () -> Unit>> {
        if (f.assignee.isNotEmpty()) {
            val who = if (f.assignee == "me") "Me" else ui.assignableUsers.firstOrNull { it.id.toString() == f.assignee }?.display() ?: "User"
            add("Assignee: $who" to { viewModel.setFilters(f.copy(assignee = "")) })
        }
        if (f.label.isNotEmpty()) {
            val name = ui.labels.firstOrNull { it.id.toString() == f.label }?.name ?: "Label"
            add("Label: $name" to { viewModel.setFilters(f.copy(label = "")) })
        }
        if (f.priority.isNotEmpty()) add("Priority: ${priorityOf(f.priority).label}" to { viewModel.setFilters(f.copy(priority = "")) })
        if ((ui.tab == TaskTab.Sprint || ui.tab == TaskTab.Scheduled) && f.status.isNotEmpty()) {
            val name = ui.agile.workflowStates.firstOrNull { it.key == f.status }?.let(::stateLabel) ?: columnOf(f.status).label
            add("Status: $name" to { viewModel.setFilters(f.copy(status = "")) })
        }
        if (f.search.isNotBlank()) add("“${f.search.trim()}”" to { viewModel.setFilters(f.copy(search = "")) })
    }
    if (chips.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, onRemove) -> RemovableChip(label, onRemove) }
        Text(
            "Clear all",
            color = colors.textMuted,
            fontSize = 0.76.rem,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).clickable(onClick = viewModel::clearFilters).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// ── Search overlay (`useGlobalSearch`) ───────────────────────────────────

@Composable
private fun SearchOverlay(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = viewModel::closeSearch)
    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Close search", onClick = viewModel::closeSearch), contentAlignment = Alignment.Center) {
                Icon(HeroIcons.ArrowLeft, "Close search", Modifier.size(20.dp), tint = colors.text)
            }
            Spacer(Modifier.width(6.dp))
            SearchField(
                ui.searchQuery, viewModel::setSearch, "Search all tasks by title or key…",
                Modifier.weight(1f).focusRequester(focus),
            )
        }
        HorizontalDivider(color = colors.border)
        val q = ui.searchQuery.trim()
        when {
            q.length < 2 -> TaskEmptyState(HeroIcons.MagnifyingGlass, "Search every task", "Type at least two characters — sprint, backlog and scheduled tasks are all searched.")
            ui.searching && ui.searchResults.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { WebSpinner() }
            ui.searchResults.isEmpty() -> TaskEmptyState(HeroIcons.MagnifyingGlass, "No results", "Nothing matches “$q”.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("${ui.searchResults.size} result${if (ui.searchResults.size == 1) "" else "s"}", color = colors.textMuted, fontSize = 0.76.rem)
                }
                items(ui.searchResults, key = { "s-${it.id}" }) { task ->
                    TaskCardM3(
                        task, ui.agile,
                        onOpen = { onOpen(task) },
                        showStatus = true,
                        trailing = {
                            Spacer(Modifier.width(6.dp))
                            MetaChip(
                                if (task.date != null) HeroIcons.CalendarDays else if (task.sprintId != null) HeroIcons.RocketLaunch else HeroIcons.ArchiveBox,
                                task.date?.take(10) ?: if (task.sprintId != null) "Sprint" else "Backlog",
                                colors.textMuted,
                            )
                        },
                    )
                }
            }
        }
    }
}

// ── Filters sheet (shared by Sprint and Backlog) ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val f = ui.filters
    var picker by remember { mutableStateOf<String?>(null) }
    TaskSheetScaffold(when (ui.tab) { TaskTab.Sprint -> "Filter sprint"; TaskTab.Scheduled -> "Filter scheduled"; else -> "Filter backlog" }, viewModel::closeSheet, HeroIcons.Bars3BottomLeft) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Assignee")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountChip("Anyone", f.assignee.isEmpty(), { viewModel.setFilters(f.copy(assignee = "")) })
                    CountChip("Me", f.assignee == "me", { viewModel.setFilters(f.copy(assignee = if (f.assignee == "me") "" else "me")) })
                    val other = f.assignee.takeIf { it.isNotEmpty() && it != "me" }
                    val name = other?.let { id -> ui.assignableUsers.firstOrNull { it.id.toString() == id }?.display() }
                    CountChip(name ?: "Someone else…", other != null, { picker = "assignee" })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Priority")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountChip("All", f.priority.isEmpty(), { viewModel.setFilters(f.copy(priority = "")) })
                    PRIORITIES.forEach { p ->
                        CountChip(p.label, f.priority == p.value, { viewModel.setFilters(f.copy(priority = if (f.priority == p.value) "" else p.value)) }, accent = colors.tone(p.tone), dot = true)
                    }
                }
            }
            if (ui.tab == TaskTab.Sprint || ui.tab == TaskTab.Scheduled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Status")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountChip("All", f.status.isEmpty(), { viewModel.setFilters(f.copy(status = "")) })
                        // The server only filters on the four legacy keys.
                        COLUMNS.forEach { c ->
                            val state = ui.agile.workflowStates.firstOrNull { it.key == c.id }
                            CountChip(
                                state?.let(::stateLabel) ?: c.label, f.status == c.id,
                                { viewModel.setFilters(f.copy(status = if (f.status == c.id) "" else c.id)) },
                                accent = hexColor(state?.color, colors.tone(c.tone)), dot = true,
                            )
                        }
                    }
                }
            }
            if (ui.labels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Label")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountChip("Any", f.label.isEmpty(), { viewModel.setFilters(f.copy(label = "")) })
                        ui.labels.forEach { l ->
                            val id = l.id.toString()
                            CountChip(l.name, f.label == id, { viewModel.setFilters(f.copy(label = if (f.label == id) "" else id)) }, accent = hexColor(l.color), dot = true)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Text")
                var text by remember { mutableStateOf(f.search) }
                LaunchedEffect(text) {
                    kotlinx.coroutines.delay(400)
                    if (text != viewModel.ui.value.filters.search) viewModel.setFilters(viewModel.ui.value.filters.copy(search = text))
                }
                SearchField(text, { text = it }, "Title or description contains…")
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Clear all", viewModel::clearFilters, Modifier.weight(1f), enabled = ui.filterCount > 0)
            PillButton("Show results", viewModel::closeSheet, Modifier.weight(1f), primary = true)
        }
    }
    if (picker == "assignee") {
        SelectSheet(
            "Assignee",
            ui.assignableUsers.map { SelectOption(it.id.toString(), it.display(), it.username?.let { u -> "@$u" }, avatar = it.display() to avatarPath(it.avatar)) },
            setOfNotNull(f.assignee.takeIf { it.isNotEmpty() && it != "me" }),
            onDismiss = { picker = null },
            onSelect = { viewModel.setFilters(f.copy(assignee = it.firstOrNull().orEmpty())) },
            icon = HeroIcons.User,
        )
    }
}

// ── Sprint picker sheet ──────────────────────────────────────────────────

@Composable
private fun SprintPickerSheet(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    TaskSheetScaffold("Choose sprint", viewModel::closeSheet, HeroIcons.RocketLaunch) {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.sprints, key = { it.id }) { sp ->
                val selected = sp.id == ui.selectedSprintId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(if (selected) colors.primary.copy(alpha = 0.10f) else colors.surface)
                        .clickable { viewModel.selectSprint(sp.id) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sp.name, color = colors.text, fontSize = 0.94.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            SprintStatusPill(sp.status)
                        }
                        Text(
                            "${formatDate(sp.startDate)} – ${formatDate(sp.endDate)}" + (sp.teamName?.let { " · $it" } ?: ""),
                            color = colors.textMuted, fontSize = 0.76.rem, maxLines = 1,
                        )
                    }
                    if (selected) Icon(HeroIcons.Check, "Selected", Modifier.size(18.dp), tint = colors.primary)
                }
            }
        }
    }
}

@Composable
internal fun SprintStatusPill(status: String) {
    val colors = LocalWebColors.current
    val (label, tint) = when (status) {
        "active" -> "Active" to colors.success
        "paused" -> "Paused" to colors.warning
        "completed" -> "Completed" to colors.textMuted
        else -> "Planned" to colors.primaryLight
    }
    Text(
        label,
        color = tint,
        fontSize = 0.66.rem,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(tint.copy(alpha = 0.14f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ── Import sheet (`SprintImportPanel`) ───────────────────────────────────

@Composable
private fun ImportSheet(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    var picker by remember { mutableStateOf(false) }
    TaskSheetScaffold("Import from backlog", viewModel::closeSheet, HeroIcons.ArchiveBoxArrowDown) {
        Text("Add backlog tickets to ${ui.currentSprint?.name ?: "this sprint"}.", color = colors.textMuted, fontSize = 0.82.rem, modifier = Modifier.padding(bottom = 12.dp))
        when {
            ui.backlogLoading && ui.importable.isEmpty() -> WebSpinner()
            ui.importable.isEmpty() -> TaskEmptyState(HeroIcons.ArchiveBox, "Nothing to import", "Every open backlog ticket is already planned.")
            else -> LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.importable, key = { "imp-${it.id}" }) { task ->
                    val configuring = ui.importTaskId == task.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(if (configuring) colors.primary.copy(alpha = 0.08f) else colors.surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PriorityGlyph(task.priority)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(task.displayKey, color = colors.textMuted, fontSize = 0.72.rem)
                                Text(task.title, color = colors.text, fontSize = 0.9.rem, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (!configuring) PillButton("Add", { viewModel.configureImport(task) }, tonal = true, icon = HeroIcons.Plus)
                        }
                        if (configuring) {
                            PropertyRow(HeroIcons.User, "Assignee", { picker = true }) {
                                PropertyText(ui.assignableUsers.firstOrNull { it.id == ui.importAssignedTo }?.display() ?: "Unassigned", muted = ui.importAssignedTo == null)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(HeroIcons.CalendarDays, null, Modifier.size(18.dp), tint = colors.textMuted)
                                Spacer(Modifier.width(12.dp))
                                Text("Due", color = colors.textSecondary, fontSize = 0.84.rem, modifier = Modifier.width(96.dp))
                                WebDateField(ui.importDueDate, viewModel::setImportDueDate, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton("Cancel", viewModel::cancelImportConfig, Modifier.weight(1f))
                                PillButton("Add to sprint", viewModel::confirmImport, Modifier.weight(1f), primary = true, icon = HeroIcons.Check)
                            }
                        }
                    }
                }
            }
        }
    }
    if (picker) {
        SelectSheet(
            "Assign to",
            listOf(SelectOption<Long?>(null, "Unassigned")) + ui.assignableUsers.map { SelectOption<Long?>(it.id, it.display(), it.username?.let { u -> "@$u" }, avatar = it.display() to avatarPath(it.avatar)) },
            setOf(ui.importAssignedTo),
            onDismiss = { picker = false },
            onSelect = { viewModel.setImportAssignee(it.firstOrNull()) },
            icon = HeroIcons.User,
        )
    }
}

/** One pager page: pull-to-refresh around its own scrollable content. */
@Composable
internal fun TaskPage(loading: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    AinoPullToRefreshBox(loading = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { content() }
}

/** Horizontal chip strip that scrolls instead of wrapping; edge padding scrolls with the content. */
@Composable
internal fun ChipStrip(modifier: Modifier = Modifier, endPadding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
