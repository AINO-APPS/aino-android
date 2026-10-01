package app.aino.mobile.feature.chat.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Robolectric has no ImageDecoder; API 27 runs the BitmapFactory + EXIF decode path.
@Config(sdk = [27])
class ChatMediaPreparerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun photo(width: Int, height: Int): File {
        val random = Random(7)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return File(context.cacheDir, "camera.jpg").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
    }

    @Test fun `a full-size camera photo is shrunk to Signal's standard constraints`() = runBlocking {
        val source = photo(4000, 3000)
        val prepared = ContentMediaPreparer(context, 25L * 1024 * 1024)
            .prepare(MediaPrepRequest(Uri.fromFile(source), "image/jpeg", "IMG_1.jpg", quality = "standard"))
        try {
            val constraints = ImageSendConstraints.Standard
            assertEquals("image/jpeg", prepared.mimeType)
            assertTrue("${prepared.length} > ${source.length()}", prepared.length < source.length())
            assertTrue(prepared.length <= constraints.maxBytes)
            val bounds = prepared.open().use { BitmapFactory.decodeStream(it) }
            assertTrue(maxOf(bounds.width, bounds.height) <= constraints.dimensionTargets.first())
            assertEquals(4f / 3f, bounds.width.toFloat() / bounds.height, 0.01f)
            assertEquals(bounds.width, prepared.width)
        } finally {
            prepared.release()
        }
    }

    @Test fun `documents stream untouched from their source`() = runBlocking {
        val file = File(context.cacheDir, "report.pdf").apply { writeBytes(ByteArray(1234) { it.toByte() }) }
        val prepared = ContentMediaPreparer(context, 25L * 1024 * 1024)
            .prepare(MediaPrepRequest(Uri.fromFile(file), "application/pdf", "report.pdf", quality = null))
        assertEquals(1234L, prepared.length)
        assertTrue(file.readBytes().contentEquals(prepared.open().use { it.readBytes() }))
    }
}
