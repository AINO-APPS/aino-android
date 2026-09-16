package app.aino.mobile.core.designsystem

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.theme.AinoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AinoComponentsRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun alertExposesItsMessageToAccessibility() {
        compose.setContent {
            AinoTheme {
                AinoAlert("Connection restored", AlertTone.Success)
            }
        }

        compose.onNodeWithText("Connection restored").assertExists()
    }
}