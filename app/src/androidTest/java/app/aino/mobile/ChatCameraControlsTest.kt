package app.aino.mobile

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import android.graphics.Bitmap
import app.aino.mobile.feature.chat.media.CaptureInputBoundary
import app.aino.mobile.feature.chat.media.ChatCameraScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatCameraControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Before fun grantCapturePermissions() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.packageName
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            val result = instrumentation.uiAutomation.executeShellCommand("pm grant $app $permission")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun imeVisible(): Boolean {
        var result = false
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return result
    }

    @Test fun focusedDraftKeyboardIsHiddenAndDraftSurvivesCameraCancel() {
        var active by mutableStateOf(false)
        var focused = false
        var text by mutableStateOf("Unsent message")
        compose.setContent {
            CaptureInputBoundary(active)
            if (active) {
                ChatCameraScreen(emptyList(), onClose = { active = false }, onCaptured = {}, onOpenGallery = {})
            } else Column(Modifier.fillMaxSize()) {
                BasicTextField(text, { text = it }, Modifier.testTag("composer").onFocusChanged { focused = it.isFocused })
                Text("Chat")
            }
        }
        compose.onNodeWithTag("composer").performClick()
        compose.waitUntil(10_000) { imeVisible() }
        compose.runOnIdle { assertTrue(focused); active = true }
        compose.onNodeWithContentDescription("Close camera").assertExists()
        compose.waitUntil(10_000) { !imeVisible() }
        compose.onNodeWithContentDescription("Close camera").performClick()
        compose.onNodeWithTag("composer").assertTextEquals("Unsent message")
        compose.runOnIdle { assertFalse(focused); assertEquals("Unsent message", text) }
        compose.waitUntil(5_000) { !imeVisible() }
    }

    @Test fun videoModeStartsAndStopsARecording() {
        var delivered by mutableStateOf(false)
        compose.setContent {
            if (delivered) Text("Captured video")
            else ChatCameraScreen(emptyList(), onClose = {}, onCaptured = { items ->
                assertEquals("video/mp4", items.single().mimeType)
                delivered = true
            }, onOpenGallery = {})
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Take photo").fetchSemanticsNodes()
                .any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
        compose.onNodeWithText("Video").performClick()
        compose.onNodeWithContentDescription("Record video").performClick()
        compose.onNodeWithContentDescription("Stop recording").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Starting…").fetchSemanticsNodes().isEmpty() }
        Thread.sleep(2_000)
        screenshot("camera-video-controls")
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(15_000) { delivered }
    }

    @Test fun holdingPhotoShutterRecordsUntilRelease() {
        var delivered by mutableStateOf(false)
        compose.setContent {
            if (delivered) Text("Captured quick video")
            else ChatCameraScreen(emptyList(), onClose = {}, onCaptured = { items ->
                assertEquals("video/mp4", items.single().mimeType)
                delivered = true
            }, onOpenGallery = {})
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Take photo").fetchSemanticsNodes()
                .any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = compose.onNodeWithContentDescription("Take photo").fetchSemanticsNode().boundsInRoot
        val downTime = android.os.SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, bounds.center.x, bounds.center.y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        touch(android.view.MotionEvent.ACTION_DOWN)
        compose.mainClock.advanceTimeBy(800)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Stop recording").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Starting…").fetchSemanticsNodes().isEmpty() }
        Thread.sleep(2_500)
        touch(android.view.MotionEvent.ACTION_UP)
        compose.waitUntil(15_000) { delivered }
    }

    @Test fun photoShutterProducesMediaThroughTheExistingPipeline() {
        var delivered by mutableStateOf(false)
        compose.setContent {
            if (delivered) Text("Captured photo")
            else ChatCameraScreen(emptyList(), onClose = {}, onCaptured = { items ->
                assertEquals("image/jpeg", items.single().mimeType)
                delivered = true
            }, onOpenGallery = {})
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Take photo").fetchSemanticsNodes()
                .any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
        screenshot("camera-photo-controls")
        compose.onNodeWithContentDescription("Take photo").performClick()
        compose.waitUntil(15_000) { delivered }
        compose.onNodeWithText("Captured photo").assertExists()
    }
}
