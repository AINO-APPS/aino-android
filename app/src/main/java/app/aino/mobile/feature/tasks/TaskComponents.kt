package app.aino.mobile.feature.tasks

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate

/** `#rrggbb` → Color; the web falls back to its CSS default when a colour is malformed. */
internal fun hexColor(hex: String?, fallback: Color = Color(0xFF6366F1)): Color {
    val value = hex?.trim()?.removePrefix("#") ?: return fallback
    return when (value.length) {
        6 -> value.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback
        3 -> value.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback
        else -> fallback
    }
}

internal fun WebColors.tone(tone: Tone): Color = when (tone) {
    Tone.Danger -> danger
    Tone.Warning -> warning
    Tone.Success -> success
    Tone.Muted -> textMuted
    Tone.PrimaryLight -> primaryLight
}

/** `color + "20"` — the web appends a hex alpha of 0x20. */
internal fun Color.hexAlpha(alpha: Int): Color = copy(alpha = alpha / 255f)

/** `.task-priority-badge` / `.backlog-status-badge` coloured pill. */
@Composable
internal fun ToneBadge(text: String, color: Color, radius: Dp = 6.dp, fontSize: TextUnit = 0.7.rem) {
    Text(
        text,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.background(color.hexAlpha(0x20), RoundedCornerShape(radius)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
internal fun PriorityBadge(priority: String?) {
    val p = priorityOf(priority)
    ToneBadge("${p.icon} ${p.label}", LocalWebColors.current.tone(p.tone))
}

@Composable
internal fun StatusBadge(status: String?) {
    val c = columnOf(status)
    ToneBadge("${c.icon} ${c.label}", LocalWebColors.current.tone(c.tone), radius = 99.dp, fontSize = 0.68.rem)
}

/** `.label-pill`. */
@Composable
internal fun LabelPill(label: TaskLabel) {
    Text(
        label.name,
        color = Color.White,
        fontSize = 0.65.rem,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.background(hexColor(label.color, Color(0xFF0EA5E9)), RoundedCornerShape(99.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** AgilePickers `WorkItemTypeBadge`. */
@Composable
internal fun WorkItemTypeBadge(typeId: Long?, agile: AgileConfig) {
    val type = agile.type(typeId) ?: return
    val color = hexColor(type.color)
    Row(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(type.name, color = color, fontSize = 0.7.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** AgilePickers `StoryPointBadge` (number only). */
@Composable
internal fun StoryPointBadge(points: Double?, agile: AgileConfig) {
    if (!agile.features.storyPoints) return
    val display = formatPoints(points).ifEmpty { return }
    val colors = LocalWebColors.current
    Box(
        Modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 22.dp)
            .background(colors.bgSecondary, RoundedCornerShape(10.dp))
            .border(1.dp, colors.primary.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(display, color = colors.primary, fontSize = 0.72.rem, fontWeight = FontWeight.Bold) }
}

/** AgilePickers `BlockerBadge`. */
@Composable
internal fun BlockerBadge(blocked: Boolean, agile: AgileConfig) {
    if (!agile.features.blockers || !blocked) return
    val danger = LocalWebColors.current.danger
    Text(
        "⛔ Blocked",
        color = danger,
        fontSize = 0.7.rem,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(danger.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .border(1.dp, danger.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Monospace `.backlog-ticket-id`. */
@Composable
internal fun TicketId(text: String, color: Color = LocalWebColors.current.textMuted, fontWeight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = 0.78.rem, fontFamily = FontFamily.Monospace, fontWeight = fontWeight, maxLines = 1)
}

/** Sanitised task HTML (`HighlightedHtml`) rendered natively. */
@Composable
internal fun TaskHtml(html: String, color: Color, fontSize: TextUnit, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    val text = remember(html) { runCatching { AnnotatedString.fromHtml(html.replace("</p><p>", "</p>\n<p>")) }.getOrElse { AnnotatedString(stripHtml(html)) } }
    Text(text, color = color, fontSize = fontSize, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = fontSize * 1.4f, modifier = modifier)
}

enum class BtnStyle { Primary, Secondary, Danger }

/** `.btn .btn-primary|.btn-secondary|.btn-danger` (+ `.btn-sm`). */
@Composable
internal fun WebButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Secondary,
    small: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    active: Boolean = false,
) {
    val colors = LocalWebColors.current
    val (bg, fg, border) = when {
        style == BtnStyle.Primary || active -> Triple(colors.primary, Color.White, colors.primary)
        style == BtnStyle.Danger -> Triple(colors.danger, Color.White, colors.danger)
        else -> Triple(colors.surface, colors.text, colors.border)
    }
    val shape = RoundedCornerShape(if (style == BtnStyle.Secondary && !small) 8.dp else 6.dp)
    Row(
        modifier
            .clip(shape)
            .background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.4f), shape)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 9.6.dp else 13.6.dp, vertical = if (small) 4.5.dp else 6.4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) fg else fg.copy(alpha = 0.4f)
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tint, fontSize = if (small) 0.8.rem else 0.85.rem, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The web's `<select>`: a bordered field that opens a menu. */
@Composable
internal fun <K> WebSelect(
    options: List<Pair<K, String>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 0.85.rem,
    enabled: Boolean = true,
) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.inputBg)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.text, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp), tint = colors.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(colors.bgElevated)) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            color = if (key == selected) colors.primary else colors.text,
                            fontSize = 0.9.rem,
                            fontWeight = if (key == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

/** `<input type="date">` via the platform picker; ✕ clears when [clearable]. */
@Composable
internal fun WebDateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, clearable: Boolean = true) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inputBg)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable {
                val initial = localDateOf(value) ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d).toString()) },
                    initial.year, initial.monthValue - 1, initial.dayOfMonth,
                ).show()
            }
            .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value.ifEmpty { "mm/dd/yyyy" }, color = if (value.isEmpty()) colors.textMuted else colors.text, fontSize = 0.85.rem, modifier = Modifier.weight(1f))
        if (clearable && value.isNotEmpty()) {
            Icon(Icons.Outlined.Close, "Clear", Modifier.size(16.dp).clickable { onChange("") }, tint = colors.textMuted)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp), tint = colors.textSecondary)
    }
}

