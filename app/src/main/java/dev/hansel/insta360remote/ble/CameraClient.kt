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
 * Architektur B (xaionaro-go/insta360ctl): Wir sind BLE-Central und verbinden
 * uns zum BE80-GATT-Server der Kamera. GPS-Injection per Cmd 0x35 UploadGPS
 * mit Header16-Format und lat/lon/alt als float64 LE.
 */
object CameraClient {

    private const val TAG = "CamClient"

    private var gatt: BluetoothGatt? = null
    private var connectingAddress: String? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var seq = 0

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var keepaliveRunning = false
    private var gpsStreamRunning = false

    @Volatile
    var pendingFix: dev.hansel.insta360remote.location.GpsFix? = null

    val isConnected: Boolean get() = gatt != null

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        if (gatt != null || connectingAddress == device.address) return
        connectingAddress = device.address
        Diagnostics.log(TAG, "VERBINDE als Central zu " + device.address)
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Diagnostics.log(TAG, "connectGatt fehlgeschlagen: " + e.message)
            gatt = null
            connectingAddress = null
        }
    }

    fun close() {
        callback.stopTimers()
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
                    if (!g.requestMtu(517)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Diagnostics.log(TAG, "Kamera-Verbindung getrennt (status=" + status + ")")
                    stopTimers()
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                    connectingAddress = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Diagnostics.log(TAG, "MTU: " + mtu)
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val be80 = java.util.UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
            val be81 = java.util.UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")
            val be82 = java.util.UUID.fromString("0000be82-0000-1000-8000-00805f9b34fb")
            val cccd = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

            writeCharacteristic = g.getService(be80)?.getCharacteristic(be81)
            val notifyChar = g.getService(be80)?.getCharacteristic(be82)
            if (writeCharacteristic == null || notifyChar == null) {
                Diagnostics.log(TAG, "WARNUNG: be80/be81/be82 auf der Kamera unvollstaendig!")
                return
            }

            g.setCharacteristicNotification(notifyChar, true)
            val desc = notifyChar.getDescriptor(cccd)
            if (desc == null) {
                Diagnostics.log(TAG, "WARNUNG: CCCD fehlt an be82")
                startTimers(g)
                return
            }
            desc.value = byteArrayOf(0x01, 0x00)
            val ok = try { g.writeDescriptor(desc) } catch (e: Exception) {
                Diagnostics.log(TAG, "CCCD-Write exception: " + e.message)
                false
            }
            Diagnostics.log(TAG, "CCCD-Write akzeptiert: " + ok)
            if (!ok) startTimers(g)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int,
        ) {
            Diagnostics.log(TAG, "CCCD geschrieben, status=" + status)
            // Reads + Sync + Timer erst nach Abschluss starten (sequenziell).
            handler.postDelayed({ startTimers(g) }, 1500)
        }

        private fun startTimers(g: BluetoothGatt) {
            // Sync-Handshake (Typ 06 + Magic syNceNdinS).
            val sync = ByteArray(20)
            sync[0] = 0x14
            sync[4] = 0x06
            "syNceNdinS".toByteArray(Charsets.US_ASCII).copyInto(sync, 7)
            writeToBe81(g, sync, "SYNC")

            // Keepalive (Typ 05) alle 2s.
            if (!keepaliveRunning) {
                keepaliveRunning = true
                Diagnostics.log(TAG, "Keepalive (Typ 05, 2s) aktiv")
                handler.postDelayed(object : Runnable {
                    override fun run() {
                        if (!keepaliveRunning || gatt == null) return
                        writeToBe81(
                            g,
                            byteArrayOf(0x07, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00),
                            null
                        )
                        handler.postDelayed(this, 2000)
                    }
                }, 0)
            }

            // GPS-Stream (Cmd 0x35 UploadGPS) alle 1s.
            if (!gpsStreamRunning) {
                gpsStreamRunning = true
                Diagnostics.log(TAG, "GPS-Stream (UploadGPS, 1s) aktiv")
                handler.postDelayed(object : Runnable {
                    override fun run() {
                        val fix = pendingFix ?: run {
                            handler.postDelayed(this, 1000); return
                        }
                        sendGpsFixInternal(g, fix)
                        handler.postDelayed(this, 1000)
                    }
                }, 500)
            }
        }

        fun stopTimers() {
            keepaliveRunning = false
            gpsStreamRunning = false
            handler.removeCallbacksAndMessages(null)
        }

        private fun writeToBe81(g: BluetoothGatt, data: ByteArray, label: String?) {
            val wc = writeCharacteristic ?: return
            try {
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                wc.value = data
                val ok = g.writeCharacteristic(wc)
                if (label != null) {
                    Diagnostics.log(TAG, label + " gesendet (" + Diagnostics.hex(data) + ") ok=" + ok)
                }
            } catch (e: Exception) {
                Diagnostics.log(TAG, "Write fehlgeschlagen: " + e.message)
            }
        }

        // -------------------------------------------------- Header16 + GPS

        private var gpsSeq = 0

        private fun nextSeq(): Byte {
            gpsSeq++
            if (gpsSeq == 0 || gpsSeq.toInt() == 255) gpsSeq = 1
            return gpsSeq.toByte()
        }

        /**
         * Header16 (X3/X4/X5 Architektur B):
         * [0..1] uint16 LE payload_len | [4] 0x04 | [7] cmd | [9] 0x02 |
         * [10] seq (1-254) | [13] 0x80 | payload ab Offset 16.
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
            for (i in 0 until 8) dst[offset + i] = ((bits ushr (8 * i)) and 0xFF).toByte()
        }

        private fun buildGpsFrame(lat: Double, lon: Double, alt: Double): ByteArray {
            val payload = ByteArray(24)
            putDoubleLE(payload, 0, lat)
            putDoubleLE(payload, 8, lon)
            putDoubleLE(payload, 16, alt)
            return buildHeader16Message(0x35, payload) // 0x35 = UploadGPS
        }

        @SuppressLint("MissingPermission")
        fun sendGpsFixInternal(g: BluetoothGatt, fix: dev.hansel.insta360remote.location.GpsFix): Boolean {
            if (fix.fixQuality == dev.hansel.insta360remote.location.GpsFix.FixQuality.NO_FIX) return false
            if (writeCharacteristic == null) return false
            val frame = buildGpsFrame(fix.latitude, fix.longitude, fix.altitudeMeters)
            try {
                writeCharacteristic!!.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                writeCharacteristic!!.value = frame
                val ok = g.writeCharacteristic(writeCharacteristic!!)
                if (ok) {
                    Diagnostics.log(
                        TAG,
                        "GPS gesendet: lat=" + fix.latitude + " lon=" + fix.longitude
                    )
                }
                return ok
            } catch (e: Exception) {
                Diagnostics.log(TAG, "GPS-Write fehlgeschlagen: " + e.message)
                return false
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Diagnostics.log(
                TAG,
                "READ " + characteristic.uuid + " status=" + status +
                    " value=" + Diagnostics.hex(characteristic.value)
            )
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            Diagnostics.log(
                TAG,
                "NOTIFY von Kamera " + characteristic.uuid + ": " + Diagnostics.hex(characteristic.value)
            )
        }
    }

    /** Sendet einen Fix per UploadGPS (Cmd 0x35) an den Kamera-Server. */
    fun sendGpsFix(fix: dev.hansel.insta360remote.location.GpsFix): Boolean {
        val g = gatt ?: return false
        return callback.sendGpsFixInternal(g, fix)
    }
}






