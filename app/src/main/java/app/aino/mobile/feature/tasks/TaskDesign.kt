package app.aino.mobile.feature.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.AinoSnackbarHost
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.launch

/*
 * Tasks design layer: Material 3 building blocks styled with the web colour
 * tokens (`LocalWebColors`) so Android reads as the same product as web and
 * desktop while behaving like a native app (chips, sheets, cards, haptics).
 */

internal val CardShape = RoundedCornerShape(14.dp)
internal val ChipShape = RoundedCornerShape(999.dp)

// ── Cards & sections ─────────────────────────────────────────────────────

/** Elevated content card. */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.cardBg)
            .border(1.dp, colors.border, CardShape)
            .padding(padding),
        content = content,
    )
}

/** Small uppercase heading above a group of content. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val colors = LocalWebColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            color = colors.textMuted,
            fontSize = 0.68.rem,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.rem,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** Centered illustration + copy + optional action. */
@Composable
internal fun TaskEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalWebColors.current
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(colors.primary.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = colors.primary)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = colors.text, fontSize = 1.02.rem, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, color = colors.textMuted, fontSize = 0.84.rem, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PillButton(action, onAction, primary = true)
        }
    }
}

/** Shimmer-free placeholder shaped like a task card. */
@Composable
internal fun TaskCardSkeleton() {
    val colors = LocalWebColors.current
    val block = colors.surfaceHover
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(colors.cardBg).border(1.dp, colors.border, CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(90.dp).height(10.dp).background(block, RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(0.85f).height(14.dp).background(block, RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).background(block, RoundedCornerShape(4.dp)))
    }
}

// ── Buttons & chips ──────────────────────────────────────────────────────

/** Rounded M3-style button (filled / tonal / outlined). */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    tonal: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalWebColors.current
    val accent = if (danger) colors.danger else colors.primary
    val (bg, fg, border) = when {
        primary || danger -> Triple(accent, Color.White, accent)
        tonal -> Triple(accent.copy(alpha = 0.14f), accent, Color.Transparent)
        else -> Triple(Color.Transparent, colors.text, colors.border)
    }
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(ChipShape)
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.4f))
            .border(1.dp, if (enabled) border else border.copy(alpha = 0.4f), ChipShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) fg else fg.copy(alpha = 0.45f)
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = tint, fontSize = 0.86.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round icon-only action with an optional count badge (toolbar buttons). */
@Composable
internal fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    active: Boolean = false,
    size: Dp = 40.dp,
) {
    val colors = LocalWebColors.current
    Box(modifier) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (active) colors.primary.copy(alpha = 0.16f) else colors.surface)
                .border(1.dp, if (active) colors.primary.copy(alpha = 0.6f) else colors.border, CircleShape)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (badge > 0) "$label, $badge active" else label },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(18.dp), tint = if (active) colors.primary else colors.text) }
        if (badge > 0) {
            Text(
                "$badge",
                color = Color.White,
                fontSize = 0.6.rem,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopEnd).widthIn(min = 16.dp).background(colors.primary, CircleShape).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** M3 FilterChip with a colour dot and count, tinted by [accent]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CountChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color = LocalWebColors.current.primary,
    dot: Boolean = false,
) {
    val colors = LocalWebColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = ChipShape,
        leadingIcon = if (dot) ({ Box(Modifier.size(8.dp).background(accent, CircleShape)) }) else null,
        label = {
            Text(label, fontSize = 0.78.rem, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text("$count", fontSize = 0.74.rem, fontWeight = FontWeight.Bold, color = if (selected) accent else colors.textMuted)
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.textSecondary,
            selectedContainerColor = accent.copy(alpha = 0.16f),
            selectedLabelColor = colors.text,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.border,
            selectedBorderColor = accent.copy(alpha = 0.7f),
        ),
    )
}

/** Removable chip for an active filter. */
@Composable
internal fun RemovableChip(text: String, onRemove: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .clip(ChipShape)
            .background(colors.primary.copy(alpha = 0.12f))
            .clickable(onClickLabel = "Remove filter $text", onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = colors.primaryLight, fontSize = 0.76.rem, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        Spacer(Modifier.width(4.dp))
        Icon(HeroIcons.XMark, null, Modifier.size(14.dp), tint = colors.primaryLight)
    }
}

