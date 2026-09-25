package app.aino.mobile.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.aino.mobile.feature.home.component.EventReminder
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val REMINDER_SCHEDULE = listOf(
    10 * 60 * 1000L to "~10 min",
    5 * 60 * 1000L to "~5 min",
    2 * 60 * 1000L to "~2 min",
)
private const val AUTO_CLOSE_MS = 15_000L

private class ReminderState {
    var reminders by mutableStateOf<List<EventReminder>>(emptyList())
    val fired = mutableSetOf<String>()
    var jobs = mutableListOf<Job>()
}

/**
 * `useEventReminder` port (P2.9). Schedules 10/5/2-minute reminders for today's
 * non-all-day events, auto-closing each after 15s. Returns the live reminder
 * list and a dismiss function.
 */
@Composable
fun rememberEventReminders(events: List<DashboardEvent>): Pair<List<EventReminder>, (String) -> Unit> {
    val state = remember { ReminderState() }
    val dismiss: (String) -> Unit = remember { { id -> state.reminders = state.reminders.filter { it.id != id } } }

    DisposableEffect(events) {
        state.jobs.forEach { it.cancel() }
        state.jobs = mutableListOf()
        val scope = CoroutineScope(Dispatchers.Default)
        val now = System.currentTimeMillis()
        events.filter { !it.allDay }.forEach { ev ->
            val startMs = runCatching { Instant.parse(ev.startTime).toEpochMilli() }.getOrDefault(0L)
            if (startMs == 0L) return@forEach
            REMINDER_SCHEDULE.forEach { (offset, label) ->
                val fireKey = "${ev.id}-$offset"
                if (fireKey in state.fired) return@forEach
                val reminderMs = startMs - offset
                val delayMs = reminderMs - now
                if (delayMs < -60_000) return@forEach
                val job = scope.launch {
                    delay(maxOf(0L, delayMs))
                    if (fireKey in state.fired) return@launch
                    state.fired += fireKey
                    val id = "${ev.id}-$offset-${System.currentTimeMillis()}"
                    state.reminders = state.reminders + EventReminder(id, ev.title, label)
                    state.jobs += scope.launch {
                        delay(AUTO_CLOSE_MS)
                        state.reminders = state.reminders.filter { it.id != id }
                    }
                }
                state.jobs += job
            }
        }
        onDispose { state.jobs.forEach { it.cancel() } }
    }
    return state.reminders to dismiss
}