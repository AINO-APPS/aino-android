package app.aino.mobile.feature.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Sprint board for phones: a sprint switcher + collapsible progress card,
 * a row of status chips (one per workflow state, with counts and WIP
 * limits) synced to a swipeable column pager. Long-press a card and drag
 * it onto a chip to move it; the card's long-press menu ("Move to…") is the
 * accessible fallback. Moves are optimistic with Undo.
 */
@Composable
internal fun SprintBoard(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val states = ui.agile.workflowStates
    val pager = rememberPagerState { states.size.coerceAtLeast(1) }
    var progressOpen by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Task?>(null) }

    // Drag-to-chip state (root coordinates).
    val chipBounds = remember { mutableStateMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<Task?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }
    val hoverKey = dragging?.let { _ -> chipBounds.entries.firstOrNull { it.value.contains(dragPos) }?.key }
    LaunchedEffect(hoverKey) { if (hoverKey != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    TaskPage(loading = ui.sprintLoading || ui.refreshing, onRefresh = { viewModel.refresh(pull = true) }) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { boardOrigin = it.boundsInRoot().topLeft }) {
            Column(Modifier.fillMaxSize()) {
                SprintHeaderCard(ui, viewModel, progressOpen) { progressOpen = !progressOpen }
                ui.error?.let { ErrorMsg(it, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) }
                ActiveFilterChips(ui, viewModel, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
                // Status chips (drop targets while dragging).
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    states.forEachIndexed { index, state ->
                        val count = tasksForColumn(ui.tasks, state).size
                        StatusTab(
                            state = state,
                            count = count,
                            wip = state.wipLimit?.takeIf { ui.agile.features.wipLimits && it > 0 },
                            selected = pager.currentPage == index,
                            dropTarget = dragging != null,
                            hovered = hoverKey == state.key,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            modifier = Modifier.onGloballyPositioned { chipBounds[state.key] = it.boundsInRoot() },
                        )
                    }
                }
                if (dragging != null) {
                    Text(
                        "Drop on a status to move",
                        color = colors.primary, fontSize = 0.74.rem, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = dragging == null, key = { states.getOrNull(it)?.key ?: it }) { page ->
                    val state = states.getOrNull(page) ?: return@HorizontalPager
                    val columnTasks = tasksForColumn(ui.tasks, state)
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            ui.sprintLoading && ui.tasks.isEmpty() -> items(3, key = { "sk-$it" }) { TaskCardSkeleton() }
                            ui.selectedSprintId == null -> item { TaskEmptyState(HeroIcons.RocketLaunch, "No sprint selected", "Create a sprint for your team to start planning.") }
                            columnTasks.isEmpty() -> item {
                                TaskEmptyState(
                                    HeroIcons.QueueList,
                                    "Nothing in ${stateLabel(state)}",
                                    if (ui.tasks.isEmpty()) "Pull tickets in from the backlog to plan this sprint." else "Swipe to see other columns, or drag a card here.",
                                    action = if (ui.tasks.isEmpty() && ui.agileEnabled) "Import from backlog" else null,
                                    onAction = { viewModel.openSheet(TaskSheet.Import) },
                                )
                            }
                            else -> items(columnTasks, key = { "t-${it.id}" }) { task ->
                                val isDragged = dragging?.id == task.id
                                var cardOrigin by remember { mutableStateOf(Offset.Zero) }
                                // pointerInput keeps its first lambdas; read the latest row (realtime may have patched it).
                                val currentTask by rememberUpdatedState(task)
                                val canMove = canChangeStatus(task, ui.userId, ui.role)
                                Box(
                                    Modifier
                                        .onGloballyPositioned { cardOrigin = it.boundsInRoot().topLeft }
                                        .alpha(if (isDragged) 0.35f else 1f)
                                        .pointerInput(task.id, states, canMove) {
                                            if (!canMove) return@pointerInput
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    dragging = currentTask
                                                    dragOrigin = cardOrigin
                                                    dragPos = cardOrigin + offset
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragPos += amount
                                                },
                                                onDragEnd = {
                                                    val target = chipBounds.entries.firstOrNull { it.value.contains(dragPos) }?.key
                                                    val moved = dragging?.let { d -> viewModel.ui.value.tasks.firstOrNull { it.id == d.id } ?: d }
                                                    dragging = null
                                                    if (moved != null && target != null) {
                                                        states.firstOrNull { it.key == target }?.let { haptics.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.moveTask(moved, it) }
                                                    }
                                                },
                                                onDragCancel = { dragging = null },
                                            )
                                        }
                                        .semantics {
                                            if (canMove) customActions = states.filter { it.key != task.status }.map { s ->
                                                CustomAccessibilityAction("Move to ${stateLabel(s)}") { viewModel.moveTask(task, s); true }
                                            }
                                        },
                                ) {
                                    TaskCardM3(
                                        task, ui.agile,
                                        onOpen = { onOpen(task) },
                                        highlighted = task.id in ui.recentlyUpdated,
                                        onComments = { viewModel.openComments(task.id) },
                                        trailing = {
                                            Box(
                                                Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "Task actions") { actionsFor = task },
                                                contentAlignment = Alignment.Center,
                                            ) { Icon(HeroIcons.EllipsisVertical, "Task actions", Modifier.size(16.dp), tint = colors.textMuted) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Floating ghost of the dragged card.
            dragging?.let { task ->
                val local = dragPos - boardOrigin
                Box(
                    Modifier
                        .offset { IntOffset((local.x - 120.dp.toPx()).roundToInt(), (local.y - 36.dp.toPx()).roundToInt()) }
                        .widthIn(max = 260.dp)
                        .rotate(-2f)
                        .shadow(12.dp, CardShape)
                        .clip(CardShape)
                        .background(colors.bgElevated)
                        .border(1.5.dp, colors.primary, CardShape)
                        .padding(12.dp),
                ) {
                    Column {
                        Text(task.displayKey, color = colors.textMuted, fontSize = 0.7.rem)
                        Text(task.title, color = colors.text, fontSize = 0.88.rem, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    actionsFor?.let { task ->
        TaskActionsSheet(task, ui, viewModel, onOpen = { onOpen(task) }, onDismiss = { actionsFor = null })
    }
}

/** Status chip doubling as pager tab and drop target. */
@Composable
private fun StatusTab(
    state: WorkflowState,
    count: Int,
    wip: Int?,
    selected: Boolean,
    dropTarget: Boolean,
    hovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    val tint = hexColor(state.color, colors.textMuted)
    val exceeded = wip != null && count > wip
    val bg by animateColorAsState(
        when {
            hovered -> tint.copy(alpha = 0.35f)
            selected -> tint.copy(alpha = 0.18f)
            dropTarget -> colors.surfaceHover
            else -> colors.surface
        }, label = "statusTabBg",
    )
    val scale by animateFloatAsState(if (hovered) 1.08f else 1f, label = "statusTabScale")
    Row(
        modifier
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .border(if (hovered || selected) 1.5.dp else 1.dp, if (exceeded) colors.danger else if (hovered || selected) tint else colors.border, CircleShape)
            .clickable(onClickLabel = "Show ${stateLabel(state)}", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(tint, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(stateLabel(state), color = if (selected) colors.text else colors.textSecondary, fontSize = 0.82.rem, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.width(7.dp))
        Text(
            if (wip != null) "$count/$wip" else "$count",
            color = if (exceeded) colors.danger else if (selected) tint else colors.textMuted,
            fontSize = 0.74.rem,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Sprint switcher row + collapsible progress / lifecycle details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SprintHeaderCard(ui: TaskUiState, viewModel: TaskViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalWebColors.current
    val sprint = ui.currentSprint ?: return
    val stats = ui.stats
    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), padding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (ui.sprints.size > 1) "Change sprint" else null, enabled = ui.sprints.size > 1) { viewModel.openSheet(TaskSheet.SprintPicker) }
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sprint.name, color = colors.text, fontSize = 1.rem, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ui.sprints.size > 1) Icon(HeroIcons.ChevronDown, null, Modifier.size(18.dp), tint = colors.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    SprintStatusPill(sprint.status)
                }
                Text(
                    "${formatDate(sprint.startDate)} – ${formatDate(sprint.endDate)}" + if (sprint.status == "active") " · ${sprintDaysLeft(sprint.endDate)} days left" else "",
                    color = colors.textMuted, fontSize = 0.76.rem, maxLines = 1,
                )
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = if (expanded) "Hide sprint details" else "Show sprint details", onClick = onToggle), contentAlignment = Alignment.Center) {
                Icon(HeroIcons.ChevronDown, null, Modifier.size(18.dp).rotate(if (expanded) 180f else 0f), tint = colors.textMuted)
            }
        }
        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { stats.percent / 100f },
                    modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    color = colors.success,
                    trackColor = colors.surfaceHover,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("${stats.done}/${stats.total} · ${stats.percent}%", color = colors.textSecondary, fontSize = 0.76.rem, fontWeight = FontWeight.SemiBold)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sprint.goal?.takeIf(String::isNotBlank)?.let {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(HeroIcons.Flag, null, Modifier.size(15.dp).padding(top = 2.dp), tint = colors.textMuted)
                            Spacer(Modifier.width(8.dp))
                            Text(it, color = colors.textSecondary, fontSize = 0.82.rem)
                        }
                    }
                    val totals = ui.sprintStats?.totals
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.agile.features.storyPoints && totals != null && totals.points > 0) {
                            MiniStat("${formatPoints(totals.donePoints)}/${formatPoints(totals.points)}", ui.agile.unitLabel, colors.primary)
                        }
                        if (totals != null && totals.unestimatedTasks > 0) MiniStat("${totals.unestimatedTasks}", "unestimated", colors.warning)
                        if (totals != null && totals.blockedTasks > 0) MiniStat("${totals.blockedTasks}", "blocked", colors.danger)
                        sprint.velocityPoints?.let { MiniStat(formatPoints(it), "velocity", colors.success) }
                    }
                    SprintLifecycleControls(ui, sprint, viewModel)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String, tint: androidx.compose.ui.graphics.Color) {
    val colors = LocalWebColors.current
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, color = tint, fontSize = 0.84.rem, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(5.dp))
        Text(label, color = colors.textSecondary, fontSize = 0.74.rem)
    }
}

/** Start (planned) · Pause/Resume + Complete with rollover (active/paused) · Completed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SprintLifecycleControls(ui: TaskUiState, sprint: AvailableSprint, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val canEdit = canManageSprint(ui.role)
    var rolloverPicker by remember { mutableStateOf(false) }
    if (!canEdit && sprint.status != "completed") return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            sprint.status == "planned" ->
                PillButton("Start sprint", viewModel::startSprint, Modifier.fillMaxWidth(), primary = true, icon = HeroIcons.PlayCircle, enabled = !ui.lifecycleBusy)
            (sprint.status == "active" || sprint.status == "paused") && !ui.completing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sprint.status == "active") PillButton("Pause", viewModel::pauseSprint, Modifier.weight(1f), icon = HeroIcons.Pause, enabled = !ui.lifecycleBusy)
                else PillButton("Resume", viewModel::resumeSprint, Modifier.weight(1f), icon = HeroIcons.Play, enabled = !ui.lifecycleBusy)
                PillButton("Complete", viewModel::beginComplete, Modifier.weight(1f), primary = true, icon = HeroIcons.CheckCircle, enabled = !ui.lifecycleBusy)
            }
            ui.completing -> {
                val target = if (ui.rolloverTo == "backlog") "Backlog" else ui.rolloverOptions.firstOrNull { it.id.toString() == ui.rolloverTo }?.name ?: "Backlog"
                PropertyRow(HeroIcons.ArrowTurnDownRight, "Roll over to", { rolloverPicker = true }) { PropertyText(target) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Cancel", viewModel::cancelComplete, Modifier.weight(1f), enabled = !ui.lifecycleBusy)
                    PillButton("Complete sprint", viewModel::completeSprint, Modifier.weight(1f), primary = true, enabled = !ui.lifecycleBusy)
                }
            }
            sprint.status == "completed" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HeroIcons.CheckCircle, null, Modifier.size(16.dp), tint = colors.success)
                Spacer(Modifier.width(6.dp))
                Text("Sprint completed", color = colors.success, fontSize = 0.84.rem, fontWeight = FontWeight.SemiBold)
            }
        }
        ui.lifecycleError?.let { Text(it, color = colors.danger, fontSize = 0.78.rem) }
    }
    if (rolloverPicker) {
        SelectSheet(
            "Roll over incomplete tickets",
            listOf(SelectOption("backlog", "Backlog")) + ui.rolloverOptions.map { SelectOption(it.id.toString(), it.name, it.status.replaceFirstChar(Char::uppercase)) },
            setOf(ui.rolloverTo),
            onDismiss = { rolloverPicker = false },
            onSelect = { viewModel.setRollover(it.firstOrNull() ?: "backlog") },
            icon = HeroIcons.ArrowTurnDownRight,
        )
    }
}

/** Long-press / overflow actions for a card: move, comments, open, delete. */
@Composable
internal fun TaskActionsSheet(task: Task, ui: TaskUiState, viewModel: TaskViewModel, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptics = app.aino.mobile.core.designsystem.rememberAinoHaptics()
    TaskSheetScaffold(task.displayKey, onDismiss) {
        Text(task.title, color = colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
        if (task.sprintId != null && canChangeStatus(task, ui.userId, ui.role)) {
            SectionTitle("Move to", Modifier.padding(bottom = 6.dp))
            ui.agile.workflowStates.forEach { state ->
                val current = task.status == state.key || (state.id != 0L && task.workflowStateId == state.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (current) colors.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = !current) { haptics.confirm(); onDismiss(); viewModel.moveTask(task, state) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(hexColor(state.color, colors.textMuted), CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text(stateLabel(state), color = if (current) colors.textMuted else colors.text, fontSize = 0.92.rem, modifier = Modifier.weight(1f))
                    if (current) Text("Current", color = colors.textMuted, fontSize = 0.74.rem)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        ActionRow(HeroIcons.ArrowTopRightOnSquare, "Open details") { onDismiss(); onOpen() }
        ActionRow(HeroIcons.ChatBubbleOvalLeft, "Comments" + if (task.commentCount > 0) " (${task.commentCount})" else "") { onDismiss(); viewModel.openComments(task.id) }
        ActionRow(HeroIcons.DocumentDuplicate, "Copy ${task.displayKey}") {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(task.displayKey))
            onDismiss()
        }
        if (ui.userId != null && ui.userId == task.userId) {
            ActionRow(HeroIcons.Trash, "Delete", danger = true) { onDismiss(); viewModel.requestDelete(task) }
        }
    }
}

@Composable
internal fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    val tint = if (danger) colors.danger else colors.text
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (danger) colors.danger else colors.textSecondary)
        Spacer(Modifier.width(14.dp))
        Text(text, color = tint, fontSize = 0.92.rem)
    }
}
