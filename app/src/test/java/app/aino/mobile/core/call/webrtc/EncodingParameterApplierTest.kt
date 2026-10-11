package app.aino.mobile.core.call.webrtc

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingParameterApplierTest {
    @Test
    fun rejectedVideoTierIsRetriedEvenWhenAudioSucceeded() = runTest {
        var attempts = 0
        val video: suspend () -> Boolean = { ++attempts > 1 }
        assertFalse(applyEncodingCaps(audio = { true }, video = video))
        assertTrue(applyEncodingCaps(audio = { true }, video = video))
        assertEquals(2, attempts)
    }

    @Test
    fun audioRejectionDoesNotSkipTheVideoWrite() = runTest {
        var videoWritten = false
        assertFalse(applyEncodingCaps(
            audio = { false },
            video = { videoWritten = true; true },
        ))
        assertTrue(videoWritten)
    }

    @Test
    fun successfulCapsNeedNoRetry() = runTest {
        assertTrue(applyEncodingCaps(audio = { true }, video = { true }))
    }
}
