package app.aino.mobile.feature.tasks

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * `pages/Tasks.tsx` at ≤480px: page header, tab switcher (Sprint · Backlog ·
 * Service Desk), toolbar buttons, global search, filter bar, then the tab.
 * The kanban stacks its columns (`@media (max-width: 640px)`); a card's status
 * moves through a long-press "Move to" menu instead of desktop drag-and-drop.
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
    PullToRefreshBox(
        isRefreshing = ui.refreshing,
        onRefresh = { viewModel.refresh(pull = true) },
        modifier = Modifier.fillMaxSize().background(colors.bg),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 64.dp),
        ) {
            item { TasksHeader(ui, viewModel, onOpenInsights) }
            if (ui.tab != TaskTab.ServiceDesk) {
                item { GlobalSearch(ui, viewModel, open) }
                if (ui.filtersOpen) item { FilterBar(ui, viewModel) }
            }
            when (ui.tab) {
                TaskTab.Sprint -> sprintTab(ui, viewModel, open)
                TaskTab.Backlog -> backlogTab(ui, viewModel, open)
                TaskTab.ServiceDesk -> item { serviceDesk() }
            }
        }
    }
    ui.inlineComments?.let { thread ->
        val task = (ui.tasks + ui.backlog).firstOrNull { it.id == thread.taskId }
        ModalBottomSheet(
            onDismissRequest = viewModel::closeComments,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.bgElevated,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp), tint = colors.text)
                    Spacer(Modifier.width(6.dp))
                    Text("Comments — ${task?.title.orEmpty()}", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.rem, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Close, "Close", Modifier.size(20.dp).clickable(onClick = viewModel::closeComments), tint = colors.textMuted)
                }
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    CommentSection(
                        taskId = thread.taskId,
                        comments = thread.items,
                        loading = thread.loading,
                        currentUserId = ui.userId,
                        users = ui.assignableUsers,
                        viewModel = viewModel,
                        placeholder = "Write a comment...",
                    )
                }
            }
        }
    }
    ui.confirm?.let { TaskConfirmDialog(it, viewModel::acceptConfirm, viewModel::dismissConfirm) }
}

// ── Header (`TasksHeader.tsx`) ───────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TasksHeader(ui: TaskUiState, viewModel: TaskViewModel, onOpenInsights: () -> Unit) {
    val colors = LocalWebColors.current
    val sprint = ui.currentSprint
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            if (ui.tab == TaskTab.Sprint) {
                FlowRow(verticalArrangement = Arrangement.Center) {
                    Text(
                        "🏃 ${ui.teamName ?: "Team"} — ${sprint?.name ?: "Sprint"}",
                        color = colors.text, fontSize = 1.25.rem, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).rem,
                    )
                    when (sprint?.status) {
                        "active" -> SprintStatusPill("Active", Color(0xFF10B981))
                        "paused" -> SprintStatusPill("Paused", Color(0xFFF59E0B))
                    }
                }
                Text(sprintSubtitle(sprint), color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(top = 4.dp))
            } else {
                val desk = ui.tab == TaskTab.ServiceDesk
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (desk) Icons.Outlined.SupportAgent else Icons.Outlined.Inventory2, null, Modifier.size(18.dp), tint = colors.text)
                    Spacer(Modifier.width(6.dp))
                    Text(if (desk) "Service Desk" else "Backlog", color = colors.text, fontSize = 1.25.rem, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).rem)
                }
                Text(
                    if (desk) "Report bugs, request features, or raise access issues" else "Unscheduled items waiting to be planned",
                    color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Tab switcher fills its row so all three tabs stay visible.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.glass)
                .border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp)),
        ) {
            if (ui.sprintTabVisible) TabButton("Sprint", null, ui.tab == TaskTab.Sprint, null) { viewModel.selectTab(TaskTab.Sprint) }
            TabButton("Backlog", Icons.Outlined.Inventory2, ui.tab == TaskTab.Backlog, ui.backlog.size.takeIf { it > 0 }) { viewModel.selectTab(TaskTab.Backlog) }
            TabButton("Service Desk", Icons.Outlined.SupportAgent, ui.tab == TaskTab.ServiceDesk, null) { viewModel.selectTab(TaskTab.ServiceDesk) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            val half = Modifier.weight(1f)
            if (ui.agileEnabled) WebButton("Insights", onOpenInsights, half, small = true, icon = Icons.Outlined.BarChart)
            if (ui.agileEnabled && ui.tab == TaskTab.Sprint && ui.sprints.size > 1) {
                WebSelect(
                    ui.sprints.map { it.id to (it.name + if (it.status == "active") " (Active)" else "") },
                    ui.selectedSprintId ?: ui.sprints.first().id,
                    viewModel::selectSprint,
                    half,
                )
            }
            if (ui.tab != TaskTab.ServiceDesk) {
                val count = ui.filterCount
                WebButton(if (count > 0) "Filters ($count)" else "Filters", viewModel::toggleFilters, half, small = true, icon = Icons.Outlined.Search, active = count > 0)
            }
            if (ui.tab == TaskTab.Backlog) WebButton("➕ New Ticket", viewModel::toggleBacklogForm, half, small = true)
            if (ui.agileEnabled && ui.tab == TaskTab.Sprint && ui.selectedSprintId != null) {
                WebButton("Import from Backlog", viewModel::toggleImport, half, small = true, icon = Icons.Outlined.Inventory2)
            }
        }
    }
}

