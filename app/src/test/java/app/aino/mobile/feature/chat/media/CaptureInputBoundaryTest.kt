package app.aino.mobile.feature.chat.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import app.aino.mobile.ComposeTestHostActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureInputBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun cameraEntryClearsFocusWithoutErasingTheDraftOrRefocusingOnClose() {
        var active by mutableStateOf(false)
        var focused = false
        var text by mutableStateOf("Unsent draft")
        compose.setContent {
            Column {
                CaptureInputBoundary(active)
                if (active) Text("Camera")
                else BasicTextField(text, { text = it }, Modifier.testTag("draft").onFocusChanged { focused = it.isFocused })
            }
        }
        compose.onNodeWithTag("draft").performClick()
        compose.runOnIdle { assertTrue(focused); active = true }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(focused); assertEquals("Unsent draft", text); active = false }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(focused); assertEquals("Unsent draft", text) }
    }
}
