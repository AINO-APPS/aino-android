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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `TaskDetailModal` at ≤768px: the modal is full screen. Header (badges +
 * Edit/Delete or Save/Cancel + close), then view or edit mode, then the
 * Comments · History tabs.
 */
@Composable
fun TaskDetailScreen(viewModel: TaskViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val task = ui.detail
    LaunchedEffect(task == null) { if (task == null) onClose() }
    task ?: return
    val colors = LocalWebColors.current
    var draft by remember(task.id, ui.detailEditing) { mutableStateOf(TaskEditDraft.from(task)) }
    Column(Modifier.fillMaxSize().background(colors.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        DetailHeader(task, ui, viewModel, onSave = { viewModel.saveEdit(draft) })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            ui.error?.let { ErrorMsg(it, Modifier.padding(bottom = 12.dp)) }
            if (ui.detailEditing) EditMode(task, ui, viewModel, draft) { draft = it } else ViewMode(task, ui, viewModel)
            DetailTabs(task, ui, viewModel)
        }
    }
    ui.confirm?.let { TaskConfirmDialog(it, viewModel::acceptConfirm, viewModel::dismissConfirm) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeader(task: Task, ui: TaskUiState, viewModel: TaskViewModel, onSave: () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier.fillMaxWidth().background(colors.bgSecondary).padding(horizontal = 13.6.dp, vertical = 12.8.dp),
        verticalArrangement = Arrangement.spacedBy(9.6.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.4.dp), verticalArrangement = Arrangement.spacedBy(6.4.dp)) {
            PriorityBadge(task.priority)
            StatusBadge(task.status)
            if (task.isBacklogItem) {
                Row(
                    Modifier.background(colors.warning.copy(alpha = 0.15f), RoundedCornerShape(99.dp)).padding(horizontal = 9.6.dp, vertical = 3.2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Inventory2, null, Modifier.size(12.dp), tint = colors.warning)
                    Spacer(Modifier.width(3.dp))
                    Text("Backlog", color = colors.warning, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold)
                }
            }
            task.date?.let { date ->
                Row(
                    Modifier.background(colors.primary.copy(alpha = 0.15f), RoundedCornerShape(99.dp)).padding(horizontal = 9.6.dp, vertical = 3.2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(12.dp), tint = colors.primary)
                    Spacer(Modifier.width(3.dp))
                    Text(date.take(10), color = colors.primary, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (!ui.detailEditing) {
                WebButton("Edit", viewModel::startEdit, small = true, icon = Icons.Outlined.Edit)
                if (ui.userId != null && ui.userId == task.userId) {
                    WebButton("Delete", { viewModel.requestDelete(task) }, style = BtnStyle.Danger, small = true, icon = Icons.Outlined.Delete)
                }
            } else {
                WebButton("Save Changes", onSave, style = BtnStyle.Primary, small = true, icon = Icons.Outlined.Save)
                WebButton("Cancel Edit", viewModel::cancelEdit, small = true)
            }
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = viewModel::closeDetail), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Close", Modifier.size(16.dp), tint = colors.textMuted)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

// ── View mode ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewMode(task: Task, ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 9.6.dp),
        horizontalArrangement = Arrangement.spacedBy(9.6.dp),
        verticalArrangement = Arrangement.spacedBy(6.4.dp),
    ) {
        val projectColor = task.project?.color?.let { hexColor(it) }
        if (task.issueKey != null) {
            Text(
                task.issueKey,
                color = projectColor ?: colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 0.78.rem,
                modifier = Modifier.background(projectColor?.hexAlpha(0x22) ?: Color.Transparent, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        } else TicketId("#${task.id}")
        WorkItemTypeBadge(task.workItemTypeId, ui.agile)
        Text(task.title, color = colors.text, fontSize = 1.1.rem, fontWeight = FontWeight.Bold)
        StoryPointBadge(task.storyPoints, ui.agile)
        BlockerBadge(task.isBlocked, ui.agile)
    }
    if (task.labels.isNotEmpty()) {
        FlowRow(Modifier.padding(bottom = 9.6.dp), horizontalArrangement = Arrangement.spacedBy(4.8.dp), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
            task.labels.forEach { LabelPill(it) }
        }
    }
    task.description?.takeIf(String::isNotBlank)?.let { html ->
        TaskHtml(
            html, colors.textSecondary, 0.92.rem,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .background(colors.glass, RoundedCornerShape(6.dp))
                .border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp))
                .padding(16.dp),
        )
    }
    if (task.isBlocked) {
        Text(
            "⛔ Blocked" + (task.blockedReason?.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""),
            color = colors.danger,
            fontSize = 0.75.rem,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 8.dp)
                .background(colors.danger.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                .border(1.dp, colors.danger.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    if (ui.customFieldsEnabled) CustomFieldsSummary(task.id, viewModel)
    val criteria = task.acceptanceCriteria
    if (criteria.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(colors.glass, RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(Modifier.padding(bottom = 6.dp)) {
                Text("ACCEPTANCE CRITERIA ", color = colors.textMuted, fontSize = 0.78.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.4.rem / 16)
                Text("(${criteria.count { it.done }}/${criteria.size})", color = colors.primary, fontSize = 0.75.rem)
            }
            criteria.forEach { c ->
                Text(
                    "${if (c.done) "☑" else "☐"} ${c.text}",
                    color = if (c.done) colors.textMuted else colors.text,
                    fontSize = 0.85.rem,
                    textDecoration = if (c.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // Meta grid collapses to one column at ≤768px.
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        task.assignee?.let { a -> MetaItem("Assigned to") { PersonValue(a) } }
        if (task.creator != null && task.assignedTo != null && task.userId != task.assignedTo) {
            MetaItem("Created by") { PersonValue(task.creator) }
        }
        formatDueDate(task.dueDate)?.let { due ->
            val overdue = isDueOverdue(task.dueDate) && task.status != "done"
            MetaItem("Due date") {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(12.dp), tint = if (overdue) colors.danger else colors.text)
                Spacer(Modifier.width(3.dp))
                Text(due, color = if (overdue) colors.danger else colors.text, fontSize = 0.88.rem, fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        task.createdAt?.let { MetaItem("Created") { Text(formatLocaleString(it), color = colors.text, fontSize = 0.88.rem) } }
        task.completedAt?.let { MetaItem("Completed") { Text(formatLocaleString(it), color = colors.text, fontSize = 0.88.rem) } }
        task.sprintId?.let { sprintId ->
            MetaItem("Sprint") {
                Text("Sprint ", color = colors.text, fontSize = 0.75.rem, fontWeight = FontWeight.SemiBold)
                Text(ui.sprints.firstOrNull { it.id == sprintId }?.name ?: "Sprint #$sprintId", color = colors.text, fontSize = 0.88.rem)
            }
        }
    }
    if (task.sprintId != null) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Move to:", color = colors.textMuted, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold)
            COLUMNS.forEach { col ->
                val active = task.status == col.id
                val tint = colors.tone(col.tone)
                Text(
                    "${col.icon} ${col.label}",
                    color = if (active) Color.White else colors.text,
                    fontSize = 0.78.rem,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) tint else colors.glass)
                        .border(1.dp, if (active) tint else colors.border, RoundedCornerShape(8.dp))
                        .clickable(enabled = !active) { viewModel.detailStatus(task, col) }
                        .padding(horizontal = 12.dp, vertical = 5.6.dp),
                )
            }
        }
    }
}

@Composable
private fun MetaItem(label: String, value: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.glass, RoundedCornerShape(8.dp))
            .border(1.dp, colors.glassBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 13.6.dp, vertical = 10.4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), color = colors.textMuted, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.rem)
        Row(verticalAlignment = Alignment.CenterVertically) { value() }
    }
}

@Composable
private fun PersonValue(person: TaskPerson) {
    UserAvatar(person.display(), avatarPath(person.avatar), 20.dp)
    Spacer(Modifier.width(6.4.dp))
    Text(person.display(), color = LocalWebColors.current.text, fontSize = 0.88.rem)
}

// ── Edit mode ────────────────────────────────────────────────────────────

@Composable
private fun EditMode(task: Task, ui: TaskUiState, viewModel: TaskViewModel, draft: TaskEditDraft, onDraft: (TaskEditDraft) -> Unit) {
    val colors = LocalWebColors.current
    val backlogLike = task.isBacklogItem || ui.tab == TaskTab.Backlog
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            FieldLabel("Title", uppercase = true, fontSize = 0.8.rem)
            WebTextField(draft.title, { onDraft(draft.copy(title = it)) }, "", maxLength = 200)
        }
        Column {
            FieldLabel("Description", uppercase = true, fontSize = 0.8.rem)
            WebTextField(draft.description, { onDraft(draft.copy(description = it)) }, "Description", singleLine = false, minLines = 4)
        }
        Column {
            FieldLabel("Priority")
            WebSelect(PRIORITIES.map { it.value to "${it.icon} ${it.label}" }, draft.priority, { onDraft(draft.copy(priority = it)) }, Modifier.fillMaxWidth())
        }
        Column {
            FieldLabel("Assign to")
            WebSelect(assigneeOptions(ui.assignableUsers), draft.assignedTo, { onDraft(draft.copy(assignedTo = it)) }, Modifier.fillMaxWidth())
        }
        Column {
            FieldLabel(if (backlogLike) "Due date / Schedule to" else "Due date")
            WebDateField(draft.dueDate, { onDraft(draft.copy(dueDate = it)) }, Modifier.fillMaxWidth())
        }
        if (ui.sprints.isNotEmpty()) {
            Column {
                FieldLabel("🏃 Sprint")
                WebSelect(sprintOptions(ui.sprints), draft.sprintId, { id ->
                    val end = id?.let { wanted -> ui.sprints.firstOrNull { it.id == wanted }?.endDate?.take(10) }
                    onDraft(draft.copy(sprintId = id, dueDate = if (id == null) "" else end ?: draft.dueDate))
                }, Modifier.fillMaxWidth())
            }
        }
        LabelSelector(ui.labels, draft.labels) { id ->
            onDraft(draft.copy(labels = if (id in draft.labels) draft.labels - id else draft.labels + id))
        }
        Column {
            FieldLabel("Type")
            WebSelect(typeOptions(ui.agile), draft.workItemTypeId, { onDraft(draft.copy(workItemTypeId = it)) }, Modifier.fillMaxWidth())
        }
        if (ui.projects.isNotEmpty()) {
            Column {
                FieldLabel("Project", Icons.Outlined.Folder)
                if (task.projectId != null) {
                    // One-way: an issue key is permanent once assigned.
                    val tint = task.project?.color?.let { hexColor(it) }
                    Row(
                        Modifier
                            .background(tint?.hexAlpha(0x22) ?: Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, null, Modifier.size(12.dp), tint = tint ?: colors.text)
                        Spacer(Modifier.width(6.dp))
                        Text(task.issueKey ?: task.project?.key ?: "—", color = tint ?: colors.text, fontSize = 12.dp.value.let { 0.75.rem }, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    WebSelect(projectOptions(ui.projects), draft.projectId, { onDraft(draft.copy(projectId = it)) }, Modifier.fillMaxWidth())
                }
            }
        }
        StoryPointPicker(draft.storyPoints, { onDraft(draft.copy(storyPoints = it)) }, ui.agile)
        Row {
            if (backlogLike) {
                WebButton("Schedule to Day", { viewModel.schedule(task.id, task.title, draft.dueDate, closeAfter = true) }, small = true, icon = Icons.Outlined.CalendarMonth)
            } else {
                WebButton("Move to Backlog", { viewModel.unschedule(task.id, task.title, closeAfter = true) }, small = true, icon = Icons.Outlined.Inventory2)
            }
        }
        BlockerControl(task, ui, viewModel)
        AcceptanceCriteriaEditor(task.id, ui, viewModel)
        ParentChildPanel(task, ui, viewModel)
        val isEpic = ui.agile.type(task.workItemTypeId)?.isEpic == true
        if (!isEpic) DependenciesPanel(task.id, ui, viewModel)
        if (ui.customFieldsEnabled) CustomFieldsEditor(task, viewModel)
        Spacer(Modifier.height(8.dp))
    }
}

/** `BlockerControl`: toggle + reason. */
@Composable
private fun BlockerControl(task: Task, ui: TaskUiState, viewModel: TaskViewModel) {
    if (!ui.agile.features.blockers) return
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reason by remember(task.id) { mutableStateOf(task.blockedReason.orEmpty()) }
    fun apply(blocked: Boolean, why: String?) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { viewModel.repo.setBlocker(task.id, blocked, why) }.isSuccess }
            busy = false
            if (ok) {
                editing = false
                viewModel.refreshDetail()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!editing) {
            val tint = if (task.isBlocked) colors.danger else colors.textSecondary
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (task.isBlocked) colors.danger.copy(alpha = 0.12f) else colors.surface)
                    .border(1.dp, if (task.isBlocked) colors.danger.copy(alpha = 0.4f) else colors.border, RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) { if (task.isBlocked) apply(false, null) else editing = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(13.dp), tint = tint)
                Spacer(Modifier.width(4.dp))
                Text(if (task.isBlocked) "Blocked" else "Mark blocked", color = tint, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold)
            }
            if (task.isBlocked && !task.blockedReason.isNullOrBlank()) {
                Text("“${task.blockedReason}”", color = colors.textMuted, fontSize = 0.78.rem)
            }
        } else {
            WebTextField(reason, { reason = it }, "Why is this blocked?", maxLength = 500)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WebButton("Block", { apply(true, reason) }, style = BtnStyle.Danger, small = true, enabled = !busy)
                WebButton("Cancel", { editing = false }, small = true)
            }
        }
    }
}

