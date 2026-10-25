package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
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
 * The Plan card anatomy on Android, matching web's `TaskCardParts`:
 * key · points · blocked on the quiet line, the title, then status · due ·
 * labels · avatar. Compact (the default) drops only the comment count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskCardUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val task = Task(
        id = 7,
        title = "Refund webhook retries",
        issueKey = "PAY-142",
        priority = "high",
        storyPoints = 5.0,
        workflowStateKey = "in_progress",
        assignee = TaskPerson(username = "ada", fullName = "Ada Lovelace"),
        labels = listOf(
            TaskLabel(1, "payments", "#22c55e"),
            TaskLabel(2, "billing", "#3b82f6"),
            TaskLabel(3, "urgent", "#ef4444"),
        ),
        dueDate = "2099-11-01",
        commentCount = 4,
    )

    private fun show(compact: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                TaskCardM3(
                    task = task,
                    agile = AgileConfig(),
                    onOpen = {},
                    showStatus = true,
                    compact = compact,
                )
            }
        }
    }

    @Test
    fun compactCardShowsKeyTitleStatusDueLabelsAndAvatar() {
        show(compact = true)
        compose.onNodeWithText("PAY-142").assertIsDisplayed()
        compose.onNodeWithText("Refund webhook retries").assertIsDisplayed()
        compose.onNodeWithText("In Progress").assertIsDisplayed()
        compose.onNodeWithText("payments").assertIsDisplayed()
        compose.onNodeWithText("billing").assertIsDisplayed()
        compose.onNodeWithText("+1").assertIsDisplayed()
        compose.onNodeWithContentDescription("High priority").assertIsDisplayed()
        // The avatar is the assignee affordance; the name is not repeated.
        assertEquals(0, compose.onAllNodesWithText("Ada Lovelace").fetchSemanticsNodes().size)
    }

    @Test
    fun compactCardHidesTheCommentCount() {
        show(compact = true)
        assertEquals(0, compose.onAllNodesWithText("4").fetchSemanticsNodes().size)
    }

    @Test
    fun comfortableCardAddsTheCommentCount() {
        show(compact = false)
        compose.onNodeWithText("4").assertIsDisplayed()
    }
}
