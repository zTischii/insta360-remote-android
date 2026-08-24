package dev.hansel.insta360remote.ble

import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.location.GpsFix
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * VERIFIZIERTER GPS-Frame-Encoder fuer das Insta360 X4 (Architektur A, ce82-Notify).
 *
 * Quelle: TheAngryRaven/insta360-ble-gps-spec (Passiv-Sniff mit nRF52840 gegen eine
 * physische X4 + Original-GPS-Remote, Juli 2026) - konsistent mit den Kommando-
 * Frames aus tsunghowu/insta360_ble_rc_rpi_pico_w und pwchalk/insta360_ble_esp32.
 *
 * WICHTIG: Die ce82-Payload ist NICHT Protobuf (die fruehere "0x0A 0x35"-Hypothese
 * war falsch). Das echte Format ist ein HDLC-aehnlicher Frame um einen NMEA-RMC-
 * Satz, gestreamt mit ~10 Hz:
 *
 * ```
 * FC EF FE 83 00 <LEN>  ",26.7," <0x07> ","  $GNRMC,...*CS
 * └── Header (6) ─────┘ └─ Prefix (8) ──┘     └── NMEA ──────┘
 * ```
 *
 * - Header: Magic FC EF FE (remote->camera), Typ 0x83 (GPS/Sensor), B4=0x00,
 *   LEN = Laenge alles danach (~82-84, variierend mit den ASCII-Feldbreiten).
 * - Prefix: leerer Leading-Field-Komma, konstanter Wert "26.7" (in allen Captures
 *   identisch - vermutlich Temperatur bzw. Sensorwert), dann ein rohes Byte 0x07
 *   (konstant - vermutlich Satellite-Count/Fix-Indikator), dann Komma.
 * - NMEA: RMC-Satz mit Standard-NMEA-XOR-Checksumme (XOR aller Zeichen zwischen
 *   '$' und '*', zwei Hex-Digits, Grossbuchstaben).
 *
 * NMEA-Besonderheiten des Original-Remotes - BITTE EXAKT NACHBILDEN:
 *  - Laengengrad ist VORZEICHENBEHAFTET, Hemisphaeren-Buchstabe IMMER 'E'
 *    (West -> negative Zahl mit 'E', z.B. 74°W -> "-7400.0000,E").
 *  - Zwischen Mode-Zeichen und Checksumme steht ein zusaetzliches Feld 'V':
 *    "...,A,V*CS".
 *  - Koordinaten als ddmm.mmmm bzw. dddmm.mmmm (Grad + Dezimalminuten), NICHT
 *    Dezimalgrad.
 *  - Talker-ID "GN" ($GNRMC).
 *
 * Liveness-Regel (Spec §7.6): Der Strom darf NIEMALS verstummen, solange die
 * Verbindung steht. Ohne Fix weiter RMC-Frames mit Status 'V' (void) senden -
 * [encodeGpsUpdate] erzeugt fuer GpsFix.FixQuality.NO_FIX automatisch Void-Saetze.
 *
 * Arbeitbeispiel aus der Spec (40°00.0000'N, 74°00.0000'W):
 * ```
 * $GNRMC,120000.000,A,4000.0000,N,-7400.0000,E,0.00,0.00,010126,0.0,W,A,V*6E
 * FC EF FE 83 00 52 2C 32 36 2E 37 2C 07 2C 24 47 4E 52 4D 43 ...
 * ```
 */
class NmeaGpsFrameEncoder : GpsPayloadEncoder {

    override fun encodeGpsUpdate(fix: GpsFix, sn: Int): ByteArray {
        // SN wird bei GPS-Frames nicht verwendet (B4 bleibt 0x00, siehe Klassen-Doku);
        // der Parameter bleibt Teil des Interfaces fuer Button-/Kommando-Encoder.
        @Suppress("UNUSED_PARAMETER") sn
        return buildFrame(fix)
    }

    /**
     * Baut den kompletten ce82-Notify-Frame. Ohne Fix wird ein RMC-Voidsatz
     * (Status 'V') erzeugt - die Kamera soll den Datenstrom nie als verloren sehen.
     */
    fun buildFrame(fix: GpsFix, nowUtcMillis: Long = System.currentTimeMillis()): ByteArray {
        val nmea = if (fix.fixQuality == GpsFix.FixQuality.NO_FIX) {
            buildVoidSentence(nowUtcMillis)
        } else {
            buildActiveSentence(fix)
        }

        val prefix = PREFIX.toByteArray(Charsets.US_ASCII)
        val nmeaBytes = nmea.toByteArray(Charsets.US_ASCII)

        val frame = ByteArray(FRAME_HEADER_SIZE + prefix.size + nmeaBytes.size)
        frame[0] = 0xFC.toByte()
        frame[1] = 0xEF.toByte()
        frame[2] = 0xFE.toByte()
        frame[3] = TYPE_GPS
        frame[4] = 0x00
        frame[5] = (prefix.size + nmeaBytes.size).toByte()
        prefix.copyInto(frame, FRAME_HEADER_SIZE)
        nmeaBytes.copyInto(frame, FRAME_HEADER_SIZE + prefix.size)
        return frame
    }

    /** Aktiver Satz mit Status 'A'. */
    private fun buildActiveSentence(fix: GpsFix): String {
        val cal = utcCalendar(fix.utcEpochMillis)
        val time = formatUtcTime(cal)
        val date = formatUtcDate(cal)

        val ns = if (fix.latitude >= 0) "N" else "S"
        val speedKnots = fix.speedMps * MPS_TO_KNOTS

        val body = String.format(Locale.US, "\$GNRMC,%s,A,%s,%s,%s,E,%.2f,%.2f,%s,0.0,W,A,V",
            time,
            degMin(fix.latitude),
            ns,
            degMin(fix.longitude), // Vorzeichen behalten, Hemisphaere immer E
            speedKnots,
            fix.bearingDeg.toDouble(),
            date)
        return appendChecksum(body)
    }

