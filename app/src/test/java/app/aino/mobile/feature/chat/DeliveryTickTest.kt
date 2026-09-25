package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Thresholds ported from `aino-platform/client/src/components/chat/DeliveryStatus.tsx`:
 * given others = (participantCount ?: 2) - 1, delivered/read counts exclude the sender.
 */
class DeliveryTickTest {
    private fun message(deliveredTo: List<Long> = emptyList()) = ChatMessage(
        id = 1L,
        senderId = 10L,
        createdAt = "2024-01-01T00:00:00Z",
        deliveredTo = deliveredTo,
    )

    private fun receipt(userId: Long, lastReadAt: String) = ReadReceipt(userId = userId, lastReadAt = lastReadAt, fullName = "User $userId")

    @Test fun `no delivery or read receipts is Sent`() {
        assertEquals(DeliveryTick.Sent, deliveryTick(message(), emptyList(), participantCount = 2))
    }

    @Test fun `delivered to the only other participant is Delivered`() {
        assertEquals(DeliveryTick.Delivered, deliveryTick(message(deliveredTo = listOf(20L)), emptyList(), participantCount = 2))
    }

    @Test fun `read by the only other participant is Read`() {
        val receipts = listOf(receipt(20L, "2024-01-01T00:05:00Z"))
        assertEquals(DeliveryTick.Read, deliveryTick(message(deliveredTo = listOf(20L)), receipts, participantCount = 2))
    }

    @Test fun `partial read in a group still counts as Read once any read plus full delivery`() {
        val receipts = listOf(receipt(20L, "2024-01-01T00:05:00Z"))
        assertEquals(DeliveryTick.Read, deliveryTick(message(deliveredTo = listOf(20L, 30L)), receipts, participantCount = 3))
    }

    @Test fun `partial delivery in a group is Delivered`() {
        assertEquals(DeliveryTick.Delivered, deliveryTick(message(deliveredTo = listOf(20L)), emptyList(), participantCount = 3))
    }

    @Test fun `sender's own read receipt is excluded from the read count`() {
        val receipts = listOf(receipt(10L, "2024-01-01T00:05:00Z"))
        assertEquals(DeliveryTick.Delivered, deliveryTick(message(deliveredTo = listOf(20L)), receipts, participantCount = 2))
    }

    @Test fun `missing participant count defaults to a 1-1 conversation`() {
        assertEquals(DeliveryTick.Delivered, deliveryTick(message(deliveredTo = listOf(20L)), emptyList(), participantCount = null))
    }

    @Test fun `non-positive other-participant count is treated as Sent`() {
        assertEquals(DeliveryTick.Sent, deliveryTick(message(deliveredTo = listOf(20L)), emptyList(), participantCount = 1))
    }
}
