package app.aino.mobile.feature.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class LocationProvider(private val context: Context) {
    fun hasPrecisePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentPreciseLocation(): LocationProof {
        check(hasPrecisePermission()) { "Precise location permission is required" }
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> error("Location services are disabled")
        }
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    cancellation,
                    ContextCompat.getMainExecutor(context),
                ) { location: Location? ->
                    if (!continuation.isActive) return@getCurrentLocation
                    if (location == null || !location.hasAccuracy()) {
                        continuation.resumeWith(Result.failure(IllegalStateException("A precise location fix is unavailable")))
                    } else {
                        continuation.resume(LocationProof(location.latitude, location.longitude, location.accuracy))
                    }
                }
            } catch (error: SecurityException) {
                continuation.resumeWith(Result.failure(IllegalStateException("Precise location permission was revoked", error)))
            }
        }
    }
}