package dev.hansel.insta360remote.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Standortquelle ueber FusedLocationProviderClient (Google Play Services).
 *
 * Default ist PRIORITY_BALANCED_POWER_ACCURACY (akkuschonend, WLAN/Cell-assistiert,
 * nutzt aber auch GPS wenn noetig). Alternativ waehlbar: HIGH_ACCURACY.
 */
class FusedLocationSource(private val context: Context) : LocationSource {

    override fun fixes(intervalMs: Long): Flow<GpsFix> = callbackFlow {
        if (!isPermissionGranted()) {
            close(SecurityException("ACCESS_FINE_LOCATION nicht gewaehrt"))
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)

        val priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs * 4)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toGpsFix()) }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
