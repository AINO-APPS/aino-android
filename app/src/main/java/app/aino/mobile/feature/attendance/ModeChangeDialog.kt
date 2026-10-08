package app.aino.mobile.feature.attendance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * Today's first clock-in fixed the work mode; switching needs the manager's
 * approval (web `WorkModeChangeDialog`).
 */
@Composable
fun ModeChangeDialog(
    draft: ModeChangeDraft,
    onReason: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalWebColors.current
    val requested = draft.requested.name
    AlertDialog(
        onDismissRequest = { if (!draft.sending) onDismiss() },
        containerColor = colors.bgElevated,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
        title = { Text(if (draft.sent) "Request sent" else "Work mode change needs approval") },
        text = {
            if (draft.sent) {
                Text("Your manager has been asked to approve $requested for today. You can clock in as $requested once it's approved.")
            } else {
                Column {
                    Text("You already clocked in as ${draft.locked.name} today. Working ${requested.lowercase()} for the rest of the day needs your manager's approval.")
                    OutlinedTextField(
                        value = draft.reason,
                        onValueChange = onReason,
                        label = { Text("Reason") },
                        placeholder = { Text("e.g. Leaving office early for an appointment") },
                        enabled = !draft.sending,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 96.dp),
                    )
                    draft.error?.let { Text(it, color = colors.danger, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (draft.sent) onDismiss else onSubmit, enabled = !draft.sending) {
                Text(
                    when {
                        draft.sent -> "OK"
                        draft.sending -> "Sending…"
                        else -> "Request change"
                    },
                    color = colors.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = if (draft.sent) null else {
            { TextButton(onClick = onDismiss, enabled = !draft.sending) { Text("Cancel") } }
        },
    )
}
