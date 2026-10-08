package app.aino.mobile.core.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.time.ZoneId

@Serializable
data class TimeEntryDto(
    @SerialName("entry_type") val entryType: String,
    val timestamp: String,
    @SerialName("work_mode") val workMode: String? = null,
    @SerialName("is_manual") val isManual: Boolean = false,
)

@Serializable
data class WorkModeRequestState(
    val id: Long = 0,
    val status: String = "pending",
    val workMode: String = "",
    val rejectReason: String? = null,
)

/**
 * Mode to preselect for the next clock-in: while logged out, an approved switch
 * or today's locked mode, so the clock-in is not refused (web `preferredWorkMode`).
 */
fun TrackerStatus.preferredWorkMode(): String {
    if (state != "logged_out") return workMode
    val approved = workModeRequest?.takeIf { it.status == "approved" }?.workMode?.takeIf(String::isNotBlank)
    return approved ?: lockedWorkMode ?: workMode
}

@Serializable
data class TrackerStatus(
    val state: String = "logged_out",
    val floorMinutes: Int = 0,
    val breakMinutes: Int = 0,
    /** Exact seconds at response time; older servers omit them and only send whole minutes. */
    @Serializable(with = LenientIntNullableSerializer::class) val floorSeconds: Int? = null,
    @Serializable(with = LenientIntNullableSerializer::class) val breakSeconds: Int? = null,
    val entries: List<TimeEntryDto> = emptyList(),
    val isWeekend: Boolean = false,
    val workMode: String = "office",
    val targetMinutes: Int = 480,
    val dailyTargetMet: Boolean = false,
    val autoLoggedOut: Boolean = false,
    /** Mode of today's first clock-in; another mode needs an approved request. */
    val lockedWorkMode: String? = null,
    val workModeRequest: WorkModeRequestState? = null,
    /**
     * Wall-clock time this body was originally received from the server (for a
     * cached body: when it was fetched, not when it was painted); 0 = unknown.
     */
    @Transient val receivedAtEpochMs: Long = 0L,
) {
    val exactFloorSeconds: Long get() = (floorSeconds?.toLong() ?: (floorMinutes * 60L)).coerceAtLeast(0)
    val exactBreakSeconds: Long get() = (breakSeconds?.toLong() ?: (breakMinutes * 60L)).coerceAtLeast(0)

    /** False for a cached body fetched on an earlier local day: its totals and state no longer apply. */
    fun isFromDayOf(nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        receivedAtEpochMs <= 0L ||
            Instant.ofEpochMilli(receivedAtEpochMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
}

/**
 * Where the live work timer counts from: [floorSeconds]/[breakSeconds] were
 * true at [atEpochMs]; the bucket matching [state] grows by the wall-clock
 * time since. Held by ViewModels so recomposition / tab switches never reset it.
 */
data class TimerAnchor(val floorSeconds: Long, val breakSeconds: Long, val state: String, val atEpochMs: Long) {
    /** (floor, break) seconds at [nowEpochMs]. */
    fun at(nowEpochMs: Long): Pair<Long, Long> {
        val elapsed = (nowEpochMs - atEpochMs).coerceAtLeast(0) / 1_000
        return (floorSeconds + if (state == "on_floor") elapsed else 0L) to
            (breakSeconds + if (state == "on_break") elapsed else 0L)
    }

    /** Milliseconds until the displayed second next changes, so a ticker stays in phase. */
    fun msToNextSecond(nowEpochMs: Long): Long = 1_000 - (nowEpochMs - atEpochMs).mod(1_000L)

    companion object {
        fun of(status: TrackerStatus, receivedAtEpochMs: Long = status.receivedAtEpochMs, nowEpochMs: Long = System.currentTimeMillis()) =
            TimerAnchor(
                floorSeconds = status.exactFloorSeconds,
                breakSeconds = status.exactBreakSeconds,
                state = status.state,
                atEpochMs = if (receivedAtEpochMs > 0) receivedAtEpochMs else nowEpochMs,
            )

        const val SMOOTHING_SECONDS = 2L
    }
}

/**
 * Re-anchor on a refresh. When the state is unchanged and both totals are
 * within [SMOOTHING_SECONDS][TimerAnchor.SMOOTHING_SECONDS] of what is on
 * screen, keep counting from the current anchor so the timer never visibly
 * jumps (or steps backwards); a real change re-anchors immediately.
 */
fun TimerAnchor?.reanchor(next: TimerAnchor, nowEpochMs: Long, toleranceSeconds: Long = TimerAnchor.SMOOTHING_SECONDS): TimerAnchor {
    val current = this ?: return next
    if (current.state != next.state) return next
    val (shownFloor, shownBreak) = current.at(nowEpochMs)
    val (nextFloor, nextBreak) = next.at(nowEpochMs)
    val close = kotlin.math.abs(shownFloor - nextFloor) <= toleranceSeconds && kotlin.math.abs(shownBreak - nextBreak) <= toleranceSeconds
    return if (close) current else next
}

fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(safe / 3600, (safe % 3600) / 60, safe % 60)
}
/** `formatTime` port: minutes → "07h 30m". */
fun formatMinutes(totalMinutes: Int): String {
    val abs = kotlin.math.abs(totalMinutes)
    return "%02dh %02dm".format(abs / 60, abs % 60)
}
