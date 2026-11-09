package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TicketStartDateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var harness: TicketSessionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = TicketSessionHarness()
        val row = harness.original.dropLast(1) + ""","start_date":"2026-11-10","due_date":"2026-11-12"}"""
        harness.boardTicket = row
        harness.detailResponse = row
        harness.loadBoardPlanAndDetail()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test fun ticketDetailAndTitleEditBothKeepStartAndDueDatePropertiesVisible() {
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                TaskDetailScreen(harness.detailViewModel, {}, {})
            }
        }
        compose.onNodeWithText("Start date").assertIsDisplayed()
        compose.onNodeWithText("Due date").assertIsDisplayed()
        compose.runOnIdle { harness.detailViewModel.startEdit() }
        compose.onNodeWithText("Start date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Due date").performScrollTo().assertIsDisplayed()
    }

    @Test fun clearingStartDateFromItsPickerDoesNotUseSchedulePlacement() {
        harness.saved = harness.detailResponse.replace("\"start_date\":\"2026-11-10\"", "\"start_date\":null")
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                TaskDetailScreen(harness.detailViewModel, {}, {})
            }
        }
        compose.onNodeWithText("Start date").performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.waitUntil(5_000) { harness.writes == 1 && harness.detailViewModel.ui.value.detail?.startDate == null }
        compose.onNodeWithText("No start date").assertIsDisplayed()
        assertEquals("""{"start_date":null,"planned_date":null}""", harness.fieldPayload.toString())
        assertEquals("2026-11-12", harness.detailViewModel.ui.value.detail?.dueDate)
        assertEquals(10L, harness.detailViewModel.ui.value.detail?.sprintId)
    }
}
