package app.aino.mobile.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * `.status-card` at mobile (P1.3).
 *
 * Web: background=--bg-secondary, border=1px --border, radius=20px (mobile
 * override), padding=1.2rem (19.2dp), no shadow, content left-aligned
 * (dashboard modules override the global center alignment to left).
 */
@Composable
fun WebCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWebColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgSecondary, RoundedCornerShape(20.dp))
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
            .padding(19.2.dp), // 1.2rem
        horizontalAlignment = androidx.compose.ui.Alignment.Start,
        content = content,
    )
}
