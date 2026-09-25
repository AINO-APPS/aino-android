package app.aino.mobile.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebType
import app.aino.mobile.core.navigation.AinoDestination

/**
 * Mobile bottom tab bar (P1.5) — loosely ported from `.mobile-tab-bar`, with a
 * Signal-Android-style taller bar and no seam against the top bar / page body:
 * same background as both (`--bg`, not `--bg-secondary`), no top border.
 *
 * 76dp, space-around, per-item Column(gap 2dp, padding 6dp vertical), icon
 * 22dp, label 0.62rem/600, active=--primary / inactive=--text-muted, chat
 * badge per `.chatBadge`.
 */
@Composable
fun WebTabBar(
    destinations: List<AinoDestination>,
    currentRoute: String?,
    chatUnread: Int,
    moreOpen: Boolean,
    moreActive: Boolean,
    onSelect: (AinoDestination) -> Unit,
    onToggleMore: () -> Unit,
) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth()
            .background(colors.bg)
            .navigationBarsPadding()
            .height(76.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            if (destination == AinoDestination.More) {
                // The More slot is a toggle for the popup, not a navigation target.
                TabItem(
                    destination = destination,
                    active = moreOpen || moreActive,
                    badge = 0,
                    colors = colors,
                    onClick = onToggleMore,
                )
            } else {
                TabItem(
                    destination = destination,
                    active = currentRoute == destination.route,
                    badge = if (destination == AinoDestination.Chat) chatUnread else 0,
                    colors = colors,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    destination: AinoDestination,
    active: Boolean,
    badge: Int,
    colors: app.aino.mobile.core.designsystem.tokens.WebColors,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(if (active) colors.primary else colors.textMuted, tween(150), label = "tabTint")
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Box {
            Icon(destination.icon, destination.label, Modifier.size(22.dp), tint = tint)
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(start = 12.dp)
                        .size(15.dp)
                        .background(colors.danger, CircleShape)
                        .border(2.dp, colors.bg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        color = Color.White,
                        style = WebType.micro,
                    )
                }
            }
        }
        Text(destination.label, color = tint, style = WebType.caption)
    }
}
