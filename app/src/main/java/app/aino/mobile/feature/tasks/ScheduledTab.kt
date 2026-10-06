package app.aino.mobile.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.US)

/**
 * Scheduled — tasks planned onto a day (from the Backlog's Schedule, the web
 * planner, or carry-forward). Overdue first, then a section per day from
 * today. A week strip jumps to a day; swipe right to reschedule, swipe left
 * to send back to the backlog (with Undo).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScheduledPage(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var rescheduling by remember { mutableStateOf<Task?>(null) }
    var actionsFor by remember { mutableStateOf<Task?>(null) }
    var pickDay by remember { mutableStateOf(false) }
    val groups = ui.scheduledGroups
    val today = LocalDate.now()

    LaunchedEffect(Unit) { if (!ui.scheduledLoaded) viewModel.loadScheduled() }

    // Index of each section header inside the LazyColumn (controls row = 0).
    val headerIndex = remember(groups, ui.error) {
        var index = 1 + (if (ui.error != null) 1 else 0)
        groups.associate { g -> (g.key to index).also { index += 1 + g.tasks.size } }
    }
    fun jumpTo(day: LocalDate) {
        val key = groups.firstOrNull { it.date != null && !it.date.isBefore(day) }?.key ?: return
        headerIndex[key]?.let { scope.launch { list.animateScrollToItem(it) } }
    }
    LaunchedEffect(ui.scheduledFocus, groups) {
        val focus = ui.scheduledFocus?.let(::localDateOf) ?: return@LaunchedEffect
        if (groups.isEmpty()) return@LaunchedEffect
        jumpTo(focus)
        viewModel.consumeScheduledFocus()
    }

    TaskPage(loading = ui.scheduledLoading || ui.refreshing, onRefresh = { viewModel.refresh(pull = true) }) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 112.dp)) {
            item(key = "controls") {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeekStrip(today, ui.scheduled, onPick = ::jumpTo, onMore = { pickDay = true })
                    ActiveFilterChips(ui, viewModel, Modifier.padding(horizontal = 16.dp))
                }
            }
            ui.error?.let { item(key = "error") { ErrorMsg(it, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) } }
            when {
                ui.scheduledLoading && ui.scheduled.isEmpty() -> items(3, key = { "sk-$it" }) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { TaskCardSkeleton() }
                }
                groups.isEmpty() -> item(key = "empty") {
                    TaskEmptyState(
                        HeroIcons.CalendarDays,
                        if (ui.filterCount > 0) "No scheduled tasks match" else "Nothing scheduled",
                        if (ui.filterCount > 0) "Try clearing a filter." else "Schedule tickets from the Backlog to plan them onto a day.",
                        action = if (ui.filterCount > 0) "Clear filters" else "Open backlog",
                        onAction = { if (ui.filterCount > 0) viewModel.clearFilters() else viewModel.selectTab(TaskTab.Backlog) },
                    )
                }
                else -> groups.forEach { group ->
                    stickyHeader(key = "sh-${group.key}") { ScheduledHeader(group, today) }
                    items(group.tasks, key = { "st-${it.id}" }) { task ->
                        ScheduledRow(
                            task, ui, viewModel, onOpen,
                            showDate = group.date == null,
                            onReschedule = { rescheduling = task },
                            onActions = { actionsFor = task },
                        )
                    }
                }
            }
            if (groups.isNotEmpty()) item(key = "footer") {
                Text(
                    "Showing the past ${SCHEDULED_PAST_DAYS} days (overdue) and the next ${SCHEDULED_AHEAD_DAYS} days",
                    color = colors.textMuted, fontSize = 0.72.rem,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    rescheduling?.let { task ->
        ScheduleDialog(task, title = "Reschedule \"${task.title}\"", initial = task.date, onDismiss = { rescheduling = null }) { date ->
            viewModel.schedule(task.id, date)
        }
    }
    if (pickDay) {
        val first = ui.scheduled.firstOrNull() ?: Task(id = 0, title = "")
        ScheduleDialog(first, title = "Jump to day", initial = today.toString(), onDismiss = { pickDay = false }) { date ->
            localDateOf(date)?.let(::jumpTo)
        }
    }
    actionsFor?.let { task ->
        TaskSheetScaffold(task.displayKey, { actionsFor = null }) {
            Text(task.title, color = colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
            ActionRow(HeroIcons.ArrowTopRightOnSquare, "Open details") { actionsFor = null; onOpen(task) }
            ActionRow(HeroIcons.CalendarDays, "Reschedule") { actionsFor = null; rescheduling = task }
            ActionRow(HeroIcons.ArchiveBox, "Move to backlog") { actionsFor = null; viewModel.unschedule(task.id, task.title, ask = false) }
            ActionRow(HeroIcons.ChatBubbleOvalLeft, "Comments" + if (task.commentCount > 0) " (${task.commentCount})" else "") { actionsFor = null; viewModel.openComments(task.id) }
            if (ui.userId != null && ui.userId == task.userId) {
                ActionRow(HeroIcons.Trash, "Delete", danger = true) { actionsFor = null; viewModel.requestDelete(task) }
            }
        }
    }
}

/** Next 7 days with a dot per day that has tasks, plus a calendar button for other days. */
@Composable
private fun WeekStrip(today: LocalDate, tasks: List<Task>, onPick: (LocalDate) -> Unit, onMore: () -> Unit) {
    val colors = LocalWebColors.current
    val counts = remember(tasks) { tasks.mapNotNull { localDateOf(it.date) }.groupingBy { it }.eachCount() }
    val days = remember(today) { (0L until 7L).map { today.plusDays(it) } }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(days, key = { it.toString() }) { day ->
                val count = counts[day] ?: 0
                val isToday = day == today
                Column(
                    Modifier
                        .width(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isToday) colors.primary.copy(alpha = 0.14f) else colors.surface)
                        .border(1.dp, if (isToday) colors.primary.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Jump to ${day.format(WEEKDAY)} ${day.dayOfMonth}") { onPick(day) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(day.format(WEEKDAY), color = if (isToday) colors.primary else colors.textMuted, fontSize = 0.66.rem, fontWeight = FontWeight.SemiBold)
                    Text("${day.dayOfMonth}", color = if (isToday) colors.primary else colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.Bold)
                    Box(Modifier.padding(top = 3.dp).size(5.dp).background(if (count > 0) colors.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundIconButton(HeroIcons.CalendarDateRange, "Pick a day", onMore, size = 42.dp)
    }
}

@Composable
private fun ScheduledHeader(group: ScheduledGroup, today: LocalDate) {
    val colors = LocalWebColors.current
    val accent = when {
        group.date == null -> colors.danger
        group.date == today -> colors.primary
        else -> colors.textMuted
    }
    Row(
        Modifier.fillMaxWidth().background(colors.bg).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group.date == null) Icon(HeroIcons.ExclamationCircle, null, Modifier.size(15.dp), tint = accent)
        else Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(group.label.uppercase(), color = if (group.date == null) colors.danger else colors.textSecondary, fontSize = 0.72.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.06.rem)
        Spacer(Modifier.width(8.dp))
        Text(
            "${group.tasks.size}",
            color = accent, fontSize = 0.68.rem, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(accent.copy(alpha = 0.14f), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

/** Swipe right = reschedule; swipe left = back to backlog. Both snap back and act. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledRow(
    task: Task,
    ui: TaskUiState,
    viewModel: TaskViewModel,
    onOpen: (Task) -> Unit,
    showDate: Boolean,
    onReschedule: () -> Unit,
    onActions: () -> Unit,
) {
    val colors = LocalWebColors.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onReschedule()
                SwipeToDismissBoxValue.EndToStart -> viewModel.unschedule(task.id, task.title, ask = false)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Reschedule") { onReschedule(); true },
                    CustomAccessibilityAction("Move to backlog") { viewModel.unschedule(task.id, task.title, ask = false); true },
                    CustomAccessibilityAction("More actions") { onActions(); true },
                )
            },
        backgroundContent = {
            val direction = state.dismissDirection
            if (direction == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val toBacklog = direction == SwipeToDismissBoxValue.EndToStart
            val tint = if (toBacklog) colors.warning else colors.primary
            Row(
                Modifier.fillMaxSize().clip(CardShape).background(tint.copy(alpha = 0.18f).compositeOver(colors.bg)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toBacklog) Arrangement.End else Arrangement.Start,
            ) {
                Icon(if (toBacklog) HeroIcons.ArchiveBox else HeroIcons.CalendarDays, null, Modifier.size(20.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(if (toBacklog) "Back to backlog" else "Reschedule", color = tint, fontSize = 0.86.rem, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        TaskCardM3(
            task, ui.agile,
            onOpen = { onOpen(task) },
            highlighted = task.id in ui.recentlyUpdated,
            showStatus = true,
            onLongPress = onActions,
            onComments = { viewModel.openComments(task.id) },
            trailing = {
                Spacer(Modifier.width(6.dp))
                Row(
                    Modifier
                        .clip(ChipShape)
                        .background((if (showDate) colors.danger else colors.primary).copy(alpha = 0.12f))
                        .clickable(onClickLabel = "Reschedule", onClick = onReschedule)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (showDate) colors.danger else colors.primaryLight
                    Icon(HeroIcons.CalendarDays, null, Modifier.size(14.dp), tint = tint)
                    Spacer(Modifier.width(4.dp))
                    Text(if (showDate) formatDate(task.date).substringBeforeLast(",") else "Reschedule", color = tint, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            },
        )
    }
}
