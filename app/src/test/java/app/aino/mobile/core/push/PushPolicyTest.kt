package app.aino.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPolicyTest {
    @Test
    fun tokenRegistrationMatchesBackendContract() {
        val encoded = encodeDeviceTokenRequest("fcm-token").toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"deviceToken\":\"fcm-token\""))
        assertTrue(encoded.contains("\"platform\":\"android\""))
    }

    @Test
    fun dedupeRetentionDropsExpiredAndCapsNewest() {
        val now = 100_000_000L
        val values = mapOf(
            "expired" to now - 86_400_001,
            "old" to now - 5_000,
            "new" to now - 1_000,
        )
        val retained = retainedDedupeEntries(values, now, limit = 1)
        assertEquals(mapOf("new" to now - 1_000), retained)
        assertFalse("expired" in retained)
    }
}