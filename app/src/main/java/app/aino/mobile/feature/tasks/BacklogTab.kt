package app.aino.mobile.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/*
 * Backlog tab — a grooming queue laid out content-first:
 *
 *   heading (title · live stats · Insights)
 *   ── sticky toolbar: search + Filters · priority chips + sort · active filter chips
 *   grouped list (priority bands / due buckets, collapsible) of compact cards
 *   compact pager
 *   FAB → "New ticket" bottom sheet;  Filters → bottom sheet
 */

// ── Heading ──────────────────────────────────────────────────────────────

/** Title row + a live summary line ("42 unscheduled · 5 overdue · 3 unassigned"). */
@Composable
internal fun BacklogHeading(ui: TaskUiState, onOpenInsights: () -> Unit) {
    val colors = LocalWebColors.current
    val total = ui.backlogTotal.takeIf { it > 0 } ?: ui.backlog.size
    val overdue = ui.backlog.count { it.status != "done" && isDueOverdue(it.dueDate) }
    val unassigned = ui.backlog.count { it.assignedTo == null && it.assignee == null }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HeroIcons.ArchiveBox, null, Modifier.size(18.dp), tint = colors.text)
                Spacer(Modifier.width(6.dp))
                Text("Backlog", color = colors.text, fontSize = 1.25.rem, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).rem)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.backlogLoading && ui.backlog.isEmpty()) {
                    Text("Unscheduled items waiting to be planned", color = colors.textMuted, fontSize = 0.8.rem)
                } else {
                    StatText("$total", "unscheduled", colors.textSecondary)
                    if (overdue > 0) {
                        StatDot()
                        StatText("$overdue", "overdue", colors.danger)
                    }
                    if (unassigned > 0) {
                        StatDot()
                        StatText("$unassigned", "unassigned", colors.textSecondary)
                    }
                }
            }
        }
        if (ui.agileEnabled) IconAction(HeroIcons.ChartBar, "Insights", onOpenInsights)
    }
}

@Composable
private fun StatText(value: String, label: String, valueColor: Color) {
    val colors = LocalWebColors.current
    Text(value, color = valueColor, fontSize = 0.8.rem, fontWeight = FontWeight.Bold)
    Spacer(Modifier.width(3.dp))
    Text(label, color = colors.textMuted, fontSize = 0.8.rem, maxLines = 1)
}

@Composable
private fun StatDot() {
    Text("·", color = LocalWebColors.current.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(horizontal = 6.dp))
}

/** A 36dp bordered square icon button with an optional count badge. */
@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, badge: Int? = null, active: Boolean = false) {
    val colors = LocalWebColors.current
    Box {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) colors.primary.copy(alpha = 0.15f) else colors.surface)
                .border(1.dp, if (active) colors.primary else colors.border, RoundedCornerShape(8.dp))
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(17.dp), tint = if (active) colors.primary else colors.text) }
        if (badge != null && badge > 0) {
            Text(
                "$badge",
                color = Color.White,
                fontSize = 0.6.rem,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(min = 16.dp)
                    .background(colors.primary, CircleShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

// ── Tab body ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.backlogTab(
    ui: TaskUiState,
    viewModel: TaskViewModel,
    onOpen: (Task) -> Unit,
    collapsed: Set<String>,
    onToggleGroup: (String) -> Unit,
) {
    stickyHeader(key = "backlog-controls") { BacklogControls(ui, viewModel, onOpen) }
    ui.error?.let { item { ErrorMsg(it, Modifier.padding(bottom = 12.dp)) } }

    if (ui.backlogLoading && ui.backlog.isEmpty()) {
        items(4, key = { "skeleton-$it" }) { BacklogCardSkeleton() }
        return
    }
    if (ui.backlog.isEmpty()) {
        item {
            if (ui.filterCount > 0) {
                BacklogEmpty(
                    HeroIcons.MagnifyingGlass, "No tickets match your filters", "Try removing a filter or two to widen the results.",
                    "Clear filters", viewModel::clearFilters,
                )
            } else {
                BacklogEmpty(
                    HeroIcons.ArchiveBox, "Backlog is empty", "Capture work that isn't scheduled yet — plan it into a sprint later.",
                    "Create first ticket", viewModel::toggleBacklogForm,
                )
            }
        }
        return
    }

    val groups = ui.backlogGroups
    if (groups == null) {
        items(ui.sortedBacklog, key = { "b-${it.id}" }) { task -> BacklogCard(task, ui, viewModel, onOpen) }
    } else {
        groups.forEach { group ->
            val isCollapsed = group.key in collapsed
            item(key = "g-${group.key}") { BacklogGroupHeader(group, isCollapsed) { onToggleGroup(group.key) } }
            if (!isCollapsed) items(group.tasks, key = { "b-${it.id}" }) { task -> BacklogCard(task, ui, viewModel, onOpen) }
        }
    }
    item(key = "backlog-pager") { BacklogPager(ui, viewModel) }
}


// ── Sticky toolbar ───────────────────────────────────────────────────────

@Composable
private fun BacklogControls(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg) // opaque so cards scroll under it cleanly
            .padding(top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { GlobalSearch(ui, viewModel, onOpen, bottomPadding = 0.dp) }
            IconAction(HeroIcons.Bars3BottomLeft, "Filters", viewModel::openFilterSheet, badge = ui.filterCount, active = ui.filterCount > 0)
        }
        PriorityAndSortRow(ui, viewModel)
        ActiveFilterChips(ui, viewModel)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
    }
}

