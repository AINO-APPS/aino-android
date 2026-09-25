package app.aino.mobile.core.network

import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.common.TimeEntryDto
import app.aino.mobile.core.common.TrackerStatus
import app.aino.mobile.feature.home.AnnouncementEnvelope
import app.aino.mobile.feature.home.TaskSummary
import app.aino.mobile.feature.attendance.LeaveBalance
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.5 — response-decode regression tests.
 *
 * Each payload under `app/src/test/resources/payloads/` mirrors the real server
 * contract for one of the 12 core endpoints probed by the P0.3 on-device API
 * probe. Decoding must not throw and must not silently collapse to empty
 * collections — that is the entire class of "screen renders but is blank" bugs.
 * If a model's `@SerialName` drifts from the server, these tests fail.
 */
class ResponseDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("payloads/$name")) {
            "missing test payload: payloads/$name"
        }.readText()

    @Test
    fun profileDecodesWithFeatureGates() {
        val user = json.decodeFromString(AinoUser.serializer(), payload("profile.json"))

        assertEquals(9L, user.id)
        assertEquals("jane.doe", user.username)
        assertEquals(42L, user.tenantId)
        assertFalse(user.tenantFeatures.isEmpty())
        assertEquals(true, user.tenantFeatures["attendance"])
        assertEquals(true, user.tenantFeatures["tasks"])
        assertEquals(true, user.tenantFeatures["chat"])
        assertEquals("pro", user.tenantPlan)
    }

    @Test
    fun trackerStatusDecodesWithEntries() {
        val status = json.decodeFromString(TrackerStatus.serializer(), payload("tracker-status.json"))

        assertEquals("on_floor", status.state)
        assertEquals(312, status.floorMinutes)
        assertFalse(status.entries.isEmpty())
        assertEquals("clock_in", status.entries.first().entryType)
        assertEquals(480, status.targetMinutes)
    }

    @Test
    fun taskSummaryDecodesWithActiveTasks() {
        val summary = json.decodeFromString(TaskSummary.serializer(), payload("tracker-task-summary.json"))

        assertEquals(12, summary.total)
        assertFalse(summary.activeTasks.isEmpty())
        assertEquals("Ship parity plan", summary.activeTasks.first().title)
    }

    @Test
    fun trackerEntriesDecode() {
        val entries = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(TimeEntryDto.serializer()),
            payload("tracker-entries-today.json"),
        )

        assertFalse(entries.isEmpty())
        assertEquals(listOf("clock_in", "break_start", "break_end"), entries.map { it.entryType })
    }

    @Test
    fun announcementsEnvelopeDecodes() {
        val envelope = json.decodeFromString(AnnouncementEnvelope.serializer(), payload("notifications-announcements.json"))

        assertFalse(envelope.data.isEmpty())
        assertEquals("ann-1", envelope.data.first().id)
        assertEquals("Welcome to the new sprint", envelope.data.first().message)
    }

    @Test
    fun leaveBalancesDecodeWithPositiveAvailability() {
        val balances = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LeaveBalance.serializer()),
            payload("leaves-balance.json"),
        )

        assertFalse(balances.isEmpty())
        val annual = balances.first { it.leaveType == "annual" }
        assertEquals(20.0, annual.quota, 0.001)
        assertEquals(6.0, annual.used, 0.001)
        assertEquals(2.0, annual.carriedForward, 0.001)
        assertTrue(annual.available > 0.0)
    }

    private inline fun <reified T> list(serializer: kotlinx.serialization.KSerializer<T>, name: String): List<T> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer), payload(name))

    /** Clock-in root cause (2026-09-25): object-shaped `office_wifi_bssids` used to null the whole policy. */
    @Test
    fun orgPolicyDecodesObjectBssidsAndQuotedNumerics() {
        val policy = json.decodeFromString(app.aino.mobile.feature.attendance.AttendancePolicy.serializer(), payload("org-current.json"))

        assertTrue(policy.verificationEnabled)
        assertTrue(policy.wifiVerificationEnabled)
        assertEquals(listOf("aa:bb:cc:dd:ee:ff", "11-22-33-44-55-66"), policy.officeWifiBssids)
        assertEquals(12.9715987, policy.officeLatitude!!, 1e-7)
        assertEquals(4.0, policy.minHoursPresent!!, 0.001)
        assertEquals("09:30", policy.officeStartTime)
    }

    @Test
    fun chatConversationsAndMessagesDecode() {
        val conversations = list(app.aino.mobile.feature.chat.ChatConversation.serializer(), "chat-conversations.json")
        assertEquals(2, conversations.size)
        assertEquals(2, conversations.first().unreadCount)
        assertEquals("Design", conversations[1].title())

        val messages = list(app.aino.mobile.feature.chat.ChatMessage.serializer(), "chat-messages.json")
        assertEquals("aino.app", messages.first().linkPreview?.siteName)
        assertEquals(1, messages.first().reactions.size)
        assertEquals("audio/mp4", messages[1].fileType)
        assertEquals("Ana Lima", messages[1].conversationName)
    }

    @Test
    fun calendarEventsDecodeWithMeetings() {
        val events = list(app.aino.mobile.feature.home.DashboardEvent.serializer(), "calendar-events.json")
        assertEquals("abc-defg-hij", events.first().meetingCode)
        assertTrue(events[1].allDay)
    }

    @Test
    fun leavePoliciesAndManualEntriesDecode() {
        val policies = list(app.aino.mobile.feature.attendance.LeavePolicy.serializer(), "leave-policy-policies.json")
        assertEquals(12.0, policies.first().annualQuota, 0.001)
        assertEquals(8.0, policies[1].annualQuota, 0.001)

        val manual = list(app.aino.mobile.feature.attendance.ManualEntryRequest.serializer(), "tracker-manual-entries.json")
        assertEquals("09:30", manual.single().metadata?.clockIn)
    }
}