/** `AcceptanceCriteria`: optimistic checklist; every change PUTs the whole list. */
@Composable
private fun AcceptanceCriteriaEditor(taskId: Long, ui: TaskUiState, viewModel: TaskViewModel) {
    if (!ui.agile.features.acceptanceCriteria) return
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    val items = remember(taskId) { mutableStateListOf<AcceptanceCriterion>() }
    var loading by remember(taskId) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(taskId) {
        val loaded = withContext(Dispatchers.IO) { runCatching { viewModel.repo.loadCriteria(taskId) }.getOrDefault(emptyList()) }
        items.clear(); items.addAll(loaded); loading = false
    }
    fun persist(next: List<AcceptanceCriterion>) {
        saving = true
        error = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { viewModel.repo.saveCriteria(taskId, next.map { CriterionPayload(it.id, it.text, it.done) }) }
            }
            result.onSuccess { items.clear(); items.addAll(it) }.onFailure { error = it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to save" }
            saving = false
        }
    }
    val done = items.count { it.done }
    val pct = if (items.isEmpty()) 0 else Math.round(done * 100f / items.size)
    Column(
        Modifier.fillMaxWidth().background(colors.glass, RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Acceptance Criteria", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold)
            if (items.isNotEmpty()) Text("  $done/${items.size} ($pct%)", color = colors.primary, fontSize = 0.75.rem)
            if (saving) Text("  Saving…", color = colors.textMuted, fontSize = 0.72.rem)
        }
        if (items.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                color = colors.success, trackColor = colors.surfaceHover, drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
        if (loading) Text("Loading…", color = colors.textMuted, fontSize = 0.8.rem)
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    if (item.done) "Mark incomplete" else "Mark complete",
                    Modifier.size(20.dp).clickable(enabled = !saving) {
                        val next = items.toList().mapIndexed { i, c -> if (i == index) c.copy(done = !c.done) else c }
                        items[index] = next[index]
                        persist(next)
                    },
                    tint = if (item.done) colors.success else colors.textMuted,
                )
                Spacer(Modifier.width(6.dp))
                var text by remember(item.id, item.text) { mutableStateOf(item.text) }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = if (item.done) colors.textMuted else colors.text,
                        fontSize = 0.85.rem,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val next = items.toList().toMutableList()
                        if (text.isBlank()) next.removeAt(index) else next[index] = item.copy(text = text)
                        persist(next)
                    }),
                    modifier = Modifier.weight(1f).onFocusChanged { focus ->
                        if (!focus.isFocused && text != item.text) {
                            val next = items.toList().toMutableList()
                            if (text.isBlank()) next.removeAt(index) else next[index] = item.copy(text = text)
                            persist(next)
                        }
                    },
                )
                Icon(Icons.Outlined.Close, "Remove criterion", Modifier.size(16.dp).clickable {
                    val next = items.toList().filterIndexed { i, _ -> i != index }
                    items.removeAt(index)
                    persist(next)
                }, tint = colors.textMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, null, Modifier.size(13.dp), tint = colors.textMuted)
            Spacer(Modifier.width(6.dp))
            WebTextField(
                adding, { adding = it }, "Add a criterion and press Enter…", maxLength = 500,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val text = adding.trim()
                    if (text.isNotEmpty()) {
                        val next = items.toList() + AcceptanceCriterion(null, text, false)
                        items.add(next.last())
                        adding = ""
                        persist(next)
                    }
                }),
            )
        }
        if (error.isNotEmpty()) Text(error, color = colors.danger, fontSize = 0.78.rem)
    }
}

