package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.designsystem.theme.AinoBlue
import app.aino.mobile.core.designsystem.theme.AinoDanger
import app.aino.mobile.core.designsystem.theme.AinoWarning
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TasksScreen(viewModel: TaskViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (ui.tab != TaskTab.Backlog) viewModel.selectTab(TaskTab.Backlog)
    }
    AinoAtmosphere {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp).padding(bottom = 76.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Tasks",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                TaskTabs(ui.tab, viewModel::selectTab)
                ui.error?.let { AinoAlert(it, AlertTone.Error) }
                ui.message?.let { AinoAlert(it, AlertTone.Success) }

                when {
                    ui.detail != null -> TaskDetailPanel(ui, viewModel)
                    ui.composerOpen -> TaskComposer(ui, viewModel)
                    else -> TaskBrowser(ui, viewModel)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (ui.detail == null && !ui.composerOpen) {
                Box(
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 24.dp)
                        .size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable(enabled = !ui.loading) {
                            viewModel.selectTab(TaskTab.Backlog)
                            viewModel.openComposer()
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Add, "Create ticket", tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
        }
    }
}

@Composable
private fun TaskBrowser(ui: TaskUiState, viewModel: TaskViewModel) {
    TaskFilterBar(ui, viewModel)
    BacklogTaskList(ui, viewModel)
}

@Composable
private fun TaskTabs(selected: TaskTab, onSelect: (TaskTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TaskTabButton("Backlog", Icons.Outlined.Inbox, selected == TaskTab.Backlog, Modifier.weight(1f)) { onSelect(TaskTab.Backlog) }
        TaskTabButton("Sprint", Icons.Outlined.RocketLaunch, false, Modifier.weight(1f), enabled = false) {}
        TaskTabButton("Service Desk", Icons.Outlined.SupportAgent, false, Modifier.weight(1f), enabled = false) {}
    }
}

@Composable
private fun TaskTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier.clickable(enabled = enabled, onClick = onClick)
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(5.dp))
            .padding(vertical = 9.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f))
        Text(
            label,
            Modifier.padding(start = 5.dp),
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TaskDayHeader(ui: TaskUiState, viewModel: TaskViewModel) {
    val date = runCatching { LocalDate.parse(ui.date) }.getOrDefault(LocalDate.now())
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NavButton(Icons.Outlined.ChevronLeft) { viewModel.changeDate(-1) }
                    Text(
                        "Today",
                        Modifier.clickable(onClick = viewModel::today).padding(horizontal = 9.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    NavButton(Icons.Outlined.ChevronRight) { viewModel.changeDate(1) }
                }
            }
            Text(
                "${ui.stats.done}/${ui.stats.total} done · ${ui.stats.inProgress} in progress · ${ui.stats.percent}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BacklogHeader(ui: TaskUiState) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backlog", style = MaterialTheme.typography.titleLarge)
            Text(
                "${ui.backlogSummary.total} unscheduled items" + if (ui.backlogHasMore) " · first page shown" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                TASK_PRIORITIES.joinToString(" · ") { "${it.replaceFirstChar(Char::uppercase)} ${ui.backlogSummary.byPriority[it] ?: 0}" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskFilterBar(ui: TaskUiState, viewModel: TaskViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val activeCount = listOf(
        ui.filters.priority != null,
        ui.filters.status != null,
        ui.filters.search.isNotBlank(),
        !ui.filters.assigneeMine,
    ).count { it }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Tickets not scheduled for a specific date",
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.background(
                if (expanded || activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ).border(1.dp, if (expanded || activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FilterList, null, Modifier.size(15.dp), tint = if (expanded || activeCount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (activeCount == 0) "Filters" else "Filters ($activeCount)",
                Modifier.padding(start = 6.dp),
                color = if (expanded || activeCount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    if (expanded) AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TaskField(
                "Search tickets…",
                ui.filters.search,
                { viewModel.setFilters(search = it) },
                imeAction = true,
                onCommit = viewModel::applySearch,
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("Mine", ui.filters.assigneeMine) { viewModel.setFilters(assigneeMine = true) }
                FilterChip("All assignees", !ui.filters.assigneeMine) { viewModel.setFilters(assigneeMine = false) }
                TASK_PRIORITIES.forEach { priority ->
                    FilterChip(priority.replaceFirstChar(Char::uppercase), ui.filters.priority == priority) {
                        if (ui.filters.priority == priority) viewModel.setFilters(clearPriority = true)
                        else viewModel.setFilters(priority = priority)
                    }
                }
                TASK_STATUSES.forEach { status ->
                    FilterChip(taskStatusLabel(status), ui.filters.status == status) {
                        if (ui.filters.status == status) viewModel.setFilters(clearStatus = true)
                        else viewModel.setFilters(status = status)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun BacklogTaskList(ui: TaskUiState, viewModel: TaskViewModel) {
    val tasks = ui.backlog
    if (tasks.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 72.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Inbox, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (ui.loading) "Loading tickets…" else if (ui.filters.search.isNotBlank() || ui.filters.priority != null || ui.filters.status != null) "No matching tickets" else "Backlog is empty",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (ui.filters.search.isNotBlank() || ui.filters.priority != null || ui.filters.status != null) "Try adjusting or clearing your filters."
                else "Create a ticket to organize work that doesn't have a scheduled date yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.forEach { task -> BacklogTaskCard(task, viewModel) }
    }
}

@Composable
private fun BacklogTaskCard(task: Task, viewModel: TaskViewModel) {
    val done = task.status == "done"
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { viewModel.openDetail(task) }.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (done) "Mark incomplete" else "Mark complete",
                Modifier.size(22.dp).clickable { viewModel.setStatus(task, if (done) "pending" else "done") },
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(task.issueKey, task.title).joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusPill(task.status)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(priorityColor(task.priority), CircleShape))
                        Text(
                            task.priority.replaceFirstChar(Char::uppercase),
                            Modifier.padding(start = 5.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val color = when (status) {
        "done" -> Color(0xFF16A34A)
        "in_progress" -> AinoBlue
        "in_review" -> AinoWarning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        taskStatusLabel(status),
        Modifier.background(color.copy(alpha = .14f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun TaskList(ui: TaskUiState, viewModel: TaskViewModel) {
    val tasks = ui.visibleTasks
    if (tasks.isEmpty()) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (ui.loading) "Loading tasks…" else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tasks.forEach { task -> TaskCard(task, viewModel) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskCard(task: Task, viewModel: TaskViewModel) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { viewModel.openDetail(task) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(priorityColor(task.priority), CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    task.issueKey?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                AinoBadge(taskStatusLabel(task.status), statusTone(task.status))
            }
            task.description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (task.isBlocked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Block, null, Modifier.size(15.dp), tint = AinoDanger)
                    Text(
                        task.blockedReason?.takeIf(String::isNotBlank) ?: "Blocked",
                        Modifier.padding(start = 6.dp),
                        color = AinoDanger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                task.labels.forEach { AinoBadge(it.name, AlertTone.Info) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.assignee?.display() ?: "Unassigned",
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (task.commentCount > 0) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        task.commentCount.toString(),
                        Modifier.padding(start = 4.dp, end = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                task.dueDate?.let {
                    Text("Due $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Advance to ${taskStatusLabel(nextTaskStatus(task.status))}",
                Modifier.clickable { viewModel.advanceStatus(task) }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun priorityColor(priority: String): Color = when (priority) {
    "high" -> AinoDanger
    "low" -> AinoBlue
    else -> AinoWarning
}

private fun statusTone(status: String): AlertTone = when (status) {
    "done" -> AlertTone.Success
    "in_progress" -> AlertTone.Warning
    "in_review" -> AlertTone.Info
    else -> AlertTone.Info
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskComposer(ui: TaskUiState, viewModel: TaskViewModel) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ui.tab == TaskTab.Today) "New task for ${ui.date}" else "New backlog item",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                Icon(
                    Icons.Outlined.Close,
                    "Close",
                    Modifier.clickable(onClick = viewModel::closeComposer),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TaskField("Title", ui.composerTitle, { viewModel.updateComposer(title = it) })
            TaskField("Description", ui.composerDescription, { viewModel.updateComposer(description = it) }, singleLine = false)
            TaskField("Due date (YYYY-MM-DD)", ui.composerDueDate, { viewModel.updateComposer(dueDate = it) })
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TASK_PRIORITIES.forEach { priority ->
                    FilterChip(priority.replaceFirstChar(Char::uppercase), ui.composerPriority == priority) {
                        viewModel.updateComposer(priority = priority)
                    }
                }
            }
            if (ui.assignableUsers.isNotEmpty()) {
                Text("Assign to", style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("Nobody", ui.composerAssignee == null) { viewModel.updateComposer(clearAssignee = true) }
                    ui.assignableUsers.forEach { user ->
                        FilterChip(user.display(), ui.composerAssignee == user.id) { viewModel.updateComposer(assignee = user.id) }
                    }
                }
            }
            AinoPrimaryButton("Create task", viewModel::submitTask, Modifier.fillMaxWidth(), !ui.loading)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskDetailPanel(ui: TaskUiState, viewModel: TaskViewModel) {
    val detail = ui.detail ?: return
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    detail.issueKey?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                    Text(detail.title, style = MaterialTheme.typography.titleLarge)
                }
                Icon(
                    Icons.Outlined.Close,
                    "Close",
                    Modifier.clickable(onClick = viewModel::closeDetail),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            detail.description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AinoBadge(detail.priority, if (detail.priority == "high") AlertTone.Error else AlertTone.Info)
                AinoBadge(taskStatusLabel(detail.status), statusTone(detail.status))
                detail.storyPoints?.let { AinoBadge("$it pts", AlertTone.Info) }
                detail.sprint?.name?.let { AinoBadge(it, AlertTone.Info) }
                detail.project?.name?.let { AinoBadge(it, AlertTone.Info) }
                detail.labels.forEach { AinoBadge(it.name, AlertTone.Info) }
            }
            if (detail.isBlocked) {
                AinoAlert(detail.blockedReason?.takeIf(String::isNotBlank) ?: "This task is blocked.", AlertTone.Warning)
            }
            Text(
                "Assignee ${detail.assignee?.display() ?: "Unassigned"} · Created by ${detail.creator?.display() ?: "—"}" +
                    (detail.dueDate?.let { " · Due $it" } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Move to", style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TASK_STATUSES.forEach { status ->
                    FilterChip(taskStatusLabel(status), detail.status == status) {
                        // Reuses the list-level optimistic path so the row behind
                        // the panel and the panel itself never disagree.
                        viewModel.setStatus(detailAsTask(detail), status)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.deleteTask(detailAsTask(detail)) }
                    .background(AinoDanger.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(17.dp), tint = AinoDanger)
                Text("Delete task (creator only)", Modifier.padding(start = 8.dp), color = AinoDanger, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    TaskComments(ui, viewModel)
}

/** The detail payload is the same enriched row, so a list action can reuse it. */
private fun detailAsTask(detail: TaskDetail): Task = Task(
    id = detail.id,
    title = detail.title,
    description = detail.description,
    priority = detail.priority,
    status = detail.status,
    date = detail.date,
    dueDate = detail.dueDate,
    storyPoints = detail.storyPoints,
    isBlocked = detail.isBlocked,
    blockedReason = detail.blockedReason,
    issueKey = detail.issueKey,
    labels = detail.labels,
    assignee = detail.assignee,
    creator = detail.creator,
    project = detail.project,
    sprint = detail.sprint,
)

@Composable
private fun TaskComments(ui: TaskUiState, viewModel: TaskViewModel) {
    val detail = ui.detail ?: return
    AinoSectionHeader("Comments", "${detail.comments.size} on this task")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.comments.forEach { comment ->
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(comment.author(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    comment.content?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    comment.fileName?.let {
                        Text("Attachment: $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    comment.createdAt?.let {
                        Text(it.take(16).replace('T', ' '), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TaskField("Add a comment", ui.commentDraft, viewModel::updateCommentDraft, singleLine = false)
            AinoPrimaryButton(
                if (ui.detailLoading) "Posting…" else "Post comment",
                viewModel::submitComment,
                Modifier.fillMaxWidth(),
                !ui.detailLoading,
            )
        }
    }
}

@Composable
private fun TaskField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    imeAction: Boolean = false,
    onCommit: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = if (imeAction) ImeAction.Search else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onSearch = { onCommit?.invoke() }),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    )
}
