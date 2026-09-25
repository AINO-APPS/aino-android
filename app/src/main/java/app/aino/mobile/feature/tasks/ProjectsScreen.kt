package app.aino.mobile.feature.tasks

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * Admin → Projects (`pages/Projects.tsx`). At 430px the web grid is a single
 * column of cards; the tasks table keeps its four columns. The web has no
 * refresh control, so the page pulls to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel, onBack: () -> Unit, onOpenTask: (Long) -> Unit, onChanged: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val changed by rememberUpdatedState(onChanged)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AdminEvent.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                AdminEvent.Changed -> changed()
            }
        }
    }
    AinoFullPage("Projects", onBack = onBack, scrollable = false) {
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { viewModel.refresh(pull = true) }, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                ProjectsHeader(ui, viewModel)
                Spacer(Modifier.height(20.dp))
                ui.error?.let {
                    ErrorMsg(it)
                    Spacer(Modifier.height(12.dp))
                }
                when {
                    ui.loading -> Text(
                        "Loading…",
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                    )
                    ui.projects.isEmpty() && ui.error == null -> EmptyProjects(ui.canEdit, viewModel::openCreate)
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ui.projects.forEach { p ->
                                ProjectCard(
                                    project = p,
                                    canEdit = ui.canEdit,
                                    canDelete = ui.canDelete,
                                    selected = ui.selectedId == p.id,
                                    onSelect = { viewModel.toggleSelected(p) },
                                    onEdit = { viewModel.openEdit(p) },
                                    onArchive = { viewModel.archive(p) },
                                    onDelete = { viewModel.requestDelete(p) },
                                )
                            }
                        }
                        PaginationBar(
                            total = ui.total,
                            limit = ui.limit,
                            offset = ui.offset,
                            pageSizes = PROJECT_PAGE_SIZES,
                            itemLabel = "project",
                            onPageChange = viewModel::setPage,
                            onLimitChange = viewModel::setLimit,
                        )
                    }
                }
                if (ui.selectedId != null) {
                    Spacer(Modifier.height(20.dp))
                    ProjectTasksPanel(ui.panel, viewModel, onOpenTask)
                }
            }
        }
    }
    ui.form?.let { ProjectFormDialog(it, ui.users, viewModel) }
    ui.confirm?.let { BrowserConfirmDialog(it, viewModel::dismissConfirm) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectsHeader(ui: ProjectsUiState, viewModel: ProjectsViewModel) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Projects", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            CodeText(
                listOf(
                    "Jira-style projects with a unique key (e.g. " to false, "WEB" to true,
                    "). Tasks in a project get a stable issue id like " to false, "WEB-123" to true, "." to false,
                ),
                color = colors.textSecondary,
                fontSize = 13.sp,
                codeSize = 12.sp,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AdminCheckbox(ui.includeArchived, viewModel::setIncludeArchived, "Show archived", fontSize = 13.sp)
            if (ui.canEdit) PrimaryButton("New project", Icons.Outlined.Add, onClick = viewModel::openCreate)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.6f)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.accent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Text(
        text,
        color = colors.text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun IconAction(icon: ImageVector, title: String, tint: Color = LocalWebColors.current.text, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = title, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, title, Modifier.size(14.dp), tint = tint) }
}

@Composable
private fun EmptyProjects(canEdit: Boolean, onCreate: () -> Unit) {
    val colors = LocalWebColors.current
    val muted = Color(0xFF9CA3AF)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(48.dp), tint = muted)
        Spacer(Modifier.height(12.dp))
        Text("No projects yet", color = colors.text, fontSize = 1.5.em(), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        CodeText(
            listOf("Group related tasks under a project to get Jira-style issue keys like " to false, "WEB-123" to true, "." to false),
            color = muted,
            fontSize = 14.sp,
            codeSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (canEdit) PrimaryButton("Create your first project", Icons.Outlined.Add, onClick = onCreate)
    }
}

/** h2 default size (1.5em of the 16px body). */
private fun Double.em() = (this * 16).sp

