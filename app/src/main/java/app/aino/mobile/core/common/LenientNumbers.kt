package app.aino.mobile.core.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tolerant decoders for PostgreSQL numeric columns (A-101).
 *
 * `node-postgres` returns `NUMERIC`/`DECIMAL` columns as **strings**, not
 * numbers, because arbitrary-precision decimals cannot round-trip through an
 * IEEE-754 double. The platform installs no `pg.types.setTypeParser`, so those
 * columns reach the client quoted:
 *
 *     { "min_hours_present": "4.00" }   // NUMERIC(4,2)
 *     { "work_hours_per_day": 8 }       // INTEGER
 *
 * The web client already absorbs this — `OrgSettings.tsx` types the field
 * `number | string` and `AttendanceCalendar.tsx` coerces with `Number(...)`.
 * Android's strict `Double` decoder instead threw, which nulled the whole
 * attendance policy and silently disabled every clock-in button.
 *
 * These serializers accept a JSON number, a quoted number, or null, so a
 * column's SQL type can change without breaking the client.
 */
object LenientDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.aino.LenientDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double =
        decodeLenientDouble(decoder) ?: throw NumericFormatException(
            "Expected a numeric value but found null or an unparsable token",
        )

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

/** Nullable variant: `null` stays `null` rather than collapsing to a default. */
object LenientDoubleNullableSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.aino.LenientDoubleNullable", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double? = decodeLenientDouble(decoder)

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

/** Tolerant `Int`, for columns that may arrive quoted (for example `COUNT(*)`). */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.aino.LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val value = decodeLenientDouble(decoder) ?: throw NumericFormatException(
            "Expected an integer value but found null or an unparsable token",
        )
        return value.toInt()
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/**
 * Shared tolerant read. Returns null for JSON null, an absent value, or an
 * empty string — the server sends `""` for a cleared numeric input.
 */
private fun decodeLenientDouble(decoder: Decoder): Double? {
    val jsonDecoder = decoder as? JsonDecoder
        ?: return decoder.decodeDouble() // Non-JSON formats keep strict behavior.
    val element = jsonDecoder.decodeJsonElement()
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: throw NumericFormatException(
        "Expected a numeric primitive but found ${element::class.simpleName}",
    )
    val content = primitive.content
    if (content.isBlank()) return null
    return content.toDoubleOrNull() ?: throw NumericFormatException(
        "Value '$content' is not a valid number",
    )
}

/** Raised when a value is present but genuinely not numeric. */
class NumericFormatException(message: String) : IllegalArgumentException(message)

/**
 * Coerce an already-decoded tolerant JSON element to Double, for the free-form
 * payloads that A-100 routes before they have concrete typed models.
 */
fun kotlinx.serialization.json.JsonElement?.asLenientDouble(): Double? =
    lenientContent()?.toDoubleOrNull()

/** Convenience for realtime payloads that carry quoted identifiers. */
fun kotlinx.serialization.json.JsonElement?.asLenientLong(): Long? {
    val content = lenientContent() ?: return null
    return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
}

/**
 * Non-blank primitive content, or null.
 *
 * `JsonNull` is itself a [JsonPrimitive], so it must be excluded explicitly —
 * a plain `as? JsonPrimitive` cast succeeds for null and would otherwise yield
 * the literal string "null".
 */
private fun kotlinx.serialization.json.JsonElement?.lenientContent(): String? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.takeIf(String::isNotBlank)
}
