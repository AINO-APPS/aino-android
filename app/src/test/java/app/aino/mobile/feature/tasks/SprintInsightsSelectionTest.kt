package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SprintInsightsSelectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private fun show(requestedSprintId: Long? = null) {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("contracts/sprint-list.no-current.json")).readText()
        val sprintList = Json.parseToJsonElement(fixture).jsonObject.getValue("data").toString()
        val viewModel = TaskViewModel(TaskRepository(ApiClient { request ->
            ApiResponse(200, emptyMap(), (if (request.path == "sprints") sprintList else "{}").toByteArray())
        }))
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                SprintInsightsScreen(viewModel, onBack = {}, requestedSprintId = requestedSprintId)
            }
        }
    }

    @Test
    fun noCurrentSprintLeavesInsightsUnselectedAfterTheListLoads() {
        show()

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("No sprint") and hasClickAction() and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No sprint").assertIsDisplayed()
        compose.onNodeWithText("Sprint tickets").assertDoesNotExist()
    }

    @Test
    fun anExplicitSprintLinkStillSelectsACompletedSprint() {
        show(requestedSprintId = 19)

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Completed sprint")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Completed sprint").assertIsDisplayed()
        compose.onNodeWithText("Sprint tickets").performScrollTo().assertIsDisplayed()
    }
}
