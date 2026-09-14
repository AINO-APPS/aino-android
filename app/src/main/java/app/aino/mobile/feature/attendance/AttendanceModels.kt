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
    @SerialName("work_hours_per_day") val workHoursPerDay: Double = 8.0,
    @SerialName("work_days") val workDays: String = "1,2,3,4,5",
    @SerialName("min_hours_present") val minHoursPresent: Double? = null,
)

@Serializable
data class AttendanceDay(
    val date: String,
    val floorMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val workMode: String = "office",
    val entries: List<app.aino.mobile.feature.home.TimeEntryDto> = emptyList(),
)

enum class AttendanceDayKind { Present, Absent, Weekend, InProgress, Future }

@Serializable
data class ManualEntryRequest(
    @SerialName("request_id") val requestId: Long,
    @SerialName("approval_status") val approvalStatus: String = "pending",
    val metadata: ManualEntryMetadata? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("approver_name") val approverName: String? = null,
)

@Serializable
data class ManualEntryMetadata(
    val date: String? = null,
    @SerialName("clock_in") val clockIn: String? = null,
    @SerialName("clock_out") val clockOut: String? = null,
    @SerialName("work_mode") val workMode: String? = null,
)

@Serializable
data class OvertimeRequest(
    val id: Long,
    val status: String = "pending",
    val reason: String? = null,
    val metadata: OvertimeMetadata? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("approver_name") val approverName: String? = null,
)

@Serializable
data class OvertimeMetadata(val date: String? = null, val hours: Double? = null)

@Serializable
data class ManualEntryPayload(
    val date: String,
    @SerialName("clock_in") val clockIn: String,
    @SerialName("clock_out") val clockOut: String? = null,
    val timezoneOffset: Int,
    @SerialName("work_mode") val workMode: String,
)

@Serializable
data class OvertimePayload(val date: String, val hours: Double, val reason: String)

@Serializable
data class AttendanceMutationResponse(val message: String)

data class MonthRange(val firstVisible: java.time.LocalDate, val lastVisible: java.time.LocalDate)

fun monthGrid(month: java.time.YearMonth): List<java.time.LocalDate> {
    val first = month.atDay(1)
    val sundayOffset = first.dayOfWeek.value % 7
    val start = first.minusDays(sundayOffset.toLong())
    return List(42) { start.plusDays(it.toLong()) }
}

fun monthRange(month: java.time.YearMonth): MonthRange {
    val grid = monthGrid(month)
    return MonthRange(grid.first(), grid.last())
}

fun workDaySet(value: String): Set<Int> = value.split(',')
    .mapNotNull { it.trim().toIntOrNull() }
    .filter { it in 0..6 }
    .toSet().ifEmpty { setOf(1, 2, 3, 4, 5) }

fun attendanceKind(
    date: java.time.LocalDate,
    today: java.time.LocalDate,
    day: AttendanceDay?,
    workDays: Set<Int>,
    minimumMinutes: Int,
): AttendanceDayKind {
    if ((day?.floorMinutes ?: 0) >= minimumMinutes) return AttendanceDayKind.Present
    if (!workDays.contains(date.dayOfWeek.value % 7)) return AttendanceDayKind.Weekend
    if (date == today) return AttendanceDayKind.InProgress
    if (date > today) return AttendanceDayKind.Future
    return AttendanceDayKind.Absent
}

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

fun validateManualEntry(date: String, clockIn: String, clockOut: String?, today: java.time.LocalDate): String? {
    val parsedDate = runCatching { java.time.LocalDate.parse(date) }.getOrNull()
        ?: return "Choose a valid date"
    if (parsedDate > today) return "Cannot add a manual entry for a future date"
    if (!validTime(clockIn)) return "Login time must use HH:MM"
    if (!clockOut.isNullOrBlank() && !validTime(clockOut)) return "Logout time must use HH:MM"
    if (!clockOut.isNullOrBlank() && clockOut <= clockIn) return "Logout time must be after login time"
    return null
}

fun validateOvertime(date: String, hours: String, reason: String): String? {
    if (runCatching { java.time.LocalDate.parse(date) }.isFailure) return "Choose a valid date"
    val parsedHours = hours.toDoubleOrNull() ?: return "Enter valid overtime hours"
    if (parsedHours <= 0 || parsedHours > 24) return "Hours must be between 0 and 24"
    if (reason.isBlank()) return "Reason is required"
    if (reason.length > 500) return "Reason must be 500 characters or less"
    return null
}

private fun validTime(value: String): Boolean {
    if (!Regex("^\\d{2}:\\d{2}$").matches(value)) return false
    val (hour, minute) = value.split(':').map(String::toInt)
    return hour in 0..23 && minute in 0..59
}

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