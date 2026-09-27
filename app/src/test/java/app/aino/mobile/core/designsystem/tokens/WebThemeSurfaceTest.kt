package app.aino.mobile.core.designsystem.tokens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Material3's default AlertDialog / DatePicker container is
 * `surfaceContainerHigh`. The web `--surface` token is a 4% wash, so the slot
 * must be composited to an opaque colour or every default dialog is see-through.
 */
class WebThemeSurfaceTest {
    @Test
    fun dialogContainerSlotsAreOpaqueInBothThemes() {
        listOf(WebColorsDark, WebColorsLight, WebColorsDark.withBrandAccent("#E91E63")).forEach { c ->
            assertEquals(1f, surfaceContainerHighFor(c).alpha, 0f)
            assertEquals(1f, surfaceContainerHighestFor(c).alpha, 0f)
        }
    }

    @Test
    fun opaqueSurfaceKeepsTheTokenTint() {
        val base = WebColorsDark.bgElevated
        val composited = opaqueSurface(WebColorsDark.surfaceHover, base)
        assertNotEquals(base, composited)
        assertEquals(Color.Red, opaqueSurface(Color.Red, base))
    }
}
