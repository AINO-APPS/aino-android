package app.aino.mobile.core.network

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

/**
 * TLS certificate pinning for the AINO hosts (P2.5). See
 * `docs/CERT_PINNING_RUNBOOK.md` for how pins are computed and rotated.
 *
 * Pins come from the `AINO_CERT_PINS` build property: `;`-separated
 * `sha256/<base64 SPKI hash>` entries, **at least two** (current key + backup
 * key under a different CA), applied to every host under [PINNED_DOMAIN].
 * With fewer than two valid pins pinning stays off rather than risk locking
 * every installed app out on the next certificate renewal. Debug builds never
 * pin (local servers, proxies).
 */
object CertificatePinning {
    const val PINNED_DOMAIN = "aino.org.in"
    private val PIN = Regex("^sha256/[A-Za-z0-9+/]{43}=$")

    /** Valid pins from [raw]; empty when fewer than two (no backup pin). */
    fun parsePins(raw: String?): List<String> {
        val pins = raw.orEmpty().split(';', ',', '\n').map(String::trim).filter(PIN::matches).distinct()
        return if (pins.size >= 2) pins else emptyList()
    }

    /** The pinner for [raw] pins, or null when pinning is off. */
    fun pinner(raw: String?, debug: Boolean): CertificatePinner? {
        if (debug) return null
        val pins = parsePins(raw).takeIf { it.isNotEmpty() } ?: return null
        return CertificatePinner.Builder().apply {
            add(PINNED_DOMAIN, *pins.toTypedArray())
            add("**.$PINNED_DOMAIN", *pins.toTypedArray())
        }.build()
    }

    /** The app-wide pinner from BuildConfig. */
    val current: CertificatePinner? by lazy {
        pinner(app.aino.mobile.BuildConfig.AINO_CERT_PINS, app.aino.mobile.BuildConfig.DEBUG)
    }

    /** [builder] with the app pins applied (unchanged when pinning is off). */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder = current?.let(builder::certificatePinner) ?: builder
}
