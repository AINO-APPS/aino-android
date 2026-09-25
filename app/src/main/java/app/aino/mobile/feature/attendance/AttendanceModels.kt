package app.aino.mobile.feature.attendance

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.LenientDoubleSerializer
import app.aino.mobile.core.common.TimeEntryDto
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Organization attendance policy from `GET /api/org/current`.
 *
 * Every numeric field uses a tolerant decoder. `min_hours_present` is
 * `NUMERIC(4,2)` and node-pg serializes it as a **string** (`"4.00"`); the
 * latitude/longitude columns are `DOUBLE PRECISION` and arrive as numbers,
 * while `office_radius_m` and `work_hours_per_day` are `INTEGER`. A strict
 * `Double` decoder on `min_hours_present` threw, nulled the entire policy and
 * silently disabled clock-in — the decoders keep any of these shapes working.
 */
@Serializable
data class AttendancePolicy(
    @SerialName("attendance_verification_enabled") val verificationEnabled: Boolean = false,
    @SerialName("office_latitude")
    @Serializable(with = LenientDoubleNullableSerializer::class)
    val officeLatitude: Double? = null,
    @SerialName("office_longitude")
    @Serializable(with = LenientDoubleNullableSerializer::class)
    val officeLongitude: Double? = null,
    @SerialName("office_radius_m")
    @Serializable(with = LenientDoubleSerializer::class)
    val officeRadiusMeters: Double = 150.0,
    @SerialName("office_address") val officeAddress: String? = null,
    @SerialName("office_wifi_verification_enabled") val wifiVerificationEnabled: Boolean = false,
    @SerialName("work_hours_per_day")
    @Serializable(with = LenientDoubleSerializer::class)
    val workHoursPerDay: Double = 8.0,
    @SerialName("work_days") val workDays: String = "1,2,3,4,5",
    @SerialName("min_hours_present")
    @Serializable(with = LenientDoubleNullableSerializer::class)
    val minHoursPresent: Double? = null,
    /**
     * Organization "Regular Office Start Time" — the Manual Entry form
     * pre-fills the login field from it (`ManualEntry.tsx`), falling back to
     * 09:00 when not configured.
     */
    @SerialName("office_start_time") val officeStartTime: String? = null,
    /**
     * Office Wi-Fi allow-list used by the verification sheet
     * (`ClockInVerifyModal`): when `wifiVerificationEnabled` and the connected
     * BSSID is in this list the server skips the geofence check.
     */
    @SerialName("office_wifi_bssids")
    @Serializable(with = BssidListSerializer::class)
    val officeWifiBssids: List<String> = emptyList(),
)

/**
 * `office_wifi_bssids` is JSONB stored as `{ bssid, label, added_by, added_at }`
 * objects (legacy rows are bare strings). Decoding it as `List<String>` threw on
 * the first registered AP, which collapsed the whole policy to "verification
 * off" — clock-in then skipped the verify sheet and the server rejected it.
 */
