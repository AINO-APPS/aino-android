package app.aino.mobile.feature.chat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMathTest {
    private val eps = 1e-4f

    private fun assertVec(expected: Vec2, actual: Vec2) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    private fun assertRect(expected: NRect, actual: NRect) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    @Test fun shutterTapIsPhotoHoldIsVideo() {
        assertEquals(ShutterAction.Photo, shutterAction(0))
        assertEquals(ShutterAction.Photo, shutterAction(399))
        assertEquals(ShutterAction.Video, shutterAction(400))
        assertEquals(ShutterAction.Video, shutterAction(5_000))
        assertEquals(ShutterAction.Photo, shutterAction(700, holdThresholdMs = 800))
    }

    @Test fun orientRotatesClockwise() {
        assertVec(Vec2(1f, 0f), orientPoint(Vec2(0f, 0f), 1, false))
        assertVec(Vec2(1f, 1f), orientPoint(Vec2(0f, 0f), 2, false))
        assertVec(Vec2(0f, 1f), orientPoint(Vec2(0f, 0f), 3, false))
        assertVec(Vec2(0.8f, 0.3f), orientPoint(Vec2(0.3f, 0.2f), 1, false))
        assertVec(Vec2(0.7f, 0.2f), orientPoint(Vec2(0.3f, 0.2f), 0, true))
        assertVec(Vec2(0.3f, 0.2f), orientPoint(Vec2(0.3f, 0.2f), 4, false))
    }

    @Test fun unorientInvertsOrient() {
        val p = Vec2(0.13f, 0.77f)
        for (t in -2..5) for (f in listOf(false, true)) {
            assertVec(p, unorientPoint(orientPoint(p, t, f), t, f))
        }
    }

    @Test fun flipDisplayedMirrorsWhatUserSees() {
        val p = Vec2(0.2f, 0.9f)
        for (t in 0..3) for (f in listOf(false, true)) {
            val before = orientPoint(p, t, f)
            val (nt, nf, _) = flipDisplayedH(t, f, NRect.Full)
            val after = orientPoint(p, nt, nf)
            assertVec(Vec2(1f - before.x, before.y), after)
        }
    }

    @Test fun rotateCwMovesCropWithImage() {
        val crop = NRect(0.1f, 0.2f, 0.5f, 0.6f)
        val (turns, flip, rotated) = rotateCw(0, false, crop)
        assertEquals(1, turns)
        assertFalse(flip)
        assertRect(NRect(0.4f, 0.1f, 0.8f, 0.5f), rotated)
        // Crop corners must follow the same mapping as image points.
        assertVec(Vec2(rotated.right, rotated.top), orientPoint(Vec2(crop.left, crop.top), 1, false))
        var r = crop
        var t = 0
        repeat(4) { val (nt, _, nr) = rotateCw(t, false, r); t = nt; r = nr }
        assertEquals(0, t)
        assertRect(crop, r)
    }

    @Test fun clampAndMoveStayInsideImage() {
        assertRect(NRect(0f, 0f, 0.5f, 0.5f), clampRect(NRect(-0.2f, -0.1f, 0.3f, 0.4f)))
        assertRect(NRect(0.9f, 0.9f, 1f, 1f), clampRect(NRect(0.95f, 0.95f, 0.96f, 0.96f)))
        assertRect(NRect(0.6f, 0f, 1f, 0.4f), moveRect(NRect(0.5f, 0.1f, 0.9f, 0.5f), 0.3f, -0.5f))
    }

    @Test fun dragCornerFreeKeepsAnchorAndBounds() {
        val r = NRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertRect(NRect(0.1f, 0.3f, 0.8f, 0.8f), dragCorner(r, Corner.TopLeft, -0.1f, 0.1f, null, 1f))
        assertRect(NRect(0.2f, 0.2f, 1f, 1f), dragCorner(r, Corner.BottomRight, 0.9f, 0.9f, null, 1f))
        // Cannot invert or shrink below the minimum size.
        assertRect(NRect(0.2f, 0.2f, 0.3f, 0.3f), dragCorner(r, Corner.BottomRight, -1f, -1f, null, 1f, minSize = 0.1f))
    }

    @Test fun dragCornerAspectLocked() {
        // 2:1 pixel image, square lock → normalized width is half the height.
        val r = NRect(0.25f, 0f, 0.75f, 1f)
        val out = dragCorner(r, Corner.BottomRight, 0f, -0.4f, 1f, 2f)
        assertEquals(0.25f, out.left, eps)
        assertEquals(0f, out.top, eps)
        assertEquals(out.width * 2f, out.height, eps)
        assertTrue(out.right <= 1f && out.bottom <= 1f)
        // Growing beyond the edge is capped while keeping aspect.
        val big = dragCorner(NRect(0.5f, 0.5f, 0.6f, 0.6f), Corner.BottomRight, 1f, 1f, 1f, 1f)
        assertRect(NRect(0.5f, 0.5f, 1f, 1f), big)
    }

    @Test fun fitAspectCentersInside() {
        assertRect(NRect(0.25f, 0f, 0.75f, 1f), fitAspect(NRect.Full, 1f, 2f))
        assertRect(NRect(0f, 0.125f, 1f, 0.875f), fitAspect(NRect.Full, 4f / 3f, 1f))
    }

    @Test fun viewportAndScreenMappingRoundTrip() {
        val vp = fitViewport(200f, 100f, 400f, 400f)
        assertEquals(2f, vp.scale, eps)
        assertEquals(0f, vp.offsetX, eps)
        assertEquals(100f, vp.offsetY, eps)
        val crop = NRect(0.1f, 0.2f, 0.9f, 0.7f)
        val p = Vec2(0.4f, 0.6f)
        val s = sourceToScreen(p, vp, crop, 300, 200, 1, true)
        assertVec(p, screenToSource(s, vp, crop, 300, 200, 1, true))
        assertEquals(200 to 300, orientedSize(300, 200, 1))
        assertEquals(300 to 200, orientedSize(300, 200, 2))
    }

    @Test fun historyUndoRedo() {
        var h = EditorHistory(0)
        assertFalse(h.canUndo)
        h = h.push(1).push(2).push(2)
        assertEquals(2, h.current)
        assertEquals(listOf(0, 1), h.past)
        h = h.undo()
        assertEquals(1, h.current)
        assertTrue(h.canRedo)
        h = h.redo()
        assertEquals(2, h.current)
        h = h.undo().undo().undo()
        assertEquals(0, h.current)
        h = h.push(5)
        assertFalse(h.canRedo)
        assertEquals(listOf(0), h.past)
    }
}
