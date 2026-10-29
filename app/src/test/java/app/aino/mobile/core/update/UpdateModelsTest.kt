package app.aino.mobile.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // The approved direct-key migration changes the Android update identity.
    @Test
    fun detectsAKeyChangeThatAndroidWouldReject() {
        assertTrue(
            signedByDifferentKey(
                installed = setOf("c490582bf04deaa2cf5575cbaa4d6b1c40e99ab5df7b950f56457cff4a9b6806"),
                candidate = setOf("1111111111111111111111111111111111111111111111111111111111111111"),
            ),
        )
    }

    @Test
    fun acceptsARebuildSignedByTheSameKey() {
        val key = "c490582bf04deaa2cf5575cbaa4d6b1c40e99ab5df7b950f56457cff4a9b6806"
        assertFalse(signedByDifferentKey(installed = setOf(key), candidate = setOf(key)))
    }

    @Test
    fun treatsUnknownSignaturesAsPermitted() {
        // The package manager is the authority: if either side could not be read, let
        // the installer decide rather than blocking a valid update.
        assertFalse(signedByDifferentKey(installed = emptySet(), candidate = setOf("abc")))
        assertFalse(signedByDifferentKey(installed = setOf("abc"), candidate = emptySet()))
    }

    @Test
    fun treatsSignerOrderAsIrrelevant() {
        // APK signature blocks do not guarantee signer ordering, so the same set of
        // signers in a different order must not look like a key change.
        assertFalse(
            signedByDifferentKey(
                installed = setOf("aaa", "bbb"),
                candidate = setOf("bbb", "aaa"),
            ),
        )
    }
}