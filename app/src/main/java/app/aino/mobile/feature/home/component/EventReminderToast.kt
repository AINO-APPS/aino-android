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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

data class EventReminder(val id: String, val title: String, val timeLabel: String)

/** `EventReminderToast` port (P2.9): transient reminder stack, auto-dismiss. */
@Composable
fun EventReminderToast(reminders: List<EventReminder>, onDismiss: (String) -> Unit) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reminders.forEach { reminder ->
            Row(
                Modifier.fillMaxWidth()
                    .background(colors.bgElevated, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(30.dp).background(colors.primaryGlow, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(15.dp), tint = colors.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(reminder.title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("starts ${reminder.timeLabel}", color = colors.textMuted, fontSize = 11.sp)
                }
                IconButton(onClick = { onDismiss(reminder.id) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "Dismiss", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}