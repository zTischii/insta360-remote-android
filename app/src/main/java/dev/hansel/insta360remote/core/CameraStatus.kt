package dev.hansel.insta360remote.core

/**
 * Von der Kamera gemeldeter Anzeigezustand aus den ce81-Display-Strings
 * (Frame-Typ 0x10, ~1 Hz) gemaess X4-Spec §6:
 *
 * ````
 * FE EF FE 10 <flag> <LEN> <c1..c4> <ASCII (LEN-4)>
 * ````
 *
 * Beobachtete Inhalte:
 *  - Waehrend der Aufnahme: Live-Timer ".HH:MM:SS" (zaehlt 1 Hz hoch) -
 *    das ist DER verlaessliche Aufnahme-Indikator (das 0x02-Statuswort ist es
 *    laut Sniff nicht).
 *  - Idle alternierend: Modus/Aufloesung "4K|30|UW" und Akku-Runtime "13h09m".
 */
data class CameraDisplayState(
    /** Elapsed-Sekunden der laufenden Aufnahme, null = keine Aufnahme aktiv. */
    val recordingElapsedSeconds: Int? = null,
    /** Zuletzt gemeldeter Modus-String (z.B. "4K|30|UW"), null = unbekannt. */
    val modeString: String? = null,
    /** Zuletzt gemeldete Akku-Runtime der Kamera (z.B. "13h09m"), null = unbekannt. */
    val batteryRuntimeString: String? = null,
    /** Roher letzter Display-String (Diagnose). */
    val lastRaw: String? = null,
    /** Zeitpunkt (epoch ms) des letzten Display-Frames. */
    val updatedAtMillis: Long = 0L,
) {
    val isRecording: Boolean get() = recordingElapsedSeconds != null
}

/**
 * Speicher-Infos der Kamera (experimentell, Architektur B Query GetStorageInfo):
 * Payload laut xaionaro-go/insta360ctl: totalMB/freeMB/fileCount als uint32 LE.
 * Negative Werte = unbekannt.
 */
data class CameraStorageInfo(
    val totalMb: Long = -1,
    val freeMb: Long = -1,
    val fileCount: Long = -1,
    /** Zeitpunkt (epoch ms) der letzten erfolgreichen Abfrage. */
    val queriedAtMillis: Long = 0L,
)

/** Akku-Infos der Kamera (experimentell, Architektur B Query GetBatteryInfo). */
data class CameraBatteryInfo(
    val levelPercent: Int = -1,
    val voltageMv: Int = -1,
    /** Zeitpunkt (epoch ms) der letzten erfolgreichen Abfrage. */
    val queriedAtMillis: Long = 0L,
)

/** Formatierungshelfer fuer Kamera-Statuswerte (Notification + UI). */
object CameraStatusFormatter {

    /** Sekunden -> "HH:MM:SS". */
    fun formatRecTime(totalSeconds: Int?): String {
        if (totalSeconds == null || totalSeconds < 0) return "--:--:--"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** MB -> "12.3 GB". */
    fun formatGb(megabytes: Long): String =
        String.format(java.util.Locale.US, "%.1f GB", megabytes / 1024.0)
}