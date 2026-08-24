package dev.hansel.insta360remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.hansel.insta360remote.core.Diagnostics

/**
 * Experimenteller Central-Rollen-Client (Architektur B): Das X4 advertised im
 * Pairing-Modus selbst die 16-bit-UUID 0xBE80; wir verbinden uns als Central
 * und schreiben Header16-Kommandos auf be81.
 *
 * FORSCHUNGSSTAND (Aug 2026):
 *  - Die X-Serie nutzt das Header16-Format OHNE Sync-/Autorisierungs-Pflicht;
 *    Sync ("syNceNdinS") + CheckAuthorization sind laut xaionaro-go nur bei
 *    GO 2/GO 3 (FF-Frame-Format mit CRC) noetig - dort werden sie vor Commands
 *    ausgefuehrt. Fuer X3/X4/X5 ist kein oeffentlicher Beleg bekannt, dass GPS
 *    ueber be81/0x35 in .insv-Dateien eingebettet wird.
 *  - "status=0" im onCharacteristicWrite-Callback bei WRITE_TYPE_NO_RESPONSE
 *    bedeutet NUR, dass der Link-Layer das Paket gesendet hat - NICHT, dass die
 *    Kamera-Firmware den Befehl semantisch akzeptiert hat.
 *  - Der hier historisch gesendete 7-Byte-Keepalive (07 00 00 00 05 00 00) und
 *    die 20-Byte-Sync-Sequenz stammen aus dem WiFi/TCP-Protokoll (4-Byte-
 *    Laengenpraefix + 3-Byte-Pakettyp) und sind auf BLE bedeutungslos - sie
 *    stoeren aber nicht und bleiben zwecks Verhaltenskontinuitaet aktiv.
 *    Der verifizierte GPS-Pfad ist Architektur A (ce82-NMEA-Strom, siehe
 *    GattServerManager + NmeaGpsFrameEncoder).
 */
object CameraClient {

    private const val TAG = "CamClient"

    private var gatt: BluetoothGatt? = null
    private var connectingAddress: String? = null

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        if (gatt != null || connectingAddress == device.address) return
        connectingAddress = device.address
        Diagnostics.log(TAG, "VERBINDE als Central zu ${device.address} (${device.name})...")
        try {
            gatt = device.connectGatt(
                context, false, callback, BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            Diagnostics.log(TAG, "connectGatt fehlgeschlagen: ${e.message}")
            gatt = null
            connectingAddress = null
        }
    }

