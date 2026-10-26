package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Sprint tab's Board ⇄ Plan switch must work in both directions.
 *
 * The switch used to live only in the board's header card, so once the user
 * switched to Plan there was no way back to the Board. Both surfaces now render
 * the same switch, and this pins that down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SprintViewSwitchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private fun show(selected: SprintView, onSelect: (SprintView) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                SprintViewSwitch(selected = selected, onSelect = onSelect)
            }
        }
    }

    @Test
    fun bothSurfacesAreAlwaysOffered() {
        show(SprintView.Plan) {}

        compose.onNodeWithText("Board").assertIsDisplayed()
        compose.onNodeWithText("Plan").assertIsDisplayed()
    }

    @Test
    fun planCanSwitchBackToTheBoard() {
        var picked: SprintView? = null
        show(SprintView.Plan) { picked = it }

        compose.onNodeWithText("Board").performClick()
        assertEquals(SprintView.Board, picked)
    }

    @Test
    fun boardCanSwitchToThePlan() {
        var picked: SprintView? = null
        show(SprintView.Board) { picked = it }

        compose.onNodeWithText("Plan").performClick()
        assertEquals(SprintView.Plan, picked)
    }
}
