package app.aino.mobile.core.update

import android.app.Activity
import android.content.Intent
import app.aino.mobile.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RequiredPlayUpdateTest {
    @Test
    fun requiredUpdateHasAStoreActionWithoutAnOptionalUpdateCheck() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val updater = PlayAppUpdater()
        updater.dismiss()
        updater.installRequired(activity)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}", intent.data.toString())
    }
}
