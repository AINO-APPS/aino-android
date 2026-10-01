package app.aino.mobile.feature.chat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSendConstraintsTest {
    @Test fun `image constraints match Signal SD and HD levels`() {
        assertEquals(ImageSendConstraints.Standard, ImageSendConstraints.forQuality("standard"))
        assertEquals(ImageSendConstraints.Standard, ImageSendConstraints.forQuality(null))
        assertEquals(1600, ImageSendConstraints.Standard.dimensionTargets.first())
        assertEquals(70, ImageSendConstraints.Standard.jpegQuality)
        assertEquals(1L * 1024 * 1024, ImageSendConstraints.Standard.maxBytes)
        assertEquals(4096, ImageSendConstraints.forQuality("hd").dimensionTargets.first())
        assertEquals(3L * 1024 * 1024, ImageSendConstraints.High.maxBytes)
    }

    @Test fun `long edge is capped with aspect kept and never upscaled`() {
        assertEquals(1600 to 1200, fitLongEdge(4000, 3000, 1600))
        assertEquals(900 to 1600, fitLongEdge(2250, 4000, 1600))
        assertEquals(800 to 600, fitLongEdge(800, 600, 1600))
    }

    @Test fun `video output keeps aspect, caps the short side and uses even sizes`() {
        assertEquals(1280 to 720, VideoSendConstraints.Standard.outputSize(1920, 1080))
        assertEquals(720 to 1280, VideoSendConstraints.Standard.outputSize(1080, 1920))
        assertEquals(1920 to 1080, VideoSendConstraints.High.outputSize(3840, 2160))
        // Never upscaled; odd sizes rounded down to even.
        assertEquals(640 to 480, VideoSendConstraints.Standard.outputSize(641, 481))
    }

    @Test fun `transcode only when the source exceeds the target`() {
        val sd = VideoSendConstraints.Standard
        val max = 25L * 1024 * 1024
        assertTrue(sd.needsTranscode(1920, 1080, 1_500_000, 5_000_000, max))
        assertTrue(sd.needsTranscode(1280, 720, 8_000_000, 5_000_000, max))
        assertTrue(sd.needsTranscode(640, 360, 1_000_000, max + 1, max))
        assertFalse(sd.needsTranscode(1280, 720, 2_000_000, 5_000_000, max))
    }

    @Test fun `bitrate shrinks so long videos fit the upload limit`() {
        val sd = VideoSendConstraints.Standard
        val max = 25L * 1024 * 1024
        assertEquals(2_000_000, sd.bitrateFor(30_000, max))
        val fiveMinutes = sd.bitrateFor(300_000, max)
        assertTrue(fiveMinutes < 2_000_000)
        assertTrue((fiveMinutes + sd.audioBitrate) * 300L / 8 <= max)
        assertEquals(2_000_000, sd.bitrateFor(0, max))
    }
}
