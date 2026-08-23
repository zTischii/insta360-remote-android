package dev.hansel.insta360remote.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.ParcelUuid
import java.util.UUID

/**
 * UUIDs des Insta360-GPS-Remotes.
 *
 * VERIFIZIERT gegen die funktionierende ESP32-Referenz
 * (github.com/pchwalek/insta360_ble_esp32, Insta_BLE.ino):
 *
 *   Hauptservice:      ce80 (-> 0000ce80-0000-1000-8000-00805f9b34fb)
 *     - ce81: WRITE    (Kamera -> Remote Kommandos)
 *     - ce82: NOTIFY   (Remote -> Kamera Status/GPS, mit CCCD 2902)
 *     - ce83: READ     (Wert 0x0201 - vermutliche Firmware-Version)
 *
 *   Sekundaerservice:  0000D0FF-3C17-D293-8E48-14FE2E4DA212
 *     - ffd1 WRITE, ffd2 READ, ffd3 READ (0x301e9001), ffd4 READ (0x18002001),
 *       ffd5 READ, ffd8 WRITE, fff1 READ, fff2 WRITE, ffe0 READ
 *
 *   Device-Name:       "Insta360 GPS Remote" (im Scan-Response!)
 *   Advertising:       BEIDE Service-UUIDs im Adv-Payload, Name im Scan-Response
 */
object Insta360Uuids {

    // 16-bit-Alias eingebettet in die Bluetooth-SIG-Basisadresse
    // z.B. "be80" -> "0000be80-0000-1000-8000-00805f9b34fb"
    private const val BASE = "0000%s-0000-1000-8000-00805f9b34fb"

    private fun uuid(shortHex: String): UUID =
        UUID.fromString(String.format(java.util.Locale.US, BASE, shortHex))

    /** Offizieller Name des originalen Remotes - die Kamera findet uns darueber. */
    const val REMOTE_DEVICE_NAME = "Insta360 GPS Remote"

    val SERVICE_UUID: UUID = uuid("ce80")
    val CHAR_WRITE_UUID: UUID = uuid("ce81")   // Kamera -> Remote (Kommandos)
    val CHAR_NOTIFY_UUID: UUID = uuid("ce82")  // Remote -> Kamera (Status/GPS, notify)
    val CHAR_EXTRA_UUID: UUID = uuid("ce83")   // READ, Wert 0x0201

    /** Sekundaerservice aus dem ESP32-Referenz-Sniff. */
    val SECONDARY_SERVICE_UUID: UUID =
        UUID.fromString("0000D0FF-3C17-D293-8E48-14FE2E4DA212")

    val SEC_FFD1_WRITE: UUID = uuid("ffd1")
    val SEC_FFD2_READ: UUID = uuid("ffd2")
    val SEC_FFD3_READ: UUID = uuid("ffd3")
    val SEC_FFD4_READ: UUID = uuid("ffd4")
    val SEC_FFD5_READ: UUID = uuid("ffd5")
    val SEC_FFD8_WRITE: UUID = uuid("ffd8")
    val SEC_FFF1_READ: UUID = uuid("fff1")
    val SEC_FFF2_WRITE: UUID = uuid("fff2")
    val SEC_FFE0_READ: UUID = uuid("ffe0")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)
    val SECONDARY_SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SECONDARY_SERVICE_UUID)

    private fun readChar(uuid: UUID, value: ByteArray): BluetoothGattCharacteristic =
        BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { this.value = value }

    /**
     * Baut beide GATT-Services exakt nach dem Vorbild des ESP32-Sketches.
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
        notifyChar.value = byteArrayOf(0x00)
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        notifyChar.addDescriptor(cccd)

        val extraChar = readChar(CHAR_EXTRA_UUID, byteArrayOf(0x01, 0x02)) // uint16 0x0201 LE

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)
        service.addCharacteristic(extraChar)
        return service
    }

    /** Sekundaerservice mit den Read-Werten aus dem ESP32-Referenzsketch. */
    fun buildSecondaryService(): BluetoothGattService {
        val service = BluetoothGattService(
            SECONDARY_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val ffd1 = BluetoothGattCharacteristic(
            SEC_FFD1_WRITE,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(ffd1)
        service.addCharacteristic(readChar(SEC_FFD2_READ, byteArrayOf(0x00)))
        // uint32-Werte little endian wie ESP32 setValue(uint32)
        service.addCharacteristic(readChar(SEC_FFD3_READ, byteArrayOf(0x01, 0x90.toByte(), 0x1e, 0x30)))
        service.addCharacteristic(readChar(SEC_FFD4_READ, byteArrayOf(0x01, 0x20, 0x00, 0x18)))
        service.addCharacteristic(readChar(SEC_FFD5_READ, byteArrayOf(0x00)))

        val ffd8 = BluetoothGattCharacteristic(
            SEC_FFD8_WRITE,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(ffd8)

        service.addCharacteristic(readChar(SEC_FFF1_READ, byteArrayOf(0x00)))

        val fff2 = BluetoothGattCharacteristic(
            SEC_FFF2_WRITE,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(fff2)

        service.addCharacteristic(readChar(SEC_FFE0_READ, byteArrayOf(0x00)))
        return service
    }
}