/** One horizontally scrolling row of All / High / Medium / Low counts, then the sort menu. */
@Composable
private fun PriorityAndSortRow(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val all = ui.backlogSummary.total.takeIf { it > 0 } ?: ui.backlogTotal.takeIf { it > 0 } ?: ui.backlog.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PriorityChip("All", "$all", colors.primary, ui.filters.priority.isEmpty(), viewModel::summaryTotal)
            PRIORITIES.forEach { p ->
                PriorityChip(
                    p.label, "${ui.backlogSummary.byPriority[p.value] ?: 0}", colors.tone(p.tone),
                    ui.filters.priority == p.value, { viewModel.summaryPriority(p.value) }, dot = true,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        SortMenu(ui.backlogSort, viewModel::setSort)
    }
}

@Composable
private fun PriorityChip(label: String, count: String, accent: Color, active: Boolean, onClick: () -> Unit, dot: Boolean = false) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.16f) else colors.surface)
            .border(1.dp, if (active) accent else colors.glassBorder, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = if (active) colors.text else colors.textSecondary, fontSize = 0.74.rem, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Text(count, color = if (active) accent else colors.textMuted, fontSize = 0.74.rem, fontWeight = FontWeight.Bold)
    }
}

/** Compact "⇅ Priority ▾" sort trigger with a checked menu. */
@Composable
private fun SortMenu(selected: BacklogSort, onSelect: (BacklogSort) -> Unit) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Sort", role = Role.DropdownList) { open = true }
                .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HeroIcons.ArrowsRightLeft, null, Modifier.size(13.dp).rotate(90f), tint = colors.textMuted)
            Spacer(Modifier.width(4.dp))
            Text(selected.label, color = colors.text, fontSize = 0.74.rem, fontWeight = FontWeight.Medium, maxLines = 1)
            Icon(HeroIcons.ChevronDown, null, Modifier.size(15.dp), tint = colors.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(colors.bgElevated)) {
            Text("Sort by", color = colors.textMuted, fontSize = 0.68.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            BacklogSort.entries.forEach { sort ->
                val isSelected = sort == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            sort.label,
                            color = if (isSelected) colors.primary else colors.text,
                            fontSize = 0.9.rem,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (isSelected) ({ Icon(HeroIcons.Check, null, Modifier.size(16.dp), tint = colors.primary) }) else null,
                    onClick = {
                        open = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}


/** Removable chips for each active filter, plus "Clear all". Hidden when nothing is set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilterChips(ui: TaskUiState, viewModel: TaskViewModel) {
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
        if (f.search.isNotBlank()) add("“${f.search.trim()}”" to { viewModel.setFilters(f.copy(search = "")) })
    }
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, onRemove) ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.primary.copy(alpha = 0.12f))
                    .clickable(onClickLabel = "Remove filter", onClick = onRemove)
                    .padding(start = 8.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label, color = colors.primaryLight, fontSize = 0.72.rem, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
                )
                Spacer(Modifier.width(3.dp))
                Icon(HeroIcons.XMark, "Remove", Modifier.size(12.dp), tint = colors.primaryLight)
            }
        }
        Text(
            "Clear all",
            color = colors.textMuted,
            fontSize = 0.72.rem,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = viewModel::clearFilters).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

// ── Groups ───────────────────────────────────────────────────────────────

@Composable
private fun BacklogGroupHeader(group: BacklogGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = LocalWebColors.current
    val accent = group.tone?.let { colors.tone(it) } ?: colors.textMuted
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = if (collapsed) "Expand" else "Collapse", onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(group.label.uppercase(), color = colors.textSecondary, fontSize = 0.7.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.06.rem)
        Spacer(Modifier.width(8.dp))
        Text(
            "${group.tasks.size}",
            color = accent,
            fontSize = 0.66.rem,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(accent.copy(alpha = 0.14f), RoundedCornerShape(99.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
        )
        Spacer(Modifier.weight(1f))
        Icon(HeroIcons.ChevronDown, null, Modifier.size(16.dp).rotate(if (collapsed) -90f else 0f), tint = colors.textMuted)
    }
}


// ── Card ─────────────────────────────────────────────────────────────────

/** Compact backlog card: left priority rail, quiet meta line, title, preview, labels, footer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BacklogCard(task: Task, ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val pri = priorityOf(task.priority)
    val done = task.status == "done"
    val preview = stripHtml(task.description).replace('\n', ' ').trim()
    val type = ui.agile.type(task.workItemTypeId)
    val points = if (ui.agile.features.storyPoints) formatPoints(task.storyPoints) else ""
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(colors.glass)
            .border(1.dp, colors.glassBorder, shape)
            .clickable { onOpen(task) }
            .let { if (done) it.alpha(0.6f) else it },
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(colors.tone(pri.tone)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Line 1 — key · type · points … status (only when not To Do) · blocked
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    TicketId(task.displayKey)
                    if (type != null) {
                        MetaSep()
                        Box(Modifier.size(6.dp).background(hexColor(type.color), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(type.name, color = colors.textMuted, fontSize = 0.72.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (points.isNotEmpty()) {
                        MetaSep()
                        Text("$points pt${if (points == "1") "" else "s"}", color = colors.textMuted, fontSize = 0.72.rem, maxLines = 1)
                    }
                }
                if (task.status != "pending") {
                    Spacer(Modifier.width(6.dp))
                    StatusBadge(task.status)
                }
                if (task.isBlocked && ui.agile.features.blockers) {
                    Spacer(Modifier.width(6.dp))
                    BlockerBadge(true, ui.agile)
                }
            }
            Text(
                task.title,
                color = colors.text,
                fontSize = 0.9.rem,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 0.9.rem * 1.3f,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = doneDecoration(done),
            )
            if (preview.isNotEmpty()) {
                Text(preview, color = colors.textMuted, fontSize = 0.76.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (task.labels.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    task.labels.take(2).forEach { LabelPill(it) }
                    if (task.labels.size > 2) {
                        Text("+${task.labels.size - 2}", color = colors.textMuted, fontSize = 0.68.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 1.dp))
                    }
                }
            }
            if (ui.scheduleTaskId == task.id) ScheduleRow(ui, viewModel, task) else CardFooter(task, done) { viewModel.startSchedule(task.id) }
        }
    }
}


/** Footer: assignee (or Unassigned) · due · comments · age … Schedule. */
@Composable
private fun CardFooter(task: Task, done: Boolean, onSchedule: () -> Unit) {
    val colors = LocalWebColors.current
    val due = formatDueDate(task.dueDate)
    val overdue = isDueOverdue(task.dueDate) && !done
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val assignee = task.assignee
            if (assignee != null) {
                Row(Modifier.widthIn(max = 130.dp), verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(assignee.display(), avatarPath(assignee.avatar), 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(assignee.display(), color = colors.textSecondary, fontSize = 0.72.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                MetaChip(HeroIcons.User, "Unassigned", colors.textMuted.copy(alpha = 0.8f), italic = true)
            }
            due?.let { MetaChip(HeroIcons.CalendarDays, it, if (overdue) colors.danger else colors.textMuted, bold = overdue) }
            if (task.commentCount > 0) MetaChip(HeroIcons.ChatBubbleOvalLeft, "${task.commentCount}", colors.textMuted)
            if (due == null) {
                formatRelativeTime(task.createdAt).takeIf(String::isNotEmpty)?.let { MetaChip(HeroIcons.Clock, it, colors.textMuted.copy(alpha = 0.7f)) }
            }
        }
        Spacer(Modifier.width(6.dp))
        ScheduleButton(onSchedule)
    }
}

@Composable
private fun MetaSep() {
    Text("·", color = LocalWebColors.current.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(horizontal = 5.dp))
}

/** Small "Schedule" pill on the card footer. */
@Composable
private fun ScheduleButton(onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Schedule", role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HeroIcons.CalendarDays, null, Modifier.size(13.dp), tint = colors.primaryLight)
        Spacer(Modifier.width(4.dp))
        Text("Schedule", color = colors.primaryLight, fontSize = 0.7.rem, fontWeight = FontWeight.SemiBold)
    }
}

/** Inline date picker that replaces the footer while scheduling. */
@Composable
private fun ScheduleRow(ui: TaskUiState, viewModel: TaskViewModel, task: Task) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .background(colors.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Schedule to a day", color = colors.textSecondary, fontSize = 0.7.rem, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WebDateField(ui.scheduleDate, viewModel::setScheduleDate, Modifier.weight(1f), clearable = false)
            Spacer(Modifier.width(6.dp))
            WebButton("Schedule", { viewModel.schedule(task.id, task.title) }, style = BtnStyle.Primary, small = true)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Cancel", onClick = viewModel::cancelSchedule)
                    .padding(6.dp),
            ) { Icon(HeroIcons.XMark, "Cancel", Modifier.size(14.dp), tint = colors.text) }
        }
    }
}