    fun close() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        connectingAddress = null
    }

    /** true, wenn ein Central-Link zur Kamera be80-Server besteht. */
    val isConnected: Boolean get() = gatt != null

    /** Sendet einen Fix per UploadGPS (Cmd 0x35) an den Kamera-Server. */
    fun sendGpsFix(fix: dev.hansel.insta360remote.location.GpsFix): Boolean =
        callback.sendGpsFix(fix)

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Diagnostics.log(TAG, "Mit Kamera verbunden - requestMtu(517)")
                    // Wie ESP32-Referenz: MTU-Anforderung vor der Discovery.
                    if (!g.requestMtu(517)) {
                        Diagnostics.log(TAG, "requestMtu abgelehnt - mache direkt Discovery")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Diagnostics.log(TAG, "Kamera-Verbindung getrennt (status=$status)")
                    stopKeepalive()
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                    connectingAddress = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Diagnostics.log(TAG, "MTU ausgehandelt: $mtu (status=$status)")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Diagnostics.log(TAG, "=== GATT-DATENBANK DER KAMERA (status=$status) ===")
            for (service in g.services) {
                Diagnostics.log(TAG, "SERVICE ${service.uuid}")
                for (c in service.characteristics) {
                    Diagnostics.log(
                        TAG,
                        "  CHAR ${c.uuid} [prop=${c.properties}] init=${Diagnostics.hex(c.value)}"
                    )
                }
            }
            Diagnostics.log(TAG, "=== ENDE GATT-DATENBANK ===")

            // Schritt 1: Nur das CCCD von be82 aktivieren. GATT-Operationen sind
            val be80Service = g.getService(java.util.UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb"))
            val be82 = be80Service?.getCharacteristic(
                java.util.UUID.fromString("0000be82-0000-1000-8000-00805f9b34fb")
            )
            if (be82 == null) {
                Diagnostics.log(TAG, "WARNUNG: be82 auf der Kamera nicht gefunden!")
                return
            }
            g.setCharacteristicNotification(be82, true)
            val cccd = be82.getDescriptor(
                java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            if (cccd == null) {
                Diagnostics.log(TAG, "WARNUNG: CCCD an be82 fehlt!")
                return
            }
            cccd.value = byteArrayOf(0x01, 0x00) // Notification enable
            val accepted = try { g.writeDescriptor(cccd) } catch (e: Exception) {
                Diagnostics.log(TAG, "writeDescriptor exception: ${e.message}")
                false
            }
            Diagnostics.log(TAG, "CCCD-Write fuer be82 akzeptiert: $accepted")
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int,
        ) {
            Diagnostics.log(
                TAG,
                "CCCD geschrieben (${descriptor.characteristic.uuid}) status=$status"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // Schritt 2: Jetzt alle lesbaren Charakteristiken der Kamera lesen.
            for (service in g.services) {
                for (c in service.characteristics) {
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                        c.uuid != java.util.UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
                    ) {
                        try { g.readCharacteristic(c) } catch (_: Exception) {}
                    }
                }
            }
            Diagnostics.log(TAG, "Alle Read-Versuche abgeschickt - Kamera-Link vollstaendig aufgebaut")

            // Schritt 3: GPS-Stream starten (Cmd 0x35 UploadGPS alle 1s).
            startGpsStream(g)

            // Schritt 3: Sync-Handshake (Typ 06 + Magic "syNceNdinS", gemaess
            // GO-Ultra-Protokoll-Doku) und danach 2s-Keepalive starten.
            writeCharacteristic = g.getService(
                java.util.UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
            )?.getCharacteristic(
                java.util.UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")
            )
            if (writeCharacteristic != null) {
                val syncFrame = ByteArray(20)
                syncFrame[0] = 0x14 // len = 20
                syncFrame[4] = 0x06 // Type: Sync
                "syNceNdinS".toByteArray(Charsets.US_ASCII).copyInto(syncFrame, 7)
                try {
                    writeCharacteristic!!.writeType =
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    writeCharacteristic!!.value = syncFrame
                    val ok = g.writeCharacteristic(writeCharacteristic!!)
                    Diagnostics.log(
                        TAG,
                        "SYNC gesendet (" + Diagnostics.hex(syncFrame) + ") ok=" + ok
                    )
                } catch (e: Exception) {
                    Diagnostics.log(TAG, "Sync-Write fehlgeschlagen: ${e.message}")
                }
                startKeepalive(g)
            } else {
                Diagnostics.log(TAG, "WARNUNG: be81 (write) nicht gefunden!")
            }
        }

        // ------------------------------------------------------------ Kommandos

        private var writeCharacteristic: BluetoothGattCharacteristic? = null

        /** SN-Zaehler, Startwert 5120 (0x1400) gemaess ESP32-Referenz. */
        private var sn = 5120

        /**
         * Exakte Replik von create_cmd() aus dem funktionierenden One-X2-Remote:
         * [total_len][00 00 00][mode][00 00][c1][00][sn_hi][sn_lo][00 00 80 00 00]
         * [protobuf-payload ab Offset 16]
         */
        private fun buildCmd(mode: Int, c1: Int?, payload: ByteArray?): ByteArray {
            val pb = payload ?: ByteArray(0)
            val cmd = ByteArray(16 + pb.size)
            cmd[4] = mode.toByte()
            var len = 7
            if (c1 != null && c1 != 0xFF) {
                cmd[7] = c1.toByte()
                cmd[8] = 0
                cmd[9] = ((sn shr 8) and 0xFF).toByte()
                cmd[10] = (sn and 0xFF).toByte()
                sn++
                cmd[11] = 0x00
                cmd[12] = 0
                cmd[13] = 0x80.toByte()
                cmd[14] = 0
                cmd[15] = 0
                len += 9
            }
            pb.copyInto(cmd, 16)
            cmd[0] = (len + pb.size).toByte()
            return cmd.copyOf(len + pb.size)
        }

        private val keepaliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var keepaliveRunning = false

        /** BLE-Keepalive-Frame wie er uns vom X4 selbst zugesendet wurde. */
        private val KEEPALIVE_FRAME = byteArrayOf(
            0x07, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00
        )

        @SuppressLint("MissingPermission")
        private fun startKeepalive(g: BluetoothGatt) {
            if (keepaliveRunning) return
            keepaliveRunning = true
            Diagnostics.log(TAG, "Starte 2s-Keepalive (Typ 05) auf be81")
            val tick = object : Runnable {
                override fun run() {
                    val wc = writeCharacteristic
                    if (!keepaliveRunning || wc == null) return
                    try {
                        wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        wc.value = KEEPALIVE_FRAME
                        val ok = g.writeCharacteristic(wc)
                        if (!ok) Diagnostics.log(TAG, "keepalive write lieferte false")
                    } catch (e: Exception) {
                        Diagnostics.log(TAG, "Keepalive-Write fehlgeschlagen: ${e.message}")
                    }
                    keepaliveHandler.postDelayed(this, 2000)
                }
            }
            keepaliveHandler.post(tick)
        }

        /** Status-Poll (Cmd-ID 15 = GetCurrentCaptureStatus), fuer Experimente. */
        @SuppressLint("MissingPermission")
        fun sendStatusPoll() {
            val wc = writeCharacteristic ?: return
            val g = gatt ?: return
            try {
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                wc.value = buildCmd(0x04, 0x0F, null)
                g.writeCharacteristic(wc)
            } catch (_: Exception) {}
        }

        // -------------------------------------------------- GPS-Injection
        // Header16-Cmd 0x35 (CodeUploadGPS) - auf GO 2/GO 3 verifiziert
        // (xaionaro-go/insta360ctl pkg/direct/gps.go), dort Payload = 3 x float64
        // LE (lat, lon, alt). Fuer die X-Serie unbestaetigt; wir senden es
        // weiterhin als Experiment, verlassen uns aber primaer auf Architektur A.
        // Alternativ in der Literatur: striktes Protobuf UploadGps{bytes gps=1}
        // mit innerem GPS{double lon=1, lat=2, alt=3} (insta360-wifi-api) -
        // ebenfalls ohne X4-Erfolgsbeleg.

        private var gpsSeq = 0

        private fun nextSeq(): Byte {
            gpsSeq++
            if (gpsSeq == 0 || gpsSeq.toInt() == 255) gpsSeq = 1
            return gpsSeq.toByte()
        }

        /**
         * Header16-Nachricht (X3/X4/X5-Architektur B):
         * [0..1] uint16 LE payload_length (ohne Header)
         * [4]    0x04 (Mode)
         * [7]    Command code
         * [9]    0x02 (Content type protobuf)
         * [10]   Sequence number (1-254)
         * [13]   0x80 (is_last_fragment)
         */
        private fun buildHeader16Message(cmd: Int, payload: ByteArray): ByteArray {
            val msg = ByteArray(16 + payload.size)
            msg[0] = (payload.size and 0xFF).toByte()
            msg[1] = ((payload.size shr 8) and 0xFF).toByte()
            msg[4] = 0x04
            msg[7] = cmd.toByte()
            msg[9] = 0x02
            msg[10] = nextSeq()
            msg[13] = 0x80.toByte()
            payload.copyInto(msg, 16)
            return msg
        }

        private fun putDoubleLE(dst: ByteArray, offset: Int, value: Double) {
            val bits = java.lang.Double.doubleToRawLongBits(value)
            for (i in 0 until 8) {
                dst[offset + i] = ((bits ushr (8 * i)) and 0xFF).toByte()
            }
        }

        /** 
         * GPS-Frame: Cmd 0x35 (UploadGPS).
         * Fuer X4 verwenden wir das strikte Protobuf-Format gem. insta360-wifi-api:
         * message UploadGps { bytes gps = 1; }
         * message GPS { double longitude = 1; double latitude = 2; double altitude = 3; }
         */
        fun buildGpsFrame(lat: Double, lon: Double, alt: Double): ByteArray {
            val payload = ByteArray(29)
            // Outer UploadGps message (tag=1, wire_type=2 -> 0x0A)
            payload[0] = 0x0A
            // Length of inner GPS message
            payload[1] = 27.toByte() 

            // Inner GPS message
            // Field 1 (longitude): tag=1, wire_type=1 (double) -> 0x09
            payload[2] = 0x09
            putDoubleLE(payload, 3, lon)
            
            // Field 2 (latitude): tag=2, wire_type=1 (double) -> 0x11
            payload[11] = 0x11
            putDoubleLE(payload, 12, lat)
            
            // Field 3 (altitude): tag=3, wire_type=1 (double) -> 0x19
            payload[20] = 0x19
            putDoubleLE(payload, 21, alt)

            return buildHeader16Message(0x35, payload)
        }

        /** Letzter Fix, der an die Kamera gestreamt werden soll. */
        @Volatile
        var pendingFix: dev.hansel.insta360remote.location.GpsFix? = null

        /**
         * Sendet den uebergebenen Fix als UploadGPS-Kommando an die Kamera
         * (Modell B: wir sind Central, Kamera-GATT-Server hostet be81).
         */
        @SuppressLint("MissingPermission")
        fun sendGpsFix(fix: dev.hansel.insta360remote.location.GpsFix): Boolean {
            val wc = writeCharacteristic ?: return false
            val g = gatt ?: return false
            if (fix.fixQuality == dev.hansel.insta360remote.location.GpsFix.FixQuality.NO_FIX) {
                return false // Kein gueltiger Fix - nichts senden.
            }
            return try {
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                wc.value = buildGpsFrame(fix.latitude, fix.longitude, fix.altitudeMeters)
                val ok = g.writeCharacteristic(wc)
                if (ok) {
                    Diagnostics.log(
                        TAG,
                        "GPS gesendet: lat=" + fix.latitude + " lon=" + fix.longitude +
                            " alt=" + fix.altitudeMeters
                    )
                }
                ok
            } catch (e: Exception) {
                Diagnostics.log(TAG, "GPS-Write fehlgeschlagen: ${e.message}")
                false
            }
        }

        @SuppressLint("MissingPermission")
        private fun startGpsStream(g: BluetoothGatt) {
            Diagnostics.log(TAG, "Starte GPS-Stream (Cmd 0x35 UploadGPS) auf be81")
            val tick = object : Runnable {
                override fun run() {
                    val fix = pendingFix ?: return
                    sendGpsFix(fix)
                    keepaliveHandler.postDelayed(this, 1000)
                }
            }
            keepaliveHandler.post(tick)
        }

        private fun stopKeepalive() {
            keepaliveRunning = false
            keepaliveHandler.removeCallbacksAndMessages(null)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Diagnostics.log(
                TAG,
                "READ ${characteristic.uuid} status=$status value=${Diagnostics.hex(characteristic.value)}"
            )
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            Diagnostics.log(
                TAG,
                "NOTIFY von Kamera ${characteristic.uuid}: ${Diagnostics.hex(characteristic.value)}"
            )
        }
    }
}

