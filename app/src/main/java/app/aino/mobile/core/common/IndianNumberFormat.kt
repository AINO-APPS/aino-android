package app.aino.mobile.core.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * JavaScript `Number(x).toLocaleString("en-IN")`: Indian digit grouping
 * (`12,34,567`), at most three fraction digits, trailing zeros dropped.
 * Used for the web's `₹` amounts (salary slips, compensation, payout balance).
 */
fun formatIndianNumber(value: Double): String {
    if (!value.isFinite()) return value.toString()
    val scaled = BigDecimal(value.toString()).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
    val plain = scaled.abs().toPlainString()
    val intPart = plain.substringBefore('.')
    val fraction = plain.substringAfter('.', "")
    val grouped = if (intPart.length <= 3) {
        intPart
    } else {
        val groups = ArrayDeque<String>()
        var rest = intPart.dropLast(3)
        while (rest.length > 2) {
            groups.addFirst(rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) groups.addFirst(rest)
        groups.joinToString(",") + "," + intPart.takeLast(3)
    }
    val sign = if (scaled.signum() < 0) "-" else ""
    return sign + grouped + if (fraction.isNotEmpty()) ".$fraction" else ""
}

/** `₹{Number(x).toLocaleString("en-IN")}`; a missing value reads as zero like `Number(null)`. */
fun formatRupees(value: Double?): String = "\u20B9" + formatIndianNumber(value ?: 0.0)