/** ParentChildPanel: Epics list children + rollup; others show "Part of" + a picker. */
@Composable
private fun ParentChildPanel(task: Task, ui: TaskUiState, viewModel: TaskViewModel) {
    if (!ui.agile.features.epics) return
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    val isEpic = ui.agile.type(task.workItemTypeId)?.isEpic == true
    var loading by remember(task.id, isEpic) { mutableStateOf(true) }
    var children by remember(task.id) { mutableStateOf<ChildrenResponse?>(null) }
    var parent by remember(task.id) { mutableStateOf<ParentTask?>(null) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(task.id, isEpic, reloadKey) {
        withContext(Dispatchers.IO) {
            if (isEpic) {
                children = runCatching { viewModel.repo.loadChildren(task.id) }.getOrNull()
                parent = null
            } else {
                parent = runCatching { viewModel.repo.loadParent(task.id) }.getOrNull()
                children = null
            }
        }
        loading = false
    }
    fun setParent(id: Long?) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { viewModel.repo.setParent(task.id, id) } }
            result.onSuccess {
                picking = false
                reloadKey++
                viewModel.refreshDetail()
            }.onFailure { error = it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to set parent" }
        }
    }
    if (loading) {
        Text("Loading…", color = colors.textMuted, fontSize = 0.8.rem)
        return
    }
    if (isEpic) {
        PanelBox {
            val rollup = children?.rollup
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Link, null, Modifier.size(13.dp), tint = colors.text)
                Spacer(Modifier.width(4.dp))
                Text("Child tickets", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold)
                if (rollup != null && rollup.totalChildren > 0) {
                    Text(
                        "  ${rollup.doneChildren}/${rollup.totalChildren} done" +
                            if (rollup.totalPoints > 0) " · ${formatPoints(rollup.donePoints)}/${formatPoints(rollup.totalPoints)} pts (${rollup.percentByPoints}%)" else "",
                        color = colors.textMuted, fontSize = 0.72.rem,
                    )
                }
            }
            val list = children?.children.orEmpty()
            if (list.isEmpty()) {
                Text("No child tickets yet. Open any ticket → Edit → set this Epic as its Parent.", color = colors.textMuted, fontSize = 0.78.rem)
            } else {
                list.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        c.typeName?.let { name ->
                            val tint = hexColor(c.typeColor, colors.textSecondary)
                            Text(name, color = tint, fontSize = 0.68.rem, modifier = Modifier.background(tint.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        TicketId("#${c.id}")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            c.title, color = colors.primary, fontSize = 0.82.rem, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickable { openLinked(viewModel, c.id) },
                        )
                        c.stateName?.let { name ->
                            val tint = hexColor(c.stateColor, Color(0xFF6B7280))
                            Text(name, color = tint, fontSize = 0.68.rem, modifier = Modifier.padding(start = 6.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(99.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                        c.storyPoints?.let { Text(formatPoints(it), color = colors.primary, fontSize = 0.7.rem, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp)) }
                    }
                }
            }
            if (error.isNotEmpty()) Text(error, color = colors.danger, fontSize = 0.78.rem)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val p = parent
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p != null) {
                Row(
                    Modifier.weight(1f, fill = false).background(colors.surface, RoundedCornerShape(99.dp)).border(1.dp, colors.border, RoundedCornerShape(99.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Link, null, Modifier.size(11.dp), tint = colors.textMuted)
                    Spacer(Modifier.width(4.dp))
                    Text("Part of ", color = colors.textMuted, fontSize = 0.75.rem)
                    Text(
                        "#${p.id} ${p.title}", color = colors.primary, fontSize = 0.75.rem, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable { openLinked(viewModel, p.id) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Close, "Detach from parent", Modifier.size(11.dp).clickable { setParent(null) }, tint = colors.textMuted)
                }
                if (!picking) Text("Change parent", color = colors.primary, fontSize = 0.75.rem, modifier = Modifier.clickable { picking = true })
            } else if (!picking) {
                Row(Modifier.clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Link, null, Modifier.size(11.dp), tint = colors.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("Link to parent…", color = colors.primary, fontSize = 0.75.rem)
                }
            }
        }
        if (picking) {
            val epicIds = ui.agile.workItemTypes.filter { it.isEpic }.map { it.id }.toSet()
            TaskPicker(
                placeholder = "Search by ID or title (Epics shown first)…",
                excludeId = task.id,
                viewModel = viewModel,
                sort = { list -> list.sortedWith(compareBy<QuickTask> { if (it.workItemTypeId in epicIds) 0 else 1 }.thenByDescending { it.id }) },
                badge = { if (it.workItemTypeId in epicIds) "Epic" else null },
                onPick = { setParent(it.id) },
                onCancel = { picking = false },
            )
        }
        if (error.isNotEmpty()) Text(error, color = colors.danger, fontSize = 0.78.rem)
    }
}

private fun openLinked(viewModel: TaskViewModel, id: Long) = viewModel.openDetailById(id)

private val DEP_TYPES = listOf("blocks" to "Blocks", "relates" to "Relates to", "duplicates" to "Duplicates", "clones" to "Clones")

/** `DependenciesPanel`. */
@Composable
private fun DependenciesPanel(taskId: Long, ui: TaskUiState, viewModel: TaskViewModel) {
    if (!ui.agile.features.dependencies) return
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var data by remember(taskId) { mutableStateOf(DependenciesResponse()) }
    var loading by remember(taskId) { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    var linkType by remember { mutableStateOf("blocks") }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(taskId, reloadKey) {
        data = withContext(Dispatchers.IO) { runCatching { viewModel.repo.loadDependencies(taskId) }.getOrDefault(DependenciesResponse()) }
        loading = false
    }
    if (loading) {
        Text("Loading dependencies…", color = colors.textMuted, fontSize = 0.8.rem)
        return
    }
    PanelBox {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Link, null, Modifier.size(13.dp), tint = colors.text)
            Spacer(Modifier.width(4.dp))
            Text("Dependencies", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            WebButton(if (adding) "Cancel" else "Add", { adding = !adding }, small = true, icon = Icons.Outlined.Add)
        }
        if (adding) {
            WebSelect(DEP_TYPES, linkType, { linkType = it }, Modifier.fillMaxWidth())
            TaskPicker(
                placeholder = "Search tasks by ID or title…",
                excludeId = taskId,
                viewModel = viewModel,
                onPick = { other ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { viewModel.repo.addDependency(taskId, other.id, linkType) } }
                        result.onSuccess { adding = false; reloadKey++ }
                            .onFailure { error = it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to add link" }
                    }
                },
                onCancel = null,
            )
        }
        listOf(Triple("Blocks", data.blocks, "This task blocks…"), Triple("Blocked by", data.blockedBy, "This task is blocked by…")).forEach { (title, list, hint) ->
            Text(title, color = colors.textMuted, fontSize = 0.72.rem, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            if (list.isEmpty()) Text(hint, color = colors.textMuted, fontSize = 0.78.rem)
            list.forEach { dep ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dep.type, color = colors.primary, fontSize = 0.68.rem, modifier = Modifier.background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    Spacer(Modifier.width(6.dp))
                    TicketId("#${dep.id}")
                    Spacer(Modifier.width(6.dp))
                    Text(
                        dep.title, color = if (dep.isBlocked) colors.danger else colors.text, fontSize = 0.82.rem, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { openLinked(viewModel, dep.id) },
                    )
                    Icon(Icons.Outlined.Close, "Remove link", Modifier.size(14.dp).clickable {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { viewModel.repo.removeDependency(taskId, dep.linkId) } }
                            result.onSuccess { reloadKey++ }.onFailure { error = it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to remove link" }
                        }
                    }, tint = colors.textMuted)
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = colors.danger, fontSize = 0.78.rem)
    }
}

/** Debounced (200 ms) `GET /tasks/lookup/quicksearch` picker. */
@Composable
private fun TaskPicker(
    placeholder: String,
    excludeId: Long,
    viewModel: TaskViewModel,
    onPick: (QuickTask) -> Unit,
    onCancel: (() -> Unit)?,
    sort: (List<QuickTask>) -> List<QuickTask> = { it },
    badge: (QuickTask) -> String? = { null },
) {
    val colors = LocalWebColors.current
    var search by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<QuickTask>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(search) {
        if (search.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        searching = true
        results = sort(withContext(Dispatchers.IO) { runCatching { viewModel.repo.quickSearch(search.trim()) }.getOrDefault(emptyList()) }.filter { it.id != excludeId })
        searching = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).background(colors.inputBg, RoundedCornerShape(6.dp)).border(1.dp, colors.inputBorder, RoundedCornerShape(6.dp)).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(12.dp), tint = colors.textMuted)
                WebTextField(search, { search = it }, placeholder, modifier = Modifier.border(0.dp, Color.Transparent), fontSize = 0.82.rem)
            }
            if (onCancel != null) {
                Spacer(Modifier.width(6.dp))
                WebButton("Cancel", onCancel, small = true)
            }
        }
        if (searching) Text("Searching…", color = colors.textMuted, fontSize = 0.75.rem)
        if (!searching && search.isNotBlank() && results.isEmpty()) Text("No matching tasks", color = colors.textMuted, fontSize = 0.75.rem)
        if (results.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(colors.bgElevated, RoundedCornerShape(6.dp)).border(1.dp, colors.border, RoundedCornerShape(6.dp))) {
                results.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        badge(r)?.let {
                            Text(it, color = Color(0xFF8B5CF6), fontSize = 0.65.rem, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0x228B5CF6), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        TicketId("#${r.id}")
                        Spacer(Modifier.width(6.dp))
                        Text(r.title, color = colors.text, fontSize = 0.82.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelBox(content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier.fillMaxWidth().background(colors.glass, RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

// ── Custom fields (`CustomFieldRenderer`) ────────────────────────────────

/** Loads the org's field definitions + this task's values once per task. */
@Composable
private fun rememberCustomFields(taskId: Long, viewModel: TaskViewModel, reloadKey: Int = 0): Pair<List<CustomFieldDef>, JsonObject?> {
    var defs by remember { mutableStateOf<List<CustomFieldDef>>(emptyList()) }
    var values by remember(taskId) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(taskId, reloadKey) {
        withContext(Dispatchers.IO) {
            defs = runCatching { viewModel.repo.loadCustomFields() }.getOrDefault(emptyList())
            values = if (defs.isEmpty()) JsonObject(emptyMap()) else runCatching { viewModel.repo.loadCustomFieldValues(taskId) }.getOrDefault(JsonObject(emptyMap()))
        }
    }
    return defs to values
}

private fun JsonElement?.isEmptyValue(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonArray -> isEmpty()
    is JsonPrimitive -> isString && content.isEmpty()
    else -> false
}

private fun optionLabel(field: CustomFieldDef, value: String): String =
    field.options.orEmpty().firstOrNull { it.value.contentOrNull == value }?.label?.ifEmpty { null } ?: value

private fun displayValue(field: CustomFieldDef, value: JsonElement?): String = when {
    value.isEmptyValue() -> "—"
    field.fieldType == "checkbox" -> if ((value as? JsonPrimitive)?.booleanOrNull == true) "✓ Yes" else "— No"
    field.fieldType == "select" -> optionLabel(field, (value as JsonPrimitive).content)
    field.fieldType == "multiselect" -> (value as? JsonArray ?: JsonArray(listOf(value!!))).joinToString(", ") { optionLabel(field, (it as? JsonPrimitive)?.content.orEmpty()) }
    else -> (value as? JsonPrimitive)?.content ?: value.toString()
}

/** `CustomFieldsSummary` (view mode). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomFieldsSummary(taskId: Long, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val (defs, values) = rememberCustomFields(taskId, viewModel)
    val shown = defs.filter { !values?.get(it.id.toString()).isEmptyValue() }
    if (shown.isEmpty()) return
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { f ->
            Row {
                Text("${f.label}: ", color = colors.textMuted, fontSize = 0.78.rem)
                Text(displayValue(f, values?.get(f.id.toString())), color = colors.text, fontSize = 0.78.rem)
            }
        }
    }
}

/** `CustomFieldsEditor` (edit mode): per-type inputs + "Save" when dirty. */
@Composable
private fun CustomFieldsEditor(task: Task, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    val (defs, loaded) = rememberCustomFields(task.id, viewModel)
    val visible = defs.filter { f -> f.appliesToTypes.isNullOrEmpty() || task.workItemTypeId == null || task.workItemTypeId in f.appliesToTypes }
    if (visible.isEmpty()) return
    var original by remember(loaded) { mutableStateOf(loaded ?: JsonObject(emptyMap())) }
    var values by remember(loaded) { mutableStateOf(loaded ?: JsonObject(emptyMap())) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun set(id: Long, value: JsonElement) { values = JsonObject(values + (id.toString() to value)) }
    PanelBox {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Custom fields", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (values != original) {
                WebButton(if (saving) " Saving…" else " Save", {
                    val missing = visible.filter { it.isRequired && it.fieldType != "checkbox" && values[it.id.toString()].isEmptyValue() }
                    if (missing.isNotEmpty()) {
                        error = "Required: " + missing.joinToString(", ") { it.label }
                        return@WebButton
                    }
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { viewModel.repo.saveCustomFieldValues(task.id, values) } }
                        result.onSuccess { fresh -> values = fresh; original = fresh; error = "" }
                            .onFailure { error = it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to save custom fields" }
                        saving = false
                    }
                }, style = BtnStyle.Primary, small = true, enabled = !saving, icon = Icons.Outlined.Save)
            }
        }
        if (error.isNotEmpty()) Text(error, color = colors.danger, fontSize = 0.78.rem)
        if (loaded == null) Text("Loading…", color = colors.textMuted, fontSize = 0.8.rem)
        visible.forEach { f ->
            val value = values[f.id.toString()]
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (f.fieldType != "checkbox") {
                    Row {
                        Text(f.label, color = colors.textSecondary, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold)
                        if (f.isRequired) Text("*", color = colors.danger, fontSize = 0.78.rem)
                    }
                    f.description?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.textMuted, fontSize = 0.72.rem) }
                    Spacer(Modifier.height(4.dp))
                }
                val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                when (f.fieldType) {
                    "number" -> WebTextField(text, { set(f.id, it.toDoubleOrNull()?.let(::JsonPrimitive) ?: if (it.isEmpty()) JsonNull else JsonPrimitive(it)) }, f.label, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    "date" -> WebDateField(text, { set(f.id, if (it.isEmpty()) JsonNull else JsonPrimitive(it)) }, Modifier.fillMaxWidth())
                    "url" -> WebTextField(text, { set(f.id, JsonPrimitive(it)) }, "https://…", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    "checkbox" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                            onCheckedChange = { set(f.id, JsonPrimitive(it)) },
                            colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.textMuted),
                        )
                        Text(f.label, color = colors.text, fontSize = 0.85.rem)
                        if (f.isRequired) Text("*", color = colors.danger, fontSize = 0.85.rem)
                    }
                    "select" -> WebSelect(
                        listOf("" to "—") + f.options.orEmpty().map { it.value.content to it.label.ifEmpty { it.value.content } },
                        text, { set(f.id, if (it.isEmpty()) JsonNull else JsonPrimitive(it)) }, Modifier.fillMaxWidth(),
                    )
                    "multiselect" -> {
                        val selected = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                        f.options.orEmpty().forEach { opt ->
                            val on = opt.value.content in selected
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                                val next = if (on) selected - opt.value.content else selected + opt.value.content
                                set(f.id, JsonArray(next.map(::JsonPrimitive)))
                            }) {
                                Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.textMuted))
                                Text(opt.label.ifEmpty { opt.value.content }, color = colors.text, fontSize = 0.85.rem)
                            }
                        }
                    }
                    else -> WebTextField(text, { set(f.id, JsonPrimitive(it)) }, f.label)
                }
            }
        }
    }
}

