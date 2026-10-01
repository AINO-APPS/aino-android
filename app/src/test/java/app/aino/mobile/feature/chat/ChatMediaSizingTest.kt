package app.aino.mobile.feature.chat

import androidx.compose.ui.unit.IntSize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMediaSizingTest {
    private fun assertSize(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.01f)
        assertEquals(expected.second, actual.second, 0.01f)
    }

    @Test fun `in bounds media keeps its natural size`() {
        assertSize(200f to 200f, signalMediaSize(IntSize(200, 200), density = 1f))
        assertSize(200f to 150f, signalMediaSize(IntSize(600, 450), density = 3f))
    }

    @Test fun `large landscape photo is capped by max width`() {
        assertSize(240f to 180f, signalMediaSize(IntSize(4000, 3000), density = 2.75f))
    }

    @Test fun `tall photo is capped by max height then widened to min width`() {
        assertSize(150f to 320f, signalMediaSize(IntSize(1800, 4000), density = 1f))
        assertSize(240f to 320f, signalMediaSize(IntSize(1800, 4000), density = 1f, withContent = true))
    }

    @Test fun `panorama is capped by max width then raised to min height`() {
        assertSize(240f to 100f, signalMediaSize(IntSize(4000, 800), density = 1f))
    }

    @Test fun `small media is scaled up to the min bounds`() {
        assertSize(150f to 150f, signalMediaSize(IntSize(300, 300), density = 3f))
        assertSize(240f to 240f, signalMediaSize(IntSize(300, 300), density = 3f, withContent = true))
        // min width ratio is the smaller one, so width reaches 150dp and height is capped at 320dp.
        assertSize(150f to 320f, signalMediaSize(IntSize(60, 140), density = 1f))
    }

    @Test fun `unknown size falls back to the 210dp default`() {
        assertSize(210f to 210f, signalMediaSize(null, density = 2f))
        assertSize(240f to 240f, signalMediaSize(null, density = 2f, withContent = true))
        assertSize(210f to 210f, signalMediaSize(IntSize(0, 100), density = 2f))
    }

    @Test fun `aspect only media sizes like a camera photo`() {
        assertEquals(IntSize(4000, 3000), aspectDims(4f / 3f))
        assertEquals(IntSize(1800, 4000), aspectDims(9f / 20f))
        assertNull(aspectDims(0f))
        assertNull(aspectDims(Float.NaN))
        assertSize(240f to 180f, signalMediaSize(aspectDims(4f / 3f), density = 3f))
        assertSize(150f to 320f, signalMediaSize(aspectDims(9f / 20f), density = 3f))
    }

    @Test fun `fill target dimensions matches signal ratio selection`() {
        // max height ratio wins when larger than the max width ratio.
        assertSize(160f to 320f, fillTargetDimensions(500f, 1000f, 150f, 240f, 100f, 320f))
        // min height ratio wins when smaller than the min width ratio.
        assertSize(240f to 100f, fillTargetDimensions(200f, 50f, 150f, 240f, 100f, 320f))
    }

    @Test fun `media dims come from upload metadata`() {
        val message = ChatMessage(
            id = 1, conversationId = 1, senderId = 1, createdAt = "2024-01-01T00:00:00Z", fileType = "image/jpeg",
            metadata = JsonObject(mapOf("width" to JsonPrimitive(1080), "height" to JsonPrimitive(1920))),
        )
        assertEquals(IntSize(1080, 1920), message.mediaDims())
        assertNull(message.copy(metadata = null).mediaDims())
    }
}
