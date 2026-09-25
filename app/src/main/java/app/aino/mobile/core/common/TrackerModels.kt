package app.aino.mobile.core.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TimeEntryDto(
    @SerialName("entry_type") val entryType: String,
    val timestamp: String,
    @SerialName("work_mode") val workMode: String? = null,
    @SerialName("is_manual") val isManual: Boolean = false,
)

@Serializable
data class TrackerStatus(
    val state: String = "logged_out",
    val floorMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val entries: List<TimeEntryDto> = emptyList(),
    val isWeekend: Boolean = false,
    val workMode: String = "office",
    val targetMinutes: Int = 480,
    val dailyTargetMet: Boolean = false,
    val autoLoggedOut: Boolean = false,
)

fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(safe / 3600, (safe % 3600) / 60, safe % 60)
}
/** `formatTime` port: minutes → "07h 30m". */
fun formatMinutes(totalMinutes: Int): String {
    val abs = kotlin.math.abs(totalMinutes)
    return "%02dh %02dm".format(abs / 60, abs % 60)
}
