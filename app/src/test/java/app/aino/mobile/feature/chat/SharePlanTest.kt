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

    @Test fun chromeScreenshotWithLinkSendsTheImageAndTheLinkSeparately() {
        val share = IncomingShare("Great read https://example.com/article", listOf(image))
        assertTrue(share.hasLinkWithMedia)
        assertEquals("https://example.com/article", share.link)
        val plan = sharePlan(share, share.text!!)
        assertEquals(null, plan.mediaCaption)
        assertEquals("Great read https://example.com/article", plan.text)
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
