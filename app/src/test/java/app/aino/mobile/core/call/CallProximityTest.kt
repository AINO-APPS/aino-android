package app.aino.mobile.core.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallProximityTest {
    @Test
    fun onlyActiveAudioOnEarpieceUsesProximity() {
        for (route in CallAudioRoute.entries) {
            assertFalse(shouldUseCallProximity(false, false, route))
            assertFalse(shouldUseCallProximity(true, true, route))
            if (route == CallAudioRoute.Earpiece) assertTrue(shouldUseCallProximity(true, false, route))
            else assertFalse(shouldUseCallProximity(true, false, route))
        }
    }

    @Test
    fun cameraOffDefaultsDoNotClaimLiveVideo() {
        val voice = ActiveCallUi(visible = true, callType = "voice")
        assertTrue(voice.videoOff)
        assertTrue(voice.remoteVideoOff)
        assertFalse(voice.hasVideo)
        assertFalse(voice.copy(callType = "video").hasVideo)
    }
}
