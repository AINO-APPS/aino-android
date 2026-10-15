package app.aino.mobile.feature.chat

import android.net.Uri
import app.aino.mobile.core.share.IncomingShare
import app.aino.mobile.core.share.SharedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharePlanTest {
    private val image = SharedItem(Uri.parse("content://x/1"), "image/png", "shot.png")

    @Test fun linkWithItsThumbnailImageSendsOnlyTheLink() {
        val share = IncomingShare("Great read https://example.com/article", listOf(image))
        assertTrue(share.hasLinkWithMedia)
        assertTrue(share.isLinkThumbnail)
        assertEquals("https://example.com/article", share.link)
        val plan = sharePlan(share, share.text!!)
        assertEquals(SharePlan(null, "Great read https://example.com/article", sendMedia = false), plan)
    }

    @Test fun linkWithVideoSendsTheVideoAndTheLinkSeparately() {
        val video = SharedItem(Uri.parse("content://x/2"), "video/mp4", "clip.mp4")
        val share = IncomingShare("https://example.com/clip", listOf(image, video))
        assertFalse(share.isLinkThumbnail)
        assertEquals(SharePlan(null, "https://example.com/clip"), sharePlan(share, share.text!!))
    }

    @Test fun linkWithDocumentKeepsTheDocument() {
        val pdf = SharedItem(Uri.parse("content://x/3"), "application/pdf", "doc.pdf")
        val share = IncomingShare("https://example.com/doc", listOf(pdf))
        assertEquals(SharePlan(null, "https://example.com/doc"), sharePlan(share, share.text!!))
    }

    @Test fun removingTheLinkKeepsTheImageWithItsCaption() {
        val share = IncomingShare("Pin https://maps.app.goo.gl/x", listOf(image))
        assertEquals(SharePlan("Pin", null), sharePlan(share, "Pin"))
    }

    @Test fun plainCaptionStaysOnTheMedia() {
        val share = IncomingShare("Holiday", listOf(image))
        assertFalse(share.hasLinkWithMedia)
        assertEquals(SharePlan("Holiday", null), sharePlan(share, "Holiday"))
    }

    @Test fun textOnlyShareIsAText() {
        val share = IncomingShare("https://example.com", emptyList())
        assertEquals(SharePlan(null, "https://example.com"), sharePlan(share, " https://example.com "))
    }

    @Test fun emptyMessageSendsJustTheMedia() {
        assertEquals(SharePlan(null, null), sharePlan(IncomingShare(null, listOf(image)), "  "))
    }
}
