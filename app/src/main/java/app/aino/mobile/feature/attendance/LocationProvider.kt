package app.aino.mobile.feature.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Freshest usable fix among candidates: must be recent and have an accuracy reading; best accuracy wins. */
fun pickRecentFix(fixes: List<LocationProof?>, ageMs: (LocationProof) -> Long, maxAgeMs: Long): LocationProof? =
    fixes.filterNotNull().filter { ageMs(it) in 0..maxAgeMs }.minByOrNull { it.accuracyMeters }

class LocationProvider(private val context: Context) {
    fun hasPrecisePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * The web asks `navigator.geolocation` (fused, high accuracy). GPS alone
     * indoors often never fixes, so: reuse a fresh (<2 min) cached fix, else ask
     * fused → network → GPS in turn, each bounded so the sheet never hangs.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentPreciseLocation(): LocationProof {
        check(hasPrecisePermission()) { "Precise location permission is required" }
        val manager = context.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(manager)) error("Location services are disabled. Turn on location and try again.")
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) error("Location services are disabled. Turn on location and try again.")

        val now = SystemClock.elapsedRealtimeNanos()
        val cached = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.takeIf(Location::hasAccuracy)
        }
        val ages = cached.associate { it.toProof() to (now - it.elapsedRealtimeNanos) / 1_000_000 }
        pickRecentFix(ages.keys.toList(), { ages[it] ?: Long.MAX_VALUE }, maxAgeMs = 120_000)
            ?.takeIf { it.accuracyMeters <= 100f }
            ?.let { return it }

        for (provider in providers) {
            val fix = withTimeoutOrNull(if (provider == LocationManager.GPS_PROVIDER) 20_000L else 10_000L) { requestFix(manager, provider) }
            if (fix != null) return fix
        }
        // Fall back to a fresh-enough cached fix of any accuracy; the server judges it.
        return pickRecentFix(ages.keys.toList(), { ages[it] ?: Long.MAX_VALUE }, maxAgeMs = 300_000)
            ?: error("A precise location fix is unavailable. Move near a window or connect to the office Wi-Fi.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(manager: LocationManager, provider: String): LocationProof? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, ContextCompat.getMainExecutor(context)) { location: Location? ->
                    if (continuation.isActive) continuation.resume(location?.takeIf(Location::hasAccuracy)?.toProof())
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun Location.toProof() = LocationProof(latitude, longitude, accuracy)
}
