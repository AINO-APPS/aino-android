package app.aino.mobile.core.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** Non-blocking "update available" strip shown above the app shell. */
@Composable
fun UpdateBanner(state: UpdateUiState, onInstall: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (state.available == null && !state.readyToInstall && state.message == null) return
    val colors = LocalWebColors.current
    val title = when {
        state.readyToInstall -> "Update downloaded"
        state.message != null -> state.message
        else -> "A new version of AINO is available"
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(colors.bgSecondary)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
        } else {
            if (!state.readyToInstall) TextButton(onClick = onDismiss) { Text("Later", color = colors.textSecondary) }
            if (state.available != null || state.readyToInstall) {
                TextButton(onClick = onInstall) {
                    Text(if (state.readyToInstall) "Restart" else "Update", color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("OK", color = colors.primary) }
            }
        }
    }
}