@Composable
private fun SprintStatusPill(text: String, color: Color) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 0.7.rem,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.02.rem,
        modifier = Modifier
            .padding(start = 10.dp)
            .background(color.hexAlpha(0x22), RoundedCornerShape(999.dp))
            .border(1.dp, color.hexAlpha(0x55), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, active: Boolean, badge: Int?, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .weight(1f)
            .background(if (active) colors.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (active) Color.White else colors.textMuted
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tint, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                "$badge",
                color = tint,
                fontSize = 0.6.rem,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(99.dp)).padding(horizontal = 5.6.dp, vertical = 0.8.dp),
            )
        }
    }
}

// ── Global search (`useGlobalSearch`) ────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalSearch(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.glass, RoundedCornerShape(6.dp))
                .border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 13.6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(15.dp), tint = colors.text.copy(alpha = 0.6f))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = ui.searchQuery,
                onValueChange = viewModel::setSearch,
                singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 0.88.rem),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.weight(1f).padding(vertical = 10.4.dp).onFocusChanged { if (it.isFocused) viewModel.reopenSearch() },
                decorationBox = { inner ->
                    Box {
                        if (ui.searchQuery.isEmpty()) Text("Search all tasks...", color = colors.textMuted.copy(alpha = 0.6f), fontSize = 0.88.rem)
                        inner()
                    }
                },
            )
            if (ui.searchQuery.isNotEmpty()) {
                Text("✕", color = colors.textMuted.copy(alpha = 0.6f), fontSize = 0.85.rem, modifier = Modifier.clickable {
                    viewModel.setSearch("")
                    viewModel.closeSearch()
                }.padding(4.dp))
            }
        }
        if (ui.searchOpen) {
            Column(
                Modifier
                    .padding(top = 5.6.dp)
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .background(colors.bg, RoundedCornerShape(6.dp))
                    .border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    ui.searching -> SearchStatus("Searching...")
                    ui.searchResults.isEmpty() -> SearchStatus("No results found")
                    else -> ui.searchResults.forEachIndexed { index, task ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.closeSearch()
                                    onOpen(task)
                                }
                                .padding(horizontal = 13.6.dp, vertical = 10.4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TicketId("#${task.id}")
                                Spacer(Modifier.width(8.dp))
                                Text(task.title, color = colors.text, fontSize = 0.88.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FlowRow(
                                Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                StatusBadge(task.status)
                                PriorityBadge(task.priority)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (task.date != null) Icons.Outlined.CalendarMonth else Icons.Outlined.Inventory2, null, Modifier.size(11.dp), tint = colors.textMuted)
                                    Spacer(Modifier.width(3.dp))
                                    Text(task.date?.take(10) ?: "Backlog", color = colors.textMuted, fontSize = 0.68.rem)
                                }
                                task.labels.forEach { LabelPill(it) }
                            }
                        }
                        if (index < ui.searchResults.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchStatus(text: String) {
    Text(text, color = LocalWebColors.current.textMuted, fontSize = 0.82.rem, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

// ── Filter bar ───────────────────────────────────────────────────────────

@Composable
private fun FilterBar(ui: TaskUiState, viewModel: TaskViewModel) {
    val f = ui.filters
    GlassPanel(Modifier.padding(bottom = 16.dp), radius = 6.dp, padding = 9.6.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(7.2.dp)) {
            FilterGroup("Assignee") {
                WebSelect(
                    listOf("" to "All", "me" to "My Tasks") + ui.assignableUsers.map { it.id.toString() to it.display() },
                    f.assignee, { viewModel.setFilters(f.copy(assignee = it)) }, Modifier.fillMaxWidth(), fontSize = 0.8.rem,
                )
            }
            FilterGroup("Label") {
                WebSelect(
                    listOf("" to "All") + ui.labels.map { it.id.toString() to it.name },
                    f.label, { viewModel.setFilters(f.copy(label = it)) }, Modifier.fillMaxWidth(), fontSize = 0.8.rem,
                )
            }
            FilterGroup("Priority") {
                WebSelect(
                    listOf("" to "All") + PRIORITIES.map { it.value to "${it.icon} ${it.label}" },
                    f.priority, { viewModel.setFilters(f.copy(priority = it)) }, Modifier.fillMaxWidth(), fontSize = 0.8.rem,
                )
            }
            if (ui.tab == TaskTab.Sprint) {
                FilterGroup("Status") {
                    WebSelect(
                        listOf("" to "All") + COLUMNS.map { it.id to "${it.icon} ${it.label}" },
                        f.status, { viewModel.setFilters(f.copy(status = it)) }, Modifier.fillMaxWidth(), fontSize = 0.8.rem,
                    )
                }
            }
            if (ui.filterCount > 0) WebButton("✕ Clear", viewModel::clearFilters, Modifier.fillMaxWidth(), small = true)
        }
    }
}

@Composable
private fun FilterGroup(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(label, uppercase = true, fontSize = 0.62.rem)
        content()
    }
}

// ── Sprint tab ───────────────────────────────────────────────────────────

private fun LazyListScope.sprintTab(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    item { SprintProgressCard(ui, viewModel) }
    if (ui.carriedCount > 0) item { CarryBanner(ui.carriedCount) }
    ui.error?.let { item { ErrorMsg(it, Modifier.padding(bottom = 16.dp)) } }
    if (ui.importOpen) item { SprintImportPanel(ui, viewModel) }
    if (ui.sprintLoading) {
        item { WebSpinner() }
        return
    }
    // Columns stack at ≤640px (`.kanban-board { grid-auto-flow: row }`).
    ui.agile.workflowStates.forEach { state ->
        item(key = "col-${state.id}-${state.key}") { KanbanColumn(ui, state, viewModel, onOpen) }
    }
    if (ui.tasks.isEmpty()) item { EmptyState(Icons.Outlined.ListAlt, "No items in this sprint", "Assign tickets from the Backlog to this sprint.") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SprintProgressCard(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val stats = ui.stats
    GlassPanel(Modifier.padding(bottom = 16.dp), radius = 8.dp, padding = 0.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${stats.done}/${stats.total} completed", color = colors.textSecondary, fontSize = 0.85.rem, modifier = Modifier.weight(1f))
                Text("${stats.percent}%", color = colors.primary, fontSize = 1.1.rem, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { stats.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                color = colors.primary,
                trackColor = colors.surfaceHover,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(9.6.dp)) {
                COLUMNS.forEach { col ->
                    Text("${col.icon} ${ui.tasks.count { it.status == col.id }} ${col.label}", color = colors.tone(col.tone), fontSize = 0.72.rem)
                }
            }
            val totals = ui.sprintStats?.totals
            if (ui.agile.features.storyPoints && totals != null && totals.points > 0) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(9.6.dp)) {
                    Text(
                        "📊 ${formatPoints(totals.donePoints)} / ${formatPoints(totals.points)} ${ui.agile.unitLabel} (${totals.percentByPoints}%)",
                        color = colors.textMuted, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold,
                    )
                    if (totals.unestimatedTasks > 0) Text("⚠ ${totals.unestimatedTasks} unestimated", color = Color(0xFFF59E0B), fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold)
                    if (totals.blockedTasks > 0) Text("⛔ ${totals.blockedTasks} blocked", color = Color(0xFFEF4444), fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold)
                }
            }
            ui.currentSprint?.let { sprint ->
                Box(Modifier.padding(top = 10.dp)) { SprintLifecycleControls(ui, sprint, viewModel) }
            }
        }
    }
}

/** `SprintLifecycleControls`: Start (planned) · Complete + rollover (active) · Completed badge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SprintLifecycleControls(ui: TaskUiState, sprint: AvailableSprint, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val canEdit = canManageSprint(ui.role)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            sprint.status == "planned" ->
                WebButton("Start Sprint", viewModel::startSprint, style = BtnStyle.Primary, small = true, enabled = canEdit && !ui.lifecycleBusy, icon = Icons.Outlined.PlayCircle)
            sprint.status == "active" && !ui.completing ->
                WebButton("Complete Sprint", viewModel::beginComplete, style = BtnStyle.Primary, small = true, enabled = canEdit && !ui.lifecycleBusy, icon = Icons.Outlined.CheckCircle)
            sprint.status == "active" -> {
                Text("Roll over incomplete tickets to:", color = colors.textSecondary, fontSize = 0.78.rem)
                WebSelect(
                    listOf("backlog" to "Backlog") + ui.rolloverOptions.map { it.id.toString() to "${it.name} (${it.status})" },
                    ui.rolloverTo, viewModel::setRollover, Modifier.widthIn(min = 140.dp), fontSize = 0.8.rem,
                )
                WebButton("Complete", viewModel::completeSprint, style = BtnStyle.Primary, small = true, enabled = !ui.lifecycleBusy)
                WebButton("Cancel", viewModel::cancelComplete, small = true, enabled = !ui.lifecycleBusy)
            }
            sprint.status == "completed" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(13.dp), tint = colors.success)
                Spacer(Modifier.width(4.dp))
                Text("Completed", color = colors.success, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold)
                sprint.velocityPoints?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("velocity ${formatPoints(it)}", color = colors.textSecondary, fontSize = 0.7.rem)
                }
            }
        }
        ui.lifecycleError?.let { Text(it, color = colors.danger, fontSize = 0.78.rem) }
    }
}

@Composable
private fun CarryBanner(count: Int) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, colors.primary.copy(alpha = 0.30f), RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = 9.6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ArrowCircleDown, null, Modifier.size(14.dp), tint = colors.text)
        Spacer(Modifier.width(5.dp))
        Text("$count incomplete item${if (count > 1) "s" else ""} from yesterday carried forward automatically.", color = colors.text, fontSize = 0.85.rem)
    }
}

/** `SprintImportPanel`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SprintImportPanel(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    GlassPanel(Modifier.padding(bottom = 16.dp), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Inventory2, null, Modifier.size(14.dp), tint = colors.text)
            Spacer(Modifier.width(4.dp))
            Text("Import tickets from Backlog into this sprint", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Close, "Close", Modifier.size(16.dp).clickable(onClick = viewModel::closeImport), tint = colors.textMuted)
        }
        Spacer(Modifier.height(10.dp))
        when {
            ui.backlogLoading -> WebSpinner()
            ui.importable.isEmpty() -> Text("No backlog tickets available to import.", color = colors.textMuted, fontSize = 0.82.rem)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.importable.forEach { task ->
                    val configuring = ui.importTaskId == task.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (configuring) colors.primary.copy(alpha = 0.08f) else colors.surface, RoundedCornerShape(6.dp))
                            .border(1.dp, if (configuring) colors.primary else colors.border, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TicketId("#${task.id}")
                            PriorityBadge(task.priority)
                            Text(task.title, color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.SemiBold)
                            if (!configuring) WebButton("Import", { viewModel.configureImport(task) }, style = BtnStyle.Primary, small = true)
                        }
                        if (configuring) {
                            FieldLabel("Assign to", Icons.Outlined.Person)
                            WebSelect(assigneeOptions(ui.assignableUsers), ui.importAssignedTo, viewModel::setImportAssignee, Modifier.fillMaxWidth())
                            FieldLabel("Due date", Icons.Outlined.CalendarMonth)
                            WebDateField(ui.importDueDate, viewModel::setImportDueDate, Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WebButton("Confirm Import", viewModel::confirmImport, style = BtnStyle.Primary, small = true, icon = Icons.Outlined.Check)
                                WebButton("Cancel", viewModel::cancelImportConfig, small = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** `KanbanBoard` column (stacked). */
@Composable
private fun KanbanColumn(ui: TaskUiState, state: WorkflowState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val columnTasks = tasksForColumn(ui.tasks, state)
    val wip = ui.agile.features.wipLimits && state.wipLimit != null && state.wipLimit > 0
    val exceeded = wip && columnTasks.size > (state.wipLimit ?: 0)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .heightIn(min = 240.dp)
            .background(colors.glass, RoundedCornerShape(8.dp))
            .border(1.dp, if (exceeded) colors.danger else colors.glassBorder, RoundedCornerShape(8.dp))
            .padding(13.6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(hexColor(state.color, colors.textMuted), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (state.isInitial) "New" else state.name).uppercase(),
                color = colors.text, fontSize = 0.82.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.05.rem,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${columnTasks.size}" + if (wip) " / ${state.wipLimit}" else "",
                color = colors.textMuted,
                fontSize = 0.75.rem,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 24.dp)
                    .background(colors.surface, RoundedCornerShape(99.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(99.dp))
                    .padding(horizontal = 8.dp, vertical = 1.6.dp),
            )
        }
        Box(Modifier.padding(top = 10.4.dp, bottom = 13.6.dp).fillMaxWidth().height(1.dp).background(colors.border))
        Column(verticalArrangement = Arrangement.spacedBy(8.8.dp)) {
            if (columnTasks.isEmpty()) {
                Text(
                    "No items",
                    color = colors.textMuted,
                    fontSize = 0.78.rem,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                        .padding(vertical = 19.2.dp, horizontal = 8.dp),
                )
            }
            columnTasks.forEach { task -> TaskCard(task, ui, viewModel, onOpen) }
        }
    }
}

