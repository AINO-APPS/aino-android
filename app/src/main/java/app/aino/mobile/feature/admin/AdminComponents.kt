package app.aino.mobile.feature.admin

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate

/*
 * Admin form/list primitives. They mirror `OrganizationComponents.kt`
 * (same web `.formGroup` / `.btn*` / table-row-as-card styling); the module
 * boundary guard forbids importing them across features.
 */

@Composable
internal fun AdminNoticeBanner(notice: AdminNotice, modifier: Modifier = Modifier) {
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
internal fun AdminError(text: String, modifier: Modifier = Modifier) = AdminNoticeBanner(AdminNotice(false, text), modifier)

@Composable
internal fun AdminLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = LocalWebColors.current.primary, strokeWidth = 2.5.dp)
    }
}

@Composable
internal fun AdminEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalWebColors.current.textSecondary,
        fontSize = 0.88.rem,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
    )
}

/** Renders a [Load]: spinner on first load, error banner, then [content] once data exists. */
@Composable
internal fun <T> AdminLoadState(load: Load<T>, content: @Composable (T) -> Unit) {
    load.error?.let { AdminError(it, Modifier.padding(bottom = 8.dp)) }
    when {
        load.data != null -> content(load.data)
        load.loading -> AdminLoading()
    }
}

@Composable
internal fun adminFieldColors(): TextFieldColors {
    val colors = LocalWebColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        disabledTextColor = colors.textSecondary,
        focusedContainerColor = colors.inputBg,
        unfocusedContainerColor = colors.inputBg,
        disabledContainerColor = colors.inputBg,
        focusedBorderColor = colors.inputBorderFocus,
        unfocusedBorderColor = colors.inputBorder,
        disabledBorderColor = colors.inputBorder,
        cursorColor = colors.primary,
        focusedPlaceholderColor = colors.textMuted,
        unfocusedPlaceholderColor = colors.textMuted,
        disabledPlaceholderColor = colors.textMuted,
        focusedTrailingIconColor = colors.textSecondary,
        unfocusedTrailingIconColor = colors.textSecondary,
        disabledTrailingIconColor = colors.textSecondary,
    )
}

private val fieldText @Composable get() = TextStyle(fontSize = 0.9.rem, color = LocalWebColors.current.text)

@Composable
internal fun AdminFieldLabel(text: String) {
    Text(
        text,
        color = LocalWebColors.current.textSecondary,
        fontSize = 0.82.rem,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
internal fun AdminHint(text: String) {
    Text(text, color = LocalWebColors.current.textMuted, fontSize = 0.75.rem, modifier = Modifier.padding(top = 4.dp))
}

/** Label + input stacked (`.formGroup`). */
@Composable
internal fun AdminField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    hint: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) AdminFieldLabel(label)
        AdminTextInput(value, onValueChange, placeholder, keyboardType = keyboardType, password = password, singleLine = singleLine, enabled = enabled)
        hint?.let { AdminHint(it) }
    }
}

@Composable
internal fun AdminTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 0.9.rem) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        enabled = enabled,
        textStyle = fieldText,
        colors = adminFieldColors(),
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
    )
}

/** `<select>`: a read-only field that opens a dropdown menu. */
@Composable
internal fun <K> AdminPicker(
    label: String,
    options: List<Pair<K, String>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) AdminFieldLabel(label)
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = shown,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                textStyle = fieldText,
                colors = adminFieldColors(),
                shape = RoundedCornerShape(8.dp),
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LocalWebColors.current.bgElevated) {
                options.forEach { (key, text) ->
                    DropdownMenuItem(
                        text = { Text(text, color = LocalWebColors.current.text, fontSize = 0.9.rem) },
                        onClick = { open = false; onSelect(key) },
                    )
                }
            }
        }
    }
}

/** `<input type="date">` via the platform date picker. */
@Composable
internal fun AdminDateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) AdminFieldLabel(label)
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                textStyle = fieldText,
                colors = adminFieldColors(),
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
                        { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d).toString()) },
                        initial.year, initial.monthValue - 1, initial.dayOfMonth,
                    ).show()
                },
            )
        }
    }
}

@Composable
internal fun AdminSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 0.88.rem, fontWeight = FontWeight.Medium)
            hint?.let { AdminHint(it) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = Color.White),
        )
    }
}

enum class AdminButtonStyle { Primary, Secondary, Danger, Cancel }

@Composable
internal fun AdminButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AdminButtonStyle = AdminButtonStyle.Primary,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val colors = LocalWebColors.current
    val (bg, fg, border) = when (style) {
        AdminButtonStyle.Primary -> Triple(colors.primary, colors.onAccent, Color.Transparent)
        AdminButtonStyle.Secondary -> Triple(colors.surfaceHover, colors.text, colors.border)
        AdminButtonStyle.Danger -> Triple(colors.danger, Color.White, Color.Transparent)
        AdminButtonStyle.Cancel -> Triple(Color.Transparent, colors.textSecondary, colors.border)
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
internal fun AdminButtonRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** A table row as a card. */
@Composable
internal fun AdminRowCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.cardBg, shape)
            .border(1.dp, colors.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
internal fun AdminCell(label: String, value: String) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = colors.textMuted, fontSize = 0.78.rem, modifier = Modifier.width(104.dp))
        Text(value, color = colors.text, fontSize = 0.85.rem, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun AdminTitle(text: String, subtitle: String? = null) {
    val colors = LocalWebColors.current
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(text, color = colors.text, fontSize = 1.05.rem, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = colors.textSecondary, fontSize = 0.8.rem, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Coloured pill (`.badge`). */
@Composable
internal fun AdminPill(text: String, tint: Color) {
    Text(
        text,
        color = tint,
        fontSize = 0.68.rem,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.background(tint.copy(alpha = 0.14f), RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

internal fun adminRoleColor(role: String): Color = when (role) {
    "platform_admin" -> Color(0xFF8B5CF6)
    "super_admin" -> Color(0xFF0284C7)
    "hr_admin" -> Color(0xFFEC4899)
    "manager" -> Color(0xFF3B82F6)
    "team_lead" -> Color(0xFFF59E0B)
    else -> Color(0xFF6B7280)
}

@Composable
internal fun AdminRolePill(role: String) = AdminPill(AdminCatalog.roleLabel(role), adminRoleColor(role))

internal fun isHexColor(hex: String): Boolean = Regex("^#[0-9a-fA-F]{6}$").matches(hex)

internal fun hexColor(hex: String?, fallback: Color = Color(0xFF6366F1)): Color =
    if (hex != null && isHexColor(hex)) Color(("FF" + hex.drop(1)).toLong(16)) else fallback

@Composable
internal fun AdminColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(color)
            .border(2.dp, if (selected) colors.text else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
    )
}

/** `confirm()` as a Material dialog; [extra] hosts inline inputs (reason, typed confirm). */
@Composable
internal fun AdminConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    confirmText: String = "OK",
    danger: Boolean = true,
    confirmEnabled: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = LocalWebColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = title?.let { { Text(it, color = colors.text, fontWeight = FontWeight.Bold) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, color = colors.textSecondary)
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmText, color = if (danger) colors.danger else colors.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

@Composable
internal fun VGap(height: Int) = Spacer(Modifier.height(height.dp))

/** `YYYY-MM-DD HH:mm` from an ISO timestamp (display only). */
internal fun shortDateTime(iso: String?): String = iso?.replace('T', ' ')?.take(16).orEmpty().ifEmpty { "\u2014" }

internal fun shortDate(iso: String?): String = iso?.take(10).orEmpty().ifEmpty { "\u2014" }
