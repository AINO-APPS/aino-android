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
class SubtaskDetailUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var harness: TicketSessionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = TicketSessionHarness()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test fun parentShowsSubtaskProgressAndOpensChildEvenWhenEpicsAreDisabled() {
        harness.detailResponse = harness.original.dropLast(1) + """,
            "subtask_summary":{"total":2,"done":1},
            "subtasks":[{"id":202,"title":"Finished child","completed_at":"2026-11-09","is_subtask":true},
                        {"id":203,"title":"Open child","is_subtask":true}]}"""
        harness.loadBoardPlanAndDetail()
        harness.session.writePlanScope(harness.session.planScope.value.copy(
            agile = AgileConfig(features = AgileFeatures(epics = false)),
        ))
        var opened: Long? = null
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                TaskDetailScreen(harness.detailViewModel, {}, { opened = it })
            }
        }
        compose.onNodeWithText("Subtasks · 1/2 done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open child").performScrollTo().performClick()
        assertEquals(203L, opened)
    }

    @Test fun subtaskOpensParentAndNeverOffersOrWritesStoryPoints() {
        harness.detailResponse = harness.original.dropLast(1) + """,
            "is_subtask":true,"parent_task_id":7,"parent_key":"SRC-7","story_points":13}"""
        harness.loadBoardPlanAndDetail()
        var opened: Long? = null
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                TaskDetailScreen(harness.detailViewModel, {}, { opened = it })
            }
        }
        compose.onNodeWithText("Estimate").assertDoesNotExist()
        compose.onNodeWithText("Parent SRC-7").performScrollTo().performClick()
        assertEquals(7L, opened)
        harness.detailViewModel.setStoryPoints(harness.detailViewModel.ui.value.detail!!, "13")
        Thread.sleep(100)
        assertEquals(0, harness.writes)
    }
}
