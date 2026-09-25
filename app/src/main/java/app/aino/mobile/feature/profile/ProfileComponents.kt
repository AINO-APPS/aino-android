package app.aino.mobile.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** Full-screen profile page: back arrow + title bar over a scrolling body. */
@Composable
fun ProfilePage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    app.aino.mobile.core.designsystem.component.AinoFullPage(title, onBack, content = content)

/** A titled group of rows/fields on a settings-style card. */
@Composable
fun ProfileSection(
    title: String? = null,
    icon: ImageVector? = null,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().background(colors.bgSecondary, shape)
            .border(1.dp, if (danger) colors.danger.copy(alpha = .35f) else colors.border, shape),
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon?.let { Icon(it, null, Modifier.size(15.dp), tint = if (danger) colors.danger else colors.textSecondary) }
                Text(title, color = if (danger) colors.danger else colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        content()
    }
}

/** Settings row: leading icon, label (+ optional supporting text), trailing slot. */
@Composable
fun ProfileRow(
    icon: ImageVector?,
    label: String,
    onClick: (() -> Unit)?,
    supporting: String? = null,
    tint: Color = LocalWebColors.current.text,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalWebColors.current
    val alpha = if (enabled) 1f else .45f
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            leading != null -> leading()
            icon != null -> Icon(icon, null, Modifier.size(20.dp), tint = (if (tint == colors.text) colors.textSecondary else tint).copy(alpha = alpha))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = tint.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            supporting?.let { Text(it, color = colors.textMuted.copy(alpha = alpha), fontSize = 12.sp) }
        }
        trailing?.invoke()
    }
}

@Composable
fun ProfileTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    prefix: String? = null,
) {
    val colors = LocalWebColors.current
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = colors.textMuted) },
        prefix = prefix?.let { { Text(it, color = colors.textSecondary) } },
        singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        trailingIcon = if (password) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide password" else "Show password")
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.inputBorder,
            focusedLabelColor = colors.primary,
            unfocusedLabelColor = colors.textSecondary,
            focusedContainerColor = colors.inputBg,
            unfocusedContainerColor = colors.inputBg,
            cursorColor = colors.primary,
        ),
    )
}

/** Primary/danger action with the web's "Saving…" busy label and a native spinner. */
@Composable
fun ProfileButton(
    label: String,
    busyLabel: String,
    busy: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = LocalWebColors.current
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) colors.danger else colors.primary,
            contentColor = Color.White,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(busyLabel)
        } else {
            icon?.let { Icon(it, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)) }
            Text(label)
        }
    }
}

@Composable
fun NoticeText(notice: Notice?) {
    notice ?: return
    val colors = LocalWebColors.current
    Text(notice.text, color = if (notice.ok) colors.success else colors.danger, fontSize = 13.sp)
}

/** `ConfirmDialog` equivalent on the platform dialog. */
@Composable
fun ProfileConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    busy: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalWebColors.current
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(confirmText, color = if (danger) colors.danger else colors.primary)
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
        containerColor = colors.bgElevated,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}

@Composable
fun ProfileAlert(message: String?, onDismiss: () -> Unit) {
    message ?: return
    val colors = LocalWebColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        containerColor = colors.bgElevated,
        textContentColor = colors.text,
    )
}

@Composable
fun SectionBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionDescription(text: String) {
    Text(text, color = LocalWebColors.current.textSecondary, fontSize = 13.sp)
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(LocalWebColors.current.border))
}