/** A bordered text input (`.form-group input` / `.task-edit-input`). */
@Composable
internal fun WebTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    fontSize: TextUnit = 0.875.rem,
) {
    val colors = LocalWebColors.current
    BasicTextField(
        value = value,
        onValueChange = { onChange(if (maxLength != null) it.take(maxLength) else it) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = TextStyle(color = colors.text, fontSize = fontSize),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .fillMaxWidth()
            .background(colors.inputBg, RoundedCornerShape(6.dp))
            .border(1.dp, colors.inputBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 13.6.dp, vertical = 8.8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = colors.textMuted, fontSize = fontSize)
                inner()
            }
        },
    )
}

/** Uppercase `.filter-group label` / `.form-extra-group label`. */
@Composable
internal fun FieldLabel(text: String, icon: ImageVector? = null, uppercase: Boolean = false, fontSize: TextUnit = 0.75.rem) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(13.dp), tint = colors.textMuted)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (uppercase) text.uppercase() else text,
            color = colors.textMuted,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (uppercase) 0.05.rem else TextUnit.Unspecified,
        )
    }
}

/** `.glass` bordered panel (`--glass` / `--glass-border`). */
@Composable
internal fun GlassPanel(modifier: Modifier = Modifier, radius: Dp = 8.dp, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.glass, RoundedCornerShape(radius))
            .border(1.dp, colors.glassBorder, RoundedCornerShape(radius))
            .padding(padding),
    ) { content() }
}