object BssidListSerializer : kotlinx.serialization.KSerializer<List<String>> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(String.serializer())
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: List<String>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): List<String> {
        val element = (decoder as? kotlinx.serialization.json.JsonDecoder)?.decodeJsonElement() ?: return delegate.deserialize(decoder)
        val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            when (entry) {
                is kotlinx.serialization.json.JsonPrimitive -> entry.contentOrNullSafe()
                is kotlinx.serialization.json.JsonObject -> (entry["bssid"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()
                else -> null
            }
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content.takeIf(String::isNotBlank)
}

@Serializable
data class AttendanceDay(
    val date: String,
    val floorMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val workMode: String = "office",
    val entries: List<TimeEntryDto> = emptyList(),
)

enum class AttendanceDayKind { Present, Leave, LeavePending, Holiday, Absent, Weekend, InProgress, Future }

@Serializable
data class LeaveOverlay(
    val id: Long,
    val date: String,
    @SerialName("leave_type") val leaveType: String,
    val duration: String = "full",
    val status: String = "pending",
    val reason: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("approved_by_name") val approvedByName: String? = null,
)

@Serializable
data class HolidayOverlay(
    val id: Long,
    val date: String,
    val name: String,
    @SerialName("is_optional") val isOptional: Boolean = false,
)

/** `GET /tracker/widgets` summary block rendered above the analytics charts. */
@Serializable
data class TrackerWidgets(
    @Serializable(with = LenientDoubleSerializer::class) val avgFloorMinutes: Double = 0.0,
    @Serializable(with = LenientDoubleSerializer::class) val punctualityPercent: Double = 0.0,
    @Serializable(with = LenientDoubleSerializer::class) val attendancePercent: Double = 0.0,
    val targetMetDays: Int = 0,
    val workDays: Int = 0,
    val leaveCount: Int = 0,
    val officeDays: Int = 0,
    val remoteDays: Int = 0,
)

/** `GET /notifications/metrics?hours=24` — the Analytics "Notification Routing" card. */
@Serializable
data class NotificationMetrics(
    val successRate: Double? = null,
    val counts: NotificationMetricCounts = NotificationMetricCounts(),
    val latency: NotificationMetricLatency = NotificationMetricLatency(),
)

@Serializable
data class NotificationMetricCounts(val routingAttempts: Int = 0, val successfulRoutes: Int = 0)

@Serializable
data class NotificationMetricLatency(val p95Ms: Double = 0.0)

/** Card value: `successRate.toFixed(1)%` or "No data". */
fun routingSuccessLabel(metrics: NotificationMetrics?): String =
    metrics?.successRate?.let { "%.1f%%".format(java.util.Locale.US, it) } ?: "No data"

/** `x/y routes · p95 Nms`. */
fun routingMeta(metrics: NotificationMetrics): String =
    "${metrics.counts.successfulRoutes}/${metrics.counts.routingAttempts} routes" +
        if (metrics.latency.p95Ms > 0) " · p95 ${Math.round(metrics.latency.p95Ms)}ms" else ""

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
data class OvertimeMetadata(
    val date: String? = null,
    // Stored inside an `approval_requests.metadata` JSON blob, so the hours can
    // arrive either as a JSON number or a quoted string depending on how the
    // request was created.
    @Serializable(with = LenientDoubleNullableSerializer::class) val hours: Double? = null,
)

@Serializable
data class ManualEntryPayload(
    val date: String,
    @SerialName("clock_in") val clockIn: String,
    @SerialName("clock_out") val clockOut: String? = null,
    val timezoneOffset: Int,
    @SerialName("work_mode") val workMode: String,
    val breaks: List<ManualBreakPayload> = emptyList(),
)

@Serializable
data class ManualBreakPayload(val start: String, val end: String)

@Serializable
data class RawTimeEntry(
    val id: Long? = null,
    @SerialName("entry_type") val entryType: String,
    val timestamp: String,
    @SerialName("work_mode") val workMode: String? = null,
    @SerialName("is_manual") val isManual: Boolean = false,
    @SerialName("approval_status") val approvalStatus: String? = null,
)

data class EditableDay(
    val clockIn: String,
    val clockOut: String?,
    val workMode: WorkMode,
    val breaks: List<ManualBreakPayload>,
    val hasExistingEntries: Boolean,
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
    leave: LeaveOverlay? = null,
    holiday: HolidayOverlay? = null,
): AttendanceDayKind {
    if ((day?.floorMinutes ?: 0) >= minimumMinutes) return AttendanceDayKind.Present
    if (leave != null) return if (leave.status == "approved") AttendanceDayKind.Leave else AttendanceDayKind.LeavePending
    if (holiday != null) return AttendanceDayKind.Holiday
    if (!workDays.contains(date.dayOfWeek.value % 7)) return AttendanceDayKind.Weekend
    if (date == today) return AttendanceDayKind.InProgress
    if (date > today) return AttendanceDayKind.Future
    return AttendanceDayKind.Absent
}

/**
 * Clock-in/out body, mirroring `client/src/api/workforce.ts` (P3.6):
 * geo fix, office Wi-Fi BSSID, optional face descriptor, plus the native
 * device-biometric fallback the server accepts (`fingerprint_verified`).
 */
@Serializable
data class AttendanceActionRequest(
    @SerialName("work_mode") val workMode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    @SerialName("wifi_bssid") val wifiBssid: String? = null,
    @SerialName("face_descriptor") val faceDescriptor: List<Float>? = null,
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

fun validateManualEntry(
    date: String,
    clockIn: String,
    clockOut: String?,
    today: java.time.LocalDate,
    breaks: List<ManualBreakPayload> = emptyList(),
): String? {
    val parsedDate = runCatching { java.time.LocalDate.parse(date) }.getOrNull()
        ?: return "Choose a valid date"
    if (parsedDate > today) return "Cannot add a manual entry for a future date"
    if (!validTime(clockIn)) return "Login time must use HH:MM"
    if (!clockOut.isNullOrBlank() && !validTime(clockOut)) return "Logout time must use HH:MM"
    if (!clockOut.isNullOrBlank() && clockOut <= clockIn) return "Logout time must be after login time"
    val sorted = breaks.sortedBy { it.start }
    sorted.forEachIndexed { index, item ->
        if (!validTime(item.start) || !validTime(item.end)) return "Each break must use HH:MM"
        if (item.end <= item.start) return "Break end time must be after break start time"
        if (item.start < clockIn || (!clockOut.isNullOrBlank() && item.end > clockOut)) return "Break times must be within clock-in and clock-out times"
        if (index < sorted.lastIndex && item.end > sorted[index + 1].start) return "Break times must not overlap"
    }
    return null
}

fun editableDay(entries: List<RawTimeEntry>): EditableDay? {
    if (entries.isEmpty()) return null
    val clockIn = entries.firstOrNull { it.entryType == "clock_in" } ?: return null
    val clockOut = entries.lastOrNull { it.entryType == "clock_out" }
    val starts = entries.filter { it.entryType == "break_start" }
    val ends = entries.filter { it.entryType == "break_end" }
    return EditableDay(
        clockIn = localTime(clockIn.timestamp),
        clockOut = clockOut?.timestamp?.let(::localTime),
        workMode = WorkMode.entries.firstOrNull { it.name.equals(clockIn.workMode, true) } ?: WorkMode.Office,
        breaks = starts.mapIndexedNotNull { index, start -> ends.getOrNull(index)?.let { ManualBreakPayload(localTime(start.timestamp), localTime(it.timestamp)) } },
        hasExistingEntries = true,
    )
}

private fun localTime(timestamp: String): String = runCatching {
    java.time.Instant.parse(timestamp.replace(" ", "T").let { if (it.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(it)) it else "${it}Z" })
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("09:00")

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

/** Why a location fix does not prove office presence — same wording as the server's clock-in errors. */
fun officeProofFailure(policy: AttendancePolicy, proof: LocationProof): String {
    val lat = policy.officeLatitude
    val lng = policy.officeLongitude
    if (lat == null || lng == null) return "Office location is not configured. Contact your admin or switch to remote."
    val maxAccuracy = maxOf(policy.officeRadiusMeters, 200.0)
    if (proof.accuracyMeters > maxAccuracy) {
        return "Your location accuracy is ±${proof.accuracyMeters.toInt()} m — too coarse for the office geofence (max ±${maxAccuracy.toInt()} m). Enable precise location or connect to the office Wi-Fi."
    }
    val distance = distanceMeters(proof.latitude, proof.longitude, lat, lng).toInt()
    return "You are $distance m from the office (allowed ${policy.officeRadiusMeters.toInt()} m). Move closer or switch to remote."
}

fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * earthRadius * asin(sqrt(a))
}