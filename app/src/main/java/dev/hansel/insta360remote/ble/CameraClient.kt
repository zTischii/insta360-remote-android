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
                    Diagnostics.log(TAG, "Mit Kamera verbunden - starte Service-Discovery")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Diagnostics.log(TAG, "Kamera-Verbindung getrennt (status=$status)")
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                    connectingAddress = null
                }
            }
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

