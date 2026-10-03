package app.aino.mobile.core.common

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TrackerModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesExactSecondsWhenTheServerSendsThem() {
        val status = json.decodeFromString<TrackerStatus>(
            """{"state":"on_floor","floorMinutes":125,"breakMinutes":10,"floorSeconds":7537,"breakSeconds":"615"}""",
        )
        assertEquals(7_537, status.floorSeconds)
        assertEquals(615, status.breakSeconds)
        assertEquals(7_537L, status.exactFloorSeconds)
        assertEquals(615L, status.exactBreakSeconds)
        assertEquals(0L, status.receivedAtEpochMs)
    }

    @Test
    fun olderServersWithoutSecondsFallBackToWholeMinutes() {
        val status = json.decodeFromString<TrackerStatus>("""{"state":"on_break","floorMinutes":125,"breakMinutes":10}""")
        assertNull(status.floorSeconds)
        assertNull(status.breakSeconds)
        assertEquals(7_500L, status.exactFloorSeconds)
        assertEquals(600L, status.exactBreakSeconds)

        val nulls = json.decodeFromString<TrackerStatus>("""{"floorMinutes":2,"floorSeconds":null,"breakSeconds":""}""")
        assertEquals(120L, nulls.exactFloorSeconds)
        assertEquals(0L, nulls.exactBreakSeconds)
    }

    @Test
    fun anchorGrowsOnlyTheRunningBucket() {
        val floor = TimerAnchor(floorSeconds = 100, breakSeconds = 50, state = "on_floor", atEpochMs = 10_000)
        assertEquals(103L to 50L, floor.at(13_999))

        val onBreak = floor.copy(state = "on_break")
        assertEquals(100L to 53L, onBreak.at(13_000))

        val out = floor.copy(state = "logged_out")
        assertEquals(100L to 50L, out.at(99_000))
        // Clock skew never counts backwards.
        assertEquals(100L to 50L, floor.at(5_000))
    }

    @Test
    fun anchorFromStatusKeepsExactSecondsAndGrowsOnlyTheRunningBucket() {
        // 1h 45m 37s worked, 15m 12s break when the response arrived.
        val working = TrackerStatus(state = "on_floor", floorMinutes = 105, breakMinutes = 15, floorSeconds = 6_337, breakSeconds = 912, receivedAtEpochMs = 1_000_000)
        assertEquals(6_337L to 912L, TimerAnchor.of(working).at(1_000_000))
        assertEquals("01:45:42", formatDuration(TimerAnchor.of(working).at(1_005_400).first))

        val onBreak = TrackerStatus(state = "on_break", floorMinutes = 60, breakMinutes = 5, floorSeconds = 3_610, breakSeconds = 301, receivedAtEpochMs = 1_000)
        assertEquals(3_610L to 331L, TimerAnchor.of(onBreak).at(31_000))
    }

    @Test
    fun anchorUsesReceiveTimeAndFallsBackToNow() {
        val status = TrackerStatus(state = "on_floor", floorSeconds = 61, receivedAtEpochMs = 1_000)
        assertEquals(1_000L, TimerAnchor.of(status, nowEpochMs = 9_000).atEpochMs)
        assertEquals(9_000L, TimerAnchor.of(status.copy(receivedAtEpochMs = 0), nowEpochMs = 9_000).atEpochMs)
    }

    @Test
    fun refreshWithinTwoSecondsKeepsCountingFromTheShownValue() {
        // Showing 10:00:07 (anchored at 0 with 36_000 s, 7 s later).
        val shown = TimerAnchor(36_000, 0, "on_floor", atEpochMs = 0)
        val now = 7_500L
        // Server says 36_005 s at t=6_000 → 36_006 s now: 1 s behind the display.
        val behind = TimerAnchor(36_005, 0, "on_floor", atEpochMs = 6_000)
        assertSame(shown, shown.reanchor(behind, now))
        // 2 s ahead is still smoothed.
        val ahead = TimerAnchor(36_009, 0, "on_floor", atEpochMs = 7_000)
        assertSame(shown, shown.reanchor(ahead, now))
    }

    @Test
    fun largerDifferencesOrStateChangesReanchorImmediately() {
        val shown = TimerAnchor(36_000, 0, "on_floor", atEpochMs = 0)
        val now = 7_500L
        // Another device logged 5 more minutes.
        val changed = TimerAnchor(36_307, 0, "on_floor", atEpochMs = 7_000)
        assertSame(changed, shown.reanchor(changed, now))
        // Backwards by 3 s is a real correction too.
        val corrected = TimerAnchor(36_004, 0, "on_floor", atEpochMs = 7_000)
        assertSame(corrected, shown.reanchor(corrected, now))
        // A break started: same floor total, but the running bucket changed.
        val onBreak = TimerAnchor(36_007, 0, "on_break", atEpochMs = 7_000)
        assertSame(onBreak, shown.reanchor(onBreak, now))
        // Break total drifting beyond the window re-anchors as well.
        val breakChanged = TimerAnchor(36_007, 120, "on_floor", atEpochMs = 7_000)
        assertSame(breakChanged, shown.reanchor(breakChanged, now))
        // Nothing shown yet: take the new anchor.
        assertSame(changed, null.reanchor(changed, now))
    }

    @Test
    fun tickerWaitsUntilTheNextWholeSecond() {
        val anchor = TimerAnchor(0, 0, "on_floor", atEpochMs = 1_000)
        assertEquals(700L, anchor.msToNextSecond(3_300))
        assertEquals(1_000L, anchor.msToNextSecond(4_000))
    }

    @Test
    fun cachedStatusFromAnEarlierDayIsNotToday() {
        val utc = ZoneOffset.UTC
        val noon = java.time.Instant.parse("2026-10-03T12:00:00Z").toEpochMilli()
        val morning = java.time.Instant.parse("2026-10-03T06:00:00Z").toEpochMilli()
        val yesterday = java.time.Instant.parse("2026-10-02T23:59:00Z").toEpochMilli()
        assertTrue(TrackerStatus(receivedAtEpochMs = morning).isFromDayOf(noon, utc))
        assertFalse(TrackerStatus(receivedAtEpochMs = yesterday).isFromDayOf(noon, utc))
        assertTrue(TrackerStatus().isFromDayOf(noon, utc))
    }
}
