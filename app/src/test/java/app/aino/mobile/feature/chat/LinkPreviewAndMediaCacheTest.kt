package app.aino.mobile.feature.chat

import androidx.compose.ui.unit.IntSize
import app.aino.mobile.core.media.videoPosterCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPreviewAndMediaCacheTest {
    @Test fun domainIsTheHostWithoutWww() {
        assertEquals("share.google", linkPreviewDomain(LinkPreview("https://share.google/abc123")))
        assertEquals("example.com", linkPreviewDomain(LinkPreview("https://www.Example.com/a?b=c", siteName = "Example")))
        assertEquals("maps.app.goo.gl", linkPreviewDomain(LinkPreview("https://maps.app.goo.gl/x")))
    }

    @Test fun domainFallsBackToTheSiteName() {
        assertEquals("Example", linkPreviewDomain(LinkPreview("not a url", siteName = " Example ")))
        assertEquals("", linkPreviewDomain(LinkPreview("::")))
    }

    @Test fun linkRangesSkipTrailingPunctuation() {
        val text = "See https://a.example/x, and (https://b.example/y)."
        val links = linkRangesIn(text).map(text::substring)
        assertEquals(listOf("https://a.example/x", "https://b.example/y"), links)
        assertEquals(emptyList<IntRange>(), linkRangesIn("no links here"))
    }

    @Test fun posterKeyIsDistinctFromTheVideoKey() {
        val url = "https://aino.app/uploads/chat/clip.mp4"
        assertEquals("poster:$url", videoPosterCacheKey(url))
    }

    @Test fun mediaMemoryLinesRoundTrip() {
        val line = formatMediaMemoryLine("/uploads/a.jpg", IntSize(1200, 800), "content://app.aino.mobile.fileprovider/x")
        assertEquals(Triple("/uploads/a.jpg", IntSize(1200, 800), "content://app.aino.mobile.fileprovider/x"), parseMediaMemoryLine(line))
        assertEquals(Triple("/uploads/b.mp4", IntSize(720, 1280), null), parseMediaMemoryLine(formatMediaMemoryLine("/uploads/b.mp4", IntSize(720, 1280), null)))
        assertNull(parseMediaMemoryLine(formatMediaMemoryLine("/uploads/c.jpg", null, null)))
        assertNull(parseMediaMemoryLine("garbage"))
    }
}