// ── Comments · History ───────────────────────────────────────────────────

@Composable
private fun DetailTabs(task: Task, ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            DetailTabButton(Icons.Outlined.ChatBubbleOutline, "Comments", ui.detailComments.size, ui.detailTab == DetailTab.Comments) { viewModel.setDetailTab(DetailTab.Comments) }
            DetailTabButton(Icons.Outlined.Schedule, "History", ui.history.size, ui.detailTab == DetailTab.History) { viewModel.setDetailTab(DetailTab.History) }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.border))
        Spacer(Modifier.height(16.dp))
        when (ui.detailTab) {
            DetailTab.Comments -> CommentSection(
                taskId = task.id,
                comments = ui.detailComments,
                loading = ui.detailLoading,
                currentUserId = ui.userId,
                users = ui.assignableUsers,
                viewModel = viewModel,
            )
            DetailTab.History -> HistoryList(ui.history)
        }
    }
}

@Composable
private fun DetailTabButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (active) colors.primary else colors.textMuted
            Icon(icon, null, Modifier.size(13.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
            Text(label, color = tint, fontSize = 0.85.rem, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.8.dp))
            Text(
                "$count", color = colors.textMuted, fontSize = 0.72.rem, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(colors.surfaceHover, RoundedCornerShape(99.dp)).padding(horizontal = 6.4.dp, vertical = 1.6.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) colors.primary else Color.Transparent))
    }
}

