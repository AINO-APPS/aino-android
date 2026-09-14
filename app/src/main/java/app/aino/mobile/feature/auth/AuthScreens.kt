package app.aino.mobile.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.auth.RealmOption
import app.aino.mobile.R
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AlertTone
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults

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
    AuthFrame {
        BrandMark()
        Text("Welcome to AINO", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("Sign in to your workspace", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { AinoAlert(it, AlertTone.Success) }
        error?.let { AinoAlert(it, AlertTone.Error) }
        AinoTextField(username, { username = it }, "Username")
        AinoTextField(
            password,
            { password = it },
            "Password",
            isPassword = true,
        )
        AinoPrimaryButton(
            text = if (loading) "Signing in…" else "Sign in",
            onClick = { onLogin(username, password) },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(end = 8.dp).size(18.dp), tint = Color.White) },
        )
        if (biometricAvailable && biometricEnrolled) {
            TextButton(onClick = onBiometricLogin, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Fingerprint, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Sign in with biometrics", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun RealmChoiceScreen(realms: List<RealmOption>, loading: Boolean, error: String?, onChoose: (String) -> Unit, onBack: () -> Unit) {
    AuthFrame {
        BrandMark()
        Text("Choose where to work", style = MaterialTheme.typography.headlineMedium)
        Text("Your account belongs to more than one workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { AinoAlert(it, AlertTone.Error) }
        realms.forEach { option ->
            AinoPrimaryButton(
                option.label + if (option.default) " · Default" else "",
                { onChoose(option.realm) },
                Modifier.fillMaxWidth(),
                !loading,
            )
        }
        TextButton(onClick = onBack, enabled = !loading) { Text("Back") }
    }
}

@Composable
fun ChangePasswordScreen(displayName: String, loading: Boolean, error: String?, onSubmit: (String, String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AuthFrame {
        BrandMark()
        Text("Change your password", style = MaterialTheme.typography.headlineMedium)
        Text("Welcome, $displayName. Set a secure password to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { AinoAlert(it, AlertTone.Error) }
        PasswordField("Current password", current) { current = it }
        PasswordField("New password", next) { next = it }
        PasswordField("Confirm new password", confirmation) { confirmation = it }
        AinoPrimaryButton(
            if (loading) "Updating…" else "Set new password",
            { onSubmit(current, next, confirmation) },
            Modifier.fillMaxWidth(),
            !loading,
            leadingIcon = { Icon(Icons.Outlined.Lock, null, Modifier.padding(end = 8.dp).size(18.dp), tint = Color.White) },
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) =
    AinoTextField(value, onChange, label, isPassword = true)

@Composable
private fun AuthFrame(content: @Composable () -> Unit) {
    AinoAtmosphere {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A0E1C))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.aino_icon), contentDescription = "AINO", Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)))
    }
}

@Composable
private fun AinoTextField(value: String, onChange: (String) -> Unit, label: String, isPassword: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label.uppercase(), style = MaterialTheme.typography.labelMedium) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        ),
    )
}