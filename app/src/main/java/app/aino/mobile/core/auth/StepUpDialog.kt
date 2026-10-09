package app.aino.mobile.core.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** Asks an administrator for an authenticator (or recovery) code before a device credential is added. */
@Composable
fun StepUpDialog(prompt: StepUpPrompt, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { if (!prompt.submitting) onSubmit(code) }
    AlertDialog(
        onDismissRequest = { if (!prompt.submitting) onCancel() },
        title = { Text("Confirm it's you") },
        text = {
            Column {
                Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filterNot(Char::isWhitespace).take(MAX_CODE_LENGTH) },
                    label = { Text("6-digit code or recovery code") },
                    singleLine = true,
                    enabled = !prompt.submitting,
                    isError = prompt.error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .focusRequester(focus)
                        .testTag("stepUpCode"),
                )
                prompt.error?.let {
                    Text(it, color = LocalWebColors.current.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !prompt.submitting && code.isNotBlank()) {
                Text(if (prompt.submitting) "Verifying…" else "Verify")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !prompt.submitting) { Text("Cancel") }
        },
    )
}

private const val MAX_CODE_LENGTH = 32
