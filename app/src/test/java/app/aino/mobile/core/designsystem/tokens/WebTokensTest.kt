package app.aino.mobile.core.designsystem.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1.1 acceptance: assert each token's ARGB matches the CSS custom property in
 * `client/src/global.css`. Guards against transcription drift between the web
 * client and the Android token set.
 */
class WebTokensTest {

    private fun assertArgb(expected: Int, color: Color, name: String) {
        assertEquals("$name", expected, color.toArgb())
    }

    @Test
    fun darkTokensMatchGlobalCss() {
        val c = WebColorsDark
        assertArgb(0xFF2383E2.toInt(), c.primary, "primary")
        assertArgb(0xFF529CCA.toInt(), c.primaryLight, "primaryLight")
        assertArgb(0xFF1A6DBE.toInt(), c.primaryDark, "primaryDark")
        assertArgb(0xFF2383E2.toInt(), c.accent, "accent")
        assertArgb(0xFF529CCA.toInt(), c.accentHover, "accentHover")
        assertArgb(0xFFFFFFFF.toInt(), c.onAccent, "onAccent")
        assertArgb(0xFF4DAA57.toInt(), c.success, "success")
        assertArgb(0xFFCB912F.toInt(), c.warning, "warning")
        assertArgb(0xFFE03E3E.toInt(), c.danger, "danger")
        assertArgb(0xFF131314.toInt(), c.bg, "bg")
        assertArgb(0xFF1B1B1C.toInt(), c.bgSecondary, "bgSecondary")
        assertArgb(0xFF202021.toInt(), c.bgElevated, "bgElevated")
    }

    @Test
    fun lightTokensMatchGlobalCssOverrides() {
        val c = WebColorsLight
        assertArgb(0xFF2383E2.toInt(), c.primary, "primary")
        assertArgb(0xFF1B6FC2.toInt(), c.accentHover, "accentHover")
        assertArgb(0xFFFBFBFA.toInt(), c.bg, "bg")
        assertArgb(0xFFF5F4F1.toInt(), c.bgSecondary, "bgSecondary")
        assertArgb(0xFFFFFFFF.toInt(), c.bgElevated, "bgElevated")
        assertArgb(0xFF37352F.toInt(), c.text, "text")
        assertArgb(0xFFFFFFFF.toInt(), c.cardBg, "cardBg")
        assertArgb(0xFFF7F6F3.toInt(), c.inputBg, "inputBg")
    }

    @Test
    fun remBaseIs16sp() {
        assertEquals(16f, 1.rem.value, 0.001f)
        assertEquals(9.92f, 0.62.rem.value, 0.001f)
        assertEquals(22.4f, 1.4.rem.value, 0.001f)
    }
}
