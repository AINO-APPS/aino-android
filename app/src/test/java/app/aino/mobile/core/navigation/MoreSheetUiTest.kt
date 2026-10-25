package app.aino.mobile.core.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.WebTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MoreSheetUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val open = mutableStateOf(true)
    private val route = mutableStateOf<String?>(null)
    private val navigations = mutableListOf<AinoDestination>()
    private var dismissals = 0
    private val allDestinations = listOf(
        AinoDestination.Notes,
        AinoDestination.Calendar,
        AinoDestination.Organization,
        AinoDestination.Manager,
        AinoDestination.Tenants,
        AinoDestination.ApiProbe,
    )

    @Test
    fun rendersOnlySuppliedDestinationsInOrderWithLargeTouchTargets() {
        show(allDestinations)

        val positions = allDestinations.map { destination ->
            val node = compose.onNode(hasText(destination.label) and hasClickAction())
                .fetchSemanticsNode()
            assertTrue(node.size.height / compose.density.density >= 48f)
            node.positionInRoot.y
        }
        assertEquals(positions.sorted(), positions)
        compose.onNodeWithText("Profile").assertDoesNotExist()
        compose.onNodeWithText("Attendance").assertDoesNotExist()
        compose.onNodeWithText("Plan").assertDoesNotExist()
    }

    @Test
    fun restrictedAccountDoesNotGainHiddenSections() {
        show(availableMoreDestinations("employee", hasReports = false, orgId = 7))

        compose.onNodeWithText("Organization").assertIsDisplayed()
        listOf("Notes", "Calendar", "My Team", "Tenants", "API Probe").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun activeStateUsesTheOwningManagerRouteAndUpdatesWithTheRoute() {
        route.value = MANAGER_ROUTE_PATTERN
        show(listOf(AinoDestination.Calendar, AinoDestination.Manager))

        compose.onNodeWithText("My Team").assertIsSelected()
        compose.onNodeWithText("Calendar").assertIsNotSelected()
        compose.runOnIdle { route.value = AinoDestination.Calendar.route }
        compose.onNodeWithText("Calendar").assertIsSelected()
        compose.onNodeWithText("My Team").assertIsNotSelected()
    }

    @Test
    fun selectionHidesTheSheetAndNavigatesExactlyOnce() {
        show(listOf(AinoDestination.Calendar))
        compose.onNodeWithText("Calendar").assertIsDisplayed()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithText("Calendar").performClick().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Calendar").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithText("More").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(AinoDestination.Calendar), navigations)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun closeActionDismissesWithoutNavigating() {
        show(listOf(AinoDestination.Notes))
        compose.onNodeWithContentDescription("Close More menu").performClick()
        assertDismissed()
    }

    @Test
    @Suppress("DEPRECATION")
    fun nativeDialogBackDismissesWithoutNavigating() {
        show(listOf(AinoDestination.Notes))
        compose.onNodeWithText("Notes").assertIsDisplayed()
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        assertDismissed()
    }

    @Test
    fun scrimDismissesWithoutNavigating() {
        show(listOf(AinoDestination.Notes))
        compose.onNodeWithContentDescription("Close sheet").performClick()
        assertDismissed()
    }

    @Test
    fun downwardSwipeDismissesWithoutNavigating() {
        show(allDestinations)
        compose.onNodeWithText("Notes").assertIsDisplayed()
        compose.onNode(isDialog()).performTouchInput {
            swipeDown(startY = height * 0.3f, endY = height * 0.95f, durationMillis = 200)
        }
        assertDismissed()
    }

    @Test
    fun smallHeightAndLargeFontsKeepCloseVisibleAndAllowScrollingToLastItem() {
        show(allDestinations, modifier = Modifier.heightIn(max = 300.dp), fontScale = 1.8f)

        compose.onNodeWithText("More").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close More menu").assertIsDisplayed()
        compose.onNodeWithText("API Probe").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close More menu").assertIsDisplayed().performClick()
        assertDismissed()
    }

    @Test
    fun emptyMenuExplainsWhyThereAreNoSectionsAndCanBeClosed() {
        show(emptyList(), darkTheme = false)

        compose.onNodeWithText("No additional sections are available for your account.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close More menu").performClick()
        assertDismissed()
    }

    private fun show(
        destinations: List<AinoDestination>,
        modifier: Modifier = Modifier,
        fontScale: Float = 1f,
        darkTheme: Boolean = true,
    ) {
        compose.setContent {
            WebTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    if (open.value) {
                        MoreSheet(
                            destinations = destinations,
                            currentRoute = route.value,
                            onNavigate = { navigations += it; open.value = false },
                            onDismiss = { dismissals++; open.value = false },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    private fun assertDismissed() {
        compose.onNodeWithText("More").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertTrue(navigations.isEmpty())
        }
    }
}
