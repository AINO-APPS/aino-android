package app.aino.mobile.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebType

/**
 * More popup sheet (P1.6) — port of `.mobile-more-popup`.
 *
 * Anchored above the bottom bar, min 190dp, --bg-elevated, 14dp radius,
 * 1px --border, 6dp padding, 2dp item gap, shadow `0 10px 34px rgba(0,0,0,0.3)`.
 * Rows: 18dp icon + 10dp gap + label 0.85rem/600 --text-secondary, 10dp
 * radius, 10px 12px padding, active → --bg-hover + --text.
 */
@Composable
fun MoreSheet(
    destinations: List<AinoDestination>,
    currentRoute: String?,
    onNavigate: (AinoDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    Column(
        modifier
            .widthIn(min = 190.dp)
            .shadow(12.dp, RoundedCornerShape(14.dp))
            .background(colors.bgElevated, RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        destinations.forEach { destination ->
            val active = currentRoute == destination.route
            Row(
                Modifier.clickable { onNavigate(destination) }
                    .background(if (active) colors.bgHover else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    destination.icon,
                    null,
                    Modifier.size(18.dp),
                    tint = if (active) colors.text else colors.textSecondary,
                )
                Text(
                    destination.label,
                    color = if (active) colors.text else colors.textSecondary,
                    style = WebType.bodyStrong,
                )
            }
        }
    }
}
