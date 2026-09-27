package app.aino.mobile.feature.chat

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** Server `chat_message_delivered`: [userId]'s device received [messageId]. */
@Serializable
data class ChatDeliveredEvent(val messageId: Long, val conversationId: Long, val userId: Long)

fun applyDelivered(messages: List<ChatMessage>, event: ChatDeliveredEvent): List<ChatMessage> = messages.map {
    if (it.id != event.messageId || event.userId in it.deliveredTo) it else it.copy(deliveredTo = it.deliveredTo + event.userId)
}

/**
 * Signal-style delivery receipts: once a message reaches this device (socket,
 * push or thread load), tell the server so the sender's tick advances to
 * Delivered. Each id is acknowledged at most once per process.
 */
object DeliveryReceipts {
    private val acked = ConcurrentHashMap.newKeySet<Long>()

    /** Incoming messages that still need a receipt from [me]. */
    fun pending(messages: List<ChatMessage>, me: Long?): List<Long> {
        if (me == null) return emptyList()
        return messages.filter { it.id > 0 && it.senderId != me && it.deletedAt == null && me !in it.deliveredTo && it.id !in acked }
            .map { it.id }
    }

    /** Blocking; call off the main thread. Failed acks are retried on the next sighting. */
    fun send(repository: ChatRepository, ids: List<Long>) {
        ids.forEach { id ->
            if (acked.add(id)) runCatching { repository.acknowledgeDelivered(id) }.onFailure { acked.remove(id) }
        }
    }

    internal fun resetForTest() = acked.clear()
}
