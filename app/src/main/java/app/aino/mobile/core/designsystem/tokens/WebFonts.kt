package app.aino.mobile.core.designsystem.tokens

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import app.aino.mobile.R

/**
 * Inter via Google Fonts downloadable fonts (P1.2). Falls back to the platform
 * sans-serif while the font downloads, so the app never blocks on a network
 * fetch and never ships a bundled font binary.
 *
 * Requires `res/values/font_certs.xml` with the Google Play Services font
 * certificates (already present) and the `ui-text-google-fonts` dependency.
 */
object WebFonts {
    private val provider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs,
    )

    private val interFont = GoogleFont("Inter")

    val inter: FontFamily = FontFamily(
        Font(googleFont = interFont, fontProvider = provider, weight = FontWeight.Normal),
        Font(googleFont = interFont, fontProvider = provider, weight = FontWeight.Medium),
        Font(googleFont = interFont, fontProvider = provider, weight = FontWeight.SemiBold),
        Font(googleFont = interFont, fontProvider = provider, weight = FontWeight.Bold),
        Font(googleFont = interFont, fontProvider = provider, weight = FontWeight.ExtraBold),
    )
}
