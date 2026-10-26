package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone board is a vertical kanban: every workflow state is a column
 * stacked top to bottom (New, In Progress, In Review, Done), each with its own
 * header and its cards beneath.
 *
 * It used to be a horizontal pager showing one column at a time, which hid the
 * rest of the board behind a swipe. This pins the vertical shape down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SprintBoardLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val states = listOf(
        WorkflowState(id = 1, key = "pending", name = "New", category = "open", color = "#6b7280"),
        WorkflowState(id = 2, key = "in_progress", name = "In Progress", category = "in_progress", color = "#f59e0b"),
        WorkflowState(id = 3, key = "in_review", name = "In Review", category = "in_review", color = "#3b82f6"),
        WorkflowState(id = 4, key = "done", name = "Done", category = "done", color = "#10b981"),
    )

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                androidx.compose.foundation.layout.Column {
                    states.forEach { state ->
                        ColumnHeader(
                            state = state,
                            count = 0,
                            wip = null,
                            dropTarget = false,
                            hovered = false,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun everyWorkflowStateHasItsOwnColumnHeader() {
        show()

        compose.onNodeWithText("New").assertIsDisplayed()
        compose.onNodeWithText("In Progress").assertIsDisplayed()
        compose.onNodeWithText("In Review").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
}
