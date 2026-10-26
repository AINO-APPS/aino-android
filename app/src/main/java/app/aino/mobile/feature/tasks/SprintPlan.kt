package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * Sprint → Plan: the ranked backlog beside the sprint, so work can be pulled
 * in or pushed back out (D21).
 *
 * On a phone the two panes stack rather than sitting side by side — a 360px
 * viewport cannot show two ranked lists at once (D39). The sprint pane comes
 * first because that is what the user came to plan.
 */
@Composable
internal fun SprintPlanPage(ui: TaskUiState, viewModel: TaskViewModel, onOpen: (Task) -> Unit) {
    val colors = LocalWebColors.current
    val sprint = ui.currentSprint
    var moving by remember { mutableStateOf<Task?>(null) }

    TaskPage(loading = ui.sprintLoading || ui.backlogLoading || ui.refreshing, onRefresh = { viewModel.refresh(pull = true) }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            item(key = "header") { PlanHeader(ui, viewModel) }
            ui.error?.let { item(key = "error") { ErrorMsg(it, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) } }

            // ── Sprint pane ──────────────────────────────────────────────
            item(key = "sprint-title") {
                PlanSectionTitle(
                    "Sprint",
                    sprint?.let { sp ->
                        val points = ui.tasks.sumOf { it.storyPoints ?: 0.0 }
                        "${formatPoints(points)} ${ui.agile.unitLabel}"
                    },
                )
            }
            if (ui.tasks.isEmpty()) {
                item(key = "sprint-empty") {
                    Text(
                        "Nothing in this sprint yet. Pull work in from the backlog below.",
                        color = colors.textMuted,
                        fontSize = 0.78.rem,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(ui.tasks, key = { "sp-${it.id}" }) { task ->
                    PlanRow(task, ui, onOpen, actionLabel = "Move to backlog") {
                        viewModel.assignSprint(task, null)
                    }
                }
            }

            // ── Epics rollup ─────────────────────────────────────────────
            if (ui.epics.isNotEmpty()) {
                item(key = "epics-title") { PlanSectionTitle("Epics", null) }
                items(ui.epics, key = { "ep-${it.id}" }) { epic -> EpicRollupRow(epic) }
            }

            // ── Backlog pane ─────────────────────────────────────────────
            item(key = "backlog-title") {
                val count = ui.planningBacklog.size
                PlanSectionTitle("Backlog", "$count ${if (count == 1) "ticket" else "tickets"}")
            }
            val pullable = ui.planningBacklog
            if (pullable.isEmpty()) {
                item(key = "backlog-empty") {
                    Text(
                        "The backlog is empty — nothing left to pull in.",
                        color = colors.textMuted,
                        fontSize = 0.78.rem,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(pullable, key = { "pb-${it.id}" }) { task ->
                    PlanRow(task, ui, onOpen, actionLabel = "Pull into sprint") {
                        moving = task
                    }
                }
            }
        }
    }

    moving?.let { task ->
        SelectSheet(
            "Pull into sprint",
            ui.sprints.map {
                SelectOption<Long?>(it.id, it.name, it.status, color = if (it.status == "active") colors.success else colors.primaryLight)
            },
            setOf(task.sprintId),
            onDismiss = { moving = null },
            onSelect = { ids -> ids.firstOrNull()?.let { viewModel.assignSprint(task, it) } },
            icon = HeroIcons.RocketLaunch,
        )
    }
}

/** Goal (editable inline), committed points, velocity and the scope delta. */
@Composable
private fun PlanHeader(ui: TaskUiState, viewModel: TaskViewModel) {
    val colors = LocalWebColors.current
    val sprint = ui.currentSprint ?: return
    var editingGoal by remember { mutableStateOf(false) }
    var goal by remember(sprint.id) { mutableStateOf(sprint.goal ?: "") }
    val committed = ui.baseline?.committedPoints ?: 0.0
    val added = ui.baseline?.addedPoints ?: 0.0
    val removed = ui.baseline?.removedPoints ?: 0.0

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(CardShape)
            .background(colors.cardBg.compositeOver(colors.bg))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sprint.name,
                color = colors.text,
                fontSize = 0.98.rem,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            StatusPill(sprint.status, if (sprint.status == "active") colors.success else colors.primaryLight)
        }
        // Goal: tap to edit, Enter/blur to save.
        if (editingGoal) {
            WebTextField(
                goal,
                { goal = it },
                "What is this sprint for?",
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Cancel", { editingGoal = false; goal = sprint.goal ?: "" }, Modifier.weight(1f))
                PillButton("Save goal", {
                    viewModel.saveSprintGoal(sprint.id, goal)
                    editingGoal = false
                }, Modifier.weight(1f), primary = true)
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ChipShape)
                    .clickable(onClickLabel = "Edit sprint goal") { editingGoal = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HeroIcons.Flag, null, Modifier.size(14.dp), tint = colors.textMuted)
                Spacer(Modifier.width(6.dp))
                Text(
                    sprint.goal?.takeIf { it.isNotBlank() } ?: "Add a goal",
                    color = if (sprint.goal.isNullOrBlank()) colors.textMuted else colors.textSecondary,
                    fontSize = 0.8.rem,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlanStat("Committed", formatPoints(committed), colors.textSecondary)
            PlanStat("Added", "+${formatPoints(added)}", if (added > 0) colors.warning else colors.textMuted)
            PlanStat("Removed", "−${formatPoints(removed)}", if (removed > 0) colors.danger else colors.textMuted)
        }
    }
}

@Composable
private fun PlanStat(label: String, value: String, tint: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label.uppercase(), color = LocalWebColors.current.textMuted, fontSize = 0.62.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.06.rem)
        Text(value, color = tint, fontSize = 0.86.rem, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlanSectionTitle(title: String, trailing: String?) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            color = colors.textSecondary,
            fontSize = 0.7.rem,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.rem,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) {
            Text(trailing, color = colors.textMuted, fontSize = 0.7.rem, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A compact plan row: key, title, points, and one move action. */
@Composable
private fun PlanRow(task: Task, ui: TaskUiState, onOpen: (Task) -> Unit, actionLabel: String, onAction: () -> Unit) {
    val colors = LocalWebColors.current
    val points = if (ui.agile.features.storyPoints) formatPoints(task.storyPoints) else ""
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${task.displayKey}", onClick = { onOpen(task) })
            .semantics { customActions = listOf(CustomAccessibilityAction(actionLabel) { onAction(); true }) }
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            task.displayKey,
            color = colors.textMuted,
            fontSize = 0.7.rem,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            task.title,
            color = colors.text,
            fontSize = 0.84.rem,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (points.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(points, color = colors.textMuted, fontSize = 0.72.rem, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = actionLabel, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (actionLabel.startsWith("Pull")) HeroIcons.ArrowLeft else HeroIcons.ArrowRight,
                actionLabel,
                Modifier.size(16.dp),
                tint = colors.primary,
            )
        }
    }
}

/** Completed-versus-total for one epic, in count and points (D24). */
@Composable
private fun EpicRollupRow(epic: EpicSummary) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                epic.title,
                color = colors.textSecondary,
                fontSize = 0.8.rem,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${epic.doneChildren}/${epic.totalChildren} · ${epic.percentByPoints}%",
                color = colors.textMuted,
                fontSize = 0.7.rem,
            )
        }
        // A simple progress bar; the percentage is the accessible value.
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.border)) {
            Box(
                Modifier
                    .fillMaxWidth((epic.percentByPoints / 100f).coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.success),
            )
        }
    }
}
