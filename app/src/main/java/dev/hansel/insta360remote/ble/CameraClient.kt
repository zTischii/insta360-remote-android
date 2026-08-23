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
 * Experimenteller Central-Rollen-Client: Das X4 advertised im Pairing-Modus
 * selbst die 16-bit-UUID 0xBE80. Moegliches Protokoll-Design: Das REMOTE ist
 * beim Pairing der Central und verbindet sich zur Kamera. Diese Klasse baut
 * diese Verbindung auf und protokolliert die komplette GATT-Datenbank der
 * Kamera - damit sehen wir exakt, welche Dienste die Kamera anbietet.
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
            // strikt sequenziell - die Reads folgen im onDescriptorWrite-Callback.
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

            // Schritt 3: be81-Write-Charakteristik merken und Keepalive starten
            // (Status-Poll 0x04/0x0F im 1-Hz-Takt wie die ESP32-Referenz).
            writeCharacteristic = g.getService(
                java.util.UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
            )?.getCharacteristic(
                java.util.UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")
            )
            if (writeCharacteristic != null) {
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

        @SuppressLint("MissingPermission")
        private fun startKeepalive(g: BluetoothGatt) {
            if (keepaliveRunning) return
            keepaliveRunning = true
            Diagnostics.log(TAG, "Starte 1Hz-Status-Poll (0x04/0x0F) auf be81")
            val tick = object : Runnable {
                override fun run() {
                    val wc = writeCharacteristic
                    if (!keepaliveRunning || wc == null) return
                    try {
                        wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        wc.value = buildCmd(0x04, 0x0F, null)
                        val ok = g.writeCharacteristic(wc)
                        if (!ok) Diagnostics.log(TAG, "writeCharacteristic lieferte false")
                    } catch (e: Exception) {
                        Diagnostics.log(TAG, "Keepalive-Write fehlgeschlagen: ${e.message}")
                    }
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

