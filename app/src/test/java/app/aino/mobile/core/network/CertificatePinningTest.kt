package app.aino.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePinningTest {
    private val current = "sha256/" + "A".repeat(43) + "="
    private val backup = "sha256/" + "B".repeat(43) + "="

    @Test
    fun needsACurrentAndABackupPin() {
        assertEquals(listOf(current, backup), CertificatePinning.parsePins("$current; $backup"))
        // A single pin would brick every install on the next key change: pinning stays off.
        assertTrue(CertificatePinning.parsePins(current).isEmpty())
        assertTrue(CertificatePinning.parsePins("").isEmpty())
        assertTrue(CertificatePinning.parsePins("sha1/abc; md5/xyz").isEmpty())
        assertEquals(listOf(current, backup), CertificatePinning.parsePins("$current,$backup,$current"))
    }

    @Test
    fun pinsTheAinoDomainAndItsSubdomainsInReleaseOnly() {
        val pinner = CertificatePinning.pinner("$current;$backup", debug = false)
        assertNotNull(pinner)
        val hosts = pinner!!.pins.map { it.pattern }.toSet()
        assertEquals(setOf("aino.org.in", "**.aino.org.in"), hosts)
        assertNull(CertificatePinning.pinner("$current;$backup", debug = true))
        assertNull(CertificatePinning.pinner(current, debug = false))
    }

    @Test
    fun aPinnedClientChecksTheAinoHostsOnly() {
        val pinner = CertificatePinning.pinner("$current;$backup", debug = false)!!
        assertTrue(pinner.findMatchingPins("next.aino.org.in").isNotEmpty())
        assertTrue(pinner.findMatchingPins("www.aino.org.in").isNotEmpty())
        // Third-party hosts (GIPHY, link previews, Firebase) are never pinned.
        assertTrue(pinner.findMatchingPins("media.giphy.com").isEmpty())
        assertTrue(pinner.findMatchingPins("evilaino.org.in").isEmpty())
    }
}
