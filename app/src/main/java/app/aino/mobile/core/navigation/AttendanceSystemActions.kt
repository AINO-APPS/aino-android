package app.aino.mobile.core.navigation

/**
 * Android system shortcuts the attendance verify sheet offers as one-tap fixes
 * (implemented by MainActivity, which owns the Activity context and prompts).
 */
data class AttendanceSystemActions(
    /** App details → Permissions (location was denied with "Don't ask again"). */
    val openAppSettings: () -> Unit,
    /** The system Location toggle is off. */
    val openLocationSettings: () -> Unit,
    /** No fingerprint / screen lock set up on the phone. */
    val openSecuritySettings: () -> Unit,
    /** Enroll this device's credential (fingerprint / PIN), then resume the clock action. */
    val enableFingerprintForAttendance: () -> Unit,
)
