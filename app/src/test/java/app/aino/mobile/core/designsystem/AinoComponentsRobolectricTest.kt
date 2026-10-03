package app.aino.mobile.core.designsystem

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsSelected
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.theme.AinoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

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

    @Test
    fun tonalCardKeepsContentAccessibleInTheFallbackTheme() {
        compose.setContent {
            AinoTheme(dynamicColor = false) {
                AinoGlassCard {
                    androidx.compose.material3.Text("Quarterly progress")
                }
            }
        }

        compose.onNodeWithText("Quarterly progress").assertExists()
    }

    @Test
    fun segmentedTabsExposeTheSelectedTabSemantics() {
        compose.setContent {
            AinoTheme(dynamicColor = false) {
                AinoSegmentedTabs(
                    items = listOf("Today", "History"),
                    selected = "Today",
                    label = { it },
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("Today").assertIsSelected()
        compose.onNodeWithText("History").assertExists()
    }

    @Test
    fun navigationAndEmptyStateExposeAccessibleLabels() {
        compose.setContent {
            AinoTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Column {
                    AinoEmptyState(HeroIcons.ChatBubbleOvalLeft, "No messages yet")
                    AinoNavigationBar(
                        items = listOf(
                            AinoNavigationItem("home", "Home", HeroIcons.Home),
                            AinoNavigationItem("chat", "Chat", HeroIcons.ChatBubbleOvalLeft, badgeCount = 3),
                        ),
                        selectedKey = "home",
                        onSelect = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("No messages yet").assertExists()
        // NavigationBarItem merges child semantics into the selectable parent;
        // verify the labelled icons in the unmerged tree and the visible labels
        // in the merged tree, matching what accessibility services receive.
        compose.onNodeWithContentDescription("Home", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Chat", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Home").assertExists()
        compose.onNodeWithText("Chat").assertExists()
        compose.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    // Real text measurement: legacy Robolectric graphics reports zero-width text.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test
    fun countBadgeShowsWholeLabelsAndHidesAtZero() {
        compose.setContent {
            AinoTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Row {
                    app.aino.mobile.core.designsystem.component.CountBadge(7)
                    // Inside an icon-sized box, as on the tab bar: must not wrap or clip.
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(26.dp)) {
                        app.aino.mobile.core.designsystem.component.CountBadge(42)
                    }
                    app.aino.mobile.core.designsystem.component.CountBadge(250)
                    app.aino.mobile.core.designsystem.component.CountBadge(0)
                }
            }
        }

        compose.onNodeWithText("7", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("42", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("99+", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("0 unread").assertDoesNotExist()
        // Two-digit counts get a pill wider than its height instead of a clipped circle.
        val size = compose.onNodeWithContentDescription("42 unread").fetchSemanticsNode().size
        assert(size.width >= size.height) { "badge ${size.width}x${size.height} clips its label" }
        // And the label stays on one line even inside a 26dp icon box.
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("42", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assert(layouts.single().lineCount == 1) { "\"42\" wrapped onto ${layouts.single().lineCount} lines" }
    }
}