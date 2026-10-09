package app.aino.mobile.core.designsystem

import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.aino.mobile.ComposeTestHostActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureScreenRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun blocksScreenshotsOnlyWhileShown() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        compose.setContent {
            if (first) SecureScreen()
            if (second) SecureScreen()
        }
        compose.waitForIdle()
        assertEquals(true, secure())

        // Another secure screen is still showing: keep the flag.
        first = false
        compose.waitForIdle()
        assertEquals(true, secure())

        second = false
        compose.waitForIdle()
        assertEquals(false, secure())
    }
}
