package app.aino.mobile.feature.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.DashboardEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun eventTime(iso: String?): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("—")

private fun parseIso(iso: String?): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

private fun eventColor(color: String?, fallback: Color): Color = color?.let {
    runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
} ?: fallback

/** `TodayEventsCard` port (P2.4): today + tomorrow, meetings-first, +N more. */
@Composable
fun TodayEventsCard(today: List<DashboardEvent>, tomorrow: List<DashboardEvent>, onCalendar: () -> Unit) {
    val colors = LocalWebColors.current
    val accent = colors.primary
    val now = System.currentTimeMillis()
    val todaySorted = today.sortedBy { parseIso(it.startTime) }
    val meetings = todaySorted.filter { it.meetingCode != null && parseIso(it.endTime) > now }.take(4)
    val meetingIds = meetings.map { it.id }.toSet()
    val others = todaySorted.filter { it.id !in meetingIds }
    val visibleOthers = others.take(4)
    val hidden = others.size - visibleOthers.size
    val tomorrowSorted = tomorrow.sortedBy { parseIso(it.startTime) }.take(3)

    WebCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp), tint = colors.primary)
            Text(" Today's Events", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (todaySorted.isNotEmpty()) {
                Text(" ${todaySorted.size}", color = colors.textMuted, fontSize = 13.sp)
            }
        }

        if (meetings.isNotEmpty()) {
            Text("Upcoming Meetings", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            meetings.forEach { ev -> MeetingRow(ev, accent, colors) }
        }

        if (visibleOthers.isNotEmpty()) {
            if (meetings.isNotEmpty()) Text("Other Events", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            visibleOthers.forEach { ev -> EventRow(ev, accent, colors) }
            if (hidden > 0) {
                Text("+$hidden more", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp).clickable(onClick = onCalendar))
            }
        }

        if (todaySorted.isEmpty()) {
            Text("No events scheduled for today.", color = colors.textMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }

        if (tomorrowSorted.isNotEmpty()) {
            Text("Tomorrow", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            tomorrowSorted.forEach { ev -> EventRow(ev, accent, colors) }
        }
    }
}

@Composable
private fun EventRow(ev: DashboardEvent, accent: Color, colors: app.aino.mobile.core.designsystem.tokens.WebColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).background(eventColor(ev.color, accent), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(ev.title, color = colors.text, fontSize = 14.sp)
            Text(if (ev.allDay) "All day" else "${eventTime(ev.startTime)} – ${eventTime(ev.endTime)}", color = colors.textMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MeetingRow(ev: DashboardEvent, accent: Color, colors: app.aino.mobile.core.designsystem.tokens.WebColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Videocam, null, Modifier.size(16.dp), tint = accent)
        Column(Modifier.weight(1f)) {
            Text(ev.title, color = colors.text, fontSize = 14.sp)
            Text("${eventTime(ev.startTime)} – ${eventTime(ev.endTime)}", color = colors.textMuted, fontSize = 12.sp)
        }
        Text("Join", color = colors.onAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(accent, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}