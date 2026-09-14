package app.aino.mobile.feature.attendance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class AttendancePolicy(
    @SerialName("attendance_verification_enabled") val verificationEnabled: Boolean = false,
    @SerialName("office_latitude") val officeLatitude: Double? = null,
    @SerialName("office_longitude") val officeLongitude: Double? = null,
    @SerialName("office_radius_m") val officeRadiusMeters: Double = 150.0,
    @SerialName("office_address") val officeAddress: String? = null,
    @SerialName("office_wifi_verification_enabled") val wifiVerificationEnabled: Boolean = false,
)

@Serializable
data class AttendanceActionRequest(
    @SerialName("work_mode") val workMode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    @SerialName("fingerprint_verified") val fingerprintVerified: Boolean? = null,
)

@Serializable
data class AttendanceActionResponse(
    val message: String,
    @SerialName("work_mode") val workMode: String? = null,
    @SerialName("verified_via") val verifiedVia: String? = null,
)

data class LocationProof(val latitude: Double, val longitude: Double, val accuracyMeters: Float)

enum class AttendanceAction { ClockIn, ClockOut }
enum class WorkMode { Office, Remote, Hybrid }

fun requiresLocation(policy: AttendancePolicy, mode: WorkMode): Boolean =
    policy.verificationEnabled && mode != WorkMode.Remote

fun requiresAttendanceVerification(
    policy: AttendancePolicy,
    action: AttendanceAction,
    selectedMode: WorkMode,
    currentSessionMode: String?,
): Boolean {
    if (!policy.verificationEnabled) return false
    // The server deliberately exempts remote session clock-out from the office
    // location and identity gate so a remote worker cannot become trapped.
    if (action == AttendanceAction.ClockOut && currentSessionMode.equals("remote", ignoreCase = true)) return false
    return true
}

fun canUseFingerprintFallback(policy: AttendancePolicy, mode: WorkMode, proof: LocationProof?): Boolean =
    policy.verificationEnabled && mode != WorkMode.Remote && proof != null &&
        policy.officeLatitude != null && policy.officeLongitude != null &&
        proof.accuracyMeters <= maxOf(policy.officeRadiusMeters, 200.0) &&
        distanceMeters(proof.latitude, proof.longitude, policy.officeLatitude, policy.officeLongitude) <= policy.officeRadiusMeters

fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * earthRadius * asin(sqrt(a))
}