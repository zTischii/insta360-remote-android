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
 * Raw-Binary-GPS-Encoder (HISTORISCH / UNGUELTIG).
 *
 * Dies war ein Best-Guess auf Basis eines fehlinterpretierten Sniffs und hat
 * sich als FALSCH herausgestellt: Die Kamera erwartet auf ce82 kein
 * Laengenbyte+Kommandoblock+Protobuf, sondern FC-EF-FE-83-Frames mit
 * NMEA-RMC-Saetzen ([NmeaGpsFrameEncoder]). Diese Klasse bleibt nur als
 * Dokumentation des Irrwegs im Code.
 *
 * Der Kommandoblock verwendet CMD_GPS_DATA (0x35).
 */
class BestGuessGpsPayloadEncoder : GpsPayloadEncoder {

    override fun encodeGpsUpdate(fix: GpsFix, sn: Int): ByteArray {
        // Payload: 8+8+8 + 8 + 4 + 4 + 1 + 1 = 42 bytes
        val payload = ByteArray(42)
        putF64(payload,  0, fix.latitude)
        putF64(payload,  8, fix.longitude)
        putF64(payload, 16, fix.altitudeMeters)
        putI64(payload, 24, fix.utcEpochMillis)
        putF32(payload, 32, fix.speedMps)
        putF32(payload, 36, fix.bearingDeg)
        payload[40] = when (fix.fixQuality) {
            GpsFix.FixQuality.GPS_FIX      -> 1
            GpsFix.FixQuality.DIFFERENTIAL -> 2
            GpsFix.FixQuality.RTK          -> 4
            else                           -> 0
        }.toByte()
        payload[41] = fix.satelliteCount.coerceIn(0, 255).toByte()

        val block = Insta360Protocol.buildCommandBlock(
            sn = sn,
            commandId = Insta360Protocol.CMD_GPS_DATA,
            payloadLength = payload.size
        )
        val frame = Insta360Protocol.frame(block, payload)
        Diagnostics.log(TAG, "GPS frame (sn=$sn lat=${fix.latitude} lon=${fix.longitude} qual=${fix.fixQuality}): ${Diagnostics.hex(frame)}")
        return frame
    }

    private fun putF64(dst: ByteArray, off: Int, v: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        for (i in 0 until 8) dst[off + i] = ((bits ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putI64(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putF32(dst: ByteArray, off: Int, v: Float) {
        val bits = java.lang.Float.floatToRawIntBits(v)
        for (i in 0 until 4) dst[off + i] = ((bits ushr (8 * i)) and 0xFF).toByte()
    }

    companion object {
        private const val TAG = "GpsEncoder"
    }
}