/** `TaskCard`. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(task: Task, ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val done = task.status == "done"
    val due = formatDueDate(task.dueDate)
    val overdue = isDueOverdue(task.dueDate) && !done
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp))
                .combinedClickable(onClick = { onOpen(task) }, onLongClick = { menu = true }, onLongClickLabel = "Move to")
                .padding(13.6.dp)
                .let { if (done) it.alpha(0.6f) else it },
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.6.dp), verticalArrangement = Arrangement.spacedBy(5.6.dp)) {
                    WorkItemTypeBadge(task.workItemTypeId, ui.agile)
                    PriorityBadge(task.priority)
                    StoryPointBadge(task.storyPoints, ui.agile)
                    BlockerBadge(task.isBlocked, ui.agile)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val projectColor = task.project?.color?.let { hexColor(it) }
                    Row(
                        Modifier
                            .widthIn(max = 110.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(projectColor?.hexAlpha(0x22) ?: Color.White.copy(alpha = 0.06f))
                            .border(1.dp, projectColor?.hexAlpha(0x55) ?: colors.border, RoundedCornerShape(5.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(task.displayKey))
                                copied = true
                                Toast.makeText(context, "Copied ${task.displayKey}", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = projectColor ?: colors.textMuted
                        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Click to copy ${task.displayKey}", Modifier.size(11.dp), tint = tint)
                        Spacer(Modifier.width(4.dp))
                        Text(task.displayKey, color = tint, fontSize = 0.66.rem, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.clickable { viewModel.openComments(task.id) }) {
                        Text("💬", fontSize = 0.9.rem)
                        if (task.commentCount > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(8.dp, (-6).dp).size(14.dp).background(colors.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("${task.commentCount}", color = Color.White, fontSize = 0.55.rem, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            Text(
                task.title,
                color = if (done) colors.textMuted else colors.text,
                fontSize = 0.9.rem,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 0.9.rem * 1.4f,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = doneDecoration(done),
            )
            if (task.labels.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 4.8.dp), horizontalArrangement = Arrangement.spacedBy(4.8.dp), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
                    task.labels.forEach { LabelPill(it) }
                }
            }
            task.description?.takeIf(String::isNotBlank)?.let {
                TaskHtml(it, colors.textMuted, 0.75.rem, maxLines = 2, modifier = Modifier.padding(top = 4.8.dp, start = 2.4.dp))
            }
            Spacer(Modifier.weight(1f, fill = false).heightIn(min = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    task.assignee?.let { MetaChip(Icons.Outlined.Person, it.display(), colors.textMuted) }
                    if (task.creator != null && task.assignedTo != null && task.userId != task.assignedTo) {
                        MetaChip(Icons.Outlined.Edit, task.creator.display(), colors.textMuted.copy(alpha = 0.7f), italic = true)
                    }
                    due?.let { MetaChip(Icons.Outlined.CalendarMonth, it, if (overdue) colors.danger else colors.textMuted, bold = overdue) }
                }
                Text("⠿ hold to move", color = colors.textMuted.copy(alpha = 0.5f), fontSize = 0.65.rem, letterSpacing = 0.02.rem)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(colors.bgElevated)) {
            ui.agile.workflowStates.forEach { state ->
                val current = task.status == state.key || (state.id != 0L && task.workflowStateId == state.id)
                DropdownMenuItem(
                    enabled = !current,
                    leadingIcon = { Box(Modifier.size(10.dp).background(hexColor(state.color, colors.textMuted), CircleShape)) },
                    text = { Text(if (state.isInitial) "New" else state.name, color = if (current) colors.textMuted else colors.text) },
                    onClick = {
                        menu = false
                        viewModel.requestMove(task, state)
                    },
                )
            }
        }
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, italic: Boolean = false, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = color)
        Spacer(Modifier.width(3.dp))
        Text(
            text, color = color, fontSize = 0.7.rem, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = colors.textMuted)
        Spacer(Modifier.height(8.dp))
        Text(title, color = colors.textSecondary, fontSize = 1.1.rem, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, color = colors.textMuted, fontSize = 0.85.rem, textAlign = TextAlign.Center)
    }
}

// ── Backlog tab (`BacklogTab.tsx`) ───────────────────────────────────────

private fun LazyListScope.backlogTab(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    ui.error?.let { item { ErrorMsg(it, Modifier.padding(bottom = 16.dp)) } }
    val loaded = !ui.backlogLoading && ui.backlog.isNotEmpty()
    if (loaded) {
        item { BacklogSummaryBar(ui, viewModel) }
        item { BacklogToolbar(ui, viewModel) }
    }
    if (ui.backlogFormOpen) item { BacklogForm(ui, viewModel) }
    if (ui.backlogLoading) {
        item { WebSpinner() }
        return
    }
    if (ui.backlog.isEmpty()) {
        item { EmptyState(Icons.Outlined.Inventory2, "Backlog is empty", "Create a ticket to organize work that doesn't have a scheduled date yet.") }
        return
    }
    item { PaginationBar(ui, viewModel) }
    items(ui.sortedBacklog, key = { "b-${it.id}" }) { task -> BacklogCard(task, ui, viewModel, onOpen) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BacklogSummaryBar(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    GlassPanel(Modifier.padding(bottom = 12.dp), padding = 8.dp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.4.dp), verticalArrangement = Arrangement.spacedBy(6.4.dp)) {
            SummaryChip(
                "${ui.backlogSummary.total.takeIf { it > 0 } ?: ui.backlog.size}", "Total", colors.primary,
                active = ui.filters.priority.isEmpty(), onClick = viewModel::summaryTotal,
            )
            PRIORITIES.forEach { p ->
                SummaryChip(
                    "${ui.backlogSummary.byPriority[p.value] ?: 0}", "${p.icon} ${p.label}", colors.tone(p.tone),
                    active = ui.filters.priority == p.value, onClick = { viewModel.summaryPriority(p.value) },
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(value: String, label: String, accent: Color, active: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(if (active) 2.dp else 1.dp, if (active) accent else colors.glassBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.8.dp, vertical = 4.8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, color = accent, fontSize = 0.86.rem, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(5.6.dp))
        Text(label, color = colors.textMuted, fontSize = 0.66.rem, maxLines = 1)
    }
}

@Composable
private fun BacklogToolbar(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort by", color = colors.textMuted, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            WebSelect(BacklogSort.entries.map { it to it.label }, ui.backlogSort, viewModel::setSort, Modifier.weight(1f), fontSize = 0.78.rem)
        }
        Text("${ui.backlog.size} ticket${if (ui.backlog.size != 1) "s" else ""}", color = colors.textMuted, fontSize = 0.78.rem)
    }
}

/** `components/common/Pagination` with the backlog's 10/25/50/100 page sizes. */
@Composable
private fun PaginationBar(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val total = ui.backlogTotal.takeIf { it > 0 } ?: ui.backlog.size
    val limit = maxOf(1, ui.backlogLimit)
    val page = ui.backlogOffset / limit + 1
    val pages = maxOf(1, (total + limit - 1) / limit)
    fun go(p: Int) = viewModel.setPage((p.coerceIn(1, pages) - 1) * limit)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(paginationLabel(total, limit, ui.backlogOffset, "ticket"), color = colors.textSecondary, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Rows", color = colors.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.width(2.dp))
            WebSelect(listOf(10, 25, 50, 100).map { it to "$it" }, limit, viewModel::setPageSize, Modifier.width(72.dp), fontSize = 0.75.rem)
            Spacer(Modifier.width(8.dp))
            PagerButton(Icons.Outlined.FastRewind, "First page", page > 1) { go(1) }
            PagerButton(Icons.Outlined.ChevronLeft, "Previous page", page > 1) { go(page - 1) }
            Text("Page $page of $pages", color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
            PagerButton(Icons.Outlined.ChevronRight, "Next page", page < pages) { go(page + 1) }
            PagerButton(Icons.Outlined.FastForward, "Last page", page < pages) { go(pages) }
        }
    }
}

