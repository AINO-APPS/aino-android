package app.aino.mobile.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.realtime.ConnectionIndicator

/** The shell's debounced connection status; defaults to connected (previews, tests). */
val LocalConnectionIndicator = compositionLocalOf { ConnectionIndicator.Connected }

/**
 * One quiet line while the live connection is actually down. Screens keep
 * showing what they have; failed refreshes retry on their own when it returns.
 */
@Composable
fun ConnectionStatusStrip(
    modifier: Modifier = Modifier,
    indicator: ConnectionIndicator = LocalConnectionIndicator.current,
) {
    val colors = LocalWebColors.current
    AnimatedVisibility(
        visible = indicator != ConnectionIndicator.Connected,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val label = if (indicator == ConnectionIndicator.WaitingForNetwork) "Waiting for network…" else "Connecting…"
        Row(
            Modifier.fillMaxWidth()
                .background(colors.warningGlow)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (indicator == ConnectionIndicator.Connecting) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(12.dp), color = colors.warning, strokeWidth = 1.5.dp)
            }
            Text(label, color = colors.textSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}
