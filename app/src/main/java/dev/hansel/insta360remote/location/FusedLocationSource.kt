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
import dev.hansel.insta360remote.core.Diagnostics
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Standortquelle ueber FusedLocationProviderClient (Google Play Services).
 *
 * Die Prioritaet kommt aus [dev.hansel.insta360remote.core.AppPreferences.locationPriority]
 * ("balanced" | "high_accuracy"):
 *
 *  - **balanced**  (Default): PRIORITY_BALANCED_POWER_ACCURACY - akkuschonend,
 *    WLAN/Cell-assistiert, nutzt aber auch GNSS wenn noetig.
 *  - **high_accuracy**: PRIORITY_HIGH_ACCURACY - aktives GNSS mit hoechster
 *    Rate/Genauigkeit, spuerbar mehr Verbrauch (~+10-25 mA).
 */
class FusedLocationSource(
    private val context: Context,
    /** Aus AppPreferences.locationPriority: siehe Konstanten unten. */
    private val prioritySetting: String = PRIORITY_BALANCED,
) : LocationSource {

    override fun fixes(intervalMs: Long): Flow<GpsFix> = callbackFlow {
        if (!isPermissionGranted()) {
            close(SecurityException("ACCESS_FINE_LOCATION nicht gewaehrt"))
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)

        val priority = resolvePriority()
        Diagnostics.log(TAG, "Prioritaet=$priority ('$prioritySetting'), Intervall=${intervalMs}ms")

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

    /**
     * Mappt den Pref-String auf die FusedLocation-Prioritaet. Unbekannte Werte
     * fallen bewusst auf akkuschonend zurueck.
     */
    private fun resolvePriority(): Int = when (prioritySetting.trim().lowercase(Locale.US)) {
        PRIORITY_HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
        else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    }

    private fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "FusedLoc"

        const val PRIORITY_BALANCED = "balanced"
        const val PRIORITY_HIGH_ACCURACY = "high_accuracy"
    }
}
