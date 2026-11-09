package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

@Composable
internal fun KanbanBoardPage(
    ui: TaskUiState,
    boardUi: ProjectBoardUiState,
    viewModel: TaskViewModel,
    backlog: Boolean = false,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Task) -> Unit,
) {
    val error = if (backlog) boardUi.backlogError else boardUi.boardError
    val response = if (backlog) boardUi.backlog else boardUi.board
    val loading = if (backlog) {
        boardUi.backlogLoading || (!boardUi.backlogLoaded && error == null)
    } else {
        boardUi.boardLoading || (response == null && error == null)
    }
    val colors = LocalWebColors.current

    TaskPage(loading = loading, onRefresh = onRefresh) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "kanban-header") {
                Column {
                    if (loading && response == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            if (backlog) "Loading backlog…" else "Loading board…",
                            color = colors.textMuted,
                            fontSize = 0.8.rem,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    error?.let {
                        ErrorMsg(it, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
                        TextButton(onClick = onRetry, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Try again")
                        }
                    }
                    if (ui.filterCount > 0) {
                        ActiveFilterChips(ui, viewModel, Modifier.padding(bottom = 8.dp))
                    }
                }
            }

            if (response == null && loading) {
                items(3, key = { "kanban-skeleton-$it" }) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { TaskCardSkeleton() }
                }
            } else if (response == null && error != null) {
                item(key = "kanban-error") {
                    TaskEmptyState(
                        HeroIcons.ExclamationTriangle,
                        "Could not load ${if (backlog) "backlog" else "board"}",
                        "Check your connection and try again.",
                    )
                }
            } else if (backlog) {
                val tasks = boardUi.filteredBacklogTasks
                if (tasks.isEmpty()) {
                    item(key = "kanban-backlog-empty") {
                        if (ui.filterCount > 0 && boardUi.backlogTasks.isNotEmpty()) {
                            TaskEmptyState(
                                HeroIcons.MagnifyingGlass,
                                "No tickets match your filters",
                                "Clear the filters to see the project's initial-state tickets.",
                                action = "Clear filters",
                                onAction = viewModel::clearFilters,
                            )
                        } else {
                            TaskEmptyState(
                                HeroIcons.ArchiveBox,
                                "Backlog is empty",
                                "Tickets in the project's initial workflow state appear here.",
                            )
                        }
                    }
                } else {
                    items(tasks, key = { "kanban-backlog-${it.id}" }) { task ->
                        TaskCardM3(
                            task = task,
                            agile = ui.agile,
                            onOpen = { onOpen(task) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            highlighted = task.id in ui.recentlyUpdated,
                        )
                    }
                }
            } else {
                val visibleTasks = boardUi.columns.flatMap(boardUi::tasksForColumn)
                if (boardUi.boardLoading && response == null) {
                    Unit
                } else if (boardUi.columns.isEmpty()) {
                    item(key = "kanban-no-columns") {
                        TaskEmptyState(
                            HeroIcons.ViewColumns,
                            "No workflow columns",
                            "This project's workflow has no active board states.",
                        )
                    }
                } else if (visibleTasks.isEmpty()) {
                    item(key = "kanban-board-empty") {
                        if (ui.filterCount > 0 && boardUi.boardTasks.isNotEmpty()) {
                            TaskEmptyState(
                                HeroIcons.MagnifyingGlass,
                                "No tickets match your filters",
                                "Clear the filters to see the project's board.",
                                action = "Clear filters",
                                onAction = viewModel::clearFilters,
                            )
                        } else {
                            TaskEmptyState(
                                HeroIcons.ViewColumns,
                                "This board is empty",
                                "Open project tickets will appear in their workflow columns.",
                            )
                        }
                    }
                }
                boardUi.columns.forEach { column ->
                    val state = column.state ?: return@forEach
                    val tasks = boardUi.tasksForColumn(column)
                    item(key = "kanban-column-${column.stateId}") {
                        ProjectBoardColumnHeader(
                            state = state,
                            count = column.count,
                            limit = column.limit.takeIf { boardUi.serverProject?.wipEnabled == true },
                            accepting = column.accepting,
                        )
                    }
                    items(tasks, key = { "kanban-ticket-${it.id}" }) { task ->
                        TaskCardM3(
                            task = task,
                            agile = ui.agile,
                            onOpen = { onOpen(task) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            highlighted = task.id in ui.recentlyUpdated,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectBoardColumnHeader(
    state: WorkflowState,
    count: Int,
    limit: Int?,
    accepting: Boolean,
) {
    val colors = LocalWebColors.current
    val stateColor = state.color.takeIf(String::isNotBlank)?.let(::hexColor) ?: colors.primary
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .background(colors.bgElevated, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(stateColor, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(
            state.name.ifBlank { state.key.replace('_', ' ').replaceFirstChar(Char::uppercase) },
            modifier = Modifier.weight(1f).semantics { heading() },
            color = colors.text,
            fontSize = 0.9.rem,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (limit == null) count.toString() else "$count/$limit",
            color = if (limit != null && !accepting) colors.danger else colors.textMuted,
            fontSize = 0.78.rem,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(
                (if (limit != null && !accepting) colors.danger else colors.primary).copy(alpha = 0.12f),
                RoundedCornerShape(99.dp),
            ).padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}
