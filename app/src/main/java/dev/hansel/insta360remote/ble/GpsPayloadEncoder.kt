package dev.hansel.insta360remote.ble

import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.location.GpsFix

/**
 * Austauschbare Komponente fuer das (noch zu verifizierende) GPS-Payload-Format.
 *
 * Die Kamera erwartet die GPS-Daten als Notify auf CHAR_NOTIFY_UUID. Das exakte
 * Format ist in den oeffentlichen Reverse-Engineering-Quellen NICHT vollstaendig
 * dokumentiert. Sobald per HCI-Snoop-Log / nRF Connect das echte Format eines
 * originalen Insta360-GPS-Remotes ermittelt ist, kann hier eine zweite
 * [GpsPayloadEncoder]-Implementierung ergaenzt und in GattServerManager
 * ausgetauscht werden - ohne Aenderungen am Rest der App.
 */
interface GpsPayloadEncoder {

    /**
     * Erzeugt einen vollstaendigen, unfragmentierten Frame
     * (Laengenbyte + 16-Byte-Kommandoblock + Payload) fuer einen GPS-Update-Notify.
     */
    fun encodeGpsUpdate(fix: GpsFix, sn: Int): ByteArray
}

/**
 * Minimaler handgeschriebener Protobuf-Wire-Format-Encoder (keine externe Lib).
 * Erzeugt nur die Payload; der Frame wird vom Encoder drumherum gebaut.
 */
object MiniProtoWriter {

    fun writeVarint(out: ArrayList<Byte>, value: Long) {
        var v = value
        while (v and -0x80L != 0L) {          // solange v > 127
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
    }

    private fun tag(fieldNumber: Int, wireType: Int): Int = (fieldNumber shl 3) or wireType

    /** int32/int64-Feld (varint). */
    fun varintField(out: ArrayList<Byte>, fieldNumber: Int, value: Long) {
        writeVarint(out, tag(fieldNumber, 0).toLong())
        writeVarint(out, value)
    }

    /** double-Feld (fixed64 little endian). */
    fun doubleField(out: ArrayList<Byte>, fieldNumber: Int, value: Double) {
        writeVarint(out, tag(fieldNumber, 1).toLong())
        val bits = java.lang.Double.doubleToRawLongBits(value)
        repeat(8) { i -> out.add((bits ushr (8 * i)).toByte()) }
    }

    /** float-Feld (fixed32 little endian). */
    fun floatField(out: ArrayList<Byte>, fieldNumber: Int, value: Float) {
        writeVarint(out, tag(fieldNumber, 5).toLong())
        val bits = java.lang.Float.floatToRawIntBits(value)
        repeat(4) { i -> out.add((bits ushr (8 * i)).toByte()) }
    }

    /** length-delimited-Feld. */
    fun bytesField(out: ArrayList<Byte>, fieldNumber: Int, value: ByteArray) {
        writeVarint(out, tag(fieldNumber, 2).toLong())
        writeVarint(out, value.size.toLong())
        for (b in value) out.add(b)
    }

    fun toByteArray(out: ArrayList<Byte>): ByteArray = out.toByteArray()
}

/**
 * BEST-GUESS-Implementierung des GPS-Payloads.
 *
 * Annahme (UNVERIFIZIERT!): Die Kamera erwartet die GPS-Daten als protobuf-
 * kodierte Nachricht hinter dem Kommandoblock, aehnlich den bekannten
 * Kommando-Nachrichten. Diese Implementierung nutzt:
 *
 *   Field 1: latitude          (double)
 *   Field 2: longitude         (double)
 *   Field 3: altitude          (double)
 *   Field 4: speed             (float)
 *   Field 5: timestamp UTC ms  (uint64)
 *   Field 6: fix quality       (uint32)
 *
 * VERIFIKATIONSPFAD: Mit einem originalen GPS-Remote + HCI-Snoop-Log
 * (Developer Options -> Bluetooth HCI snoop log) bzw. nRF Connect die Notify-
 * Pakete mitschneiden, gegen diese Struktur vergleichen und anschliessend
 * entweder die Feldnummern/-typen hier korrigieren oder eine neue
 * GpsPayloadEncoder-Implementierung registrieren.
 */
class BestGuessGpsPayloadEncoder : GpsPayloadEncoder {

    override fun encodeGpsUpdate(fix: GpsFix, sn: Int): ByteArray {
        val payload = ArrayList<Byte>(64)
        with(MiniProtoWriter) {
            doubleField(payload, 1, fix.latitude)
            doubleField(payload, 2, fix.longitude)
            doubleField(payload, 3, fix.altitudeMeters)
            floatField(payload, 4, fix.speedMps)
            varintField(payload, 5, fix.utcEpochMillis)
            varintField(payload, 6, fix.fixQuality.ordinal.toLong())
        }
        val block = Insta360Protocol.buildCommandBlock(
            sn = sn,
            commandId = Insta360Protocol.CMD_GPS_DATA,
            payloadLength = payload.size
        )
        val frame = Insta360Protocol.frame(block, MiniProtoWriter.toByteArray(payload))
        Diagnostics.log(TAG, "GPS frame (sn=$sn): ${Diagnostics.hex(frame)}")
        return frame
    }

    companion object {
        private const val TAG = "GpsEncoder"
    }
}
