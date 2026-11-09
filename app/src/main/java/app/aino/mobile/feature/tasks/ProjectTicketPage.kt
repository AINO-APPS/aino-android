package app.aino.mobile.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

private val projectListFields = linkedMapOf(
    "key" to "Key", "title" to "Title", "type" to "Type", "status" to "Status",
    "assignee" to "Assignee", "priority" to "Priority", "start_date" to "Start date", "due_date" to "Due date",
)

@Composable
internal fun ProjectTicketPage(
    ui: ProjectTicketUiState,
    upcoming: Boolean = false,
    onRefresh: () -> Unit,
    onQuery: (ProjectTicketQuery) -> Unit,
    onFilters: (TaskFilters) -> Unit,
    onOpen: (Task) -> Unit,
) {
    val colors = LocalWebColors.current
    TaskPage(loading = ui.loading, onRefresh = onRefresh) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "collection-controls") {
                Column {
                    if (upcoming) {
                        Text("Open tickets grouped by start date, with overdue work first.", color = colors.textSecondary)
                        Text("Calendar is available on the web.", color = colors.textMuted)
                    }
                    ProjectTicketControls(ui, onQuery, onFilters, showSort = !upcoming)
                    if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    ui.error?.let {
                        ErrorMsg(it)
                        TextButton(onClick = onRefresh) { Text("Try again") }
                    }
                }
            }
            if (upcoming) {
                ui.upcoming.forEach { group ->
                    item(key = "group-${group.key}") {
                        Text(
                            group.label,
                            color = if (group.key == "overdue") colors.danger else colors.text,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                        )
                    }
                    items(group.tickets, key = Task::id) { ProjectTicketRow(it, ui.planScope, onOpen) }
                }
            } else {
                items(ui.tickets, key = Task::id) { ProjectTicketRow(it, ui.planScope, onOpen) }
            }
            if (!ui.loading && ui.error == null && (if (upcoming) ui.upcoming.isEmpty() else ui.tickets.isEmpty())) {
                item(key = "empty") {
                    Text(if (upcoming) "No upcoming tickets match these filters." else "No tickets match these filters.", color = colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun ProjectTicketRow(ticket: Task, scope: PlanScope, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val type = scope.agile.workItemTypes.firstOrNull { it.id == ticket.workItemTypeId }?.name ?: "—"
    val status = scope.agile.workflowStates.firstOrNull { it.id == ticket.workflowStateId }?.name
        ?: ticket.workflowStateKey ?: "—"
    Surface(color = colors.cardBg, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().clickable { onOpen(ticket) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ticket.displayKey, color = colors.textMuted)
            Text(ticket.title, color = colors.text, fontWeight = FontWeight.SemiBold)
            Text("$type · $status · ${ticket.priority}", color = colors.textSecondary)
            Text(ticket.assignee?.display() ?: "Unassigned", color = colors.textSecondary)
            Text("Start: ${ticket.startDate?.take(10) ?: "—"} · Due: ${ticket.dueDate?.take(10) ?: "—"}", color = colors.textMuted)
        }
    }
}

@Composable
private fun ProjectTicketControls(
    ui: ProjectTicketUiState,
    onQuery: (ProjectTicketQuery) -> Unit,
    onFilters: (TaskFilters) -> Unit,
    showSort: Boolean,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf("title") }
    val filters = ui.planScope.filters
    val query = ui.query
    Row {
        if (showSort) {
            Column {
                TextButton(onClick = { sortOpen = true }) { Text("Sort: ${projectListFields[query.sort]}") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    projectListFields.forEach { (key, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { sortOpen = false; onQuery(query.copy(sort = key)) })
                    }
                }
            }
            TextButton(onClick = { onQuery(query.copy(direction = if (query.direction == "asc") "desc" else "asc")) }) {
                Text(if (query.direction == "asc") "Ascending" else "Descending")
            }
        }
    }
    Row {
        Column {
            TextButton(onClick = { filterOpen = true }) { Text("Filter: ${projectListFields[field]}") }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                projectListFields.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { field = key; filterOpen = false })
                }
            }
        }
        TextButton(onClick = { onQuery(ProjectTicketQuery()); onFilters(TaskFilters()) }) { Text("Clear filters") }
    }
    val options = when (field) {
        "type" -> ui.planScope.agile.workItemTypes.map { "${it.id}" to it.name }
        "status" -> ui.planScope.agile.workflowStates.map { it.key to it.name }
        "assignee" -> listOf("unassigned" to "Unassigned") + ui.planScope.assignableUsers.map { "${it.id}" to it.display() }
        "priority" -> listOf("high" to "High", "medium" to "Medium", "low" to "Low")
        else -> emptyList()
    }
    val value = when (field) {
        "key" -> query.key
        "title" -> query.title
        "type" -> query.workItemTypeId
        "status" -> filters.status
        "assignee" -> filters.assignee
        "priority" -> filters.priority
        "start_date" -> query.startOn
        else -> query.dueOn
    }
    val setValue: (String) -> Unit = { next ->
        when (field) {
            "key" -> onQuery(query.copy(key = next))
            "title" -> onQuery(query.copy(title = next))
            "type" -> onQuery(query.copy(workItemTypeId = next))
            "status" -> onFilters(filters.copy(status = next))
            "assignee" -> onFilters(filters.copy(assignee = next))
            "priority" -> onFilters(filters.copy(priority = next))
            "start_date" -> onQuery(query.copy(startOn = next))
            else -> onQuery(query.copy(dueOn = next))
        }
    }
    if (options.isNotEmpty()) {
        var choicesOpen by remember(field) { mutableStateOf(false) }
        Column {
            TextButton(onClick = { choicesOpen = true }) { Text(options.firstOrNull { it.first == value }?.second ?: "All") }
            DropdownMenu(expanded = choicesOpen, onDismissRequest = { choicesOpen = false }) {
                (listOf("" to "All") + options).forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { choicesOpen = false; setValue(key) })
                }
            }
        }
    } else {
        OutlinedTextField(
            value = value, onValueChange = setValue, singleLine = true,
            label = { Text("Filter ${projectListFields[field]?.lowercase()}") },
            placeholder = { if (field.endsWith("_date")) Text("YYYY-MM-DD") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
