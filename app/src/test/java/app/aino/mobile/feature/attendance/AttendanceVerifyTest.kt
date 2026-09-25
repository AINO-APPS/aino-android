package app.aino.mobile.feature.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P3.6/P3.7: verify-sheet classification, BSSID normalisation, hash tabs. */
class AttendanceVerifyTest {

    private val office = AttendancePolicy(verificationEnabled = true, officeLatitude = 12.9716, officeLongitude = 77.5946, officeRadiusMeters = 150.0)

    @Test
    fun explainsWhyALocationFixDoesNotProveOfficePresence() {
        assertEquals(
            "Office location is not configured. Contact your admin or switch to remote.",
            officeProofFailure(AttendancePolicy(verificationEnabled = true), LocationProof(1.0, 1.0, 10f)),
        )
        assert(officeProofFailure(office, LocationProof(12.9716, 77.5946, 900f)).startsWith("Your location accuracy is ±900 m"))
        assert(officeProofFailure(office, LocationProof(12.9816, 77.5946, 20f)).startsWith("You are 11"))
    }

    @Test
    fun picksTheMostAccurateRecentFix() {
        val fresh = LocationProof(1.0, 1.0, 30f)
        val better = LocationProof(2.0, 2.0, 12f)
        val stale = LocationProof(3.0, 3.0, 5f)
        val ages = mapOf(fresh to 10_000L, better to 60_000L, stale to 600_000L)
        assertEquals(better, pickRecentFix(listOf(fresh, better, stale, null), { ages.getValue(it) }, maxAgeMs = 120_000))
        assertNull(pickRecentFix(listOf(stale), { ages.getValue(it) }, maxAgeMs = 120_000))
    }

    @Test
    fun classifiesServerErrorsByCodeFirst() {
        val location = classifySubmitError("whatever", "OUTSIDE_GEOFENCE", AttendanceAction.ClockIn)
        assertEquals(VerifyErrorKind.Location, location.kind)
        assertEquals("Location Mismatch", location.title)

        val face = classifySubmitError("whatever", "FACE_MISMATCH", AttendanceAction.ClockIn)
        assertEquals(VerifyErrorKind.Face, face.kind)
        assertEquals("Face Mismatch", face.title)

        val generic = classifySubmitError("server exploded", null, AttendanceAction.ClockOut)
        assertEquals(VerifyErrorKind.Generic, generic.kind)
        assertEquals("Clock-out Failed", generic.title)

        assertEquals("Login Failed", classifySubmitError("server exploded", null, AttendanceAction.ClockIn).title)
    }

    @Test
    fun fallsBackToKeywordSniffingWithoutACode() {
        assertEquals(
            VerifyErrorKind.Location,
            classifySubmitError("You are 320 m from the office", null, AttendanceAction.ClockIn).kind,
        )
        assertEquals(
            VerifyErrorKind.Face,
            classifySubmitError("Face verification failed", null, AttendanceAction.ClockIn).kind,
        )
    }

    @Test
    fun normalisesMacishBssidsAndRejectsPlaceholders() {
        assertEquals("AA:BB:CC:DD:EE:FF", WifiProvider.normaliseBssid("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WifiProvider.normaliseBssid("aabb.ccdd.eeff"))
        // Android's redacted placeholder when location permission is missing.
        assertNull(WifiProvider.normaliseBssid("02:00:00:00:00:00"))
        assertNull(WifiProvider.normaliseBssid("too-short"))
        assertNull(WifiProvider.normaliseBssid(null))
    }

    @Test
    fun deepLinkTabsMatchTheWebHashes() {
        assertEquals(AttendanceTab.Overview, AttendanceTab.fromHash(""))
        assertEquals(AttendanceTab.Overview, AttendanceTab.fromHash(null))
        assertEquals(AttendanceTab.Leaves, AttendanceTab.fromHash("leaves"))
        assertEquals(AttendanceTab.Leaves, AttendanceTab.fromHash("#leaves"))
        assertEquals(AttendanceTab.Manual, AttendanceTab.fromHash("#manual-entry"))
        assertEquals(AttendanceTab.Analytics, AttendanceTab.fromHash("analytics"))
        assertEquals(AttendanceTab.Overview, AttendanceTab.fromHash("nonsense"))
    }
}
