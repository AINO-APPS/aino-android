package app.aino.mobile.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class IndianNumberFormatTest {
    @Test
    fun groupsLikeToLocaleStringEnIn() {
        assertEquals("0", formatIndianNumber(0.0))
        assertEquals("999", formatIndianNumber(999.0))
        assertEquals("1,000", formatIndianNumber(1000.0))
        assertEquals("50,000", formatIndianNumber(50000.0))
        assertEquals("6,00,000", formatIndianNumber(600000.0))
        assertEquals("1,23,45,678", formatIndianNumber(12345678.0))
        assertEquals("-12,345", formatIndianNumber(-12345.0))
    }

    @Test
    fun keepsUpToThreeFractionDigitsWithoutTrailingZeros() {
        assertEquals("41,000.5", formatIndianNumber(41000.50))
        assertEquals("1.235", formatIndianNumber(1.2345))
        assertEquals("12,345.68", formatIndianNumber(12345.68))
    }

    @Test
    fun rupeesTreatMissingAsZero() {
        assertEquals("\u20B950,000", formatRupees(50000.0))
        assertEquals("\u20B90", formatRupees(null))
    }
}
