package app.aino.mobile.core.common

import android.content.Context
import app.aino.mobile.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Play builds: Firebase Crashlytics. Only crashes and the app/OS version are
 * reported. No user id, name, e-mail or tenant is attached, and nothing is
 * collected from debug builds or when the Firebase config is absent.
 */
object CrashReporting {
    fun init(context: Context) {
        // Local builds without google-services.json have no FirebaseApp.
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled =
            BuildConfig.CRASH_REPORTING && !BuildConfig.DEBUG
    }
}
