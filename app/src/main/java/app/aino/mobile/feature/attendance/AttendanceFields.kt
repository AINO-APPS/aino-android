package app.aino.mobile.feature.attendance

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.LocalTime

/**
 * Form fields matching the web inputs (`Leaves.module.css` /
 * `ManualEntry.module.css`): input background, 1px border, 8px radius.
 */
@Composable
private fun FieldBox(modifier: Modifier, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    var base = modifier
        .clip(RoundedCornerShape(8.dp))
        .background(colors.inputBg)
        .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
    if (onClick != null) base = base.clickable(onClick = onClick)
    Box(base.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}

/** `<input type="date">` equivalent backed by the platform date picker. */
@Composable
fun WebDateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "yyyy-mm-dd") {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    FieldBox(modifier, {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
        DatePickerDialog(
            context,
            { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toString()) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }) {
        Text(
            value.ifBlank { placeholder },
            color = if (value.isBlank()) colors.textMuted else colors.text,
            fontSize = 0.85.rem,
        )
    }
}

/** `<input type="time">` equivalent backed by the platform time picker. */
@Composable
fun WebTimeField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "--:--") {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    FieldBox(modifier, {
        val initial = runCatching { LocalTime.parse(value) }.getOrNull() ?: LocalTime.of(9, 0)
        TimePickerDialog(
            context,
            { _, hour, minute -> onChange("%02d:%02d".format(hour, minute)) },
            initial.hour,
            initial.minute,
            true,
        ).show()
    }) {
        Text(
            value.ifBlank { placeholder },
            color = if (value.isBlank()) colors.textMuted else colors.text,
            fontSize = 0.85.rem,
        )
    }
}

/** Single/multi-line text input with the web input chrome. */
@Composable
fun WebTextInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = LocalWebColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inputBg)
            .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        textStyle = TextStyle(color = colors.text, fontSize = 0.85.rem),
        cursorBrush = SolidColor(colors.primary),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box {
                if (value.isBlank()) Text(placeholder, color = colors.textMuted, fontSize = 0.85.rem)
                inner()
            }
        },
    )
}

/** `.segmented` toggle row: equal buttons, active is primary-filled. */
@Composable
fun SegmentedRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) colors.primary else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) colors.onAccent else colors.textSecondary,
                    fontSize = 0.82.rem,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** `.btn .btn-primary .btn-fullwidth`: primary fill, 8px radius. */
@Composable
fun WebPrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) colors.primary else colors.primary.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 0.88.rem)
    }
}