@Composable
private fun PagerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(14.dp), tint = colors.text.copy(alpha = if (enabled) 1f else 0.4f)) }
}

/** New Backlog Ticket form. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BacklogForm(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val d = ui.draft
    GlassPanel(Modifier.padding(bottom = 24.dp), padding = 24.dp) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp), tint = colors.text)
            Spacer(Modifier.width(5.dp))
            Text("New Backlog Ticket", color = colors.text, fontSize = 1.rem, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Close, "Close", Modifier.size(14.dp).clickable(onClick = viewModel::closeBacklogForm), tint = colors.textMuted)
        }
        WebTextField(d.title, { v -> viewModel.updateDraft { it.copy(title = v) } }, "Ticket title...", maxLength = 200)
        Spacer(Modifier.height(20.dp))
        WebTextField(d.description, { v -> viewModel.updateDraft { it.copy(description = v) } }, "Description (optional)", singleLine = false, minLines = 3)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                FieldLabel("Assign to", Icons.Outlined.Person)
                WebSelect(assigneeOptions(ui.assignableUsers), d.assignedTo, { v -> viewModel.updateDraft { it.copy(assignedTo = v) } }, Modifier.fillMaxWidth())
            }
            Column {
                FieldLabel("Due date", Icons.Outlined.CalendarMonth)
                WebDateField(d.dueDate, { v -> viewModel.updateDraft { it.copy(dueDate = v) } }, Modifier.fillMaxWidth())
            }
            if (ui.sprints.isNotEmpty()) {
                Column {
                    FieldLabel("🏃 Sprint")
                    WebSelect(sprintOptions(ui.sprints), d.sprintId, viewModel::setDraftSprint, Modifier.fillMaxWidth())
                }
            }
            LabelSelector(ui.labels, d.labels) { id ->
                viewModel.updateDraft { it.copy(labels = if (id in it.labels) it.labels - id else it.labels + id) }
            }
            Column {
                FieldLabel("Type")
                WebSelect(typeOptions(ui.agile), d.workItemTypeId, { v -> viewModel.updateDraft { it.copy(workItemTypeId = v) } }, Modifier.fillMaxWidth())
            }
            if (ui.projects.isNotEmpty()) {
                Column {
                    FieldLabel("Project", Icons.Outlined.Folder)
                    WebSelect(projectOptions(ui.projects), d.projectId, { v -> viewModel.updateDraft { it.copy(projectId = v) } }, Modifier.fillMaxWidth())
                }
            }
            StoryPointPicker(d.storyPoints, { v -> viewModel.updateDraft { it.copy(storyPoints = v) } }, ui.agile)
            Row(horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
                PRIORITIES.forEach { p ->
                    val active = d.priority == p.value
                    val tint = colors.tone(p.tone)
                    Text(
                        "${p.icon} ${p.label}",
                        color = if (active) colors.text else colors.textSecondary,
                        fontSize = 0.8.rem,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) tint.copy(alpha = 0.15f) else colors.surface)
                            .border(1.dp, if (active) tint else colors.border, RoundedCornerShape(8.dp))
                            .clickable { viewModel.updateDraft { it.copy(priority = p.value) } }
                            .padding(6.4.dp),
                    )
                }
            }
            WebButton("Create Ticket", viewModel::submitBacklog, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = d.title.isNotBlank())
        }
    }
}

/** A `.backlog-card` at ≤768px: 3px priority bar on top, body, footer, actions row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BacklogCard(task: Task, ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val pri = priorityOf(task.priority)
    val done = task.status == "done"
    val due = formatDueDate(task.dueDate)
    val overdue = isDueOverdue(task.dueDate) && !done
    val preview = stripHtml(task.description)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.glass)
            .border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp))
            .clickable { onOpen(task) }
            .let { if (done) it.alpha(0.55f) else it },
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.tone(pri.tone)))
        Column(Modifier.padding(horizontal = 9.6.dp, vertical = 7.2.dp), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TicketId(task.displayKey)
                StatusBadge(task.status)
                PriorityBadge(task.priority)
                WorkItemTypeBadge(task.workItemTypeId, ui.agile)
                StoryPointBadge(task.storyPoints, ui.agile)
                BlockerBadge(task.isBlocked, ui.agile)
                task.labels.forEach { LabelPill(it) }
            }
            Text(
                task.title, color = colors.text, fontSize = 0.84.rem, fontWeight = FontWeight.SemiBold, lineHeight = 0.84.rem * 1.35f,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = doneDecoration(done),
            )
            if (preview.isNotBlank()) Text(preview, color = colors.textMuted, fontSize = 0.75.rem, lineHeight = 0.75.rem * 1.35f, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 2.4.dp).fillMaxWidth().height(1.dp).background(colors.glassBorder))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                task.assignee?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(a.display(), avatarPath(a.avatar), 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(a.display(), color = colors.textMuted, fontSize = 0.72.rem, maxLines = 1)
                    }
                }
                due?.let { MetaChip(Icons.Outlined.CalendarMonth, it, if (overdue) colors.danger else colors.textMuted, bold = overdue) }
                if (task.commentCount > 0) MetaChip(Icons.Outlined.ChatBubbleOutline, "${task.commentCount}", colors.textMuted)
                Text(formatRelativeTime(task.createdAt), color = colors.textMuted.copy(alpha = 0.7f), fontSize = 0.68.rem)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (ui.scheduleTaskId == task.id) {
                    WebDateField(ui.scheduleDate, viewModel::setScheduleDate, Modifier.weight(1f), clearable = false)
                    Spacer(Modifier.width(6.dp))
                    WebButton("Go", { viewModel.schedule(task.id, task.title) }, style = BtnStyle.Primary, small = true)
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(colors.surface).border(1.dp, colors.border, RoundedCornerShape(6.dp))
                            .clickable(onClick = viewModel::cancelSchedule).padding(6.dp),
                    ) { Icon(Icons.Outlined.Close, "Cancel", Modifier.size(14.dp), tint = colors.text) }
                } else {
                    WebButton("Schedule", { viewModel.startSchedule(task.id) }, small = true, icon = Icons.Outlined.CalendarMonth)
                }
            }
        }
    }
}
