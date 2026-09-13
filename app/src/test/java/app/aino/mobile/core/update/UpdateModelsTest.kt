package app.aino.mobile.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateModelsTest {
    @Test
    fun comparesNumericVersions() {
        assertEquals(1, compareVersions("0.1.1", "0.1.0"))
        assertEquals(0, compareVersions("v2.4.8", "2.4.8"))
        assertEquals(-1, compareVersions("2.4.7", "2.4.8"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonSemanticVersions() {
        compareVersions("latest", "0.1.0")
    }

    @Test
    fun acceptsOnlyTheConfiguredNativeReleaseChannel() {
        requireTrustedApkUrl(
            "https://cdn.aino.org.in",
            "https://cdn.aino.org.in/android/releases/android-v0.1.1/AINO-0.1.1.apk",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsApksFromAnotherHost() {
        requireTrustedApkUrl("https://cdn.aino.org.in", "https://attacker.test/AINO.apk")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTheRetiredMobileChannel() {
        val retiredPrefix = "mobile" + "/releases/AINO.apk"
        requireTrustedApkUrl("https://cdn.aino.org.in", "https://cdn.aino.org.in/$retiredPrefix")
    }
}