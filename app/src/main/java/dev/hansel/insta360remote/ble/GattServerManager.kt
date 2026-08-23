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
 * BLE-Peripheral-Rolle: Advertising + GATT-Server.
 *
 * Live-Erkenntnisse X4: Der OEM-Stack stellt onServiceAdded/
 * onDescriptorWriteRequest teils NICHT an die App zu. Daher eigene
 * Characteristic-Referenzen, Advertising per Timeout-Fallback und Broadcasts
 * an ALLE verbundenen Geraete. Die Kamera trennt nach ca. 30s ohne
 * Datenstrom -> letzten Fix alle 2s wiederholen.
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

    private val clients = LinkedHashSet<BluetoothDevice>()

    private var negotiatedMtu = DEFAULT_MTU
    private val snCounter = Insta360Protocol.SequenceCounter()
    private val assembler = Insta360Protocol.FrameAssembler()

    private var pendingServiceAdds = 0
    private var confirmedServiceAdds = 0

    @Volatile
    private var lastFix: dev.hansel.insta360remote.location.GpsFix? = null

    private val resendHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var resendRunning = false

    private var notifyCharRef: BluetoothGattCharacteristic? = null


    // ------------------------------------------------------------ Start/Stop

    @android.annotation.SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasConnectPermission()) {
            Diagnostics.log(TAG, "BLUETOOTH_CONNECT nicht gewaehrt - Start abgebrochen")
            return false
        }
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
            confirmedServiceAdds = 0
            pendingServiceAdds = 2
            val primaryService = Insta360Uuids.buildService()
            notifyCharRef = primaryService.getCharacteristic(Insta360Uuids.CHAR_NOTIFY_UUID)
            server.addService(primaryService)
            server.addService(Insta360Uuids.buildSecondaryService())
            Diagnostics.log(TAG, "addService() fuer beide Services abgeschickt")

            // Fallback fuer Stacks ohne onServiceAdded-Dispatch.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (gattServer != null && !advertising &&
                    confirmedServiceAdds < pendingServiceAdds
                ) {
                    Diagnostics.log(
                        TAG,
                        "onServiceAdded blieb aus ($confirmedServiceAdds/$pendingServiceAdds) - starte Advertising nach Timeout"
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
        stopResendLoop()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        synchronized(clients) { clients.clear() }
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
        val adapter = bluetoothManager.adapter
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            Diagnostics.log(TAG, "BluetoothLeAdvertiser nicht verfuegbar")
            return
        }
        advertiser = adv

        try {
            if (adapter.name != Insta360Uuids.REMOTE_DEVICE_NAME) {
                adapter.name = Insta360Uuids.REMOTE_DEVICE_NAME
                Diagnostics.log(TAG, "Bluetooth-Name gesetzt: " + Insta360Uuids.REMOTE_DEVICE_NAME)
            }
        } catch (e: Exception) {
            Diagnostics.log(TAG, "Konnte Bluetooth-Namen nicht setzen: " + e.message)
        }

        // EXAKT wie die ESP32-Referenz (Chwalek): beide Service-UUIDs inkl.
        // TX-Power im Adv-Paket, Name im Scan-Response.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(Insta360Uuids.SERVICE_PARCEL_UUID)
            .addServiceUuid(Insta360Uuids.SECONDARY_SERVICE_PARCEL_UUID)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        Diagnostics.log(TAG, "Advertising gestartet (" + Insta360Uuids.SERVICE_UUID + ")")
    }

    private fun restartAdvertisingIfStopped() {
        if (!advertising) {
            Diagnostics.log(TAG, "Advertising war inaktiv - Neustart (Reconnect-Pfad)")
            startAdvertising()
        }
    }

    // ------------------------------------------------------------ GPS-Sendung

    fun broadcastFix(fix: dev.hansel.insta360remote.location.GpsFix) {
        lastFix = fix
        val characteristic = notifyCharRef ?: run {
            Diagnostics.log(TAG, "Notify-Charakteristik nicht bereit - Fix verworfen")
            return
        }
        // An ALLE verbundenen Geraete senden - der Stack liefert Notify nur an
        // tatsaechlich Abonnierte (CCCD-Verwaltung liegt im Stack).
        val targets = synchronized(clients) { clients.toList() }
        if (targets.isEmpty()) return

        val frame = encoder.encodeGpsUpdate(fix, snCounter.next())
        val chunks = Insta360Protocol.fragment(frame, negotiatedMtu - ATT_HEADER_SIZE)

        var delivered = 0
        for (device in targets) {
            for (chunk in chunks) {
                characteristic.value = chunk
                val ok = try {
                    gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                } catch (e: Exception) {
                    Diagnostics.log(TAG, "notify fehlgeschlagen: " + e.message)
                    false
                }
                if (ok) delivered++
            }
        }
        ServiceStatus.incrementNotifyCount(delivered.toLong())

        startResendLoop()
    }

    /** Letzten Fix alle 2s wiederholen - verhindert den 30s-Idle-Drop. */
    private fun startResendLoop() {
        if (resendRunning) return
        resendRunning = true
        resendHandler.postDelayed(object : Runnable {
            override fun run() {
                val fix = lastFix
                val hasClients = synchronized(clients) { clients.isNotEmpty() }
                if (fix == null || !hasClients) {
                    resendRunning = false
                    return
                }
                broadcastFix(fix)
                resendHandler.postDelayed(this, 2000)
            }
        }, 2000)
    }

    private fun stopResendLoop() {
        resendRunning = false
        resendHandler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------ GATT-Callbacks

    @android.annotation.SuppressLint("MissingPermission")
    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService?) {
            Diagnostics.log(TAG, "onServiceAdded status=" + status + " uuid=" + (service?.uuid))
            if (status == BluetoothGatt.GATT_SUCCESS && service?.uuid != null) {
                confirmedServiceAdds++
                if (confirmedServiceAdds >= pendingServiceAdds && pendingServiceAdds > 0) {
                    Diagnostics.log(TAG, "Alle Services registriert - starte Advertising")
                    startAdvertising()
                    CameraScanner.start(context)
                    ServiceStatus.setBleState(BleConnectionState.Advertising)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            negotiatedMtu = mtu.coerceAtLeast(DEFAULT_MTU)
            Diagnostics.log(TAG, "MTU ausgehandelt: " + negotiatedMtu)
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
                    Diagnostics.log(TAG, "Kamera verbunden: " + address)

                    if (lastFix == null) {
                        lastFix = dev.hansel.insta360remote.location.GpsFix(
                            latitude = 0.0, longitude = 0.0, altitudeMeters = 0.0,
                            speedMps = 0f, bearingDeg = 0f, horizontalAccuracyMeters = 0f,
                            utcEpochMillis = System.currentTimeMillis(),
                            satelliteCount = 0,
                            fixQuality = dev.hansel.insta360remote.location.GpsFix.FixQuality.NO_FIX
                        )
                        Diagnostics.log(TAG, "Platzhalter-Fix gesetzt (kein GPS-Fix bisher)")
                    }
                    startResendLoop()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(clients) { clients.remove(device) }
                    assembler.reset()
                    val stillConnected = synchronized(clients) { clients.isNotEmpty() }
                    if (!stillConnected) {
                        stopResendLoop()
                        ServiceStatus.setBleState(BleConnectionState.Advertising)
                        Diagnostics.log(TAG, "Kamera getrennt (status=" + status + ") - warte auf Reconnect")
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
                else -> Diagnostics.log(TAG, "Write an " + characteristic.uuid + ": " + Diagnostics.hex(value))
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
                val enable = (value[0].toInt() and 0x01) != 0
                if (enable) {
                    Diagnostics.log(TAG, "Kamera hat Notifies aktiviert (" + device.address + ")")
                } else {
                    Diagnostics.log(TAG, "Kamera hat Notifies deaktiviert (" + device.address + ")")
                }
            }

            if (responseNeeded) {
                sendResponse(device, requestId, value)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (device == null || characteristic == null) return
            // KRITISCH: Read-Requests MUESSEN beantwortet werden.
            val value = characteristic.value ?: ByteArray(0)
            val response = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            try {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response
                )
                Diagnostics.log(TAG, "READ-Request " + characteristic.uuid + " -> " + Diagnostics.hex(response))
            } catch (e: Exception) {
                Diagnostics.log(TAG, "sendResponse(Read) fehlgeschlagen: " + e.message)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?,
        ) {
            if (device == null || descriptor == null) return
            val value = descriptor.value ?: ByteArray(0)
            val response = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            try {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response
                )
            } catch (e: Exception) {
                Diagnostics.log(TAG, "sendResponse(DescRead) fehlgeschlagen: " + e.message)
            }
        }
    }

    private fun sendResponse(device: BluetoothDevice, requestId: Int, value: ByteArray) {
        try {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        } catch (e: Exception) {
            Diagnostics.log(TAG, "sendResponse fehlgeschlagen: " + e.message)
        }
    }

    private fun handleCameraFrame(device: BluetoothDevice, chunk: ByteArray) {
        for (frame in assembler.feed(chunk)) {
            val commandId = if (frame.size >= 6) frame[5].toInt() and 0xFF else -1
            Diagnostics.log(
                TAG,
                "Frame von Kamera (" + device.address + ", cmd=0x%02X): %s".format(commandId, Diagnostics.hex(frame))
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
            Diagnostics.log(TAG, "Advertising fehlgeschlagen: " + errorCode)
        }
    }

    companion object {
        private const val TAG = "GattServer"
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_SIZE = 3
    }
}