/** `.error-msg`. */
@Composable
internal fun ErrorMsg(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color(0xFFFCA5A5),
        fontSize = 0.85.rem,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x1AEF4444), RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, Color(0x33EF4444)), RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** `.loading-spinner`. */
@Composable
internal fun WebSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = LocalWebColors.current.primary, strokeWidth = 2.5.dp)
    }
}

/** `ConfirmDialog`. */
@Composable
internal fun TaskConfirmDialog(request: ConfirmRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = { Text(request.title, color = colors.text, fontWeight = FontWeight.Bold) },
        text = { Text(request.message, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(request.confirmText, color = if (request.danger) colors.danger else colors.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

/** AgilePickers `StoryPointPicker` — `?` plus the tenant scale; tapping the selected chip clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoryPointPicker(value: String?, onChange: (String?) -> Unit, agile: AgileConfig) {
    if (!agile.features.storyPoints) return
    val colors = LocalWebColors.current
    FlowRow(
        Modifier
            .background(colors.bgSecondary, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(agile.unitLabel, color = colors.textMuted, fontSize = 0.7.rem, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
        (listOf<String?>(null) + agile.pointScale).forEach { chip ->
            val selected = samePoints(value, chip)
            Box(
                Modifier
                    .heightIn(min = 24.dp)
                    .widthIn(min = 28.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) colors.primary else colors.bg)
                    .border(1.dp, if (selected) colors.primary else colors.border, RoundedCornerShape(12.dp))
                    .clickable { onChange(if (selected) null else chip) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(chip ?: "?", color = if (selected) Color.White else colors.text, fontSize = 0.75.rem, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** LabelSelector: "🏷️ Labels (n)" toggle over a checkbox list. */
@Composable
internal fun LabelSelector(labels: List<TaskLabel>, selected: List<Long>, onToggle: (Long) -> Unit) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🏷️ Labels", color = colors.text, fontSize = 0.85.rem)
            if (selected.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${selected.size}",
                    color = Color.White,
                    fontSize = 0.65.rem,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(colors.primary, RoundedCornerShape(99.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        if (open) {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .background(colors.bgElevated, RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .padding(vertical = 4.dp),
            ) {
                if (labels.isEmpty()) {
                    Text("No labels configured", color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(10.dp))
                }
                labels.forEach { label ->
                    val on = label.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(label.id) }.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = on,
                            onCheckedChange = { onToggle(label.id) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.textMuted),
                        )
                        LabelPill(label)
                    }
                }
            }
        }
    }
}

/** Common "Unassigned" + users options. */
internal fun assigneeOptions(users: List<AssignableUser>): List<Pair<Long?, String>> =
    listOf<Pair<Long?, String>>(null to "Unassigned") + users.map { it.id to it.display() }

/** SprintSelector: "Backlog (no sprint)" + available sprints. */
internal fun sprintOptions(sprints: List<AvailableSprint>): List<Pair<Long?, String>> =
    listOf<Pair<Long?, String>>(null to "Backlog (no sprint)") + sprints.map { sp ->
        sp.id to "${sp.name} (${sp.startDate.take(10)} → ${sp.endDate.take(10)})" + if (sp.status == "active") " ● Active" else ""
    }

internal fun typeOptions(agile: AgileConfig): List<Pair<Long?, String>> =
    listOf<Pair<Long?, String>>(null to "— Type —") + agile.workItemTypes.filter { it.id != 0L }.map { it.id to it.name }

internal fun projectOptions(projects: List<ProjectOption>): List<Pair<Long?, String>> =
    listOf<Pair<Long?, String>>(null to "— No project —") + projects.map { it.id to "${it.key} · ${it.name}" }

/** Strike-through when done (`.task-done .task-title`). */
internal fun doneDecoration(done: Boolean): TextDecoration? = if (done) TextDecoration.LineThrough else null

@Composable
internal fun RowScope.Grow() = Spacer(Modifier.weight(1f))

internal val TinySp = 11.sp
