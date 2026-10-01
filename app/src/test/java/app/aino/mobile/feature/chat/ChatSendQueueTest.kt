package app.aino.mobile.feature.chat

import app.aino.mobile.core.network.ApiError
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatSendQueueTest {
    @Test fun `items of one conversation run strictly in submit order and a failure never stalls the queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = ChatSendQueue(scope, Dispatchers.Default)
        val ran = Collections.synchronizedList(mutableListOf<String>())
        val firstGate = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        queue.submit(1) { firstGate.await(); ran += "media" }
        queue.submit(1) { throw IllegalStateException("boom") }
        queue.submit(1) { ran += "text"; done.complete(Unit) }
        // Another conversation is independent of the blocked one.
        val other = CompletableDeferred<Unit>()
        queue.submit(2) { ran += "other"; other.complete(Unit) }
        withTimeout(2_000) { other.await() }
        assertEquals(listOf("other"), ran.toList())
        firstGate.complete(Unit)
        withTimeout(2_000) { done.await() }
        assertEquals(listOf("other", "media", "text"), ran.toList())
        scope.cancel()
    }

    @Test fun `only network, timeout, throttling and server errors are retried`() {
        assertTrue(isTransientSendFailure(ApiError.Network("POST", "u", java.io.IOException("offline"))))
        assertTrue(isTransientSendFailure(ApiError.Http(503, "", "POST", "u")))
        assertTrue(isTransientSendFailure(ApiError.Http(429, "", "POST", "u")))
        assertTrue(isTransientSendFailure(ChatFailure("slow", 408, RuntimeException())))
        assertFalse(isTransientSendFailure(ChatFailure("blocked", 403, RuntimeException())))
        assertFalse(isTransientSendFailure(IllegalArgumentException("Files must be 25 MB or smaller")))
    }

    @Test fun `retries transient failures then succeeds, but gives up at once on a final answer`() = runBlocking {
        var calls = 0
        val value = withSendRetries(baseDelayMs = 1) {
            calls++
            if (calls < 3) throw ApiError.Http(502, "", "POST", "u")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, calls)

        calls = 0
        try {
            withSendRetries(baseDelayMs = 1) { calls++; throw ChatFailure("nope", 400, RuntimeException()) }
            fail("expected the 400 to surface")
        } catch (error: ChatFailure) {
            assertEquals(1, calls)
        }

        calls = 0
        try {
            withSendRetries(attempts = 3, baseDelayMs = 1) { calls++; throw ApiError.Http(500, "", "POST", "u") }
            fail("expected the last failure to surface")
        } catch (error: ApiError.Http) {
            assertEquals(3, calls)
        }

        // Unbounded text retries keep going with the backoff capped.
        calls = 0
        val started = System.nanoTime()
        val delivered = withSendRetries(attempts = TEXT_SEND_ATTEMPTS, baseDelayMs = 1, maxDelayMs = 2) {
            if (++calls < 20) throw ApiError.Network("POST", "u", java.io.IOException("offline"))
            "sent"
        }
        assertEquals("sent", delivered)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }
}
