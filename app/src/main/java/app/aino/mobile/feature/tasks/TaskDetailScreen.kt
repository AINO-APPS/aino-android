package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
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
import app.aino.mobile.core.designsystem.icons.HeroIcons

/**
 * Task detail (full screen). A top bar with the issue key and actions; the
 * title and description (✎ to edit); a properties card where each field
 * (status sits under priority; only the assignee/reporter or an org admin
 * can change it) is edited inline through a picker sheet; then sections —
 * Comments · Activity · Checklist · Links · Fields. Realtime edits from
 * web/desktop refresh the page; while editing, a banner offers to reload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(viewModel: TaskViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val task = ui.detail
    LaunchedEffect(task == null) { if (task == null) onClose() }
    task ?: return
    val colors = LocalWebColors.current
    var draft by remember(task.id, ui.detailEditing) { mutableStateOf(TaskEditDraft.from(task)) }
    var menu by remember { mutableStateOf(false) }
    var statusSheet by remember { mutableStateOf(false) }
    var blockSheet by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copyKey = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(task.displayKey)) }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg, titleContentColor = colors.text),
                navigationIcon = {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = viewModel::closeDetail), contentAlignment = Alignment.Center) {
                        Icon(HeroIcons.ArrowLeft, "Back", Modifier.size(22.dp), tint = colors.text)
                    }
                },
                title = {
                    val projectColor = task.project?.color?.let { hexColor(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.displayKey,
                            color = projectColor ?: colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 0.95.rem,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Copy key", onClick = copyKey).padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        if (ui.detailLoading) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(Modifier.size(14.dp), color = colors.primary, strokeWidth = 2.dp)
                        }
                    }
                },
                actions = {
                    if (ui.detailEditing) {
                        TextButton(onClick = viewModel::cancelEdit) { Text("Cancel", color = colors.textSecondary) }
                        TextButton(onClick = { viewModel.saveEdit(draft) }, enabled = draft.title.isNotBlank()) { Text("Save", color = colors.primary, fontWeight = FontWeight.Bold) }
                    } else {
                        Box {
                            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "More actions") { menu = true }, contentAlignment = Alignment.Center) {
                                Icon(HeroIcons.EllipsisVertical, "More actions", Modifier.size(20.dp), tint = colors.text)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(colors.bgElevated)) {
                                MenuItem(HeroIcons.PencilSquare, "Edit title & description") { menu = false; viewModel.startEdit() }
                                if (ui.agile.features.blockers) {
                                    MenuItem(HeroIcons.NoSymbol, if (task.isBlocked) "Clear blocker" else "Mark blocked") { menu = false; blockSheet = true }
                                }
                                MenuItem(HeroIcons.CalendarDays, if (task.date != null) "Reschedule" else "Schedule to a day") { menu = false; scheduleOpen = true }
                                if (!task.isBacklogItem) {
                                    MenuItem(HeroIcons.ArchiveBox, "Move to backlog") { menu = false; viewModel.unschedule(task.id, task.title, closeAfter = true) }
                                }
                                MenuItem(HeroIcons.DocumentDuplicate, "Copy ${task.displayKey}") { menu = false; copyKey() }
                                MenuItem(HeroIcons.ArrowPath, "Refresh") { menu = false; viewModel.refreshDetail() }
                                if (ui.userId != null && ui.userId == task.userId) {
                                    MenuItem(HeroIcons.Trash, "Delete", danger = true) { menu = false; viewModel.requestDelete(task) }
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (ui.detailStale) {
                Row(
                    Modifier.fillMaxWidth().background(colors.warning.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HeroIcons.ArrowPath, null, Modifier.size(16.dp), tint = colors.warning)
                    Spacer(Modifier.width(8.dp))
                    Text("Updated elsewhere while you were editing.", color = colors.text, fontSize = 0.82.rem, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::cancelEdit) { Text("Reload", color = colors.warning, fontWeight = FontWeight.Bold) }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                ui.error?.let { ErrorMsg(it, Modifier.padding(bottom = 12.dp)) }
                DetailHero(task, ui, viewModel, draft, { draft = it })
                Spacer(Modifier.height(16.dp))
                PropertiesCard(task, ui, viewModel, onStatus = { statusSheet = true })
                Spacer(Modifier.height(20.dp))
                DetailSections(task, ui, viewModel)
            }
        }
    }

    if (statusSheet) {
        val current = stateOf(task, ui.agile)
        SelectSheet(
            "Status",
            ui.agile.workflowStates.map { SelectOption(it.key, stateLabel(it), color = hexColor(it.color, colors.textMuted)) },
            setOf(current?.key ?: task.status),
            onDismiss = { statusSheet = false },
            onSelect = { keys -> ui.agile.workflowStates.firstOrNull { it.key == keys.firstOrNull() }?.let { viewModel.setStatus(task, it) } },
            icon = HeroIcons.CheckCircle,
        )
    }
    if (blockSheet) BlockerSheet(task, viewModel) { blockSheet = false }
    if (scheduleOpen) {
        ScheduleDialog(task, initial = task.dueDate, onDismiss = { scheduleOpen = false }) { date -> viewModel.schedule(task.id, date, closeAfter = true) }
    }
    ui.confirm?.let { TaskConfirmDialog(it, viewModel::acceptConfirm, viewModel::dismissConfirm) }
}

// ── Hero: type, title, description, blocker ──────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHero(task: Task, ui: TaskUiState, viewModel: TaskViewModel, draft: TaskEditDraft, onDraft: (TaskEditDraft) -> Unit) {
    val colors = LocalWebColors.current
    var expanded by remember(task.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkItemTypeBadge(task.workItemTypeId, ui.agile)
            when {
                task.isBacklogItem -> MetaPill(HeroIcons.ArchiveBox, "Backlog", colors.warning)
                task.date != null -> MetaPill(HeroIcons.CalendarDays, formatDate(task.date), colors.primary)
            }
        }
        if (ui.detailEditing) {
            WebTextField(draft.title, { onDraft(draft.copy(title = it)) }, "Title", maxLength = 200, fontSize = 1.1.rem)
            WebTextField(draft.description, { onDraft(draft.copy(description = it)) }, "Description", singleLine = false, minLines = 5)
        } else {
            Text(
                task.title.ifEmpty { " " },
                color = colors.text,
                fontSize = 1.3.rem,
                fontWeight = FontWeight.Bold,
                lineHeight = 1.3.rem * 1.25f,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Edit title", onClick = viewModel::startEdit),
            )
            val description = task.description?.takeIf { stripHtml(it).isNotBlank() }
            if (description != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(colors.surface)
                        .clickable(onClickLabel = if (expanded) "Collapse description" else "Expand description") { expanded = !expanded }
                        .padding(14.dp),
                ) {
                    TaskHtml(description, colors.textSecondary, 0.92.rem, maxLines = if (expanded) Int.MAX_VALUE else 6)
                    if (!expanded && stripHtml(description).length > 280) {
                        Text("Show more", color = colors.primary, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Add description", onClick = viewModel::startEdit).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HeroIcons.Plus, null, Modifier.size(15.dp), tint = colors.textMuted)
                    Spacer(Modifier.width(6.dp))
                    Text("Add a description", color = colors.textMuted, fontSize = 0.88.rem)
                }
            }
        }
        if (task.isBlocked && ui.agile.features.blockers) {
            Row(
                Modifier.fillMaxWidth().clip(CardShape).background(colors.danger.copy(alpha = 0.12f)).border(1.dp, colors.danger.copy(alpha = 0.3f), CardShape).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HeroIcons.NoSymbol, null, Modifier.size(18.dp), tint = colors.danger)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Blocked", color = colors.danger, fontSize = 0.88.rem, fontWeight = FontWeight.Bold)
                    task.blockedReason?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.textSecondary, fontSize = 0.82.rem) }
                }
            }
        }
    }
}

@Composable
private fun MetaPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = tint)
        Spacer(Modifier.width(5.dp))
        Text(text, color = tint, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold)
    }
}

// ── Properties (inline edit) ─────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertiesCard(task: Task, ui: TaskUiState, viewModel: TaskViewModel, onStatus: () -> Unit) {
    val colors = LocalWebColors.current
    var picker by remember { mutableStateOf<String?>(null) }
    var dueOpen by remember { mutableStateOf(false) }
    var pointsOpen by remember { mutableStateOf(false) }
    SectionCard(padding = 8.dp) {
        PropertyRow(HeroIcons.User, "Assignee", { picker = "assignee" }) {
            val a = task.assignee
            if (a != null) Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(a.display(), avatarPath(a.avatar), 22.dp)
                Spacer(Modifier.width(8.dp))
                PropertyText(a.display())
            } else PropertyText("Unassigned", muted = true)
        }
        PropertyRow(HeroIcons.Flag, "Priority", { picker = "priority" }) {
            val p = priorityOf(task.priority)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityGlyph(task.priority)
                Spacer(Modifier.width(8.dp))
                PropertyText(p.label, color = colors.tone(p.tone))
            }
        }
        val canStatus = canChangeStatus(task, ui.userId, ui.role)
        PropertyRow(HeroIcons.CheckCircle, "Status", if (canStatus) onStatus else null) {
            val state = stateOf(task, ui.agile)
            val column = columnOf(task.status)
            StatusPill(state?.let(::stateLabel) ?: column.label, hexColor(state?.color, colors.tone(column.tone)))
        }
        PropertyRow(HeroIcons.CalendarDays, "Due date", { dueOpen = true }) {
            val due = formatDueDate(task.dueDate)
            val overdue = isDueOverdue(task.dueDate) && task.status != "done"
            if (due != null) PropertyText("${formatDate(task.dueDate)} · $due", color = if (overdue) colors.danger else null)
            else PropertyText("No due date", muted = true)
        }
        if (ui.agileEnabled && ui.sprints.isNotEmpty()) {
            PropertyRow(HeroIcons.RocketLaunch, "Sprint", { picker = "sprint" }) {
                val name = task.sprint?.name ?: task.sprintId?.let { id -> ui.sprints.firstOrNull { it.id == id }?.name ?: "Sprint #$id" }
                PropertyText(name ?: "Backlog (no sprint)", muted = name == null)
            }
        }
        if (ui.labels.isNotEmpty() || task.labels.isNotEmpty()) {
            PropertyRow(HeroIcons.Tag, "Labels", { picker = "labels" }) {
                if (task.labels.isEmpty()) PropertyText("None", muted = true)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { task.labels.forEach { LabelPill(it) } }
            }
        }
        if (ui.agile.features.storyPoints) {
            PropertyRow(HeroIcons.ChartBar, "Estimate", { pointsOpen = true }) {
                val pts = formatPoints(task.storyPoints)
                PropertyText(if (pts.isEmpty()) "Not estimated" else "$pts ${ui.agile.unitLabel}", muted = pts.isEmpty())
            }
        }
        if (ui.agile.workItemTypes.any { it.id != 0L }) {
            PropertyRow(HeroIcons.Square3Stack3d, "Type", { picker = "type" }) {
                PropertyText(ui.agile.type(task.workItemTypeId)?.name ?: "Default", muted = task.workItemTypeId == null)
            }
        }
        if (ui.projects.isNotEmpty() || task.project != null) {
            // One-way: an issue key is permanent once a project is set.
            PropertyRow(HeroIcons.Folder, "Project", if (task.projectId == null) ({ picker = "project" }) else null) {
                val p = task.project
                PropertyText(p?.let { listOfNotNull(it.key, it.name).joinToString(" · ") } ?: "No project", muted = p == null, color = p?.color?.let { hexColor(it) })
            }
        }
        val creator = task.creator
        if (creator != null) {
            // The enriched row omits the creator's avatar; take it from the assignable-users list.
            val avatar = creator.avatar ?: ui.assignableUsers.firstOrNull { it.id == task.userId }?.avatar
            PropertyRow(HeroIcons.PencilSquare, "Reporter", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(creator.display(), avatarPath(avatar), 22.dp)
                    Spacer(Modifier.width(8.dp))
                    PropertyText(creator.display())
                }
            }
        }
        task.createdAt?.let { PropertyRow(HeroIcons.Clock, "Created", null) { PropertyText(formatLocaleString(it), muted = true) } }
        task.completedAt?.let { PropertyRow(HeroIcons.CheckCircle, "Completed", null) { PropertyText(formatLocaleString(it), muted = true) } }
    }

    when (picker) {
        "priority" -> SelectSheet(
            "Priority",
            PRIORITIES.map { SelectOption(it.value, it.label, color = colors.tone(it.tone)) },
            setOf(task.priority), { picker = null }, { it.firstOrNull()?.let { v -> viewModel.setPriority(task, v) } }, icon = HeroIcons.Flag,
        )
        null -> Unit
        else -> TaskPickers(
            picker = picker,
            ui = ui,
            assignee = task.assignedTo,
            typeId = task.workItemTypeId,
            sprintId = task.sprintId,
            projectId = task.projectId,
            labels = task.labels.map { it.id },
            onDismiss = { picker = null },
            onAssignee = { viewModel.setAssignee(task, it) },
            onType = { viewModel.setWorkItemType(task, it) },
            onSprint = { viewModel.assignSprint(task, it) },
            onProject = { id -> id?.let { viewModel.setProject(task, it) } },
            onLabels = { viewModel.setLabels(task, it) },
        )
    }
    if (dueOpen) DueDateSheet(task, viewModel) { dueOpen = false }
    if (pointsOpen) {
        TaskSheetScaffold("Estimate", { pointsOpen = false }, HeroIcons.ChartBar) {
            StoryPointPicker(task.storyPoints?.let(::formatPoints), { v -> viewModel.setStoryPoints(task, v); pointsOpen = false }, ui.agile)
            Spacer(Modifier.height(12.dp))
            Text("Tap the selected value again to clear it.", color = colors.textMuted, fontSize = 0.76.rem)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateSheet(task: Task, viewModel: TaskViewModel, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    val start = localDateOf(task.dueDate) ?: java.time.LocalDate.now()
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms -> viewModel.setDueDate(task, java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()) }
                onDismiss()
            }) { Text("Set", color = colors.primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row {
                if (task.dueDate != null) TextButton(onClick = { viewModel.setDueDate(task, ""); onDismiss() }) { Text("Clear", color = colors.danger) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
            }
        },
    ) { androidx.compose.material3.DatePicker(state = state) }
}

/** Set / clear the blocker with a reason (`PATCH /tasks/:id/block`). */
@Composable
private fun BlockerSheet(task: Task, viewModel: TaskViewModel, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var reason by remember(task.id) { mutableStateOf(task.blockedReason.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun apply(blocked: Boolean, why: String?) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { viewModel.repo.setBlocker(task.id, blocked, why) } }
            busy = false
            result.onSuccess { viewModel.refreshDetail(); onDismiss() }
                .onFailure { error = it.message?.takeIf { m -> m != "Task action failed" } ?: "Failed to update blocker" }
        }
    }
    TaskSheetScaffold(if (task.isBlocked) "Blocked" else "Mark as blocked", onDismiss, HeroIcons.NoSymbol) {
        WebTextField(reason, { reason = it }, "Why is this blocked?", singleLine = false, minLines = 3, maxLength = 500)
        if (error.isNotEmpty()) Text(error, color = colors.danger, fontSize = 0.78.rem, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (task.isBlocked) {
                PillButton("Clear blocker", { apply(false, null) }, Modifier.weight(1f), enabled = !busy)
                PillButton("Update", { apply(true, reason.trim()) }, Modifier.weight(1f), danger = true, enabled = !busy)
            } else {
                PillButton("Cancel", onDismiss, Modifier.weight(1f))
                PillButton("Mark blocked", { apply(true, reason.trim()) }, Modifier.weight(1f), danger = true, enabled = !busy)
            }
        }
    }
}

