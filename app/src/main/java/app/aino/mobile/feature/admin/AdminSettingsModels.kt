package app.aino.mobile.feature.admin

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.LenientIntNullableSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WifiAp(val bssid: String = "", val label: String? = null)

/** `GET /org/current`: the fields the Org Settings + Attendance forms edit. */
@Serializable
data class OrgSettings(
    val id: Long = 0,
    val name: String = "",
    @SerialName("work_hours_per_day") @Serializable(LenientIntNullableSerializer::class) val workHoursPerDay: Int? = null,
    @SerialName("work_days") val workDays: String? = null,
    val timezone: String? = null,
    @SerialName("fiscal_year_start") @Serializable(LenientIntNullableSerializer::class) val fiscalYearStart: Int? = null,
    @SerialName("min_hours_present") @Serializable(LenientDoubleNullableSerializer::class) val minHoursPresent: Double? = null,
    @SerialName("office_start_time") val officeStartTime: String? = null,
    @SerialName("attendance_verification_enabled") val attendanceVerificationEnabled: Boolean? = null,
    @SerialName("office_latitude") @Serializable(LenientDoubleNullableSerializer::class) val officeLatitude: Double? = null,
    @SerialName("office_longitude") @Serializable(LenientDoubleNullableSerializer::class) val officeLongitude: Double? = null,
    @SerialName("office_radius_m") @Serializable(LenientIntNullableSerializer::class) val officeRadiusM: Int? = null,
    @SerialName("office_address") val officeAddress: String? = null,
    @SerialName("office_wifi_bssids") val officeWifiBssids: List<WifiAp>? = null,
    @SerialName("office_wifi_verification_enabled") val officeWifiVerificationEnabled: Boolean? = null,
    @SerialName("biometric_login_enabled") val biometricLoginEnabled: Boolean? = null,
)

/** `OrgSettings.tsx` form state. */
data class GeneralSettingsDraft(
    val name: String = "",
    val workHoursPerDay: String = "8",
    val workDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val timezone: String = "UTC",
    val fiscalYearStart: String = "1",
    val minHoursPresent: String = "",
    val officeStartTime: String = "",
    val biometricLoginEnabled: Boolean = true,
) {
    companion object {
        fun from(s: OrgSettings) = GeneralSettingsDraft(
            name = s.name,
            workHoursPerDay = (s.workHoursPerDay ?: 8).toString(),
            workDays = s.workDays?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: setOf(1, 2, 3, 4, 5),
            timezone = s.timezone ?: "UTC",
            fiscalYearStart = (s.fiscalYearStart ?: 1).toString(),
            minHoursPresent = s.minHoursPresent?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty(),
            officeStartTime = s.officeStartTime?.take(5).orEmpty(),
            biometricLoginEnabled = s.biometricLoginEnabled != false,
        )
    }
}

/** `OfficeLocationSettings.tsx` form state. */
data class AttendanceSettingsDraft(
    val verifyOn: Boolean = false,
    val address: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val radius: String = "150",
    val wifiOn: Boolean = false,
    val wifi: List<WifiAp> = emptyList(),
) {
    companion object {
        fun from(s: OrgSettings) = AttendanceSettingsDraft(
            verifyOn = s.attendanceVerificationEnabled == true,
            address = s.officeAddress.orEmpty(),
            latitude = s.officeLatitude?.toString().orEmpty(),
            longitude = s.officeLongitude?.toString().orEmpty(),
            radius = (s.officeRadiusM ?: 150).toString(),
            wifiOn = s.officeWifiVerificationEnabled == true,
            wifi = s.officeWifiBssids.orEmpty(),
        )
    }
}
