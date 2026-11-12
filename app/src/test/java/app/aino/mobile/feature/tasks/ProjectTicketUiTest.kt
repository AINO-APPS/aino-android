package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectTicketUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun upcomingShowsOverdueBeforeStartDateGroupsAndOpensTheTicket() {
        var opened: Long? = null
        val state = ProjectTicketUiState(
            loaded = true, today = LocalDate.of(2026, 11, 9),
            tickets = listOf(
                Task(1, "Late work", startDate = "2026-10-01"),
                Task(2, "Begin today", startDate = "2026-11-09"),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                ProjectTicketPage(state, upcoming = true, onRefresh = {}, onQuery = {}, onFilters = {}, onOpen = { opened = it.id })
            }
        }
        compose.onNodeWithText("Overdue").assertIsDisplayed()
        compose.onNodeWithText("Calendar is available on the web.").assertIsDisplayed()
        compose.onNodeWithText("Late work").performClick()
        assertEquals(1L, opened)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Today · 2026-11-09"))
        compose.onNodeWithText("Today · 2026-11-09").assertIsDisplayed()
    }

    @Test fun projectListExposesSortFieldsAndRetryWithoutCalendarActions() {
        var query: ProjectTicketQuery? = null
        var refreshed = false
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                ProjectTicketPage(
                    ProjectTicketUiState(error = "Offline"),
                    onRefresh = { refreshed = true }, onQuery = { query = it }, onFilters = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText("Try again").performClick()
        assertEquals(true, refreshed)
        compose.onNodeWithText("Sort: Key").performClick()
        compose.onNodeWithText("Start date").performClick()
        assertEquals("start_date", query?.sort)
        compose.onNodeWithText("Calendar").assertDoesNotExist()
    }
}
