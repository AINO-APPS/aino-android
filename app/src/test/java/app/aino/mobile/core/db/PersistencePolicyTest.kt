package app.aino.mobile.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistencePolicyTest {
    @Test
    fun acceptsOnlyPositiveTenantAndUserScope() {
        assertEquals(CacheScope(4, 9), CacheScope(4, 9))
        assertTrue(runCatching { CacheScope(0, 9) }.isFailure)
        assertTrue(runCatching { CacheScope(4, 0) }.isFailure)
    }

    @Test
    fun classifiesDeliveryResponses() {
        assertEquals(DeliveryDecision.Delivered, deliveryDecision(201, 0, 1_000))
        assertEquals(DeliveryDecision.Failed, deliveryDecision(400, 0, 1_000))
        assertEquals(DeliveryDecision.Failed, deliveryDecision(401, 0, 1_000))
        assertEquals(DeliveryDecision.Failed, deliveryDecision(403, 0, 1_000))
        assertEquals(DeliveryDecision.Retry(6_000), deliveryDecision(408, 0, 1_000))
        assertEquals(DeliveryDecision.Retry(6_000), deliveryDecision(429, 0, 1_000))
        assertEquals(DeliveryDecision.Retry(6_000), deliveryDecision(500, 0, 1_000))
        assertEquals(DeliveryDecision.Retry(11_000), deliveryDecision(null, 1, 1_000))
        assertEquals(DeliveryDecision.Failed, deliveryDecision(500, MAX_ATTEMPTS, 1_000))
    }

    @Test
    fun retryBackoffIsBounded() {
        assertEquals(5_000L, outboxBackoffMs(0))
        assertEquals(80_000L, outboxBackoffMs(4))
        assertEquals(900_000L, outboxBackoffMs(20))
    }

    @Test
    fun everyPersistedEntityCarriesScope() {
        val conversation = ConversationEntity(4, 9, 1, "Title", null, 0, 1)
        val message = MessageEntity(4, 9, 2, 1, 9, "Hello", 1, "sent")
        val outbox = OutboxEntity(4, 9, "client-1", 1, "{}", createdAtEpochMs = 1)
        assertEquals(listOf(4L, 9L), listOf(conversation.tenantId, conversation.userId))
        assertEquals(listOf(4L, 9L), listOf(message.tenantId, message.userId))
        assertEquals(listOf(4L, 9L), listOf(outbox.tenantId, outbox.userId))
    }
}