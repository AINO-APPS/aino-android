package app.aino.mobile.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.auth.RealmOption

@Composable
fun LoginScreen(
    loading: Boolean,
    error: String?,
    message: String?,
    biometricAvailable: Boolean,
    biometricEnrolled: Boolean,
    onLogin: (String, String) -> Unit,
    onBiometricLogin: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AuthColumn {
        Text("Welcome to AINO", style = MaterialTheme.typography.headlineMedium)
        Text("Sign in to your workspace", style = MaterialTheme.typography.bodyLarge)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onLogin(username, password) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator() else Text("Sign in")
        }
        if (biometricAvailable && biometricEnrolled) {
            TextButton(onClick = onBiometricLogin, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with biometrics")
            }
        }
    }
}

@Composable
fun RealmChoiceScreen(realms: List<RealmOption>, loading: Boolean, error: String?, onChoose: (String) -> Unit, onBack: () -> Unit) {
    AuthColumn {
        Text("Choose where to work", style = MaterialTheme.typography.headlineMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        realms.forEach { option ->
            Button(onClick = { onChoose(option.realm) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text(option.label + if (option.default) " (default)" else "")
            }
        }
        TextButton(onClick = onBack, enabled = !loading) { Text("Back") }
    }
}

@Composable
fun ChangePasswordScreen(displayName: String, loading: Boolean, error: String?, onSubmit: (String, String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AuthColumn {
        Text("Change your password", style = MaterialTheme.typography.headlineMedium)
        Text("Welcome, $displayName. Set a new password to continue.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PasswordField("Current password", current) { current = it }
        PasswordField("New password", next) { next = it }
        PasswordField("Confirm new password", confirmation) { confirmation = it }
        Button(onClick = { onSubmit(current, next, confirmation) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator() else Text("Set new password")
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}