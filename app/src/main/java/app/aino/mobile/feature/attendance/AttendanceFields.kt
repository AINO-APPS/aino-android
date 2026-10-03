package app.aino.mobile.feature.attendance

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Outlined-field colours from the web tokens (input border, primary focus). */
@Composable
fun attendanceFieldColors(): TextFieldColors {
    val colors = LocalWebColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        disabledTextColor = colors.text,
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.inputBorder,
        disabledBorderColor = colors.inputBorder,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.textSecondary,
        disabledLabelColor = colors.textSecondary,
        focusedLeadingIconColor = colors.primary,
        unfocusedLeadingIconColor = colors.textSecondary,
        disabledLeadingIconColor = colors.textSecondary,
        focusedPlaceholderColor = colors.textMuted,
        unfocusedPlaceholderColor = colors.textMuted,
        disabledPlaceholderColor = colors.textMuted,
        cursorColor = colors.primary,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )
}

/** Read-only outlined field that opens a picker; announced to TalkBack as a button. */
@Composable
fun PickerField(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
) {
    Box(
        modifier.clearAndSetSemantics {
            contentDescription = "$label: ${value.ifBlank { "not set" }}"
            role = Role.Button
            onClick(label = "Change $label") { onClick(); true }
        },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            enabled = false,
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
            colors = attendanceFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

private val LONG_DATE = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Date input ("yyyy-MM-dd" value) backed by the Material3 date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowFuture: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalDate.parse(value) }.getOrNull()
    PickerField(label, parsed?.format(LONG_DATE) ?: value, HeroIcons.CalendarDays, { open = true }, modifier, "Pick a date")
    if (open) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (parsed ?: today).toPickerMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = allowFuture || utcTimeMillis.toPickerDate() <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(it.toPickerDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Material3 date-range dialog (Insights custom period); past dates only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(from: String, to: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    val today = LocalDate.now()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = runCatching { LocalDate.parse(from) }.getOrNull()?.toPickerMillis(),
        initialSelectedEndDateMillis = runCatching { LocalDate.parse(to) }.getOrNull()?.toPickerMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.toPickerDate() <= today
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            val end = state.selectedEndDateMillis
            TextButton(
                enabled = start != null && end != null,
                onClick = { if (start != null && end != null) onConfirm(start.toPickerDate().toString(), end.toPickerDate().toString()) },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state, Modifier.weight(1f))
    }
}

/** Time input ("HH:mm") backed by the platform 24h time picker. */
@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "--:--") {
    val context = LocalContext.current
    PickerField(label, value, HeroIcons.Clock, {
        val initial = runCatching { LocalTime.parse(value) }.getOrNull() ?: LocalTime.of(9, 0)
        TimePickerDialog(context, { _, hour, minute -> onChange("%02d:%02d".format(hour, minute)) }, initial.hour, initial.minute, true).show()
    }, modifier, placeholder)
}

/** Outlined text input with the attendance colours. */
@Composable
fun TextInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: ImageVector? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        leadingIcon = leadingIcon?.let { { Icon(it, null, Modifier.size(18.dp)) } },
        colors = attendanceFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}
