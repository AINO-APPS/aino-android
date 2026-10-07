package app.aino.mobile.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegalLinksTest {
    @Test fun `legal pages live on the web origin of the API host`() {
        assertEquals("https://next.aino.org.in/privacy", LegalLinks.url(LegalLinks.PRIVACY_PATH, "https://next.aino.org.in/api"))
        assertEquals("https://next.aino.org.in/account-deletion", LegalLinks.url(LegalLinks.ACCOUNT_DELETION_PATH, "https://next.aino.org.in/api/"))
        assertEquals("http://10.0.2.2:5000/terms", LegalLinks.url(LegalLinks.TERMS_PATH, "http://10.0.2.2:5000/api"))
    }
}