    /** Void-Satz mit Status 'V' (kein Fix) - gleiche Feldanzahl wie aktiver Satz. */
    private fun buildVoidSentence(nowUtcMillis: Long): String {
        val cal = utcCalendar(nowUtcMillis)
        val body = String.format(Locale.US, "\$GNRMC,%s,V,,,,,,,%s,0.0,W,N,V",
            formatUtcTime(cal), formatUtcDate(cal))
        return appendChecksum(body)
    }

    private fun utcCalendar(epochMillis: Long): Calendar =
        Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply { timeInMillis = epochMillis }

    private fun formatUtcTime(cal: Calendar): String =
        String.format(Locale.US, "%02d%02d%02d.%03d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND), cal.get(Calendar.MILLISECOND))

    private fun formatUtcDate(cal: Calendar): String =
        String.format(Locale.US, "%02d%02d%02d",
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR) % 100)

    /**
     * Dezimalgrad -> ddmm.mmmm (Breite) / dddmm.mmmm (Laenge), Vorzeichen erhalten.
     * Pseudocode der Spec: d=trunc(|v|); m=(|v|-d)*60; sign(v)*(d*100+m).
     * Rundungs-Uebertrag abgefangen (z.B. 59.99995' -> naechste Gradminute).
     */
    internal fun degMin(value: Double): String {
        val a = kotlin.math.abs(value)
        var deg = kotlin.math.floor(a).toLong()
        val minUnits = Math.round((a - deg) * 60.0 * 10000.0) // 1/10000 Minute Aufloesung
        var d = deg
        var m = minUnits
        if (m >= 600000L) {
            d += 1
            m -= 600000L
        }
        val combined = d * 100 + m / 10000
        val frac = m % 10000
        val sign = if (value < 0) "-" else ""
        return String.format(Locale.US, "%s%d.%04d", sign, combined, frac)
    }

    /** Haengt "*XX" an, XX = XOR aller Zeichen nach dem fuehrenden '$'. */
    private fun appendChecksum(bodyWithDollar: String): String {
        var cs = 0
        for (i in 1 until bodyWithDollar.length) cs = cs xor bodyWithDollar[i].code
        return String.format(Locale.US, "%s*%02X", bodyWithDollar, cs)
    }

    companion object {
        private const val TAG = "NmeaEncoder"

        /** Typ-Byte GPS/Sensor im remote->camera-Frame. */
        const val TYPE_GPS: Byte = 0x83.toByte()

        /** Magic(3) + Type + B4 + LEN. */
        const val FRAME_HEADER_SIZE = 6

        /**
         * Konstanter Prefix aus allen Captures: leeres Feld, "26.7" (vermutlich
         * Temperatur), rohes Byte 0x07 (vermutlich Satellite-Count), Komma.
         * Gemaess Spec exakt uebernehmen und nicht "reparieren".
         */
        const val PREFIX = ",26.7,\u0007,"

        const val MPS_TO_KNOTS = 1.943844495

        /**
         * Baut die Manufacturer-Data des Wake-Beacons ("fake Apple iBeacon"),
         * OHNE Company-ID (Android addManufacturerData(0x004C, ...) liefert sie).
         *
         * Layout laut X4-Spec §1 (Gesamtpaket 31 Bytes, Flags als separates AD):
         *   02 15 | 09 'O''R''B''I''T' 09 FF 0F 00 | <SERIAL:6 ASCII> | 00 00 00 00 E4 01
         *
         * @param cameraSerial 6-stelliger Serial-Suffix der Kamera (z.B. "34UQG5")
         * @return 24 Bytes Manufacturer-Data oder null bei ungueltiger Serial
         */
        fun buildOrbitManufacturerData(cameraSerial: String?): ByteArray? {
            val s = cameraSerial?.trim()?.uppercase(Locale.US) ?: ""
            if (s.length != 6 || !s.all { it.isLetterOrDigit() }) return null

            val out = ByteArray(24)
            out[0] = 0x02
            out[1] = 0x15
            // "ORBIT" + feste Framing-Bytes (09 ... 09 FF 0F 00)
            byteArrayOf(
                0x09, 0x4F, 0x52, 0x42, 0x49, 0x54, 0x09, 0xFF.toByte(), 0x0F, 0x00
            ).copyInto(out, 2)
            // Serial als ASCII - Offset 14..19 der vollen Manufacturer-Data
            // (= Offset 12..17 hier, da die 2 Company-ID-Bytes fehlen).
            s.toByteArray(Charsets.US_ASCII).copyInto(out, 12)
            // iBeacon major/minor/TxPower - fix
            out[18] = 0x00; out[19] = 0x00; out[20] = 0x00; out[21] = 0x00
            out[22] = 0xE4.toByte(); out[23] = 0x01
            return out
        }

        /** Debug-Hilfe: vollstaendiger Wake-Beacon (mit Flags+Company-ID) als Hexstring. */
        fun debugWakeBeaconHex(cameraSerial: String): String? {
            val md = buildOrbitManufacturerData(cameraSerial) ?: return null
            val full = byteArrayOf(
                0x02, 0x01, 0x05, 0x1B, 0xFF.toByte(), 0x4C, 0x00
            ) + md
            return Diagnostics.hex(full)
        }
    }
}