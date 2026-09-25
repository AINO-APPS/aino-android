package app.aino.mobile.feature.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.DashboardAnnouncement
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** `QUOTE_ROTATION_INTERVAL` = 20s (client/src/constants/index.ts). */
private const val QUOTE_ROTATION_INTERVAL_MS = 20_000L

private fun greeting(): String {
    val hour = LocalTime.now().hour
    return if (hour < 12) "Good Morning" else if (hour < 17) "Good Afternoon" else "Good Evening"
}

private val DateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)

/** `.greeting-banner` (P2.2): greeting + date + rotating announcement carousel. */
@Composable
fun GreetingBanner(fullName: String?, announcements: List<DashboardAnnouncement>) {
    val colors = LocalWebColors.current
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(announcements.size) {
        if (announcements.size <= 1) return@LaunchedEffect
        while (true) {
            delay(QUOTE_ROTATION_INTERVAL_MS)
            index = (index + 1) % announcements.size
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            "${greeting()}, ${fullName ?: "there"}!",
            color = colors.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            LocalDate.now().format(DateFormatter),
            color = colors.textSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (announcements.isNotEmpty()) {
            val current = announcements[index % announcements.size]
            Text(
                if (current.type == "quote") "\"${current.message}\"" else current.message,
                color = colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (announcements.size > 1) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    announcements.forEachIndexed { i, _ ->
                        Box(
                            Modifier.size(6.dp).background(
                                if (i == index % announcements.size) colors.primary else colors.textMuted,
                                CircleShape,
                            ),
                        )
                    }
                }
            }
        }
    }
}
