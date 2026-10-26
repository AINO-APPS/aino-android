package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * The sprint board's empty states.
 *
 * Four full-page empty states stacked vertically (one per column) left ~800dp of
 * illustrations and repeated "Nothing in …" copy. The board now shows one
 * board-level state when the sprint is empty, and a thin dashed row per empty
 * column when only some columns are empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SprintBoardEmptyStateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val states = listOf(
        WorkflowState(id = 1, key = "pending", name = "New", category = "open", color = "#6b7280"),
        WorkflowState(id = 2, key = "in_progress", name = "In Progress", category = "in_progress", color = "#f59e0b"),
        WorkflowState(id = 3, key = "in_review", name = "In Review", category = "in_review", color = "#3b82f6"),
        WorkflowState(id = 4, key = "done", name = "Done", category = "done", color = "#10b981"),
    )

    private fun show(empty: BoardEmpty, onClearFilters: () -> Unit = {}, onImport: () -> Unit = {}, onCreate: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                BoardEmptyFooter(
                    empty = empty,
                    onClearFilters = onClearFilters,
                    onImport = onImport,
                    onCreate = onCreate,
                )
            }
        }
    }

    @Test
    fun aGenuinelyEmptySprintOffersImportAndCreate() {
        show(BoardEmpty.Empty)

        compose.onNodeWithText("This sprint is empty").assertIsDisplayed()
        compose.onNodeWithText("Import from backlog").assertIsDisplayed()
        compose.onNodeWithText("Create ticket").assertIsDisplayed()
    }

    @Test
    fun filtersThatHideEveryTicketOfferClearFiltersInstead() {
        show(BoardEmpty.Filtered)

        compose.onNodeWithText("No tickets match your filters").assertIsDisplayed()
        compose.onNodeWithText("Clear filters").assertIsDisplayed()
        // Importing is the wrong advice: the tickets exist, the view is narrowed.
        compose.onNodeWithText("Import from backlog").assertDoesNotExist()
    }

    @Test
    fun theFooterIsAbsentWhenTheBoardHasTickets() {
        show(BoardEmpty.None)

        compose.onNodeWithText("This sprint is empty").assertDoesNotExist()
        compose.onNodeWithText("No tickets match your filters").assertDoesNotExist()
    }

    @Test
    fun theFilteredActionClearsFilters() {
        var cleared = 0
        show(BoardEmpty.Filtered, onClearFilters = { cleared++ })

        compose.onNodeWithText("Clear filters").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun anEmptyColumnShowsAThinDashedRowNotAFullEmptyState() {
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                EmptyColumnRow(state = states[1], hovered = false)
            }
        }

        // One muted line, no icon, no call to action. The slot is decorative to
        // screen readers, so it is addressed by tag rather than by its text.
        compose.onNodeWithTag(EMPTY_COLUMN_TAG).assertIsDisplayed()
        compose.onNodeWithText("Nothing in In Progress").assertDoesNotExist()
        compose.onNodeWithText("Import from backlog").assertDoesNotExist()
    }
}
