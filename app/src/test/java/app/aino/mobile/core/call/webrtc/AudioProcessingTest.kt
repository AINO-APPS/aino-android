package app.aino.mobile.core.call.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioProcessingTest {
    @Test
    fun usesPlatformEffectsOnlyWhereAvailable() {
        assertEquals(AudioProcessing(hardwareAec = true, hardwareNs = true), selectAudioProcessing(33, true, true))
        assertEquals(AudioProcessing(hardwareAec = false, hardwareNs = true), selectAudioProcessing(33, false, true))
        assertEquals(AudioProcessing(hardwareAec = true, hardwareNs = false), selectAudioProcessing(29, true, false))
        assertEquals(AudioProcessing(hardwareAec = false, hardwareNs = false), selectAudioProcessing(33, false, false))
    }

    @Test
    fun forcesSoftwareProcessingBelowApi29() {
        assertEquals(AudioProcessing(hardwareAec = false, hardwareNs = false), selectAudioProcessing(28, true, true))
        assertEquals(AudioProcessing(hardwareAec = false, hardwareNs = false), selectAudioProcessing(26, true, true))
    }
}
