package dev.hansel.insta360remote.ble

import dev.hansel.insta360remote.core.Diagnostics

/**
 * Parser fuer ce81-Display-Strings der Kamera (Frame-Typ 0x10),
 * Format gemaess X4-Spec §6 (TheAngryRaven/insta360-ble-gps-spec):
 *
 * ```
 * FE EF FE 10 <flag> <LEN> <c1> <c2> <c3> <c4> <ASCII (LEN-4 Bytes)>
 * ```
 *
 * Beispiele aus dem Sniff:
 * ```
 * FE EF FE 10 81 0C 01 1C 5E 00 "4K|30|UW"   (Idle: Modus/Aufloesung)
 * FE EF FE 10 80 0B 01 12 46 01 " 13h09m"     (Idle: Akku-Runtime)
 * FE EF FE 10 80 0D 01 0E 46 01 ".00:00:05"   (Recording, t = 5 s)
 * ```
 *
 * Die Bedeutung der 4 Steuerbytes (c1..c4) ist nicht vollstaendig geklaert;
 * wir klassifizieren daher bewusst ueber den INHALT des ASCII-Strings:
 *  - Timer-Muster `[.:]?HH:MM:SS`  -> Aufnahme aktiv (verlaesslicher Indikator!)
 *  - Muster `NNhNNm`               -> Akku-Runtime
 *  - enthaelt '|'                  -> Modus/Aufloesungs-String
 *  - sonst                         -> sonstiger Display-Text (Diagnose)
 */
object CameraDisplayParser {

    private const val TAG = "CamDisplay"

    /** Art eines Display-Strings. */
    enum class Kind { RECORDING_TIMER, BATTERY_RUNTIME, MODE, OTHER }

    data class ParsedFrame(
        val kind: Kind,
        val text: String,
        /** Nur bei RECORDING_TIMER: verstrichene Sekunden. */
        val elapsedSeconds: Int?,
    )

    private val recTimeRegex = Regex("^[.:]?(\\d{2}):(\\d{2}):(\\d{2})$")
    private val runtimeRegex = Regex("^\\s*(\\d+)h(\\d+)m\\s*$")

    /**
     * Parst einen kompletten ce81-Chunk. Liefert null, wenn der Chunk kein
     * wohlgeformter 0x10-Display-Frame ist.
     */
    fun parse(chunk: ByteArray): ParsedFrame? {
        // Mindestlaenge: Magic(3)+Type+B4+LEN+Ctrl(4)+min. 1 ASCII-Zeichen
        if (chunk.size < 11) return null
        if (chunk[0] != 0xFE.toByte() || chunk[1] != 0xEF.toByte() ||
            chunk[2] != 0xFE.toByte()
        ) {
            return null
        }
        if ((chunk[3].toInt() and 0xFF) != TYPE_DISPLAY_STRING) return null

        val len = chunk[5].toInt() and 0xFF
        if (len < 5) return null // Ctrl(4) + mind. 1 Zeichen
        val strLen = len - 4
        val start = 6 + 4 // nach LEN + 4 Ctrl-Bytes
        val end = minOf(start + strLen, chunk.size)
        if (end <= start) return null

        val text = runCatching {
            String(chunk, start, end - start, Charsets.US_ASCII)
        }.getOrNull() ?: return null

        return classify(text)
    }

    /** Inhaltbasierte Klassifikation eines Display-Strings. */
    fun classify(text: String): ParsedFrame {
        recTimeRegex.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val s = m.groupValues[3].toIntOrNull() ?: 0
            return ParsedFrame(
                Kind.RECORDING_TIMER,
                text,
                elapsedSeconds = h * 3600 + min * 60 + s,
            )
        }
        runtimeRegex.find(text)?.let { return ParsedFrame(Kind.BATTERY_RUNTIME, text, null) }
        if (text.contains('|')) return ParsedFrame(Kind.MODE, text, null)
        return ParsedFrame(Kind.OTHER, text, null)
    }

    /** Debug-Hilfe: Hexdump eines nicht parsebaren Frames loggen. */
    fun logUnparseable(chunk: ByteArray) {
        Diagnostics.log(TAG, "0x10 nicht parsebar: " + Diagnostics.hex(chunk))
    }

    /** Frame-Typ Display-String (Kamera -> Remote, ce81). */
    const val TYPE_DISPLAY_STRING: Int = 0x10
}