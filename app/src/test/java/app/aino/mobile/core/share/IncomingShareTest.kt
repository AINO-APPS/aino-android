package app.aino.mobile.core.share

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingShareTest {
    @Test
    fun `shared link becomes text`() {
        val share = parseShareIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  https://aino.app  "))!!
        assertEquals("https://aino.app", share.text)
        assertTrue(share.items.isEmpty())
        assertNull(share.conversationId)
    }

    @Test
    fun `single image keeps its type`() {
        val uri = Uri.parse("content://media/external/images/1")
        val share = parseShareIntent(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri))!!
        assertEquals(listOf(uri), share.items.map { it.uri })
        assertEquals("image/jpeg", share.items.single().mimeType)
        assertTrue(share.isMediaOnly)
    }

    @Test
    fun `multiple items are capped and file uris dropped`() {
        val uris = ArrayList((1..40).map { Uri.parse("content://files/$it") } + Uri.parse("file:///sdcard/secret"))
        val share = parseShareIntent(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))!!
        assertEquals(MAX_SHARED_ITEMS, share.items.size)
        assertTrue(share.items.all { it.uri.scheme == "content" })
        assertFalse(share.isMediaOnly)
    }

    @Test
    fun `direct share target resolves the conversation`() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi")
            .putExtra(Intent.EXTRA_SHORTCUT_ID, "wp-conversation-42")
        assertEquals(42L, parseShareIntent(intent)!!.conversationId)
        assertNull(conversationIdFromShortcut("other-42"))
        assertNull(conversationIdFromShortcut("wp-conversation-abc"))
    }

    @Test
    fun `non share or empty intents are ignored`() {
        assertNull(parseShareIntent(null))
        assertNull(parseShareIntent(Intent(Intent.ACTION_VIEW, Uri.parse("aino://chat/1"))))
        assertNull(parseShareIntent(Intent(Intent.ACTION_SEND).setType("text/plain")))
    }

    @Test
    fun `file names are sanitized`() {
        assertEquals("a_b_.txt", sanitizeFileName("dir/a:b?.txt", 0))
        assertEquals("shared-3", sanitizeFileName("   ", 3))
    }
}
