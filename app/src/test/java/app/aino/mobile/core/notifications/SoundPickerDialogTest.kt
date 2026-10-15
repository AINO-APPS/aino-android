package app.aino.mobile.core.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.designsystem.tokens.WebColorsLight
import app.aino.mobile.core.settings.ChatPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Tapping a tone saves it right away; the card is opaque. */
@RunWith(RobolectricTestRunner::class)
class SoundPickerDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Before fun setup() {
        ChatPreferences.resetForTest()
        ChatPreferences.clear(compose.activity)
        NotificationSounds.clear(compose.activity)
        SoundChannels.resetForTest()
    }

    @After fun cleanup() {
        ChatPreferences.clear(compose.activity)
        NotificationSounds.clear(compose.activity)
    }

    @Test fun tappingATonePreviewsAndSavesItWithoutClosing() {
        val context = compose.activity
        val synced = mutableListOf<String>()
        var open by mutableStateOf(true)
        compose.setContent {
            if (open) SoundPickerDialog(SoundCategory.Ringtone, onDismiss = { open = false }) { id ->
                synced += id
                ChatPreferences.update(context) { SoundCategory.Ringtone.withSyncedId(it, id) }
            }
        }
        compose.onNodeWithText("Vapor").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("vapor"), synced)
        assertEquals(SoundChoice.Preset("vapor"), NotificationSounds.choice(context, SoundCategory.Ringtone))
        assertEquals(NotificationSounds.presetUri(context, SoundCategory.Ringtone.preset("vapor")), NotificationSounds.uri(context, SoundCategory.Ringtone))
        assertTrue(open)

        compose.onNodeWithText("Urgent").performScrollTo().performClick()
        compose.onNodeWithText("System default").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("vapor", "urgent"), synced)
        assertEquals(SoundChoice.SystemDefault, NotificationSounds.choice(context, SoundCategory.Ringtone))

        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertTrue(!open)
    }

    @Test fun reopenedPickerShowsTheSavedTone() {
        val context = compose.activity
        ChatPreferences.update(context) { it.copy(messageTone = "ripple") }
        compose.setContent { SoundPickerDialog(SoundCategory.Message, onDismiss = {}) {} }
        compose.onNodeWithText("Ripple").assertExists()
        assertEquals(SoundChoice.Preset("ripple"), NotificationSounds.choice(context, SoundCategory.Message))
    }

    @Test fun dialogBackgroundTokenIsOpaqueInBothThemes() {
        assertEquals(1f, WebColorsDark.bgElevated.alpha)
        assertEquals(1f, WebColorsLight.bgElevated.alpha)
        assertTrue(WebColorsDark.surface.alpha < 1f)
    }
}
