package app.aino.mobile.feature.chat

import app.aino.mobile.core.network.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Signal's per-recipient send queue (`RecipientId.toQueueKey`): every outgoing
 * item of a conversation is sent strictly in tap order, one at a time. Our server
 * stamps `created_at` on arrival (for files: after the whole upload), so a single
 * FIFO is what keeps a text typed during an upload below that image for everyone.
 * Media *preparation* runs ahead in parallel; only the network send is serialized.
 */
class ChatSendQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val queues = HashMap<Long, Channel<suspend () -> Unit>>()

    fun submit(conversationId: Long, task: suspend () -> Unit) {
        val channel = synchronized(queues) {
            queues.getOrPut(conversationId) {
                Channel<suspend () -> Unit>(Channel.UNLIMITED).also { channel ->
                    scope.launch(dispatcher) {
                        for (next in channel) {
                            try {
                                next()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                // A failed item must never stall the items queued behind it.
                            }
                        }
                    }
                }
            }
        }
        channel.trySend(task)
    }
}

/** Network blips, timeouts, throttling and 5xx are worth retrying; other 4xx answers are final. */
fun isTransientSendFailure(error: Throwable): Boolean = when (error) {
    is ApiError.Network -> true
    is ApiError.Http -> error.statusCode.isTransientStatus()
    is ChatFailure -> error.statusCode.isTransientStatus()
    else -> false
}

private fun Int.isTransientStatus() = this >= 500 || this == 408 || this == 429

/** Retries [block] on transient failures with exponential backoff (1 s, 2 s, 4 s … capped at [maxDelayMs]). */
suspend fun <T> withSendRetries(
    attempts: Int = SEND_ATTEMPTS,
    baseDelayMs: Long = 1_000,
    maxDelayMs: Long = 30_000,
    block: suspend () -> T,
): T {
    var wait = baseDelayMs
    repeat(attempts - 1) {
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isTransientSendFailure(error)) throw error
        }
        delay(wait)
        wait = minOf(wait * 2, maxDelayMs)
    }
    return block()
}

const val SEND_ATTEMPTS = 4

/**
 * Text holds its place at the head of the queue until it is delivered (Signal keeps
 * the job until the network returns): handing it off and moving on would let a
 * later message overtake it.
 */
const val TEXT_SEND_ATTEMPTS = Int.MAX_VALUE
