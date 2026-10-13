package app.aino.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPolicyTest {
    @Test
    fun chatPushesRequireAStoredCredentialButOtherPushesDoNot() {
        val chat = ValidatedPush(PushKind.ChatMessage, emptyMap(), "msg:1", 1L)
        val alert = ValidatedPush(PushKind.General, emptyMap(), "notif:1", 1L)

        assertFalse(shouldHandlePush(chat, hasStoredCredential = false))
        assertTrue(shouldHandlePush(chat, hasStoredCredential = true))
        assertTrue(shouldHandlePush(alert, hasStoredCredential = false))
    }

    @Test
    fun tokenRegistrationMatchesBackendContract() {
        val encoded = encodeDeviceTokenRequest("fcm-token").toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"deviceToken\":\"fcm-token\""))
        assertTrue(encoded.contains("\"platform\":\"android\""))
        // Version 4 retains general links/reactions and adds group avatar metadata.
        assertTrue(encoded.contains("\"pushVersion\":4"))
    }

    @Test
    fun registrationKeyTracksTokenAndUserNotTheRotatingJwt() {
        fun jwt(payload: String) = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"
        val a = registrationKey("fcm-1", jwt("""{"id":1,"iat":1}"""))
        assertEquals(a, registrationKey("fcm-1", jwt("""{"id":1,"iat":2}""")))
        assertTrue(a != registrationKey("fcm-1", jwt("""{"id":2,"iat":1}""")))
        assertTrue(a != registrationKey("fcm-2", jwt("""{"id":1,"iat":1}""")))
        assertTrue(registrationKey("fcm-1", jwt("""{"id":1,"tenant_id":2}""")) !=
            registrationKey("fcm-1", jwt("""{"id":1,"tenant_id":3}""")))
    }

    @Test
    fun aNewSessionAlwaysReRegistersButTokenRotationInsideItDoesNot() {
        // The server deletes a revoked session's push token: signing in again with the
        // same FCM token and user must re-post it (it used to be skipped for 12 h).
        fun jwt(payload: String) = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"
        val first = registrationKey("fcm-1", jwt("""{"id":1,"tenant_id":1,"sid":"a","iat":1}"""))
        assertEquals(first, registrationKey("fcm-1", jwt("""{"id":1,"tenant_id":1,"sid":"a","iat":900}""")))
        assertTrue(first != registrationKey("fcm-1", jwt("""{"id":1,"tenant_id":1,"sid":"b","iat":1}""")))
        assertEquals("1:1:a", sessionKey(jwt("""{"id":1,"tenant_id":1,"sid":"a"}""")))
    }

    @Test
    fun registrationAckExpiresDailyLikeSignalsFcmRefresh() {
        val now = 10L * PushTokenRegistrar.TTL_MS
        assertTrue(PushTokenRegistrar.isFresh(now - 1_000, now))
        assertFalse(PushTokenRegistrar.isFresh(now - PushTokenRegistrar.TTL_MS, now))
        assertFalse(PushTokenRegistrar.isFresh(0, now))
        assertFalse(PushTokenRegistrar.isFresh(now + 60_000, now))
    }

    @Test
    fun reactionTextQuotesTheMessageLikeSignal() {
        assertEquals("Reacted ❤️ to your message", reactionText("❤️", null))
        assertEquals("Reacted ❤️ to your message", reactionText("❤️", "  "))
        assertEquals("Reacted 👍 to: \"see you at 5\"", reactionText("👍", "see you\n at 5"))
        assertTrue(reactionText("👍", "x".repeat(200)).endsWith("…\""))
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