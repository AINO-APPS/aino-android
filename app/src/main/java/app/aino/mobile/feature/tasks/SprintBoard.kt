package app.aino.mobile.feature.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlin.math.roundToInt

/**
 * Sprint board for phones: a sprint switcher + collapsible progress card, then
 * the workflow states stacked **vertically** — New, In Progress, In Review,
 * Done — each with its own header (count and WIP limit) and its cards beneath.
 *
 * A vertical stack is the right shape for a phone: every column is reachable by
 * scrolling, nothing is hidden behind a swipe, and the whole board reads in the
 * same order as the workflow. Long-press a card and drag it onto another
 * column's header to move it; the card's long-press menu ("Move to…") is the
 * accessible fallback. Moves are optimistic with Undo.
 */
@Composable
internal fun SprintBoard(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val haptics = LocalHapticFeedback.current
    // Cancelled is a terminal state, not a work-in-progress column: a cancelled
    // ticket leaves the board rather than sitting in a fifth column.
    val states = ui.agile.workflowStates.filter { it.key != "cancelled" }
    var progressOpen by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Task?>(null) }

    // Drag-to-column state (root coordinates). A column claims a drop target
    // from both its header and its body, kept in separate maps so an empty
    // column's body cannot shadow its own header (the two used to write the
    // same slot, which made dropping on a header stop working).
    val headerBounds = remember { mutableStateMapOf<String, Rect>() }
    val bodyBounds = remember { mutableStateMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<Task?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }
    val hoverKey = dragging?.let { _ -> dropTargetKey(headerBounds, bodyBounds, dragPos) }
    LaunchedEffect(hoverKey) { if (hoverKey != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    TaskPage(loading = ui.sprintLoading || ui.refreshing, onRefresh = { viewModel.refresh(pull = true) }) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { boardOrigin = it.boundsInRoot().topLeft }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item(key = "header") {
                    Column {
                        SprintHeaderCard(ui, viewModel, progressOpen) { progressOpen = !progressOpen }
                        ui.error?.let { ErrorMsg(it, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) }
                        ActiveFilterChips(ui, viewModel, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
                        if (dragging != null) {
                            Text(
                                "Drop on a column to move",
                                color = colors.primary, fontSize = 0.74.rem, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                if (ui.sprintLoading && ui.tasks.isEmpty()) {
                    items(3, key = { "sk-$it" }) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { TaskCardSkeleton() }
                    }
                } else if (ui.selectedSprintId == null) {
                    item(key = "no-sprint") {
                        TaskEmptyState(HeroIcons.RocketLaunch, "No sprint selected", "Create a sprint for your team to start planning.")
                    }
                } else {
                    // One board-level state when nothing is visible, instead of a
                    // full empty state per column (four of them stacked ~800dp of
                    // illustrations and repeated "Nothing in …" copy).
                    val empty = boardEmptyState(hasTasks = ui.tasks.isNotEmpty(), filterCount = ui.filterCount)
                    states.forEach { state ->
                        val columnTasks = tasksForColumn(ui.tasks, state)
                        item(key = "col-${state.key}") {
                            ColumnHeader(
                                state = state,
                                count = columnTasks.size,
                                wip = state.wipLimit?.takeIf { ui.agile.features.wipLimits && it > 0 },
                                dropTarget = dragging != null,
                                hovered = hoverKey == state.key,
                                modifier = Modifier.onGloballyPositioned { headerBounds[state.key] = it.boundsInRoot() },
                                onAdd = { viewModel.openCreateForColumn(state) },
                            )
                        }
                        if (columnTasks.isEmpty()) {
                            // Headers render bare when the whole board is empty;
                            // the board-level state below carries the message.
                            if (empty == BoardEmpty.None) {
                                item(key = "empty-${state.key}") {
                                    EmptyColumnRow(
                                        state = state,
                                        hovered = hoverKey == state.key,
                                        // The body is the larger target, so it
                                        // registers the same bounds as its header.
                                        modifier = Modifier.onGloballyPositioned {
                                            bodyBounds[state.key] = it.boundsInRoot()
                                        },
                                    )
                                }
                            }
                        } else {
                            items(columnTasks, key = { "t-${it.id}" }) { task ->
                                val isDragged = dragging?.id == task.id
                                var cardOrigin by remember { mutableStateOf(Offset.Zero) }
                                // pointerInput keeps its first lambdas; read the latest row (realtime may have patched it).
                                val currentTask by rememberUpdatedState(task)
                                val canMove = canChangeStatus(task, ui.userId, ui.role, ui.teamId)
                                Box(
                                    Modifier
                                        .padding(horizontal = 16.dp, vertical = 5.dp)
                                        .onGloballyPositioned { cardOrigin = it.boundsInRoot().topLeft }
                                        // The slot the card left stays readable but
                                        // faded; the lift happens on the floating
                                        // copy (see the ghost below).
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
                                                    val target = dropTargetKey(headerBounds, bodyBounds, dragPos)
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
                                            if (canMove) customActions = states.filter { it.key != task.workflowStateKey }.map { s ->
                                                CustomAccessibilityAction("Move to ${stateLabel(s)}") { viewModel.moveTask(task, s); true }
                                            }
                                        },
                                ) {
                                    TaskCardM3(
                                        task, ui.agile,
                                        onOpen = { onOpen(task) },
                                        highlighted = task.id in ui.recentlyUpdated,
                                        onComments = { viewModel.openComments(task.id) },
                                        compact = ui.compact,
                                        attribution = ui.attribution[task.id],
                                        topTrailing = {
                                            Box(
                                                Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Task actions") { actionsFor = task },
                                                contentAlignment = Alignment.Center,
                                            ) { Icon(HeroIcons.EllipsisVertical, "Task actions", Modifier.size(18.dp), tint = colors.textMuted) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (empty != BoardEmpty.None) {
                        item(key = "board-empty") {
                            BoardEmptyFooter(
                                empty = empty,
                                onClearFilters = viewModel::clearFilters,
                                onImport = { viewModel.openSheet(TaskSheet.Import) },
                                onCreate = { viewModel.openSheet(TaskSheet.CreateTicket) },
                            )
                        }
                    }
                }
            }
            // Floating ghost of the dragged card: it lifts — grows, tilts and
            // picks up a shadow — so the ticket under the finger is obviously
            // the one being moved, and settles back when dropped.
            dragging?.let { task ->
                val local = dragPos - boardOrigin
                val lift by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 160),
                    label = "ghost-lift",
                )
                Box(
                    Modifier
                        .offset { IntOffset((local.x - 120.dp.toPx()).roundToInt(), (local.y - 36.dp.toPx()).roundToInt()) }
                        .widthIn(max = 260.dp)
                        .graphicsLayer {
                            scaleX = 1f + 0.05f * lift
                            scaleY = 1f + 0.05f * lift
                            rotationZ = -2f + 4.5f * lift
                            shadowElevation = 18.dp.toPx() * lift
                        }
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

/**
 * One column's header in the vertical board: the state's dot and name, its
 * count (and WIP limit), and the drop-target highlight while a card is being
 * dragged. It is also the drop target, so a card can be moved by dragging it
 * onto the header of the column it should join.
 */
@Composable
internal fun ColumnHeader(
    state: WorkflowState,
    count: Int,
    wip: Int?,
    dropTarget: Boolean,
    hovered: Boolean,
    modifier: Modifier = Modifier,
    /** Creates a ticket in this column's state; hidden when null. */
    onAdd: (() -> Unit)? = null,
) {
    val colors = LocalWebColors.current
    val tint = hexColor(state.color, colors.textMuted)
    val exceeded = wip != null && count > wip
    val bg by animateColorAsState(
        when {
            hovered -> tint.copy(alpha = 0.22f)
            dropTarget -> colors.surfaceHover
            else -> colors.surface
        },
        label = "columnHeaderBg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                if (hovered) 1.5.dp else 1.dp,
                if (exceeded) colors.danger else if (hovered) tint else colors.border,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(tint, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(
            stateLabel(state),
            color = colors.text,
            fontSize = 0.82.rem,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (wip != null) "$count/$wip" else "$count",
            color = if (exceeded) colors.danger else colors.textMuted,
            fontSize = 0.76.rem,
            fontWeight = FontWeight.Bold,
        )
        if (onAdd != null) {
            // Jira's column "+": the ticket is created in this sprint, already
            // in this column's state — never in the backlog.
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Add a ticket to ${stateLabel(state)}", onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(HeroIcons.Plus, "Add a ticket to ${stateLabel(state)}", Modifier.size(16.dp), tint = colors.textMuted) }
        }
    }
}

/**
 * The board-level empty state, shown once after the last column header.
 *
 * Lighter than [TaskEmptyState]: it is a footer to the board rather than the
 * only thing on the page, so a 40dp icon and tighter padding keep it from
 * re-creating the vertical gap that four full empty states used to make.
 */
@Composable
internal fun BoardEmptyFooter(
    empty: BoardEmpty,
    onClearFilters: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (empty == BoardEmpty.None) return
    val colors = LocalWebColors.current
    val filtered = empty == BoardEmpty.Filtered
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).background(colors.primary.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (filtered) HeroIcons.MagnifyingGlass else HeroIcons.RocketLaunch,
                null,
                Modifier.size(20.dp),
                tint = colors.primary,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (filtered) "No tickets match your filters" else "This sprint is empty",
            color = colors.text,
            fontSize = 0.92.rem,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            if (filtered) "Try removing a filter or two to widen the results."
            else "Pull work in from the backlog, or create a ticket to get started.",
            color = colors.textMuted,
            fontSize = 0.8.rem,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        if (filtered) {
            PillButton("Clear filters", onClearFilters, primary = true)
        } else {
            PillButton("Import from backlog", onImport, primary = true)
            Spacer(Modifier.height(8.dp))
            PillButton("Create ticket", onCreate)
        }
    }
}

/**
 * One empty column's body: a thin dashed slot, not a full empty state.
 *
 * It is a drop target (the caller registers its bounds) and decorative to
 * screen readers — the column header's `0` already says the column is empty, so
 * announcing it again is noise in a linear swipe-through.
 */
@Composable
internal fun EmptyColumnRow(
    state: WorkflowState,
    hovered: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    val tint = hexColor(state.color, colors.textMuted)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 2.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) tint.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = if (hovered) 1.5.dp else 1.dp,
                color = if (hovered) tint else colors.border,
                shape = RoundedCornerShape(8.dp),
            )
            .clearAndSetSemantics { testTag = EMPTY_COLUMN_TAG },
        contentAlignment = Alignment.Center,
    ) {
        Text("No tickets", color = colors.textMuted, fontSize = 0.76.rem)
    }
}

/** Test/automation handle for the decorative empty-column slot. */
internal const val EMPTY_COLUMN_TAG = "empty-column"

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
                    Text(
                        sprint.number?.let { "#$it" } ?: sprint.name,
                        color = colors.text, fontSize = 1.rem, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
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
                // Pre-flight (D32): what moves, where to, and what is dropped —
                // shown before the sprint is actually completed.
                ui.completionPreview?.let { preview ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(colors.bg)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "${preview.doneTasks} of ${preview.totalTasks} done" +
                                                        if (preview.movingPoints > 0) " · ${formatPoints(preview.movingPoints)} ${ui.agile.unitLabel}" else "",
                            color = colors.textSecondary,
                            fontSize = 0.8.rem,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (preview.incompleteTasks > 0) {
                            Text(
                                                        "${preview.incompleteTasks} unfinished (${formatPoints(preview.movingPoints)} ${ui.agile.unitLabel}) will move",
                                color = colors.warning,
                                fontSize = 0.76.rem,
                            )
                        } else {
                            Text("Nothing carries over", color = colors.success, fontSize = 0.76.rem)
                        }
                        Text(
                            "Finished work stays with this sprint.",
                            color = colors.textMuted,
                            fontSize = 0.72.rem,
                        )
                    }
                }
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
        if (task.sprintId != null && canChangeStatus(task, ui.userId, ui.role, ui.teamId)) {
            SectionTitle("Move to", Modifier.padding(bottom = 6.dp))
            ui.agile.workflowStates.forEach { state ->
                val current = task.workflowStateKey == state.key || (state.id != 0L && task.workflowStateId == state.id)
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
            ActionRow(HeroIcons.Trash, "Cancel ticket", danger = true) { onDismiss(); viewModel.requestDelete(task) }
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