/** Static placeholder mirroring the card's shape while the first page loads. */
@Composable
private fun BacklogCardSkeleton() {
    val colors = LocalWebColors.current
    val block = colors.surfaceHover
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.glass)
            .border(1.dp, colors.glassBorder, RoundedCornerShape(10.dp)),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(block))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(90.dp).height(10.dp).background(block, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(0.85f).height(14.dp).background(block, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(0.55f).height(10.dp).background(block, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun BacklogEmpty(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(colors.surfaceHover, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = colors.textMuted)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = colors.text, fontSize = 1.rem, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, color = colors.textMuted, fontSize = 0.82.rem, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        WebButton(action, onAction, style = BtnStyle.Primary)
    }
}

// ── Pager ────────────────────────────────────────────────────────────────

/** Bottom pager: range label, ‹ Page x of y ›, and a page-size menu. */
@Composable
private fun BacklogPager(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val total = ui.backlogTotal.takeIf { it > 0 } ?: ui.backlog.size
    val limit = maxOf(1, ui.backlogLimit)
    val page = ui.backlogOffset / limit + 1
    val pages = maxOf(1, (total + limit - 1) / limit)
    fun go(p: Int) = viewModel.setPage((p.coerceIn(1, pages) - 1) * limit)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(paginationLabel(total, limit, ui.backlogOffset, "ticket"), color = colors.textMuted, fontSize = 0.72.rem)
        // Only worth showing controls when there is more than a minimal page.
        if (pages > 1 || total > 10) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pages > 3) PagerIcon(HeroIcons.ChevronDoubleLeft, "First page", page > 1) { go(1) }
                PagerIcon(HeroIcons.ChevronLeft, "Previous page", page > 1) { go(page - 1) }
                Text("Page $page of $pages", color = colors.textSecondary, fontSize = 0.76.rem, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp))
                PagerIcon(HeroIcons.ChevronRight, "Next page", page < pages) { go(page + 1) }
                if (pages > 3) PagerIcon(HeroIcons.ChevronDoubleRight, "Last page", page < pages) { go(pages) }
                Spacer(Modifier.width(8.dp))
                WebSelect(listOf(10, 25, 50, 100).map { it to "$it / page" }, limit, viewModel::setPageSize, Modifier.width(104.dp), fontSize = 0.72.rem)
            }
        }
    }
}

