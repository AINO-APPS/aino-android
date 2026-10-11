package app.aino.mobile.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.theme.AinoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignalUnreadBadgeRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun conversationBadgesCenterCompactSingleLineLabels() {
        assertBadgesCentered(height = 22.dp, modifier = Modifier)
    }

    @Test
    fun toolbarBadgesCenterCompactSingleLineLabels() {
        assertBadgesCentered(height = 18.dp, modifier = Modifier.height(18.dp))
    }

    private fun assertBadgesCentered(height: Dp, modifier: Modifier) {
        val counts = listOf(1, 42, 999, 1000)
        compose.setContent {
            AinoTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalTextStyle provides TextStyle(lineHeight = 32.sp)) {
                    Column {
                        counts.forEach { count ->
                            SignalUnreadBadge(count, muted = count == 42, modifier = modifier.testTag("badge-$count"))
                        }
                    }
                }
            }
        }

        counts.forEach { count ->
            val label = if (count > 999) "999+" else count.toString()
            val badge = compose.onNodeWithTag("badge-$count", useUnmergedTree = true).fetchSemanticsNode()
            val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val layout = layouts.single()

            assertEquals(1, layout.lineCount)
            assertTrue("$label exceeds its text width", layout.getLineRight(0) - layout.getLineLeft(0) <= layout.size.width + 1f)
            assertTrue("$label exceeds its text height", layout.multiParagraph.height <= layout.size.height + 1f)
            assertEquals(12.sp, layout.layoutInput.style.fontSize)
            assertEquals(12.sp, layout.layoutInput.style.lineHeight)
            assertEquals(false, layout.layoutInput.style.platformStyle?.paragraphStyle?.includeFontPadding)
            assertEquals(with(compose.density) { height.toPx() }, badge.boundsInRoot.height, 1f)
            assertEquals(badge.boundsInRoot.center.x, text.boundsInRoot.center.x, 1f)
            assertEquals(badge.boundsInRoot.center.y, text.boundsInRoot.center.y, 1f)
            assertTrue(badge.boundsInRoot.contains(text.boundsInRoot.topLeft))
            assertTrue(badge.boundsInRoot.contains(text.boundsInRoot.bottomRight))
        }
    }
}