@Composable
private fun HistoryList(history: List<TaskHistoryEntry>) {
    val colors = LocalWebColors.current
    if (history.isEmpty()) {
        Text("No history recorded yet.", color = colors.textMuted, fontSize = 0.82.rem, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    Column {
        history.forEachIndexed { index, h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.6.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).background(colors.primary.copy(alpha = 0.12f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Text(historyIcon(h.action), color = colors.text, fontSize = 0.8.rem)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.text)) {
                                append(h.fullName?.ifEmpty { null } ?: h.username.orEmpty())
                            }
                            append(" ")
                            historyText(h).forEach { (part, text) ->
                                when (part) {
                                    HistoryPart.Plain -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.textMuted)) { append(text) }
                                    HistoryPart.Old -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.textMuted.copy(alpha = colors.textMuted.alpha * 0.6f), textDecoration = TextDecoration.LineThrough)) { append(text) }
                                    HistoryPart.New -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.text, fontWeight = FontWeight.SemiBold)) { append(text) }
                                }
                            }
                        },
                        fontSize = 0.82.rem,
                    )
                    Text(formatLocaleString(h.createdAt), color = colors.textMuted.copy(alpha = colors.textMuted.alpha * 0.7f), fontSize = 0.7.rem)
                }
            }
            if (index < history.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = colors.border.alpha * 0.5f)))
        }
    }
}