@Composable
private fun PagerIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(15.dp), tint = colors.text.copy(alpha = if (enabled) 1f else 0.35f)) }
}

// ── FAB ──────────────────────────────────────────────────────────────────

@Composable
internal fun NewTicketFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.primary)
            .clickable(onClickLabel = "New ticket", role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HeroIcons.Plus, null, Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text("New ticket", color = Color.White, fontSize = 0.88.rem, fontWeight = FontWeight.SemiBold)
    }
}


// ── Sheets ───────────────────────────────────────────────────────────────

@Composable
private fun SheetHeader(icon: ImageVector, title: String, onClose: () -> Unit) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.text)
        Spacer(Modifier.width(8.dp))
        Text(title, color = colors.text, fontSize = 1.05.rem, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "Close", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Icon(HeroIcons.XMark, "Close", Modifier.size(18.dp), tint = colors.textMuted) }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title.uppercase(), color = colors.textMuted, fontSize = 0.66.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.08.rem)
        content()
    }
}

/** Filters sheet (Assignee · Label · Priority) with Clear all / Show results. */
@Composable
internal fun BacklogFilterSheet(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val f = ui.filters
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SheetHeader(HeroIcons.Bars3BottomLeft, "Filter backlog", viewModel::closeFilterSheet)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FilterGroup("Assignee") {
                WebSelect(
                    listOf("" to "Anyone", "me" to "My tasks") + ui.assignableUsers.map { it.id.toString() to it.display() },
                    f.assignee, { viewModel.setFilters(f.copy(assignee = it)) }, Modifier.fillMaxWidth(),
                )
            }
            FilterGroup("Label") {
                WebSelect(
                    listOf("" to "Any label") + ui.labels.map { it.id.toString() to it.name },
                    f.label, { viewModel.setFilters(f.copy(label = it)) }, Modifier.fillMaxWidth(),
                )
            }
            FilterGroup("Priority") {
                PrioritySegments(f.priority, allowAll = true) { viewModel.setFilters(f.copy(priority = it)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WebButton("Clear all", viewModel::clearFilters, Modifier.weight(1f), enabled = ui.filterCount > 0)
            WebButton("Show results", viewModel::closeFilterSheet, Modifier.weight(1f), style = BtnStyle.Primary)
        }
        if (ui.backlogLoading) {
            Text("Updating…", color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
        }
    }
}

/** Segmented High / Medium / Low picker, optionally led by "All" (value ""). */
@Composable
private fun PrioritySegments(selected: String, allowAll: Boolean, onSelect: (String) -> Unit) {
    val colors = LocalWebColors.current
    val options = (if (allowAll) listOf(Triple("", "All", colors.primary)) else emptyList()) +
        PRIORITIES.map { Triple(it.value, it.label, colors.tone(it.tone)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label, tint) ->
            val active = selected == value
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) tint.copy(alpha = 0.15f) else colors.surface)
                    .border(1.dp, if (active) tint else colors.border, RoundedCornerShape(8.dp))
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value.isNotEmpty()) {
                    Box(Modifier.size(7.dp).background(tint, CircleShape))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    label, color = if (active) colors.text else colors.textSecondary, fontSize = 0.8.rem,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                )
            }
        }
    }
}


