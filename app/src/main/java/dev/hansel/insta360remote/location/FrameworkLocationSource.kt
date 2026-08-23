package dev.hansel.insta360remote.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Fallback-Standortquelle ohne Google Play Services: direkter Zugriff auf den
 * GPS-Provider via LocationManager.
 */
class FrameworkLocationSource(private val context: Context) : LocationSource {

    override fun fixes(intervalMs: Long): Flow<GpsFix> = callbackFlow {
        if (!isPermissionGranted()) {
            close(SecurityException("ACCESS_FINE_LOCATION nicht gewaehrt"))
            return@callbackFlow
        }
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            close(IllegalStateException("GPS-Provider deaktiviert"))
            return@callbackFlow
        }

        val listener = LocationListener { location ->
            trySend(location.toGpsFix())
        }

        try {
            // minDistance=0 -> rein zeitbasiert; Batching durch grosse minTime.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            close(e)
            return@callbackFlow
        }

        awaitClose { locationManager.removeUpdates(listener) }
    }

    private fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
