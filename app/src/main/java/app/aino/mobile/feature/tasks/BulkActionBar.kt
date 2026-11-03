package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * The selection bar shown while backlog rows are multi-selected.
 *
 * Bulk actions are backlog-only (D23). The bar names the count, offers the
 * four actions, and clears in one tap. One undo reverses the whole batch —
 * the ViewModel registers it as a single notice.
 */
@Composable
internal fun BulkActionBar(
    ui: TaskUiState,
    viewModel: TaskViewModel,
    planViewModel: PlanViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    if (!ui.hasSelection) return
    var picker by remember { mutableStateOf<String?>(null) }
    val count = ui.selectedIds.size
    val selected = ui.backlog.filter { it.id in ui.selectedIds }
    fun decision(action: TaskCapability): TaskCapabilityDecision =
        if (selected.size != count) TaskCapabilityDecision()
        else selected.firstOrNull { !it.capability(action).allowed }?.capability(action) ?: TaskCapabilityDecision(true)

    val canSprint = decision(TaskCapability.PlaceInSprint)
    val canEdit = decision(TaskCapability.Edit)
    val canCancel = decision(TaskCapability.Cancel)
    val canChangeStatus = if (selected.size != count) TaskCapabilityDecision() else {
        selected.firstOrNull { !it.capability(TaskCapability.ChangeState).allowed }
            ?.capability(TaskCapability.ChangeState) ?: TaskCapabilityDecision(true)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bgElevated.compositeOver(colors.bg))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count selected",
                color = colors.text,
                fontSize = 0.84.rem,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(ChipShape)
                    .clickable(onClickLabel = "Clear selection", onClick = planViewModel::clearSelection)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HeroIcons.XMark, null, Modifier.size(14.dp), tint = colors.textMuted)
                Spacer(Modifier.width(4.dp))
                Text("Clear", color = colors.textSecondary, fontSize = 0.76.rem, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.agileEnabled && ui.sprints.isNotEmpty()) {
                BulkAction(HeroIcons.RocketLaunch, "Sprint", enabled = canSprint.allowed, reason = canSprint.reasonCopy()) { picker = "sprint" }
            }
            BulkAction(HeroIcons.ArrowPath, "Status", enabled = canChangeStatus.allowed, reason = canChangeStatus.reasonCopy()) { picker = "status" }
            BulkAction(HeroIcons.User, "Assign", enabled = canEdit.allowed, reason = canEdit.reasonCopy()) { picker = "assignee" }
            BulkAction(HeroIcons.Trash, "Cancel", danger = true, enabled = canCancel.allowed, reason = canCancel.reasonCopy()) {
                planViewModel.confirmBulkDelete()
            }
        }
    }

    when (picker) {
        "sprint" -> SelectSheet(
            "Move to sprint",
            listOf(SelectOption<Long?>(null, "Backlog (no sprint)", subtitle = if (canSprint.allowed) null else canSprint.reasonCopy(), enabled = canSprint.allowed)) + ui.sprints.map {
                SelectOption<Long?>(it.id, it.name, subtitle = if (canSprint.allowed) it.status else canSprint.reasonCopy(), color = if (it.status == "active") colors.success else colors.primaryLight, enabled = canSprint.allowed)
            },
            emptySet(), { picker = null },
            { ids -> planViewModel.bulkAction("move_to_sprint", ids.firstOrNull()?.toString() ?: "") },
            icon = HeroIcons.RocketLaunch,
        )
        "status" -> SelectSheet(
            "Set status",
            ui.agile.workflowStates.map { state ->
                val denied = if (selected.size != count) TaskCapabilityDecision()
                else selected.firstOrNull { !it.dropTargetDecision(state.id, accepting = true).allowed }
                    ?.dropTargetDecision(state.id, accepting = true) ?: TaskCapabilityDecision(true)
                SelectOption(
                    state.key,
                    stateLabel(state),
                    subtitle = if (denied.allowed) null else denied.reasonCopy(),
                    color = hexColor(state.color),
                    enabled = denied.allowed,
                )
            },
            emptySet(), { picker = null },
            { ids -> ids.firstOrNull()?.let { planViewModel.bulkAction("status", it) } },
            icon = HeroIcons.ArrowPath,
        )
        "assignee" -> SelectSheet(
            "Assign to",
            listOf(SelectOption<Long?>(null, "Unassigned")) + ui.assignableUsers.map {
                SelectOption<Long?>(it.id, it.display(), it.username?.let { u -> "@$u" }, avatar = it.display() to avatarPath(it.avatar))
            },
            emptySet(), { picker = null },
            { ids -> planViewModel.bulkAction("assign", ids.firstOrNull()?.toString() ?: "") },
            icon = HeroIcons.User,
        )
    }
}

@Composable
private fun BulkAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    reason: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalWebColors.current
    val tint = if (!enabled) colors.textMuted else if (danger) colors.danger else colors.textSecondary
    Row(
        Modifier
            .clip(ChipShape)
            .background((if (danger) colors.danger else colors.primary).copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { if (enabled) customActions = listOf(CustomAccessibilityAction(label) { onClick(); true }) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(15.dp), tint = tint)
                Spacer(Modifier.width(5.dp))
                Text(label, color = tint, fontSize = 0.76.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!enabled) Text(reason ?: capabilityReasonCopy(null), color = colors.textMuted, fontSize = 0.65.rem, maxLines = 2)
        }
    }
}

/** A checkbox-style selection affordance for a backlog row. */
@Composable
internal fun SelectionToggle(selected: Boolean, onToggle: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                onClickLabel = if (selected) "Deselect" else "Select",
                role = androidx.compose.ui.semantics.Role.Checkbox,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) colors.primary else colors.bg)
                .then(
                    if (selected) Modifier
                    else Modifier.background(colors.border, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(HeroIcons.Check, "Selected", Modifier.size(13.dp), tint = colors.bg)
        }
    }
}