// ── Sections ─────────────────────────────────────────────────────────────

@Composable
private fun DetailSections(task: Task, ui: TaskUiState, viewModel: TaskViewModel) {
    val sections = buildList {
        add(DetailTab.Comments)
        add(DetailTab.History)
        if (ui.agile.features.acceptanceCriteria) add(DetailTab.Checklist)
        if (ui.agile.features.dependencies || ui.agile.features.epics) add(DetailTab.Links)
        if (ui.customFieldsEnabled) add(DetailTab.Fields)
    }
    val active = ui.detailTab.takeIf { it in sections } ?: DetailTab.Comments
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.forEach { tab ->
            val count = when (tab) {
                DetailTab.Comments -> ui.detailComments.size
                DetailTab.History -> ui.history.size
                DetailTab.Checklist -> task.acceptanceCriteria.size
                else -> null
            }
            CountChip(tab.label, tab == active, { viewModel.setDetailTab(tab) }, count = count?.takeIf { it > 0 })
        }
    }
    Spacer(Modifier.height(14.dp))
    when (active) {
        DetailTab.Comments -> CommentSection(
            taskId = task.id,
            comments = ui.detailComments,
            loading = ui.detailLoading,
            currentUserId = ui.userId,
            users = ui.assignableUsers,
            viewModel = viewModel,
        )
        DetailTab.History -> HistoryList(ui.history)
        DetailTab.Checklist -> AcceptanceCriteriaEditor(task.id, ui, viewModel)
        DetailTab.Links -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ParentChildPanel(task, ui, viewModel)
            val isEpic = ui.agile.type(task.workItemTypeId)?.isEpic == true
            if (!isEpic) DependenciesPanel(task.id, ui, viewModel)
        }
        DetailTab.Fields -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CustomFieldsEditor(task, viewModel)
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
        if (loading && items.isEmpty()) Text("Loading…", color = colors.textMuted, fontSize = 0.8.rem)
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.done) HeroIcons.SquareCheck else HeroIcons.Square,
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
                Icon(HeroIcons.XMark, "Remove criterion", Modifier.size(16.dp).clickable {
                    val next = items.toList().filterIndexed { i, _ -> i != index }
                    items.removeAt(index)
                    persist(next)
                }, tint = colors.textMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HeroIcons.Plus, null, Modifier.size(13.dp), tint = colors.textMuted)
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
                Icon(HeroIcons.Link, null, Modifier.size(13.dp), tint = colors.text)
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
                    Icon(HeroIcons.Link, null, Modifier.size(11.dp), tint = colors.textMuted)
                    Spacer(Modifier.width(4.dp))
                    Text("Part of ", color = colors.textMuted, fontSize = 0.75.rem)
                    Text(
                        "#${p.id} ${p.title}", color = colors.primary, fontSize = 0.75.rem, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable { openLinked(viewModel, p.id) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(HeroIcons.XMark, "Detach from parent", Modifier.size(11.dp).clickable { setParent(null) }, tint = colors.textMuted)
                }
                if (!picking) Text("Change parent", color = colors.primary, fontSize = 0.75.rem, modifier = Modifier.clickable { picking = true })
            } else if (!picking) {
                Row(Modifier.clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(HeroIcons.Link, null, Modifier.size(11.dp), tint = colors.primary)
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
            Icon(HeroIcons.Link, null, Modifier.size(13.dp), tint = colors.text)
            Spacer(Modifier.width(4.dp))
            Text("Dependencies", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            WebButton(if (adding) "Cancel" else "Add", { adding = !adding }, small = true, icon = HeroIcons.Plus)
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
                    Icon(HeroIcons.XMark, "Remove link", Modifier.size(14.dp).clickable {
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
                Icon(HeroIcons.MagnifyingGlass, null, Modifier.size(12.dp), tint = colors.textMuted)
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
                }, style = BtnStyle.Primary, small = true, enabled = !saving, icon = HeroIcons.DocumentCheck)
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
