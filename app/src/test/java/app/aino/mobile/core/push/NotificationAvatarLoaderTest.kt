package app.aino.mobile.core.push

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.aino.mobile.core.avatar.ChatAvatarBitmaps
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [28])
class NotificationAvatarLoaderTest {
    @Test fun realLandscapeImageDecodesWithAspectRatioAndCachePreservesCrop() = runBlocking {
        val image = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.MAGENTA)
            drawRect(100f, 0f, 150f, 100f, Paint().apply { color = Color.RED })
            drawRect(150f, 0f, 200f, 100f, Paint().apply { color = Color.BLUE })
        }
        val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val url = server.url("/avatar.png").toString()
            val context = RuntimeEnvironment.getApplication()
            val decoded = NotificationAvatarLoader.load(context, url, "1:2")!!
            assertEquals(3f, decoded.width.toFloat() / decoded.height, .05f)
            val crop = ChatAvatarBitmaps.circle(decoded)
            assertEquals(Color.TRANSPARENT, crop.getPixel(0, 0))
            assertEquals(Color.RED, crop.getPixel(24, 128))
            assertEquals(Color.BLUE, crop.getPixel(232, 128))
            val cached = NotificationAvatarLoader.cached(context, url, "1:2")!!
            assertTrue(crop.sameAs(ChatAvatarBitmaps.circle(cached)))
            assertNull(NotificationAvatarLoader.cached(context, url, "1:3"))
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
}
