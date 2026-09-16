package app.aino.mobile.core.common

import app.aino.mobile.feature.attendance.AttendancePolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-101 regression tests for the PostgreSQL NUMERIC decode defect.
 *
 * node-pg returns NUMERIC/DECIMAL columns as strings. The strict decoder threw
 * on `"min_hours_present": "4.00"`, which nulled the whole attendance policy
 * and silently disabled every clock-in button.
 */
class LenientNumbersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesQuotedNumericExactlyAsTheServerSendsIt() {
        // Verbatim shape of GET /api/org/current: NUMERIC(4,2) quoted by
        // node-pg, INTEGER and DOUBLE PRECISION unquoted.
        val body = """
            {
              "attendance_verification_enabled": true,
              "office_latitude": 12.9716,
              "office_longitude": 77.5946,
              "office_radius_m": 150,
              "work_hours_per_day": 8,
              "work_days": "1,2,3,4,5",
              "min_hours_present": "4.00"
            }
        """.trimIndent()

        val policy = json.decodeFromString<AttendancePolicy>(body)

        assertEquals(4.0, policy.minHoursPresent!!, 0.0001)
        assertEquals(150.0, policy.officeRadiusMeters, 0.0001)
        assertEquals(8.0, policy.workHoursPerDay, 0.0001)
        assertEquals(12.9716, policy.officeLatitude!!, 0.0001)
        assertTrue(policy.verificationEnabled)
    }

    @Test
    fun acceptsUnquotedNumericToo() {
        // Survives the platform later installing a pg type parser.
        val policy = json.decodeFromString<AttendancePolicy>(
            """{"min_hours_present": 4.5, "office_radius_m": "200"}""",
        )
        assertEquals(4.5, policy.minHoursPresent!!, 0.0001)
        assertEquals(200.0, policy.officeRadiusMeters, 0.0001)
    }

    @Test
    fun treatsNullAndEmptyStringAsAbsent() {
        // The org settings form posts "" for a cleared numeric input.
        assertNull(json.decodeFromString<AttendancePolicy>("""{"min_hours_present": null}""").minHoursPresent)
        assertNull(json.decodeFromString<AttendancePolicy>("""{"min_hours_present": ""}""").minHoursPresent)
        assertNull(json.decodeFromString<AttendancePolicy>("{}").minHoursPresent)
    }

    @Test
    fun keepsDefaultsWhenFieldsAreMissing() {
        val policy = json.decodeFromString<AttendancePolicy>("{}")
        assertEquals(150.0, policy.officeRadiusMeters, 0.0001)
        assertEquals(8.0, policy.workHoursPerDay, 0.0001)
        assertEquals("1,2,3,4,5", policy.workDays)
    }

    @Test
    fun rejectsGenuinelyNonNumericValues() {
        // Tolerance must not silently swallow a real contract break.
        assertThrows(NumericFormatException::class.java) {
            json.decodeFromString<AttendancePolicy>("""{"office_radius_m": "not-a-number"}""")
        }
    }

    @Test
    fun jsonElementHelpersExcludeJsonNull() {
        // JsonNull is a JsonPrimitive, so a naive cast would yield "null".
        val element = Json.parseToJsonElement("""{"a": null, "b": "42", "c": 7}""")
        val obj = element as kotlinx.serialization.json.JsonObject
        assertNull(obj["a"].asLenientDouble())
        assertNull(obj["a"].asLenientLong())
        assertEquals(42L, obj["b"].asLenientLong())
        assertEquals(7.0, obj["c"].asLenientDouble()!!, 0.0001)
        assertNull(obj["missing"].asLenientDouble())
    }
}
