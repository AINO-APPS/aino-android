package app.aino.mobile.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared primitives for the Attendance page tabs (P3), styled from the web
 * module CSS files rather than the retired cream/glass design system.
 */

/** `.wrap` / `.manual-entry-card`: glass surface, 1px border, 16px radius. */
@Composable
fun AttendanceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalWebColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.glassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp),
        content = content,
    )
}

/** `.errorBanner` / `.error-msg`: translucent red alert with the server's message verbatim. */
@Composable
fun WebErrorBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0x1AEF4444), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0x40EF4444), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(message, color = Color(0xFFEF4444), fontSize = 0.85.rem)
    }
}

/** `.success-msg` equivalent. */
@Composable
fun WebSuccessBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0x1A10B981), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0x4010B981), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(message, color = Color(0xFF10B981), fontSize = 0.85.rem)
    }
}

/** Card heading row: 17px icon + 1rem bold title (`h3` inside cards). */
@Composable
fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        androidx.compose.material3.Icon(icon, null, Modifier.size(17.dp), tint = colors.text)
        Text(title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.rem)
    }
}

/** Form label (`Leaves.module.css .label`): 0.8rem semi-bold muted. */
@Composable
fun WebFieldLabel(text: String) {
    val colors = LocalWebColors.current
    Text(text, color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 0.8.rem)
}

/** `fmtDate` port: "2026-01-15" → "Wed, Jan 15". */
fun fmtDate(value: String): String = runCatching {
    LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
}.getOrDefault(value)

/** Small selectable chip used by the leave status/type filters. */
@Composable
fun WebFilterChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Box(
        modifier
            .background(if (active) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, if (active) colors.primary else colors.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (active) colors.onAccent else colors.textSecondary,
            fontSize = 0.8.rem,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