/** Status pill coloured by the tenant workflow state. */
@Composable
internal fun StatusPill(name: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(ChipShape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), ChipShape)
            .let { if (onClick != null) it.clickable(onClickLabel = "Change status", onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(name, color = color, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (onClick != null) {
            Spacer(Modifier.width(2.dp))
            Icon(HeroIcons.ChevronDown, null, Modifier.size(13.dp), tint = color)
        }
    }
}

/** The workflow state a task sits in (by id, else the state key). */
internal fun stateOf(task: Task, agile: AgileConfig): WorkflowState? =
    agile.workflowStates.firstOrNull { it.id != 0L && it.id == task.workflowStateId }
        ?: agile.workflowStates.firstOrNull { it.key == task.workflowStateKey }

internal fun stateLabel(state: WorkflowState): String = state.name.ifEmpty { state.key }

/** Priority chevron: up for high, a dash for medium, down for low. */
@Composable
internal fun PriorityGlyph(priority: String, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    val p = priorityOf(priority)
    val tint = colors.tone(p.tone)
    val glyph = when (p.value) {
        "high" -> HeroIcons.ChevronUp
        "low" -> HeroIcons.ChevronDown
        else -> HeroIcons.Minus
    }
    Icon(
        glyph,
        contentDescription = "${p.label} priority",
        modifier = modifier.size(16.dp),
        tint = tint,
    )
}

// ── Task card ────────────────────────────────────────────────────────────

/**
 * The one task card used by the Sprint board, the Backlog and Scheduled —
 * the same anatomy as web's `TaskCardParts`:
 *
 *   top     KEY · points · blocked              priority  [menu]
 *   title   up to two lines
 *   footer  status · due · 2 labels (+N)        [trailing] avatar
 *
 * Solid surface, 1dp border, no tinted background. Comfortable adds a
 * comments count. [highlighted] flashes the border when a realtime patch lands.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun TaskCardM3(
    task: Task,
    agile: AgileConfig,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    showStatus: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    /** Accessibility label for the long-press gesture. */
    longPressLabel: String = "More actions",
    onComments: (() -> Unit)? = null,
    /** Footer actions: selection, schedule, reschedule. */
    trailing: (@Composable () -> Unit)? = null,
    /** Top-right slot, reserved for the overflow menu so it is always visible. */
    topTrailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    /** Compact (default) drops the comments count. */
    compact: Boolean = false,
    /** "Updated by <name>" from a realtime delta; fades on its own (D48). */
    attribution: String? = null,
    selected: Boolean = false,
) {
    val colors = LocalWebColors.current
    val haptics = LocalHapticFeedback.current
    val done = task.isCompleted || stateOf(task, agile)?.isTerminal == true
    val points = if (agile.features.storyPoints) formatPoints(task.storyPoints) else ""
    val due = formatDueDate(task.plannedDate ?: task.dueDate)
    val overdue = isDueOverdue(task.dueDate) && !done
    val ring = when {
        selected -> colors.primary
        highlighted -> colors.primary
        else -> colors.border
    }
    val borderColor by animateColorAsState(ring, tween(250), label = "cardBorder")
    // Opaque: swipe actions sit underneath the card.
    val bg = colors.cardBg.compositeOver(colors.bg)
    Column(
        modifier
            .fillMaxWidth()
            .clip(TaskCardShape)
            .background(bg)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor), TaskCardShape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress?.let { action -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); action() } },
                onLongClickLabel = if (onLongPress != null) longPressLabel else null,
            )
            .padding(start = 12.dp, end = if (topTrailing != null) 2.dp else 12.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            val projectColor = task.project?.color?.let { hexColor(it) }
            agile.type(task.workItemTypeId)?.let { type ->
                Box(Modifier.size(8.dp).background(hexColor(type.color), RoundedCornerShape(2.dp)).semantics { contentDescription = type.name })
                Spacer(Modifier.width(6.dp))
            }
            Text(
                task.displayKey,
                color = projectColor ?: colors.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (!compact && points.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    points,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(colors.bgHover.compositeOver(bg), ChipShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .semantics { contentDescription = "$points ${agile.unitLabel}" },
                )
            }
            if (!compact && task.isBlocked && agile.features.blockers) {
                Spacer(Modifier.width(8.dp))
                Icon(HeroIcons.NoSymbol, null, Modifier.size(12.dp), tint = colors.warningInk)
                Spacer(Modifier.width(3.dp))
                Text("Blocked", color = colors.warningInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            PriorityGlyph(task.priority)
            topTrailing?.invoke()
        }
        Text(
            task.title,
            color = if (done) colors.textMuted else colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = doneDecoration(done),
            modifier = Modifier.padding(end = if (topTrailing != null) 10.dp else 0.dp),
        )
        if (!compact) {
            stripHtml(task.description).takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
        }
        Row(
            Modifier.padding(end = if (topTrailing != null) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showStatus || done) {
                    val state = stateOf(task, agile)
                    CardStatusPill(state?.let(::stateLabel) ?: columnOf(task.workflowStateKey).label, state?.let { hexColor(it.color, colors.textMuted) } ?: colors.textMuted)
                }
                if (overdue) {
                    MetaChip(HeroIcons.Clock, "Overdue", colors.dangerInk, bold = true)
                } else {
                    due?.let { MetaChip(HeroIcons.CalendarDays, it, colors.textSecondary) }
                }
                if (!compact) task.labels.take(2).forEach { CardLabel(it) }
                if (!compact && task.labels.size > 2) {
                    Text(
                        "+${task.labels.size - 2}",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.border(1.dp, colors.border, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (!compact) {
                if (onComments != null) {
                    Row(
                        Modifier.clip(ChipShape).clickable(onClickLabel = "Comments", onClick = onComments).padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HeroIcons.ChatBubbleOvalLeft, null, Modifier.size(14.dp), tint = colors.textMuted)
                        if (task.commentCount > 0) {
                            Spacer(Modifier.width(3.dp))
                            Text("${task.commentCount}", color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                } else if (task.commentCount > 0) {
                    MetaChip(HeroIcons.ChatBubbleOvalLeft, "${task.commentCount}", colors.textMuted)
                }
            }
            trailing?.invoke()
            task.assignee?.let { UserAvatar(it.display(), avatarPath(it.avatar), 22.dp) }
        }
        if (!compact && attribution != null) {
            Text(
                "Updated by $attribution",
                color = colors.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal val TaskCardShape = RoundedCornerShape(6.dp)

/** Compact card status pill: tinted fill, coloured dot, no border. */
@Composable
private fun CardStatusPill(name: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(name, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Neutral label chip with a colour dot (tenant colours can be unreadable as text). */
@Composable
private fun CardLabel(label: TaskLabel) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .widthIn(max = 96.dp)
            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(hexColor(label.color ?: "", colors.textMuted), CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label.name, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable
internal fun MetaChip(icon: ImageVector, text: String, color: Color, italic: Boolean = false, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(
            text, color = color, fontSize = 0.72.rem, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── Sheets ───────────────────────────────────────────────────────────────

/** Themed modal bottom sheet with a title row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWebColors.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(20.dp), tint = colors.primary)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, color = colors.text, fontSize = 1.08.rem, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Close", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(HeroIcons.XMark, "Close", Modifier.size(20.dp), tint = colors.textMuted) }
            }
            content()
        }
    }
}

/** One selectable option in a [SelectSheet]. */
data class SelectOption<K>(
    val key: K,
    val label: String,
    val subtitle: String? = null,
    val color: Color? = null,
    val avatar: Pair<String, String?>? = null,
)

/**
 * Searchable single/multi picker in a bottom sheet — replaces web `<select>`
 * for assignee, labels, sprint, project and type. Single-select closes on
 * pick; multi-select applies on "Done".
 */
@Composable
internal fun <K> SelectSheet(
    title: String,
    options: List<SelectOption<K>>,
    selected: Set<K>,
    onDismiss: () -> Unit,
    onSelect: (Set<K>) -> Unit,
    multi: Boolean = false,
    searchable: Boolean = options.size > 7,
    icon: ImageVector? = null,
) {
    val colors = LocalWebColors.current
    var query by remember { mutableStateOf("") }
    var picked by remember(selected) { mutableStateOf(selected) }
    val shown = remember(options, query) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter { it.label.contains(q, true) || it.subtitle.orEmpty().contains(q, true) }
    }
    TaskSheetScaffold(title, onDismiss, icon) {
        if (searchable) {
            SearchField(query, { query = it }, "Search…", Modifier.padding(bottom = 10.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(shown, key = { it.key.toString() }) { option ->
                val on = option.key in picked
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on && !multi) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable(role = if (multi) Role.Checkbox else Role.RadioButton) {
                            if (multi) picked = if (on) picked - option.key else picked + option.key
                            else { onSelect(setOf(option.key)); onDismiss() }
                        }
                        .padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        option.avatar != null -> UserAvatar(option.avatar.first, option.avatar.second, 28.dp)
                        option.color != null -> Box(Modifier.size(12.dp).background(option.color, CircleShape))
                    }
                    if (option.avatar != null || option.color != null) Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, color = colors.text, fontSize = 0.92.rem, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        option.subtitle?.let { Text(it, color = colors.textMuted, fontSize = 0.74.rem, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (multi) {
                        Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.textMuted))
                    } else if (on) {
                        Icon(HeroIcons.Check, "Selected", Modifier.size(18.dp), tint = colors.primary)
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text("No matches", color = colors.textMuted, fontSize = 0.84.rem, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
        }
        if (multi) {
            HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Clear", { picked = emptySet() }, Modifier.weight(1f), enabled = picked.isNotEmpty())
                PillButton("Done", { onSelect(picked); onDismiss() }, Modifier.weight(1f), primary = true)
            }
        }
    }
}

/** Rounded search input. */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null) {
    val colors = LocalWebColors.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(ChipShape)
            .background(colors.inputBg)
            .border(1.dp, colors.inputBorder, ChipShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HeroIcons.MagnifyingGlass, null, Modifier.size(17.dp), tint = colors.textMuted)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.text, fontSize = 0.92.rem),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 11.dp)
                .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = colors.textMuted, fontSize = 0.92.rem)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClickLabel = "Clear search") { onChange("") }, contentAlignment = Alignment.Center) {
                Icon(HeroIcons.XMark, null, Modifier.size(16.dp), tint = colors.textMuted)
            }
        }
    }
}

/** A labelled row in a property list: icon · label … value (tap to edit). */
@Composable
internal fun PropertyRow(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    val colors = LocalWebColors.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .let { if (onClick != null) it.clickable(onClickLabel = "Edit $label", onClick = onClick) else it }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.textMuted)
        Spacer(Modifier.width(12.dp))
        Text(label, color = colors.textSecondary, fontSize = 0.84.rem, modifier = Modifier.width(96.dp), maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { value() }
        if (onClick != null) Icon(HeroIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.textMuted.copy(alpha = 0.6f))
    }
}

@Composable
internal fun PropertyText(text: String, muted: Boolean = false, color: Color? = null) {
    val colors = LocalWebColors.current
    Text(
        text,
        color = color ?: if (muted) colors.textMuted else colors.text,
        fontSize = 0.9.rem,
        fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The single collector of [TaskViewModel.notices], hosted by the app shell so a
 * notice raised while the detail screen closes (schedule, delete…) still shows.
 */
@Composable
fun TaskNoticeHost(viewModel: TaskViewModel, modifier: Modifier = Modifier) {
    val host = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            host.currentSnackbarData?.dismiss()
            scope.launch {
                val result = host.showSnackbar(
                    notice.text,
                    actionLabel = notice.actionLabel,
                    withDismissAction = notice.actionLabel == null,
                    duration = if (notice.actionLabel != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) notice.action?.invoke()
            }
        }
    }
    AinoSnackbarHost(host, modifier.padding(horizontal = 12.dp))
}
