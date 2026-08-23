package dev.hansel.insta360remote.location

import android.location.Location

/**
 * Konvertiert ein Android-[Location]-Objekt in unser [GpsFix]-Datenmodell.
 */
fun Location.toGpsFix(satellites: Int = 0): GpsFix {
    val quality = when {
        !hasAccuracy() || accuracy > 50f -> GpsFix.FixQuality.NO_FIX
        extras?.getBoolean("gps.differential", false) == true ->
            GpsFix.FixQuality.DIFFERENTIAL
        else -> GpsFix.FixQuality.GPS_FIX
    }
    return GpsFix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else 0.0,
        speedMps = if (hasSpeed()) speed else 0f,
        bearingDeg = if (hasBearing()) bearing else 0f,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy else 0f,
        utcEpochMillis = time,
        satelliteCount = satellites,
        fixQuality = quality,
    )
}

/**
 * Abstraktion der Standortquelle (FusedLocationProvider vs. LocationManager),
 * damit der Fallback ohne Play Services transparent funktioniert.
 */
interface LocationSource {
    /**
     * Liefert einen kalten Flow von Fixes mit dem gewuenschten Intervall.
     * Beendet sich, wenn der Collector die Subscription abbricht.
     */
    fun fixes(intervalMs: Long): kotlinx.coroutines.flow.Flow<GpsFix>
}
