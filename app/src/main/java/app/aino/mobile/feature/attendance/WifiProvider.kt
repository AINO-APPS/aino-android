package app.aino.mobile.feature.attendance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/**
 * Reads the connected access point's BSSID for office Wi-Fi verification
 * (`getWifiInfo` port from `client/src/utils/geolocation.ts`, P3.6).
 *
 * Android 8.1+ only exposes the BSSID while precise-location permission is
 * granted; otherwise `WifiInfo.getBssid()` returns the placeholder
 * "02:00:00:00:00:00", which is treated as "unavailable" exactly like the
 * web fallback path.
 */
class WifiProvider(private val context: Context) {

    fun currentBssid(): String? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val raw = runCatching { manager.connectionInfo?.bssid }.getOrNull() ?: return null
        return normaliseBssid(raw)
    }

    companion object {
        /** Normalise any MAC-ish string to canonical AA:BB:CC:DD:EE:FF (or null). */
        fun normaliseBssid(raw: String?): String? {
            val cleaned = raw?.replace(Regex("[^0-9a-fA-F]"), "")?.uppercase() ?: return null
            if (cleaned.length != 12 || cleaned == "020000000000") return null
            return cleaned.chunked(2).joinToString(":")
        }
    }
}
