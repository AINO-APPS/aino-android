package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMediaSaverTest {
    private val ext: (String) -> String? = { mime ->
        mapOf("image/jpeg" to "jpg", "video/mp4" to "mp4", "application/pdf" to "pdf")[mime]
    }

    @Test
    fun mediaGoesToTheMatchingPublicCollection() {
        assertEquals(SaveTarget.Pictures, saveTargetFor("image/png"))
        assertEquals(SaveTarget.Movies, saveTargetFor("video/mp4"))
        assertEquals(SaveTarget.Music, saveTargetFor("audio/ogg"))
        assertEquals(SaveTarget.Downloads, saveTargetFor("application/pdf"))
        assertEquals(SaveTarget.Downloads, saveTargetFor(null))
    }

    @Test
    fun namesAreSanitisedAndKeepTheirExtension() {
        assertEquals("report_v2.pdf", safeSaveName("report/v2.pdf", "https://x/u/abc", "application/pdf", ext))
        assertEquals("photo.jpg", safeSaveName("photo", "https://x/u/abc", "image/jpeg", ext))
        assertEquals("clip.mp4", safeSaveName(null, "https://x/uploads/clip.mp4?sig=1", "video/mp4", ext))
        assertEquals("abc", safeSaveName("", "https://x/u/abc", "application/x-unknown", ext))
    }
}
