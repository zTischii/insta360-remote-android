package dev.hansel.insta360remote.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.ParcelUuid
import java.util.UUID

/**
 * UUIDs des Insta360-GPS-Remotes.
 *
 * VERIFIZIERUNGS-HINWEIS: In den oeffentlich verfuegbaren Reverse-Engineering-
 * Quellen (pchwalek/insta360_ble_esp32, Hackaday "Insta360 X3 BLE remote control
 * with ESP32") kursieren ZWEI UUID-Sets:
 *
 *   Set A ("be8x"): 0000be80-0000-1000-8000-00805f9b34fb / be81 / be82 / be83
 *   Set B ("ce8x"): 0000ce80-0000-1000-8000-00805f9b34fb / ce81 / ce82 / ce83
 *
 * Bevor die Emulation gegen echte Hardware getestet wird, MUSS per HCI-Snoop-Log
 * oder nRF Connect geprueft werden, welches Set die eigene Kamera-Firmware im
 * Scan des originalen Remotes advertised. Der aktive Satz ist hier zentral
 * umschaltbar (ACTIVE_SET), damit kein Code an anderen Stellen angepasst werden muss.
 */
object Insta360Uuids {

    // 16-bit-Alias eingebettet in die Bluetooth-SIG-Basisadresse
    // z.B. "be80" -> "0000be80-0000-1000-8000-00805f9b34fb"
    private const val BASE = "0000%s-0000-1000-8000-00805f9b34fb"

    private fun uuid(shortHex: String): UUID =
        UUID.fromString(String.format(java.util.Locale.US, BASE, shortHex))

    // --- Aktives UUID-Set (per HCI-Sniff verifizieren!) ---
    @Volatile
    var activeSet: String = "be"   // "be" | "ce"
        set(value) {
            field = if (value == "ce") "ce" else "be"
        }

    val SERVICE_UUID: UUID get() = uuid("${activeSet}80")
    val CHAR_WRITE_UUID: UUID get() = uuid("${activeSet}81")   // Kamera -> Remote (Kommandos)
    val CHAR_NOTIFY_UUID: UUID get() = uuid("${activeSet}82")  // Remote -> Kamera (Status/GPS, notify)
    val CHAR_EXTRA_UUID: UUID get() = uuid("${activeSet}83")   // Sekundaercharakteristik

    /** Sekundaerer Service, der in einigen Sniffs neben dem Hauptservice auftauchte. */
    val SECONDARY_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val SERVICE_PARCEL_UUID: ParcelUuid get() = ParcelUuid(SERVICE_UUID)

    /**
     * Baut den GATT-Service mit allen Charakteristiken nach.
     */
    fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val writeChar = BluetoothGattCharacteristic(
            CHAR_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyChar = BluetoothGattCharacteristic(
            CHAR_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        notifyChar.addDescriptor(cccd)

        val extraChar = BluetoothGattCharacteristic(
            CHAR_EXTRA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)
        service.addCharacteristic(extraChar)
        return service
    }
}
