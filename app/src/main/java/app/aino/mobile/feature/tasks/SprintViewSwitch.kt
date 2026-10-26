package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * The Sprint tab's Board ⇄ Plan switch (D21).
 *
 * Both surfaces render this, so whichever one the user is looking at offers a
 * way to the other. It used to live only in the board's header card, which
 * made the Board unreachable once the user switched to Plan.
 */
@Composable
internal fun SprintViewSwitch(
    selected: SprintView,
    onSelect: (SprintView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SprintView.entries.forEach { view ->
            val isSelected = selected == view
            Row(
                Modifier
                    .weight(1f)
                    .clip(ChipShape)
                    .background(if (isSelected) colors.primary.copy(alpha = 0.14f) else colors.bg)
                    .clickable(
                        onClickLabel = "Show ${view.key}",
                        role = Role.Tab,
                    ) { onSelect(view) }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (view == SprintView.Board) HeroIcons.QueueList else HeroIcons.ArrowsRightLeft,
                    null,
                    Modifier.size(14.dp),
                    tint = if (isSelected) colors.primary else colors.textMuted,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (view == SprintView.Board) "Board" else "Plan",
                    color = if (isSelected) colors.primary else colors.textSecondary,
                    fontSize = 0.76.rem,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
