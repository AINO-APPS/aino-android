package app.aino.mobile.feature.organization

import app.aino.mobile.core.common.roleLabel
import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate

/** `.success` / `.error` banners. */
@Composable
internal fun OrgNoticeBanner(notice: OrgNotice, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    val tint = if (notice.ok) colors.success else colors.danger
    Text(
        notice.text,
        color = tint,
        fontSize = 0.85.rem,
        modifier = modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
internal fun OrgErrorText(text: String, modifier: Modifier = Modifier) =
    OrgNoticeBanner(OrgNotice(false, text), modifier)

@Composable
internal fun OrgLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = LocalWebColors.current.primary, strokeWidth = 2.5.dp)
    }
}

/** `.emptyRow` / centred secondary copy. */
@Composable
internal fun OrgEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalWebColors.current.textSecondary,
        fontSize = 0.88.rem,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
    )
}

@Composable
internal fun orgFieldColors(): TextFieldColors {
    val colors = LocalWebColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        disabledTextColor = colors.text,
        focusedContainerColor = colors.inputBg,
        unfocusedContainerColor = colors.inputBg,
        disabledContainerColor = colors.inputBg,
        focusedBorderColor = colors.inputBorderFocus,
        unfocusedBorderColor = colors.inputBorder,
        disabledBorderColor = colors.inputBorder,
        cursorColor = colors.primary,
        focusedLabelColor = colors.textSecondary,
        unfocusedLabelColor = colors.textSecondary,
        disabledLabelColor = colors.textSecondary,
        focusedPlaceholderColor = colors.textMuted,
        unfocusedPlaceholderColor = colors.textMuted,
        disabledPlaceholderColor = colors.textMuted,
        focusedTrailingIconColor = colors.textSecondary,
        unfocusedTrailingIconColor = colors.textSecondary,
        disabledTrailingIconColor = colors.textSecondary,
        focusedLeadingIconColor = colors.textMuted,
        unfocusedLeadingIconColor = colors.textMuted,
    )
}

private val fieldText @Composable get() = TextStyle(fontSize = 0.9.rem, color = LocalWebColors.current.text)

/** Form label above an input (`.formGroup label`). */
@Composable
internal fun OrgFieldLabel(text: String, icon: ImageVector? = null) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(13.dp), tint = colors.textSecondary)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = colors.textSecondary, fontSize = 0.82.rem, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun OrgHint(text: String) {
    Text(text, color = LocalWebColors.current.textMuted, fontSize = 0.75.rem, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun OrgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLength: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValueChange(if (maxLength != null) next.take(maxLength) else next) },
        placeholder = { Text(placeholder, fontSize = 0.9.rem) },
        singleLine = true,
        textStyle = fieldText,
        colors = orgFieldColors(),
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        modifier = modifier.fillMaxWidth(),
    )
}

/** `<select>` equivalent: a read-only field that opens a dropdown menu. */
@Composable
internal fun <K> OrgPicker(
    options: List<Pair<K, String>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = fieldText,
            colors = orgFieldColors(),
            shape = RoundedCornerShape(8.dp),
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The read-only field swallows taps; this overlay opens the menu instead.
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).clickable { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.bgElevated),
        ) {
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

/** `<input type="date">` backed by the platform date picker; the ✕ clears it. */
@Composable
internal fun OrgDateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = fieldText,
            colors = orgFieldColors(),
            shape = RoundedCornerShape(8.dp),
            placeholder = { Text("yyyy-mm-dd", fontSize = 0.9.rem) },
            trailingIcon = { Icon(Icons.Outlined.CalendarMonth, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).clickable {
                val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toString()) },
                    initial.year,
                    initial.monthValue - 1,
                    initial.dayOfMonth,
                ).show()
            },
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 36.dp)) {
                Icon(Icons.Outlined.Close, "Clear", Modifier.size(16.dp), tint = LocalWebColors.current.textMuted)
            }
        }
    }
}

enum class OrgButtonStyle { Primary, Accent, Secondary, Danger, Cancel }

/** `.btnPrimary` / `.btnSmall .btnAccent|.btnSecondary|.btnDanger` / `.btnCancel`. */
@Composable
internal fun OrgButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OrgButtonStyle = OrgButtonStyle.Primary,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val colors = LocalWebColors.current
    val (bg, fg, border) = when (style) {
        OrgButtonStyle.Primary -> Triple(colors.primary, colors.onAccent, Color.Transparent)
        OrgButtonStyle.Accent -> Triple(colors.accent, colors.onAccent, Color.Transparent)
        OrgButtonStyle.Secondary -> Triple(colors.surfaceHover, colors.text, colors.border)
        OrgButtonStyle.Danger -> Triple(colors.danger, Color.White, Color.Transparent)
        OrgButtonStyle.Cancel -> Triple(Color.Transparent, colors.textSecondary, colors.border)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.5f))
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 6.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) fg else fg.copy(alpha = 0.6f),
            fontSize = if (small) 0.8.rem else 0.88.rem,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun OrgButtonRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** A table row rendered as a card (`.table` rows on a phone). */
@Composable
internal fun OrgRowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** `<th>` label + `<td>` value. */
@Composable
internal fun OrgCell(label: String, value: String) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = colors.textMuted, fontSize = 0.78.rem, modifier = Modifier.width(96.dp))
        Text(value, color = colors.text, fontSize = 0.85.rem, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun OrgCellLabel(label: String) {
    Text(label, color = LocalWebColors.current.textMuted, fontSize = 0.78.rem)
}

/** Headcount pill (`.headcount-badge`). */
@Composable
internal fun HeadcountBadge(count: Int) {
    val colors = LocalWebColors.current
    Box(
        Modifier
            .background(colors.accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = Color.White, fontSize = 0.65.rem, fontWeight = FontWeight.Bold)
    }
}

/** `confirm()` / `ConfirmDialog` as a Material dialog. */
@Composable
internal fun OrgConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    confirmText: String = "OK",
    danger: Boolean = true,
) {
    val colors = LocalWebColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = title?.let { { Text(it, color = colors.text, fontWeight = FontWeight.Bold) } },
        text = { Text(message, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) colors.danger else colors.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

/** Parses `#rrggbb`; falls back to the default label colour. */
internal fun parseHexColor(hex: String): Color =
    if (isHexColor(hex)) Color(("FF" + hex.drop(1)).toLong(16)) else Color(("FF" + DEFAULT_LABEL_COLOR.drop(1)).toLong(16))

/** `.badgeRole[data-role=…]` colours. */
internal fun roleBadgeColor(role: String): Color? = when (role) {
    "super_admin" -> Color(0xFF0284C7)
    "hr_admin" -> Color(0xFFEC4899)
    "manager" -> Color(0xFF3B82F6)
    "team_lead" -> Color(0xFFF59E0B)
    "employee" -> Color(0xFF6B7280)
    else -> null
}

@Composable
internal fun RoleBadge(role: String) {
    val colors = LocalWebColors.current
    val tint = roleBadgeColor(role) ?: colors.textSecondary
    Text(
        roleLabel(role),
        color = tint,
        fontSize = 0.65.rem,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.background(tint.copy(alpha = 0x22 / 255f), RoundedCornerShape(20.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
internal fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, if (selected) colors.text else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun VSpace(height: Int) = Spacer(Modifier.height(height.dp))
