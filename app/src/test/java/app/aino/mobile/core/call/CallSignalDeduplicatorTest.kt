package app.aino.mobile.core.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalDeduplicatorTest {
    @Test
    fun rejectsARepeatedReliableSignal() {
        val deduplicator = CallSignalDeduplicator()
        assertTrue(deduplicator.remember("signal-1"))
        assertFalse(deduplicator.remember("signal-1"))
    }

    @Test
    fun acceptsFramesWithoutAnIdentifier() {
        val deduplicator = CallSignalDeduplicator()
        assertTrue(deduplicator.remember(null))
        assertTrue(deduplicator.remember(""))
    }

    @Test
    fun evictsTheOldestIdentifierAtCapacity() {
        val deduplicator = CallSignalDeduplicator(capacity = 2)
        assertTrue(deduplicator.remember("one"))
        assertTrue(deduplicator.remember("two"))
        assertTrue(deduplicator.remember("three"))
        assertTrue(deduplicator.remember("one"))
        assertFalse(deduplicator.remember("three"))
    }

    @Test
    fun forgottenFailedSignalCanBeRetried() {
        val deduplicator = CallSignalDeduplicator()
        assertTrue(deduplicator.remember("retry"))
        deduplicator.forget("retry")
        assertTrue(deduplicator.remember("retry"))
    }
}