package app.aino.mobile.feature.chat.media

import org.junit.Assert.*
import org.junit.Test

class CameraControlsTest {
    @Test fun zoomPresetsOnlyExposeSupportedRatios() {
        assertEquals(listOf(1f, 2f, 5f), cameraZoomPresets(1f, 8f))
        assertEquals(listOf(1f), cameraZoomPresets(1f, 1.8f))
        assertEquals(listOf(2f, 5f), cameraZoomPresets(1.2f, 5f))
        assertTrue(cameraZoomPresets(3f, 4f).isEmpty())
    }

    @Test fun modeAndLongPressChooseCapture() {
        assertEquals(ShutterAction.Photo, cameraShutterAction(CameraMode.Photo, 399, true))
        assertEquals(ShutterAction.Video, cameraShutterAction(CameraMode.Photo, 400, true))
        assertEquals(ShutterAction.Video, cameraShutterAction(CameraMode.Video, 10, true))
        assertEquals(ShutterAction.Photo, cameraShutterAction(CameraMode.Video, 400, false))
    }

    @Test fun captureRejectsDuplicateStartsAndCanceledCallbacks() {
        val session = CaptureSession()
        val first = session.begin()!!
        assertNull(session.begin())
        session.cancel()
        val second = session.begin()!!
        assertFalse(session.finish(first))
        assertTrue(session.busy)
        assertTrue(session.finish(second))
        assertFalse(session.finish(second))
        assertFalse(session.busy)
        session.dispose()
        assertNull(session.begin())
        assertFalse(session.finish(second))
    }
}
