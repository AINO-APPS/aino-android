package app.aino.mobile.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** Badge text: hidden at zero, capped at "99+". */
fun countBadgeLabel(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/**
 * `.chatBadge` / `.notif-badge`: danger pill, white bold count, ringed with
 * the surface colour so it reads cleanly over an icon.
 *
 * The pill grows with its label ("7" is a circle, "99+" a capsule) and the
 * text has a tight line box with no font padding — a fixed-size circle with
 * the inherited 1.6 line height clipped two-digit counts and sat off-centre.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, ringColor: Color = LocalWebColors.current.bg) {
    val label = countBadgeLabel(count) ?: return
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            // Size to the label, not the parent: it overhangs a 22-26dp icon box
            // whose width would otherwise squeeze "99+" into a wrapped column.
            .wrapContentSize(Alignment.Center, unbounded = true)
            .semantics { contentDescription = "$label unread" }
            .border(1.5.dp, ringColor, shape)
            .padding(1.5.dp)
            .background(colors.danger, shape)
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Never wrap: a narrow parent constraint stacked "42" into two lines.
        Text(label, color = Color.White, style = CountBadgeText, maxLines = 1, softWrap = false)
    }
}

private val CountBadgeText = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.Bold,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)
