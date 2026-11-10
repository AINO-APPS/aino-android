package app.aino.mobile.core.update

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClientUpgradeGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun anyRequestReplacesTheEntireAppWithANonDismissibleUpdateAction() {
        val upgrades = ClientUpgradeState()
        val updater = FakeUpdater()
        val fake = UpgradeAwareApiClient(ApiClient {
            ApiResponse(426, emptyMap(), """{"code":"CLIENT_UPGRADE_REQUIRED","client":"android","min_version":"0.27.18","current_version":"0.27.17"}""".toByteArray())
        }, upgrades)
        compose.setContent {
            MaterialTheme {
                ClientUpgradeGate(updater, upgrades) { Text("Normal authenticated or cached content") }
            }

        }
        compose.onNodeWithText("Normal authenticated or cached content").assertIsDisplayed()
        compose.runOnIdle { runCatching { fake.execute(ApiRequest("POST", "auth/refresh")) } }
        compose.onNodeWithText("Update required").assertIsDisplayed()
        compose.onNodeWithText("Normal authenticated or cached content").assertDoesNotExist()
        compose.onNodeWithText("Update now").performClick()
        compose.runOnIdle {
            assertEquals(1, updater.installs)
            updater.dismiss()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("Update required").assertIsDisplayed()
        compose.onNodeWithText("Normal authenticated or cached content").assertDoesNotExist()
    }

    @Test
    fun aRefusalSurvivesProcessRecreationButIsNotSharedWithANewInstalledVersion() {
        val context = compose.activity.applicationContext
        val prefs = context.getSharedPreferences("client-upgrade", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val original = ClientUpgradeState()
        original.attach(context)
        original.observe(426, """{"code":"CLIENT_UPGRADE_REQUIRED","client":"android","min_version":"0.27.18","current_version":"0.27.17"}""")
        val restored = ClientUpgradeState()
        restored.attach(context)
        assertEquals("0.27.18", restored.required.value?.minVersion)
        prefs.edit().putString("installed-version", "older-installation").commit()
        val updated = ClientUpgradeState()
        updated.attach(context)
        assertNull(updated.required.value)
        prefs.edit().clear().commit()
    }

    private class FakeUpdater : AppUpdater {
        override val ui = MutableStateFlow(UpdateUiState())
        var installs = 0
        override fun check(activity: Activity, userInitiated: Boolean) {}
        override fun install(activity: Activity) { installs++ }
        override fun dismiss() { ui.value = UpdateUiState() }
    }
}
