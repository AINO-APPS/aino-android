package app.aino.mobile.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/*
 * Backlog — a grooming queue:
 *   independent quick chips; secondary filters and view options
 *   grouped list (sticky, collapsible sprint / backlog sections)
 *   swipe right → schedule · swipe left → add to sprint · long-press → actions
 *   "New ticket" FAB → create sheet
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BacklogPage(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var scheduling by remember { mutableStateOf<Task?>(null) }
    var sprintFor by remember { mutableStateOf<Task?>(null) }
    var actionsFor by remember { mutableStateOf<Task?>(null) }
    val list = rememberLazyListState()

    TaskPage(loading = ui.backlogLoading || ui.refreshing, onRefresh = { viewModel.refresh(pull = true) }) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            item(key = "controls") { BacklogControls(ui, viewModel) }
            // "Plan sprint" is the only way into the split planner: the Sprint
            // tab is the board (ADR-015).
            if (ui.agileEnabled) {
                item(key = "plan-sprint") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton("Plan sprint", viewModel::openSprintPlan, tonal = true)
                    }
                }
            }
            ui.error?.let { item(key = "error") { ErrorMsg(it, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) } }

            when {
                ui.backlogLoading && ui.backlog.isEmpty() -> items(4, key = { "sk-$it" }) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { TaskCardSkeleton() }
                }
                ui.backlog.isEmpty() -> item(key = "empty") {
                    if (ui.filterCount > 0) {
                        TaskEmptyState(HeroIcons.MagnifyingGlass, "No tickets match your filters", "Try removing a filter or two to widen the results.", action = "Clear filters", onAction = viewModel::clearFilters)
                    } else {
                        TaskEmptyState(HeroIcons.ArchiveBox, "Backlog is empty", "Capture work that isn't scheduled yet — plan it into a sprint later.", action = "Create first ticket", onAction = { viewModel.openSheet(TaskSheet.CreateTicket) })
                    }
                }
                else -> {
                    val row: @Composable (Task) -> Unit = { task ->
                        BacklogRow(
                            task, ui, viewModel, onOpen,
                            onSchedule = { scheduling = task },
                            onSprint = { sprintFor = task },
                            onActions = { actionsFor = task },
                        )
                    }
                    // Stranded sprint tickets render in their own warning group
                    // *after* the ordinary backlog rows — never instead of them.
                    val stranded = ui.backlogGroups.orEmpty()
                    val grouped = stranded.flatMap { group -> group.tasks.map { it.id } }.toSet()
                    items(ui.sortedBacklog.filterNot { it.id in grouped }, key = { "b-${it.id}" }) { row(it) }
                    stranded.forEach { group ->
                        val isCollapsed = group.key in collapsed
                        stickyHeader(key = "g-${group.key}") {
                            BacklogGroupHeader(group, isCollapsed) {
                                collapsed = if (isCollapsed) collapsed - group.key else collapsed + group.key
                            }
                        }
                        if (!isCollapsed) items(group.tasks, key = { "b-${it.id}" }) { row(it) }
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            when {
                                ui.backlogLoadingMore -> CircularProgressIndicator(Modifier.size(22.dp), color = colors.primary, strokeWidth = 2.dp)
                                ui.backlogHasMore -> PillButton("Load more", viewModel::loadMoreBacklog, tonal = true)
                                else -> Text("That's everything", color = colors.textMuted, fontSize = 0.74.rem)
                            }
                        }
                    }
                }
            }

            item(key = "completed") { CompletedSection(ui, viewModel, onOpen) }
        }

        // Bulk actions are backlog-only (D23); the bar floats above the list.
        BulkActionBar(ui, viewModel, Modifier.align(Alignment.BottomCenter))
        }
    }

    scheduling?.let { task -> ScheduleDialog(task, onDismiss = { scheduling = null }) { date -> viewModel.schedule(task.id, date) } }
    sprintFor?.let { task ->
        SelectSheet(
            "Add to sprint",
            ui.sprints.map { SelectOption<Long?>(it.id, it.name, "${formatDate(it.startDate)} – ${formatDate(it.endDate)} · ${it.status}", color = if (it.status == "active") colors.success else colors.primaryLight) },
            setOf(task.sprintId),
            onDismiss = { sprintFor = null },
            onSelect = { ids -> ids.firstOrNull()?.let { viewModel.assignSprint(task, it) } },
            icon = HeroIcons.RocketLaunch,
        )
    }
    actionsFor?.let { task ->
        BacklogActionsSheet(task, ui, viewModel, onOpen = { onOpen(task) }, onSchedule = { scheduling = task }, onSprint = { sprintFor = task }, onDismiss = { actionsFor = null })
    }
}

/** Priority chips + sort, then active filter chips. */
@Composable
private fun BacklogControls(ui: TaskUiState, viewModel: TaskViewModel) {
    val quick = ui.quickFilters
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        ChipStrip {
            CountChip("All", quick.isAll, { viewModel.setQuickFilters(BacklogQuickFilters()) })
            CountChip("Mine", quick.mine, { viewModel.setQuickFilters(quick.copy(mine = !quick.mine)) })
            CountChip("High", quick.high, { viewModel.setQuickFilters(quick.copy(high = !quick.high)) })
            CountChip("Unscheduled", quick.unscheduled, { viewModel.setQuickFilters(quick.copy(unscheduled = !quick.unscheduled)) })
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BacklogViewControls(ui: TaskUiState, viewModel: TaskViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SortMenu(ui.backlogSort, viewModel::setSort)
        DensityToggle(ui, viewModel)
        if (ui.epics.isNotEmpty()) EpicFilterChip(ui, viewModel)
    }
    ActiveFilterChips(ui, viewModel)
}

/** Comfortable ⇄ Compact. The choice persists per user, shared with web. */
@Composable
private fun DensityToggle(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val compact = ui.compact
    Row(
        Modifier
            .clip(ChipShape)
            .clickable(
                onClickLabel = if (compact) "Switch to comfortable cards" else "Switch to compact cards",
                role = androidx.compose.ui.semantics.Role.Switch,
                onClick = { viewModel.setCardDensity(!compact) },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (compact) HeroIcons.Bars3BottomLeft else HeroIcons.QueueList,
            null,
            Modifier.size(15.dp),
            tint = colors.textMuted,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (compact) "Compact" else "Comfortable",
            color = colors.textSecondary,
            fontSize = 0.76.rem,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Narrows the backlog to one epic's children; "All" clears it. */
@Composable
private fun EpicFilterChip(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    val selected = ui.epics.firstOrNull { it.id == ui.selectedEpicId }
    Box {
        Row(
            Modifier
                .clip(ChipShape)
                .clickable(
                    onClickLabel = "Filter by epic, currently ${selected?.title ?: "all"}",
                    role = androidx.compose.ui.semantics.Role.DropdownList,
                ) { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HeroIcons.Square3Stack3d, null, Modifier.size(15.dp), tint = colors.textMuted)
            Spacer(Modifier.width(6.dp))
            Text(
                selected?.title ?: "All epics",
                color = colors.textSecondary,
                fontSize = 0.76.rem,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Icon(HeroIcons.ChevronDown, null, Modifier.size(16.dp), tint = colors.textMuted)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.bgElevated),
        ) {
            DropdownMenuItem(
                text = { Text("All epics", color = colors.text, fontSize = 0.84.rem) },
                onClick = { viewModel.selectEpic(null); open = false },
            )
            ui.epics.forEach { epic ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(epic.title, color = colors.text, fontSize = 0.84.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${epic.doneChildren}/${epic.totalChildren} done · ${epic.percentByPoints}%",
                                color = colors.textMuted,
                                fontSize = 0.7.rem,
                            )
                        }
                    },
                    onClick = { viewModel.selectEpic(epic.id); open = false },
                )
            }
        }
    }
}

/** Text-button style "⇅ Priority ▾" trigger with a checked menu. */
@Composable
private fun SortMenu(selected: BacklogSort, onSelect: (BacklogSort) -> Unit) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(ChipShape)
                .clickable(onClickLabel = "Sort, currently ${selected.label}", role = androidx.compose.ui.semantics.Role.DropdownList) { open = true }
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HeroIcons.ArrowsRightLeft, null, Modifier.size(15.dp).rotate(90f), tint = colors.textMuted)
            Spacer(Modifier.width(6.dp))
            Text(selected.label, color = colors.textSecondary, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(HeroIcons.ChevronDown, null, Modifier.size(16.dp), tint = colors.textMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(colors.bgElevated)) {
            Text("Sort by", color = colors.textMuted, fontSize = 0.7.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            BacklogSort.entries.forEach { sort ->
                val isSelected = sort == selected
                DropdownMenuItem(
                    text = { Text(sort.label, color = if (isSelected) colors.primary else colors.text, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = if (isSelected) ({ Icon(HeroIcons.Check, null, Modifier.size(16.dp), tint = colors.primary) }) else null,
                    onClick = { open = false; onSelect(sort) },
                )
            }
        }
    }
}

@Composable
private fun BacklogGroupHeader(group: BacklogGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = LocalWebColors.current
    val accent = group.tone?.let { colors.tone(it) } ?: colors.textMuted
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .clickable(onClickLabel = if (collapsed) "Expand ${group.label}" else "Collapse ${group.label}", onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(group.label.uppercase(), color = colors.textSecondary, fontSize = 0.72.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.06.rem)
        Spacer(Modifier.width(8.dp))
        Text(
            "${group.tasks.size}",
            color = accent,
            fontSize = 0.68.rem,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(accent.copy(alpha = 0.14f), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
        )
        // Points alongside the count, so a group's weight is visible before opening it.
        val points = group.tasks.sumOf { it.storyPoints ?: 0.0 }
        if (points > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "${formatPoints(points)} pts",
                color = colors.textMuted,
                fontSize = 0.68.rem,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(HeroIcons.ChevronDown, null, Modifier.size(16.dp).rotate(if (collapsed) -90f else 0f), tint = colors.textMuted)
    }
}

/** Swipe right = schedule; swipe left = add to sprint (when sprints exist). Both snap back and open a picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BacklogRow(
    task: Task,
    ui: TaskUiState,
    viewModel: TaskViewModel,
    onOpen: (Task) -> Unit,
    onSchedule: () -> Unit,
    onSprint: () -> Unit,
    onActions: () -> Unit,
) {
    val colors = LocalWebColors.current
    val canSprint = ui.agileEnabled && ui.sprints.isNotEmpty()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onSchedule()
                SwipeToDismissBoxValue.EndToStart -> if (canSprint) onSprint()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // never dismiss — the row stays until the server confirms
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .semantics {
                customActions = buildList {
                    add(CustomAccessibilityAction("Select") { viewModel.toggleSelection(task.id); true })
                    add(CustomAccessibilityAction("Schedule") { onSchedule(); true })
                    if (canSprint) add(CustomAccessibilityAction("Add to sprint") { onSprint(); true })
                    add(CustomAccessibilityAction("More actions") { onActions(); true })
                }
            },
        enableDismissFromEndToStart = canSprint,
        backgroundContent = {
            val direction = state.dismissDirection
            if (direction == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val toSprint = direction == SwipeToDismissBoxValue.EndToStart
            val tint = if (toSprint) colors.success else colors.primary
            Row(
                Modifier.fillMaxSize().clip(CardShape).background(tint.copy(alpha = 0.18f).compositeOver(colors.bg)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toSprint) Arrangement.End else Arrangement.Start,
            ) {
                Icon(if (toSprint) HeroIcons.RocketLaunch else HeroIcons.CalendarDays, null, Modifier.size(20.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(if (toSprint) "Add to sprint" else "Schedule", color = tint, fontSize = 0.86.rem, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        TaskCardM3(
            task, ui.agile,
            onOpen = { if (ui.hasSelection) viewModel.toggleSelection(task.id) else onOpen(task) },
            highlighted = task.id in ui.recentlyUpdated,
            showStatus = true,
            // Long-press starts selection; the checkbox only appears once selecting.
            onLongPress = { viewModel.toggleSelection(task.id) },
            longPressLabel = "Select",
            onComments = { viewModel.openComments(task.id) },
            compact = ui.compact,
            selected = task.id in ui.selectedIds,
            attribution = ui.attribution[task.id],
            leading = if (ui.hasSelection) {
                { SelectionToggle(task.id in ui.selectedIds) { viewModel.toggleSelection(task.id) } }
            } else null,
            trailing = if (!ui.compact) ({
                Row(
                    Modifier
                        .clip(ChipShape)
                        .background(colors.primary.copy(alpha = 0.12f))
                        .clickable(onClickLabel = "Schedule", onClick = onSchedule)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HeroIcons.CalendarDays, null, Modifier.size(14.dp), tint = colors.primaryLight)
                    Spacer(Modifier.width(4.dp))
                    Text("Schedule", color = colors.primaryLight, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }) else null,
            // Long-press selects; the overflow keeps the row actions one tap away.
            topTrailing = {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "More actions", onClick = onActions),
                    contentAlignment = Alignment.Center,
                ) { Icon(HeroIcons.EllipsisVertical, null, Modifier.size(18.dp), tint = colors.textMuted) }
            },
        )
    }
}

@Composable
private fun BacklogActionsSheet(
    task: Task,
    ui: TaskUiState,
    viewModel: TaskViewModel,
    onOpen: () -> Unit,
    onSchedule: () -> Unit,
    onSprint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalWebColors.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    TaskSheetScaffold(task.displayKey, onDismiss) {
        Text(task.title, color = colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
        ActionRow(HeroIcons.ArrowTopRightOnSquare, "Open details") { onDismiss(); onOpen() }
        ActionRow(HeroIcons.CalendarDays, "Schedule to a day") { onDismiss(); onSchedule() }
        if (ui.agileEnabled && ui.sprints.isNotEmpty()) ActionRow(HeroIcons.RocketLaunch, "Add to sprint") { onDismiss(); onSprint() }
        ActionRow(HeroIcons.ChatBubbleOvalLeft, "Comments" + if (task.commentCount > 0) " (${task.commentCount})" else "") { onDismiss(); viewModel.openComments(task.id) }
        ActionRow(HeroIcons.DocumentDuplicate, "Copy ${task.displayKey}") {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(task.displayKey))
            onDismiss()
        }
        // Manual reorder: the server computes the rank from the neighbours, so
                // the client never invents a position (D50). `rankNeighbours` brackets
                // the moved row; see its doc for why the pair order matters.
                if (ui.backlogSort == BacklogSort.Rank) {
                    val ids = ui.sortedBacklog.map { it.id }
                    rankNeighbours(ids, task.id, up = true)?.let { (before, after) ->
                        ActionRow(HeroIcons.ChevronUp, "Move up") {
                            onDismiss()
                            viewModel.reorder(task.id, before, after)
                        }
                    }
                    rankNeighbours(ids, task.id, up = false)?.let { (before, after) ->
                        ActionRow(HeroIcons.ChevronDown, "Move down") {
                            onDismiss()
                            viewModel.reorder(task.id, before, after)
                        }
                    }
                }
        if (ui.userId != null && ui.userId == task.userId) {
            ActionRow(HeroIcons.Trash, "Delete", danger = true) { onDismiss(); viewModel.requestDelete(task) }
        }
    }
}

/** M3 date picker; returns `YYYY-MM-DD`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDialog(task: Task, title: String = "Schedule \"${task.title}\"", initial: String? = null, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val colors = LocalWebColors.current
    val start = localDateOf(initial) ?: java.time.LocalDate.now()
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    onPick(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
                }
                onDismiss()
            }) { Text("Schedule", color = colors.primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    ) {
        androidx.compose.material3.DatePicker(
            state = state,
            title = { Text(title, color = colors.textSecondary, fontSize = 0.86.rem, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
        )
    }
}

// ── Create ticket sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateTicketSheet(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val d = ui.draft
    var picker by remember { mutableStateOf<String?>(null) }
    TaskSheetScaffold("New ticket", viewModel::closeSheet, HeroIcons.Plus) {
        Column(
            Modifier.weight(1f, fill = false).heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WebTextField(d.title, { v -> viewModel.updateDraft { it.copy(title = v) } }, "What needs to be done?", maxLength = 200, fontSize = 1.rem)
            WebTextField(d.description, { v -> viewModel.updateDraft { it.copy(description = v) } }, "Add a description (optional)", singleLine = false, minLines = 3)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRIORITIES.forEach { p ->
                        CountChip(p.label, d.priority == p.value, { viewModel.updateDraft { it.copy(priority = p.value) } }, accent = colors.tone(p.tone), dot = true)
                    }
                }
            }
            SectionCard(padding = 6.dp) {
                PropertyRow(HeroIcons.User, "Assignee", { picker = "assignee" }) {
                    PropertyText(ui.assignableUsers.firstOrNull { it.id == d.assignedTo }?.display() ?: "Unassigned", muted = d.assignedTo == null)
                }
                Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HeroIcons.CalendarDays, null, Modifier.size(18.dp), tint = colors.textMuted)
                    Spacer(Modifier.width(12.dp))
                    Text("Due date", color = colors.textSecondary, fontSize = 0.84.rem, modifier = Modifier.width(96.dp))
                    WebDateField(d.dueDate, { v -> viewModel.updateDraft { it.copy(dueDate = v) } }, Modifier.weight(1f))
                }
                val types = ui.agile.workItemTypes.filter { it.id != 0L }
                if (types.isNotEmpty()) {
                    PropertyRow(HeroIcons.Square3Stack3d, "Type", { picker = "type" }) {
                        PropertyText(ui.agile.type(d.workItemTypeId)?.name ?: "Default", muted = d.workItemTypeId == null)
                    }
                }
                // Only the browsed team's sprints: a ticket is created into the
                // team whose backlog is on screen, so a foreign sprint would be
                // rejected (and the ticket would leave this list anyway).
                if (ui.agileEnabled && ui.teamSprints.isNotEmpty()) {
                    PropertyRow(HeroIcons.RocketLaunch, "Sprint", { picker = "sprint" }) {
                        PropertyText(ui.teamSprints.firstOrNull { it.id == d.sprintId }?.name ?: "Backlog (no sprint)", muted = d.sprintId == null)
                    }
                }
                if (ui.projects.isNotEmpty()) {
                    PropertyRow(HeroIcons.Folder, "Project", { picker = "project" }) {
                        PropertyText(ui.projects.firstOrNull { it.id == d.projectId }?.let { "${it.key} · ${it.name}" } ?: "No project", muted = d.projectId == null)
                    }
                }
                if (ui.labels.isNotEmpty()) {
                    PropertyRow(HeroIcons.Tag, "Labels", { picker = "labels" }) {
                        val chosen = ui.labels.filter { it.id in d.labels }
                        if (chosen.isEmpty()) PropertyText("None", muted = true)
                        else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { chosen.forEach { LabelPill(it) } }
                    }
                }
            }
            if (ui.agile.features.storyPoints) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Estimate (${ui.agile.unitLabel})")
                    StoryPointPicker(d.storyPoints, { v -> viewModel.updateDraft { it.copy(storyPoints = v) } }, ui.agile)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Cancel", viewModel::closeSheet, Modifier.weight(1f))
            PillButton(if (ui.creating) "Creating…" else "Create ticket", viewModel::submitBacklog, Modifier.weight(1f), primary = true, enabled = d.title.isNotBlank() && !ui.creating)
        }
    }
    TaskPickers(
        picker = picker,
        ui = ui,
        assignee = d.assignedTo,
        typeId = d.workItemTypeId,
        sprintId = d.sprintId,
        projectId = d.projectId,
        labels = d.labels,
        onDismiss = { picker = null },
        onAssignee = { v -> viewModel.updateDraft { it.copy(assignedTo = v) } },
        onType = { v -> viewModel.updateDraft { it.copy(workItemTypeId = v) } },
        onSprint = viewModel::setDraftSprint,
        onProject = { v -> viewModel.updateDraft { it.copy(projectId = v) } },
        onLabels = { v -> viewModel.updateDraft { it.copy(labels = v) } },
    )
}

/** Shared picker sheets for assignee / type / sprint / project / labels. */
@Composable
internal fun TaskPickers(
    picker: String?,
    ui: TaskUiState,
    assignee: Long?,
    typeId: Long?,
    sprintId: Long?,
    projectId: Long?,
    labels: List<Long>,
    onDismiss: () -> Unit,
    onAssignee: (Long?) -> Unit,
    onType: (Long?) -> Unit,
    onSprint: (Long?) -> Unit,
    onProject: (Long?) -> Unit,
    onLabels: (List<Long>) -> Unit,
) {
    val colors = LocalWebColors.current
    when (picker) {
        "assignee" -> SelectSheet(
            "Assignee",
            listOf(SelectOption<Long?>(null, "Unassigned")) + ui.assignableUsers.map {
                SelectOption<Long?>(it.id, it.display(), it.username?.let { u -> "@$u" }, avatar = it.display() to avatarPath(it.avatar))
            },
            setOf(assignee), onDismiss, { onAssignee(it.firstOrNull()) }, icon = HeroIcons.User,
        )
        "type" -> SelectSheet(
            "Work item type",
            listOf(SelectOption<Long?>(null, "Default")) + ui.agile.workItemTypes.filter { it.id != 0L }.map { SelectOption<Long?>(it.id, it.name, color = hexColor(it.color)) },
            setOf(typeId), onDismiss, { onType(it.firstOrNull()) }, icon = HeroIcons.Square3Stack3d,
        )
        "sprint" -> SelectSheet(
            "Sprint",
            listOf(SelectOption<Long?>(null, "Backlog (no sprint)")) + ui.teamSprints.map {
                SelectOption<Long?>(it.id, it.name, "${formatDate(it.startDate)} – ${formatDate(it.endDate)} · ${it.status}", color = if (it.status == "active") colors.success else colors.primaryLight)
            },
            setOf(sprintId), onDismiss, { onSprint(it.firstOrNull()) }, icon = HeroIcons.RocketLaunch,
        )
        "project" -> SelectSheet(
            "Project",
            listOf(SelectOption<Long?>(null, "No project")) + ui.projects.map { SelectOption<Long?>(it.id, it.name, it.key, color = it.color?.let { c -> hexColor(c) }) },
            setOf(projectId), onDismiss, { onProject(it.firstOrNull()) }, icon = HeroIcons.Folder,
        )
        "labels" -> SelectSheet(
            "Labels",
            ui.labels.map { SelectOption(it.id, it.name, color = hexColor(it.color)) },
            labels.toSet(), onDismiss, { onLabels(it.toList()) }, multi = true, icon = HeroIcons.Tag,
        )
    }
}
