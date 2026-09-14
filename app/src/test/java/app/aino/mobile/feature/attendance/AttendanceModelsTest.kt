package app.aino.mobile.feature.attendance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceModelsTest {
    private val policy = AttendancePolicy(
        verificationEnabled = true,
        officeLatitude = 10.0,
        officeLongitude = 20.0,
        officeRadiusMeters = 150.0,
    )

    @Test
    fun officeFingerprintRequiresAccurateInsideGeofenceLocation() {
        assertTrue(canUseFingerprintFallback(policy, WorkMode.Office, LocationProof(10.0, 20.0, 25f)))
        assertFalse(canUseFingerprintFallback(policy, WorkMode.Office, LocationProof(11.0, 20.0, 25f)))
        assertFalse(canUseFingerprintFallback(policy, WorkMode.Office, LocationProof(10.0, 20.0, 500f)))
        assertFalse(canUseFingerprintFallback(policy, WorkMode.Remote, LocationProof(10.0, 20.0, 25f)))
    }

    @Test
    fun remoteClockOutDoesNotRequireOfficeVerification() {
        assertFalse(requiresAttendanceVerification(policy, AttendanceAction.ClockOut, WorkMode.Remote, "remote"))
        assertTrue(requiresAttendanceVerification(policy, AttendanceAction.ClockIn, WorkMode.Remote, null))
        assertTrue(requiresAttendanceVerification(policy, AttendanceAction.ClockOut, WorkMode.Office, "office"))
    }

    @Test
    fun disabledPolicyNeedsNoLocationOrIdentityGate() {
        val disabled = AttendancePolicy(verificationEnabled = false)
        assertFalse(requiresLocation(disabled, WorkMode.Office))
        assertFalse(requiresAttendanceVerification(disabled, AttendanceAction.ClockIn, WorkMode.Office, null))
    }
}