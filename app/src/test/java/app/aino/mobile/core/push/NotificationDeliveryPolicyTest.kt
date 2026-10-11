package app.aino.mobile.core.push

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class NotificationDeliveryPolicyTest {
    @Test fun reactionsOnlyNotifyOtherPeoplesAdditionsToOwnMessages() {
        assertTrue(reactionEligible("added", 1, 2, 1, false, false))
        assertFalse(reactionEligible("removed", 1, 2, 1, false, false))
        assertFalse(reactionEligible("added", 1, 1, 1, false, false))
        assertFalse(reactionEligible("added", 2, 3, 1, false, false))
        assertFalse(reactionEligible("added", null, 2, 1, false, false))
        assertFalse(reactionEligible("added", 1, 2, 1, true, false))
        assertFalse(reactionEligible("added", 1, 2, 1, false, true))
    }

    @Test fun versionThreeReactionWireContractIsStrictWithIsoSendTimeAndEpochExpiry() {
        val now = Instant.ofEpochSecond(1000)
        val data = mapOf("type" to "chat_reaction", "title" to "Reaction", "body" to "Reacted to your message",
            "conversationId" to "1", "messageId" to "2", "messageOwnerId" to "3", "actorId" to "4",
            "actorName" to "Asha", "actorAvatar" to "", "emoji" to "👍", "reactionId" to "5",
            "action" to "added", "dedupeKey" to "reaction:5", "tenantId" to "6", "sentAt" to Instant.ofEpochSecond(999).toString(), "expiresAt" to "2000")
        val push = validatePushPayload(data, now).getOrThrow()
        assertEquals(PushKind.ChatReaction, push.kind)
        assertEquals(NotificationTags.REACTION, notificationTag(push))
        assertEquals(5, notificationId(push))
        for (change in listOf("action" to "removed", "actorId" to "3", "dedupeKey" to "reaction:6",
            "expiresAt" to "1000", "reactionId" to "0", "sentAt" to "999", "link" to "/chat/1")) {
            assertTrue(change.toString(), validatePushPayload(data + change, now).isFailure)
        }
        assertFalse(shouldHandlePush(push, false))
        assertTrue(shouldHandlePush(push, true))
    }
}
