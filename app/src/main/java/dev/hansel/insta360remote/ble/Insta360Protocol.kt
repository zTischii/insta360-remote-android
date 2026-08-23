package dev.hansel.insta360remote.ble

/**
 * Framing des Insta360-BLE-Protokolls.
 *
 * Bekannter Aufbau (aus Reverse-Engineering-Quellen):
 *   [1 Byte Laenge inkl. sich selbst][16 Bytes Kommandoblock][optionale Payload]
 *
 * Bei >20 Bytes wird in BLE-uebliche 20-Byte-Blöcke fragmentiert (bei
 * Standard-MTU 23 -> 20 nutzbare Payload-Bytes pro ATT-Write/Notify).
 *
 * ACHTUNG / UNVERIFIZIERT: Das exakte Layout des 16-Byte-Kommandoblocks ist in
 * den oeffentlichen Quellen nicht vollstaendig dokumentiert. Die hier verwendete
 * Interpretation (SN ab 0x0200, Command-ID, Payload-Laenge) basiert auf den
 * Sniffs aus pwchalk/insta360_ble_esp32 und MUSS per HCI-Snoop gegen echte
 * Hardware verifiziert werden. Alle relevanten Konstanten sind zentral gesammelt.
 */
object Insta360Protocol {

    /** Groesse des Kommandoblocks (ohne Laengenbyte, ohne Payload). */
    const val CMD_BLOCK_SIZE = 16

    /** Startwert des Sequenzzaehlers (SN). */
    const val INITIAL_SN = 0x0200

    // --- Command-IDs (UNVERIFIZIERT - aus ESP32-Projekt-Sniffs abgeleitet) ---
    const val CMD_GPS_DATA = 0x10        // Remote -> Kamera: GPS-Update
    const val CMD_BUTTON_EVENT = 0x15    // Remote -> Kamera: Shutter/Modus-Taste
    const val CMD_STATUS_ACK = 0x01      // generisches Ack

    /**
     * Inkrementeller Sequenzzaehler. Der SN beginnt laut Sniffs bei 0x0200
     * und laeuft dann monoton weiter (2 Bytes).
     */
    class SequenceCounter {
        private var sn = INITIAL_SN

        @Synchronized
        fun next(): Int {
            val current = sn
            sn = ((sn + 1) and 0xFFFF)
            return current
        }
    }

    /**
     * Baut einen 16-Byte-Kommandoblock.
     *
     * Layout (UNVERIFIZIERT, an Sniffs angelehnt):
     *   [0] 0x01          - Version/Marker
     *   [1] 0x10          - Blocktyp (16-Byte-Kommandoblock)
     *   [2..3] SN         - Sequenznummer, big endian
     *   [4] Command-ID
     *   [5..6] Payload-Laenge, big endian
     *   [7..15] reserviert / 0x00
     */
    fun buildCommandBlock(sn: Int, commandId: Int, payloadLength: Int): ByteArray {
        require(sn in 0..0xFFFF) { "SN out of range: $sn" }
        val block = ByteArray(CMD_BLOCK_SIZE)
        block[0] = 0x01
        block[1] = 0x10
        block[2] = ((sn shr 8) and 0xFF).toByte()
        block[3] = (sn and 0xFF).toByte()
        block[4] = commandId.toByte()
        block[5] = ((payloadLength shr 8) and 0xFF).toByte()
        block[6] = (payloadLength and 0xFF).toByte()
        // [7..15] bleiben 0x00
        return block
    }

    /**
     * Verpackt einen Kommandoblock (+ optionale Payload) in einen Frame:
     * erstes Byte = Gesamtlaenge inklusive sich selbst.
     */
    fun frame(commandBlock: ByteArray, payload: ByteArray? = null): ByteArray {
        require(commandBlock.size == CMD_BLOCK_SIZE)
        val total = 1 + commandBlock.size + (payload?.size ?: 0)
        require(total <= 255) { "Frame too large: $total bytes" }
        val out = ByteArray(total)
        out[0] = total.toByte()
        commandBlock.copyInto(out, 1)
        payload?.copyInto(out, 1 + commandBlock.size)
        return out
    }

    /**
     * Fragmentiert einen Frame in BLE-ATT-Nutzdatenbrocken.
     * chunkSize = MTU - 3 (ATT-Header), klassisch also 20.
     */
    fun fragment(data: ByteArray, chunkSize: Int): List<ByteArray> {
        if (data.size <= chunkSize) return listOf(data)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            chunks.add(data.copyOfRange(offset, offset + len))
            offset += len
        }
        return chunks
    }

    /**
     * Reassembliert eingehende, ggf. ueber mehrere Write-Requests verteilte
     * Frames (Laengenpraefix-basiert). Gibt vollstaendige Frames zurueck.
     */
    class FrameAssembler {

        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray): List<ByteArray> {
            buffer += chunk
            val frames = mutableListOf<ByteArray>()
            while (buffer.isNotEmpty()) {
                val declaredLength = buffer[0].toInt() and 0xFF
                if (declaredLength < 2 || declaredLength > buffer.size) break // unvollstaendig/ungueltig
                frames.add(buffer.copyOfRange(0, declaredLength))
                buffer = buffer.copyOfRange(declaredLength, buffer.size)
            }
            return frames
        }

        fun reset() { buffer = ByteArray(0) }
    }
}
