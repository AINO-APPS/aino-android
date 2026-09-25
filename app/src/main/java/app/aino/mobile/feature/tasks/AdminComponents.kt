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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.LastPage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** A bordered `<input>` / `<textarea>` with `disabled` (opacity 0.7) and an optional blur callback. */
@Composable
internal fun AdminInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int? = null,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    fontSize: TextUnit = 0.85.rem,
    padding: Dp = 9.dp,
    onFocusLost: (() -> Unit)? = null,
) {
    val colors = LocalWebColors.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = { onChange(if (maxLength != null) it.take(maxLength) else it) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onFocusLost?.invoke() }),
        textStyle = TextStyle(color = colors.text, fontSize = fontSize, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .alpha(if (enabled) 1f else 0.7f)
            .background(colors.inputBg, RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .onFocusChanged { state ->
                if (focused && !state.isFocused) onFocusLost?.invoke()
                focused = state.isFocused
            }
            .padding(horizontal = 10.dp, vertical = padding),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = colors.textMuted, fontSize = fontSize)
                inner()
            }
        },
    )
}

/** `<label><input type="checkbox" /> text</label>`. */
@Composable
internal fun AdminCheckbox(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 0.85.rem,
) {
    val colors = LocalWebColors.current
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
            colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.textMuted),
        )
        Text(label, color = if (enabled) colors.text else colors.textMuted, fontSize = fontSize, modifier = Modifier.padding(end = 6.dp))
    }
}

/**
 * `<input type="color">`: Android has no native colour input, so this is a
 * swatch preview plus a `#rrggbb` field; [onChange] fires once the hex is valid.
 */
@Composable
internal fun HexColorInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var text by remember(value) { mutableStateOf(value) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 32.dp, height = 28.dp).clip(RoundedCornerShape(4.dp)).background(hexColor(value)))
        Spacer(Modifier.width(6.dp))
        AdminInput(
            text,
            { next ->
                val cleaned = ("#" + next.removePrefix("#").filter { it.isLetterOrDigit() }).take(7)
                text = cleaned
                if (isHexColor6(cleaned) && !cleaned.equals(value, ignoreCase = true)) onChange(cleaned.lowercase())
            },
            placeholder = "#rrggbb",
            enabled = enabled,
            monospace = true,
            fontSize = 0.8.rem,
            padding = 5.dp,
            modifier = Modifier.width(96.dp),
        )
    }
}

/** A row of round colour chips (Projects `COLORS`, TaskLabelsTab `PRESET_COLORS`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorChips(palette: List<String>, selected: String, onSelect: (String) -> Unit, ring: Color = LocalWebColors.current.primary) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        palette.forEach { c ->
            val on = c.equals(selected, ignoreCase = true)
            Box(
                Modifier
                    .size(30.dp)
                    .border(2.dp, if (on) ring else Color.Transparent, CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(hexColor(c))
                    .clickable { onSelect(c) },
            )
        }
    }
}

/** The browser `confirm()` the web pages use: message plus OK / Cancel. */
@Composable
internal fun BrowserConfirmDialog(request: ConfirmRequest, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = request.title.takeIf(String::isNotEmpty)?.let { t -> { Text(t, color = colors.text, fontWeight = FontWeight.Bold) } },
        text = { Text(request.message, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); request.action() }) {
                Text(request.confirmText, color = if (request.danger) colors.danger else colors.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

/** Inline `<code>` span. */
internal fun codeStyle(fontSize: TextUnit) = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14FFFFFF), fontSize = fontSize)

/** Plain text with `<code>` segments: pass pairs of (text, isCode). */
@Composable
internal fun CodeText(parts: List<Pair<String, Boolean>>, color: Color, fontSize: TextUnit, codeSize: TextUnit = fontSize, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            parts.forEach { (text, code) -> if (code) withStyle(codeStyle(codeSize)) { append(text) } else append(text) }
        },
        color = color,
        fontSize = fontSize,
        modifier = modifier,
    )
}

/** `components/common/Pagination.tsx`. */
@Composable
internal fun PaginationBar(
    total: Int,
    limit: Int,
    offset: Int,
    pageSizes: List<Int>,
    itemLabel: String,
    onPageChange: (Int) -> Unit,
    onLimitChange: (Int) -> Unit,
    compact: Boolean = false,
) {
    if (total <= 0) return
    val colors = LocalWebColors.current
    val safeLimit = maxOf(1, limit)
    val page = maxOf(0, offset) / safeLimit + 1
    val pages = pageCount(total, safeLimit)
    fun goto(next: Int) = onPageChange((next.coerceIn(1, pages) - 1) * safeLimit)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 4.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(paginationSummary(total, safeLimit, offset, itemLabel), color = colors.textSecondary, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rows", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                WebSelect(pageSizes.map { it to it.toString() }, safeLimit, onLimitChange, Modifier.width(76.dp), fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PagerButton(Icons.Outlined.FirstPage, "First page", page > 1) { goto(1) }
                PagerButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous page", page > 1) { goto(page - 1) }
                Text(
                    buildAnnotatedString {
                        append("Page ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(page.toString()) }
                        append(" of $pages")
                    },
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                PagerButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next page", page < pages) { goto(page + 1) }
                PagerButton(Icons.Outlined.LastPage, "Last page", page < pages) { goto(pages) }
            }
        }
    }
}

@Composable
private fun PagerButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier
            .size(28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(14.dp), tint = colors.text) }
}
