package dev.hansel.insta360remote.location

/**
 * GPS-Fix-Datenmodell mit allen Feldern, die ein echtes GPS-Remote der
 * Kamera uebermitteln koennte (Lat/Lon/Hoehe/Geschwindigkeit/UTC/Fix-Qualitaet).
 */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val horizontalAccuracyMeters: Float,
    /** UTC-Zeitstempel des Fixes in Millisekunden seit Epoch. */
    val utcEpochMillis: Long,
    val satelliteCount: Int,
    val fixQuality: FixQuality,
) {
    enum class FixQuality { NO_FIX, GPS_FIX, DIFFERENTIAL, RTK }
}
