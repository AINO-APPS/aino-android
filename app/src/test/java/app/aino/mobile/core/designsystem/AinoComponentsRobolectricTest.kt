package app.aino.mobile.core.designsystem

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.theme.AinoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
                    AinoEmptyState(Icons.Outlined.ChatBubbleOutline, "No messages yet")
                    AinoNavigationBar(
                        items = listOf(
                            AinoNavigationItem("home", "Home", Icons.Outlined.Home),
                            AinoNavigationItem("chat", "Chat", Icons.Outlined.ChatBubbleOutline, badgeCount = 3),
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
}