@Composable
private fun ProjectCard(
    project: Project,
    canEdit: Boolean,
    canDelete: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalWebColors.current
    val accent = hexColor(project.color)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .alpha(if (project.isArchived) 0.55f else 1f)
            .then(if (selected) Modifier.border(2.dp, accent, shape) else Modifier)
            .clip(shape)
            .background(colors.cardBg)
            .border(1.dp, colors.border, shape),
    ) {
        Row(
            Modifier.weight(1f).clickable(onClick = onSelect).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.background(accent.copy(alpha = 0x22 / 255f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(14.dp), tint = accent)
                Spacer(Modifier.width(4.dp))
                Text(project.key, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(project.name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                project.description?.takeIf(String::isNotEmpty)?.let {
                    Text(it, color = colors.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    buildAnnotatedString {
                        append("Lead: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(project.leadDisplay()) }
                        append("  •  ${project.taskCount} task${if (project.taskCount != 1) "s" else ""}  •  Next: ")
                        withStyle(codeStyle(11.sp)) { append("${project.key}-${project.nextTaskNumber}") }
                    },
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (canEdit) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            Column(
                Modifier.fillMaxHeight().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                IconAction(Icons.Outlined.Edit, "Edit", onClick = onEdit)
                IconAction(
                    if (project.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    if (project.isArchived) "Unarchive" else "Archive",
                    onClick = onArchive,
                )
                if (canDelete) IconAction(Icons.Outlined.Delete, "Delete", Color(0xFFEF4444), onClick = onDelete)
            }
        }
    }
}

@Composable
private fun ProjectTasksPanel(panel: ProjectTasksState, viewModel: ProjectsViewModel, onOpenTask: (Long) -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.cardBg)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tasks in this project", color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconAction(Icons.Outlined.Close, "Close", onClick = viewModel::closePanel)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        panel.error?.let { ErrorMsg(it, Modifier.padding(16.dp)) }
        when {
            panel.loading -> Text("Loading…", color = colors.text, modifier = Modifier.padding(16.dp))
            panel.tasks.isEmpty() && panel.error == null ->
                Text("No tasks yet. Create a task and assign it to this project.", color = Color(0xFF9CA3AF), modifier = Modifier.padding(16.dp))
            panel.tasks.isNotEmpty() -> {
                TaskTableRow(header = true, cells = listOf("Key", "Title", "Status", "Assignee"))
                panel.tasks.forEach { t ->
                    TaskTableRow(
                        header = false,
                        cells = listOf(t.keyDisplay(), t.title, t.status.orEmpty(), t.assigneeDisplay()),
                        onOpen = { onOpenTask(t.id) },
                    )
                }
                PaginationBar(
                    total = panel.total,
                    limit = panel.limit,
                    offset = panel.offset,
                    pageSizes = PROJECT_TASK_PAGE_SIZES,
                    itemLabel = "task",
                    onPageChange = viewModel::setPanelPage,
                    onLimitChange = viewModel::setPanelLimit,
                    compact = true,
                )
            }
        }
    }
}

private val TASK_COLUMN_WEIGHTS = listOf(0.9f, 1.6f, 0.9f, 1f)

@Composable
private fun TaskTableRow(header: Boolean, cells: List<String>, onOpen: (() -> Unit)? = null) {
    val colors = LocalWebColors.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            cells.forEachIndexed { index, text ->
                val link = onOpen != null && index <= 1
                Text(
                    if (header) text.uppercase() else text,
                    color = when {
                        header -> colors.textSecondary
                        link -> colors.accent
                        else -> colors.text
                    },
                    fontSize = if (header) 11.sp else if (index == 0) 12.sp else 13.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = if (!header && index == 0) FontFamily.Monospace else FontFamily.Default,
                    letterSpacing = if (header) 0.5.sp else 0.sp,
                    modifier = Modifier
                        .weight(TASK_COLUMN_WEIGHTS[index])
                        .then(if (link) Modifier.clickable { onOpen?.invoke() } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (header) colors.border else colors.border.copy(alpha = 0.5f)))
    }
}

@Composable
private fun ProjectFormDialog(form: ProjectFormState, users: List<AssignableUser>, viewModel: ProjectsViewModel) {
    val colors = LocalWebColors.current
    Dialog(onDismissRequest = viewModel::closeForm, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .imePadding()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgElevated)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (form.isEdit) "Edit ${form.editing?.key}" else "New project",
                    color = colors.text,
                    fontSize = 1.5.em(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconAction(Icons.Outlined.Close, "Close", onClick = viewModel::closeForm)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FormField("Name") {
                    AdminInput(form.name, { v -> viewModel.editForm { it.copy(name = v) } }, Modifier.fillMaxWidth(), placeholder = "Website Rewrite", maxLength = 200, fontSize = 13.sp)
                }
                if (!form.isEdit) {
                    FormField("Key") {
                        AdminInput(
                            form.key,
                            { v -> viewModel.editForm { it.copy(key = sanitizeProjectKey(v)) } },
                            Modifier.fillMaxWidth(),
                            placeholder = "WEB",
                            monospace = true,
                            fontSize = 13.sp,
                        )
                        CodeText(
                            listOf(
                                "2–10 chars, uppercase letters/digits/underscores. Used in branches: " to false,
                                "feature/${form.key.ifEmpty { "WEB" }}-123-…" to true,
                                ". Cannot be changed later." to false,
                            ),
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                        )
                        if (form.key.isNotEmpty() && !form.keyValid) {
                            Text("Must start with a letter; only A–Z, 0–9, and underscores.", color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                }
                FormField("Description") {
                    AdminInput(
                        form.description,
                        { v -> viewModel.editForm { it.copy(description = v) } },
                        Modifier.fillMaxWidth().heightIn(min = 70.dp),
                        placeholder = "Optional: what this project is about.",
                        singleLine = false,
                        minLines = 3,
                        maxLength = 2000,
                        fontSize = 13.sp,
                    )
                }
                FormField("Lead") {
                    WebSelect(
                        listOf<Pair<Long?, String>>(null to "— None —") + users.map { it.id to it.display() },
                        form.leadId,
                        { v -> viewModel.editForm { it.copy(leadId = v) } },
                        Modifier.fillMaxWidth(),
                        fontSize = 13.sp,
                    )
                }
                FormField("Colour") {
                    ColorChips(PROJECT_COLORS, form.color, { c -> viewModel.editForm { it.copy(color = c) } }, ring = colors.accent)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    SecondaryButton("Cancel", viewModel::closeForm)
                    PrimaryButton(
                        when {
                            form.saving -> "Saving…"
                            form.isEdit -> "Save"
                            else -> "Create"
                        },
                        enabled = !form.saving,
                        onClick = viewModel::submitForm,
                    )
                }
            }
        }
    }
}

@Composable
private fun FormField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = LocalWebColors.current.text, fontSize = 13.sp)
        content()
    }
}
