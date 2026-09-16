package app.aino.mobile.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.theme.AinoBlue
import app.aino.mobile.core.designsystem.theme.AinoCyan
import app.aino.mobile.core.designsystem.theme.AinoDanger
import app.aino.mobile.core.designsystem.theme.AinoSuccess
import app.aino.mobile.core.designsystem.theme.AinoWarning

@Composable
fun AinoAtmosphere(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

@Composable
fun AinoGlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Kept under the established name to avoid a disruptive call-site rename,
    // but this is now a real Material 3 tonal surface rather than translucent
    // white paint. The old treatment had weak contrast in both themes.
    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) { content() }
}

@Composable
fun AinoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable RowScope.() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AinoBlue, disabledContainerColor = AinoBlue),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke(this)
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

enum class AlertTone { Info, Success, Warning, Error }

@Composable
fun AinoAlert(text: String, tone: AlertTone, modifier: Modifier = Modifier) {
    val color = when (tone) {
        AlertTone.Info -> AinoBlue
        AlertTone.Success -> AinoSuccess
        AlertTone.Warning -> AinoWarning
        AlertTone.Error -> AinoDanger
    }
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(12.dp),
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun AinoSectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun AinoBadge(text: String, tone: AlertTone = AlertTone.Info, modifier: Modifier = Modifier) {
    val color = when (tone) {
        AlertTone.Info -> AinoCyan
        AlertTone.Success -> AinoSuccess
        AlertTone.Warning -> AinoWarning
        AlertTone.Error -> AinoDanger
    }
    Text(
        text,
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
fun AinoMetric(icon: ImageVector, label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.background(tint.copy(alpha = 0.14f), RoundedCornerShape(9.dp)).padding(9.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint)
        }
        Column {
            Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, color = tint, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun AinoStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color, CircleShape))
}