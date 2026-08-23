package dev.hansel.insta360remote.location

import android.content.Context
import dev.hansel.insta360remote.core.AppPreferences
import dev.hansel.insta360remote.core.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Adaptive Standortversorgung:
 *
 * - 1 Hz (bewegt, via MotionMonitor erkannt)
 * - 0,2 Hz (Stillstand) - spart massiv Akku bei z.B. statischen Timelapses
 *
 * Quelle: FusedLocationProviderClient (Play Services) mit konfigurierbarer
 * Prioritaet; Fallback auf LocationManager/GPS-Provider ohne Play Services.
 * Der Wechsel des Intervalls erfolgt ueber flatMapLatest: Die alte
 * Location-Subscription wird sauber abgebaut (removeLocationUpdates),
 * bevor die neue gestartet wird - kein Dauer-Polling, kein Batching-Verlust.
 */
class AdaptiveLocationController(
    context: Context,
    private val prefs: AppPreferences,
) {

    private val appContext = context.applicationContext

    private val movingState = MutableStateFlow(true)

    private var motionMonitor: MotionMonitor? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun fixes(scope: CoroutineScope): Flow<GpsFix> {
        motionMonitor = MotionMonitor(appContext, scope) {
            movingState.value = motionMonitor?.isMoving ?: true
        }.also { it.start() }

        return movingState.asStateFlow().flatMapLatest { moving ->
            val interval =
                if (moving) prefs.activeIntervalMs else prefs.idleIntervalMs
            Diagnostics.log(
                TAG,
                "Intervall-Wechsel: ${if (moving) "aktiv" else "ruhend"} (${interval} ms)"
            )
            createSource().fixes(interval)
        }
    }

    fun stop() {
        motionMonitor?.stop()
        motionMonitor = null
    }

    private fun createSource(): LocationSource {
        val useFused = prefs.useFusedLocation && hasPlayServices()
        return if (useFused) {
            FusedLocationSource(appContext)
        } else {
            if (prefs.useFusedLocation) {
                Diagnostics.log(TAG, "Play Services nicht verfuegbar - Fallback auf GPS-Provider")
            }
            FrameworkLocationSource(appContext)
        }
    }

    private fun hasPlayServices(): Boolean = try {
        com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance()
            .isGooglePlayServicesAvailable(appContext) ==
            com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "AdaptiveLocation"
    }
}
