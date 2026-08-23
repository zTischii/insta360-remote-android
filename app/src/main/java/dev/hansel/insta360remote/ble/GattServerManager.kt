package dev.hansel.insta360remote.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import dev.hansel.insta360remote.core.BleConnectionState
import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.core.ServiceStatus

/**
 * Verwaltet die BLE-Peripheral-Rolle: Advertising + GATT-Server.
 *
 * Die Kamera ist BLE-Central und verbindet sich mit uns (wie zum originalen
 * GPS-Remote). Wir senden GPS-Daten periodisch per Notify auf CHAR_NOTIFY_UUID
 * und empfangen Kommandos auf CHAR_WRITE_UUID.
 *
 * Reconnect-Strategie: Das Advertising bleibt dauerhaft aktiv; geht die Kamera
 * ausser Reichweite, trennt die Verbindung und verbindet sich beim Wiederkommen
 * erneut - wir muessen dafuer nur sicherstellen, dass das Advertising weiter-
 * laeuft (wird in onConnectionStateChange geprueft).
 */
class GattServerManager(
    private val context: Context,
    private val encoder: GpsPayloadEncoder,
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false

    /** Verbundene Centrals (normalerweise max. 1: die Kamera). */
    private val clients = LinkedHashSet<BluetoothDevice>()

    /** Devices, deren CCCD auf Notify gestellt wurde. */
    private val notifySubscribers = LinkedHashSet<BluetoothDevice>()

    private var negotiatedMtu = DEFAULT_MTU
    private val snCounter = Insta360Protocol.SequenceCounter()
    private val assembler = Insta360Protocol.FrameAssembler()

    /** Anzahl der Services, deren Registrierung wir abwarten (siehe start()). */
    private var pendingServiceAdds = 0
    private var confirmedServiceAdds = 0

    private val notifyCharacteristic: BluetoothGattCharacteristic?
        get() = gattServer?.services
            ?.firstOrNull { it.uuid == Insta360Uuids.SERVICE_UUID }
            ?.getCharacteristic(Insta360Uuids.CHAR_NOTIFY_UUID)

    // ------------------------------------------------------------ Start/Stop

    @android.annotation.SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasConnectPermission()) {
            Diagnostics.log(TAG, "BLUETOOTH_CONNECT nicht gewaehrt - Start abgebrochen")
            return false
        }
        // openGattServer liefert null, wenn Bluetooth aus ist - hier explizit pruefen.
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            Diagnostics.log(TAG, "Kein Bluetooth-Adapter vorhanden")
            return false
        }
        if (!adapter.isEnabled) {
            ServiceStatus.setBleState(BleConnectionState.BluetoothOff)
            Diagnostics.log(TAG, "BLUETOOTH IST AUS - bitte Bluetooth einschalten!")
            return false
        }
        return try {
            val server = bluetoothManager.openGattServer(context, serverCallback)
            if (server == null) {
                Diagnostics.log(TAG, "openGattServer lieferte null (Adapter bereit?)")
                return false
            }
            gattServer = server
            Diagnostics.log(TAG, "openGattServer erfolgreich - registriere 2 Services")
            // WICHTIG: Advertising erst starten, wenn ALLE Services vom Stack
            // bestaetigt sind (onServiceAdded). Sonst kann die Kamera waehrend
            // der Registrierung connecten, findet eine leere GATT-DB und
            // trennt sofort wieder.
            confirmedServiceAdds = 0
            pendingServiceAdds = 2
            server.addService(Insta360Uuids.buildService())
            server.addService(Insta360Uuids.buildSecondaryService())
            Diagnostics.log(TAG, "addService() fuer beide Services abgeschickt")

            // FALLBACK: Manche OEM-BT-Stacks dispatchen onServiceAdded nicht an
            // die App. Ohne Bestaetigung wuerde Advertising nie starten. Daher
            // nach 600 ms erzwungener Start: Die Services sind dann praktisch
            // registriert (die Stack-Bestätigungen kamen in allen Tests sofort).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (gattServer != null && !advertising &&
                    confirmedServiceAdds < pendingServiceAdds
                ) {
                    Diagnostics.log(
                        TAG,
                        "onServiceAdded blieb aus ($confirmedServiceAdds/$pendingServiceAdds)" +
                            " - starte Advertising nach Timeout"
                    )
                    confirmedServiceAdds = pendingServiceAdds
                    startAdvertising()
                    CameraScanner.start(context)
                    ServiceStatus.setBleState(BleConnectionState.Advertising)
                }
            }, 600)
            true
        } catch (e: Exception) {
            Diagnostics.log(TAG, "Start fehlgeschlagen: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertising = false
        CameraScanner.stop()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        synchronized(clients) { clients.clear() }
        synchronized(notifySubscribers) { notifySubscribers.clear() }
        assembler.reset()
        ServiceStatus.setBleState(BleConnectionState.Idle)
        Diagnostics.log(TAG, "GattServerManager gestoppt")
    }

    private fun hasConnectPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ Advertising

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAdvertising() {
        Diagnostics.log(TAG, "startAdvertising() aufgerufen (advertising=$advertising)")
        val adapter = bluetoothManager.adapter
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            Diagnostics.log(TAG, "BluetoothLeAdvertiser nicht verfuegbar")
            return
        }
        advertiser = adv

        // Geraetename wie das Original-Remote setzen - die Kamera findet uns
        // (zumindest teilweise) ueber den Namen "Insta360 GPS Remote".
        try {
            if (adapter.name != Insta360Uuids.REMOTE_DEVICE_NAME) {
                adapter.name = Insta360Uuids.REMOTE_DEVICE_NAME
                Diagnostics.log(TAG, "Bluetooth-Name gesetzt: ${Insta360Uuids.REMOTE_DEVICE_NAME}")
            }
        } catch (e: Exception) {
            Diagnostics.log(TAG, "Konnte Bluetooth-Namen nicht setzen: ${e.message}")
        }

        // ADVERTISE_MODE_BALANCED als Kompromiss: schnellere Connectbarkeit als
        // LOW_POWER, deutlich weniger Strom als LOW_LATENCY-Dauerbetrieb.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Adv-Paket wie das Original-Remote: Name + 16-bit UUID 0xBE80 (das ist
        // das UUID-Set, nach dem die Kamera filtert - per Pairing-Sniff des X4
        // verifiziert). Der 128-bit Sekundaerservice liegt im Scan-Response.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(Insta360Uuids.ADVERTISED_SERVICE_PARCEL_UUID)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(Insta360Uuids.SECONDARY_SERVICE_PARCEL_UUID)
            .build()

        adv.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        Diagnostics.log(TAG, "Advertising gestartet (${Insta360Uuids.SERVICE_UUID})")
    }

    private fun restartAdvertisingIfStopped() {
        if (!advertising) {
            Diagnostics.log(TAG, "Advertising war inaktiv - Neustart (Reconnect-Pfad)")
            startAdvertising()
        }
    }

    // ------------------------------------------------------------ GPS-Sendung

    /**
     * Sendet einen GPS-Fix an alle angemeldeten Kamera-Clients. Der Frame wird
     * in (MTU-3)-Byte-Brocken fragmentiert und je Brocken ein Notify ausgeloest
     * (confirm=false, um nicht auf Acks zu warten -> weniger CPU-Wachzeit).
     */
    /** true, sobald der erste "Charakteristik nicht bereit"-Fall geloggt wurde. */
    private var loggedMissingCharacteristic = false

    fun broadcastFix(fix: dev.hansel.insta360remote.location.GpsFix) {
        val characteristic = notifyCharacteristic
        if (characteristic == null) {
            if (!loggedMissingCharacteristic) {
                loggedMissingCharacteristic = true
                Diagnostics.log(TAG, "Notify-Charakteristik nicht bereit - Fixes werden verworfen (nur einmal gemeldet)")
            }
            return
        }
        val targets = synchronized(notifySubscribers) { notifySubscribers.toList() }
        if (targets.isEmpty()) return // Kamera noch nicht subscribed -> nichts senden

        loggedMissingCharacteristic = false
        val frame = encoder.encodeGpsUpdate(fix, snCounter.next())
        val chunks = Insta360Protocol.fragment(frame, negotiatedMtu - ATT_HEADER_SIZE)

        for (device in targets) {
            for (chunk in chunks) {
                characteristic.value = chunk
                val ok = try {
                    gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                } catch (e: Exception) {
                    Diagnostics.log(TAG, "notify fehlgeschlagen: ${e.message}")
                    false
                }
                if (!ok) break
            }
        }
        ServiceStatus.incrementNotifyCount(chunks.size.toLong())
    }

    // ------------------------------------------------------------ GATT-Callbacks

    @android.annotation.SuppressLint("MissingPermission")
    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService?) {
            Diagnostics.log(TAG, "onServiceAdded status=$status uuid=${service?.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS && service?.uuid != null) {
                confirmedServiceAdds++
                if (confirmedServiceAdds >= pendingServiceAdds && pendingServiceAdds > 0) {
                    Diagnostics.log(TAG, "Alle $confirmedServiceAdds Services registriert - starte Advertising")
                    startAdvertising()
                    CameraScanner.start(context)
                    ServiceStatus.setBleState(BleConnectionState.Advertising)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            negotiatedMtu = mtu.coerceAtLeast(DEFAULT_MTU)
            Diagnostics.log(TAG, "MTU ausgehandelt: $negotiatedMtu (Device=${device?.address})")
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(clients) { clients.add(device) }
                    negotiatedMtu = DEFAULT_MTU
                    ServiceStatus.setBleState(
                        BleConnectionState.Connected(device.name, address)
                    )
                    Diagnostics.log(TAG, "Kamera verbunden: $address name=${device.name}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(clients) { clients.remove(device) }
                    synchronized(notifySubscribers) { notifySubscribers.remove(device) }
                    assembler.reset()
                    val stillConnected = synchronized(clients) { clients.isNotEmpty() }
                    if (!stillConnected) {
                        ServiceStatus.setBleState(BleConnectionState.Advertising)
                        Diagnostics.log(TAG, "Kamera getrennt (status=$status) - warte auf Reconnect")
                        restartAdvertisingIfStopped()
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (device == null || value == null || characteristic == null) return
            ServiceStatus.markCameraPacket()

            when (characteristic.uuid) {
                Insta360Uuids.CHAR_WRITE_UUID -> handleCameraFrame(device, value)
                else -> Diagnostics.log(
                    TAG,
                    "Write an ${characteristic.uuid} (unbehandelt): ${Diagnostics.hex(value)}"
                )
            }

            if (responseNeeded) {
                sendResponse(device, requestId, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (device == null || descriptor == null || value == null) return

            if (descriptor.uuid == Insta360Uuids.CCCD_UUID &&
                descriptor.characteristic.uuid == Insta360Uuids.CHAR_NOTIFY_UUID
            ) {
                val enableNotifications = (value[0].toInt() and 0x01) != 0
                if (enableNotifications) {
                    synchronized(notifySubscribers) { notifySubscribers.add(device) }
                    Diagnostics.log(TAG, "Kamera hat Notifies aktiviert (${device.address})")
                } else {
                    synchronized(notifySubscribers) { notifySubscribers.remove(device) }
                    Diagnostics.log(TAG, "Kamera hat Notifies deaktiviert (${device.address})")
                }
            }

            if (responseNeeded) {
                sendResponse(device, requestId, value)
            }
        }
    }

    private fun sendResponse(device: BluetoothDevice, requestId: Int, value: ByteArray) {
        try {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        } catch (e: Exception) {
            Diagnostics.log(TAG, "sendResponse fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Fuehrt empfangene (ggf. ueber mehrere Write-Requests verteilte)
     * Kamera-Frames zusammen und loggt sie inkl. Hex-Dump - Basis fuer die
     * Protokollverifikation per HCI-Snoop (siehe README).
     */
    private fun handleCameraFrame(device: BluetoothDevice, chunk: ByteArray) {
        for (frame in assembler.feed(chunk)) {
            val commandId = if (frame.size >= 6) frame[5].toInt() and 0xFF else -1
            Diagnostics.log(
                TAG,
                "Frame von Kamera (${device.address}, cmd=0x%02X): %s"
                    .format(commandId, Diagnostics.hex(frame))
            )
        }
    }



    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Diagnostics.log(TAG, "Advertising aktiv")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Diagnostics.log(TAG, "Advertising fehlgeschlagen: $errorCode")
        }
    }

    companion object {
        private const val TAG = "GattServer"
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_SIZE = 3
    }
}