/** New ticket sheet: Basics (title, description, priority) then Details; actions pinned below. */
@Composable
internal fun BacklogFormSheet(ui: TaskUiState, viewModel: TaskViewModel) {
    val d = ui.draft
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding()) {
        SheetHeader(HeroIcons.Plus, "New backlog ticket", viewModel::closeBacklogForm)
        Column(
            Modifier.weight(1f, fill = false).heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SheetSection("Basics") {
                WebTextField(d.title, { v -> viewModel.updateDraft { it.copy(title = v) } }, "Ticket title…", maxLength = 200)
                WebTextField(d.description, { v -> viewModel.updateDraft { it.copy(description = v) } }, "Description (optional)", singleLine = false, minLines = 3)
                Column {
                    FieldLabel("Priority")
                    PrioritySegments(d.priority, allowAll = false) { v -> viewModel.updateDraft { it.copy(priority = v) } }
                }
            }
            SheetSection("Details") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Assignee", HeroIcons.User)
                        WebSelect(assigneeOptions(ui.assignableUsers), d.assignedTo, { v -> viewModel.updateDraft { it.copy(assignedTo = v) } }, Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Due date", HeroIcons.CalendarDays)
                        WebDateField(d.dueDate, { v -> viewModel.updateDraft { it.copy(dueDate = v) } }, Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Type")
                        WebSelect(typeOptions(ui.agile), d.workItemTypeId, { v -> viewModel.updateDraft { it.copy(workItemTypeId = v) } }, Modifier.fillMaxWidth())
                    }
                    if (ui.sprints.isNotEmpty()) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Sprint", HeroIcons.RocketLaunch)
                            WebSelect(sprintOptions(ui.sprints), d.sprintId, viewModel::setDraftSprint, Modifier.fillMaxWidth())
                        }
                    }
                }
                if (ui.projects.isNotEmpty()) {
                    Column {
                        FieldLabel("Project", HeroIcons.Folder)
                        WebSelect(projectOptions(ui.projects), d.projectId, { v -> viewModel.updateDraft { it.copy(projectId = v) } }, Modifier.fillMaxWidth())
                    }
                }
                LabelSelector(ui.labels, d.labels) { id ->
                    viewModel.updateDraft { it.copy(labels = if (id in it.labels) it.labels - id else it.labels + id) }
                }
                StoryPointPicker(d.storyPoints, { v -> viewModel.updateDraft { it.copy(storyPoints = v) } }, ui.agile)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WebButton("Cancel", viewModel::closeBacklogForm, Modifier.weight(1f))
            WebButton("Create ticket", viewModel::submitBacklog, Modifier.weight(1f), style = BtnStyle.Primary, enabled = d.title.isNotBlank())
        }
    }
}

