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
import dev.hansel.insta360remote.core.AppPreferences
import dev.hansel.insta360remote.core.BleConnectionState
import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.core.ServiceStatus

/**
 * BLE-Peripheral-Rolle: Advertising + GATT-Server (Architektur A - GPS-Remote).
 *
 * VERIFIZIERTES X4-Protokoll (TheAngryRaven/insta360-ble-gps-spec, nRF52840-Sniff):
 *  - ce82: 10-Hz-GPS-Strom als FC-EF-FE-83-Frames mit NMEA-RMC (NmeaGpsFrameEncoder),
 *    ununterbrochen inkl. Void-Frames ohne Fix (Liveness, sonst Idle-Drop ~30 s).
 *  - ce81: Kamera schreibt FE-EF-FE-Frames (Serial-Handshake 0x07, Status 0x02/0x05,
 *    Display-String 0x10). Write-Response ist das Ack.
 *  - CCCD-Subscription: Button-SN auf 0, Strom sofort starten.
 *
 * Live-Erkenntnisse X4/Android: Der OEM-Stack stellt onServiceAdded/
 * onDescriptorWriteRequest teils NICHT an die App zu. Daher eigene
 * Characteristic-Referenzen, Advertising per Timeout-Fallback und Broadcasts
 * an ALLE verbundenen Geraete.
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
    private var mtuWarned = false

    /** 10-Hz-Liveness-Strom gem. X4-Spec (§5): 100 ms Takt. */
    private val streamHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var streamRunning = false

    /** Button-SN gemaess Spec §4: bei jeder neuen Verbindung auf 0 zuruecksetzen. */
    private var buttonSn = 0

    // --- Diagnose-Zaehler des GPS-Stroms (Reset bei jeder neuen Verbindung) ---
    private var framesSent = 0
    private var framesVoidSent = 0
    private var framesRejected = 0

    private var pendingServiceAdds = 0
    private var confirmedServiceAdds = 0

    @Volatile
    private var lastFix: dev.hansel.insta360remote.location.GpsFix? = null

    /** Letzter gueltiger Fix (GPS_FIX/DIFF/RTK) - Basis fuer den Status-A-Strom. */
    @Volatile
    private var lastValidFix: dev.hansel.insta360remote.location.GpsFix? = null

    /**
     * Geparster Anzeige-/Aufnahme-Zustand der Kamera aus den ce81-0x10-Frames
     * (Rec-Timer/Modus/Akku). Wird bei jedem Frame gemerged und nach
     * [ServiceStatus] gespiegelt (Quelle fuer Notification + UI).
     */
    private var cameraDisplay = dev.hansel.insta360remote.core.CameraDisplayState()

    /**
     * Fix-Hold-Fenster: Nach dem letzten gueltigen Fix senden wir noch bis zu
     * 15 s lang Status-A-Saetze mit der letzten Position, BEVOR auf Void
     * umgeschaltet wird. Grund: Eine einzige schlechte Standortmeldung
     * (accuracy > 50 m, typisch drinnen) wuerde sonst sofort alles auf VOID
     * kippen - die Kamera embeddet aber nur Status-A und wirft sonst irgend-
     * wann das Remote ab (Logs 17:49: Trennung nach ~27 s reiner Void-Saetze).
     */
    private val FIX_HOLD_MS = 15000L

    /** Aktueller Modus des Streams (fuer Wechsel-Logging). */
    private var lastStreamVoid = true

    /** Rate-Limit fuer die NO_FIX-Erklaerlogs. */
    private var lastNoFixLogAt = 0L

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
            // Bonding-Experiment (AppPreferences.enableBonding): Wenn aktiv,
            // verlangen ce81/ce82-CCCD/ce83 Verschluesselung -> die Kamera muss
            // SMP-Pairing starten (IRK-Austausch), damit sie kuenftige RPAs
            // (Adressrotation nach Reboot/BT-Toggle) dem selben Remote-Eintrag
            // zuordnen kann statt ein zweites anzulegen.
            val requireBonding = try {
                AppPreferences.get(context).enableBonding
            } catch (_: Exception) {
                false
            }
            if (requireBonding) {
                Diagnostics.log(
                    TAG,
                    "BONDING-EXPERIMENT AKTIV: ce81/ce82-CCCD/ce83 verlangen ENCRYPTED_MITM - " +
                        "die Kamera muss pairen, bevor sie subscriben/schreiben darf"
                )
            }
            registerBondReceiver()
            val primaryService = Insta360Uuids.buildService(requireEncryption = requireBonding)
            notifyCharRef = primaryService.getCharacteristic(Insta360Uuids.CHAR_NOTIFY_UUID)
            server.addService(primaryService)
            Diagnostics.log(TAG, "addService() fuer Primaer-Service abgeschickt")
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
        stopStreamLoop()
        unregisterBondReceiver()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        synchronized(clients) { clients.clear() }
        ServiceStatus.setBleState(BleConnectionState.Idle)
        Diagnostics.log(TAG, "GattServerManager gestoppt")
    }

    private fun hasConnectPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Serial fuer den ORBIT-Wake-Beacon (leer = klassisches UUID-Advertising). */
    private fun cameraSerialOrNull(): String? =
        try { AppPreferences.get(context).cameraSerial.trim().uppercase().ifEmpty { null } }
        catch (_: Exception) { null }

    // -------------------------------------- Bonding-Experiment (Diagnose)

    /**
     * Empfaenger fuer Bond-State-Aenderungen: macht SMP-Pairing im Diagnose-Log
     * sichtbar (NONE -> BONDING -> BONDED), wenn das Bonding-Experiment aktiv
     * ist (AppPreferences.enableBonding). Nur System-Broadcast, keine Flags noetig.
     */
    private var bondReceiver: android.content.BroadcastReceiver? = null

    @android.annotation.SuppressLint("MissingPermission")
    private fun registerBondReceiver() {
        if (bondReceiver != null) return
        if (!hasConnectPermission()) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                if (!hasConnectPermission()) return
                @Suppress("DEPRECATION")
                val device = intent?.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                    android.bluetooth.BluetoothDevice.EXTRA_DEVICE
                )
                val newState =
                    intent?.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1) ?: -1
                val prevState =
                    intent?.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        ?: -1
                val name = try { device?.name } catch (_: SecurityException) { null }
                Diagnostics.log(
                    TAG,
                    "BOND ${bondStateName(prevState)} -> ${bondStateName(newState)} " +
                        "${device?.address ?: "?"} ${name ?: ""}".trim()
                )
            }
        }
        try {
            context.registerReceiver(
                receiver,
                android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            )
            bondReceiver = receiver
            Diagnostics.log(TAG, "Bond-State-Empfaenger registriert")
        } catch (e: Exception) {
            Diagnostics.log(TAG, "Bond-Receiver-Registrierung fehlgeschlagen: " + e.message)
        }
    }

    private fun unregisterBondReceiver() {
        val receiver = bondReceiver ?: return
        bondReceiver = null
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun bondStateName(state: Int): String = when (state) {
        android.bluetooth.BluetoothDevice.BOND_NONE -> "NONE"
        android.bluetooth.BluetoothDevice.BOND_BONDING -> "BONDING"
        android.bluetooth.BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "?$state"
    }

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

        // AdvertiseSettings wie gehabt; das Adv-Payload haengt vom Modus ab (s.u.).
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Zwei Modi:
        //  a) ORBIT-Wake-Beacon (X4-Spec §1): exakter Klon der Original-Remote-
        //     Advertisement inkl. Kamera-Serial -> weckt schlafende Kameras.
        //     Flags + Manufacturer-Data fuellen exakt die 31 Legacy-Bytes,
        //     daher KEINE Service-UUIDs/TX-Power ins Adv-Paket.
        //  b) Klassisch (Default ohne konfigurierte Serial): beide Service-UUIDs
        //     + TX-Power, Name im Scan-Response (ESP32-Referenzmuster).
        val serial = cameraSerialOrNull()
        val orbitData = NmeaGpsFrameEncoder.buildOrbitManufacturerData(serial)

        val advertiseData = if (orbitData != null) {
            Diagnostics.log(TAG, "Advertising-Modus: ORBIT-Wake-Beacon (Serial=$serial) " +
                "hex=" + NmeaGpsFrameEncoder.debugWakeBeaconHex(serial!!))
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(0x004C, orbitData) // Apple Company-ID wie Original
                .build()
        } else {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(Insta360Uuids.SERVICE_PARCEL_UUID)
                .addServiceUuid(Insta360Uuids.SECONDARY_SERVICE_PARCEL_UUID)
                .build()
        }

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

    /**
     * Neuesten Fix uebernehmen. Gesendet wird nicht hier, sondern vom
     * 10-Hz-Stream ([startStreamLoop]) - so wie beim Original-Remote.
     *
     * Gueltige Fixes (nicht NO_FIX) werden zusaetzlich als [lastValidFix]
     * gehalten und stuetzen den Status-A-Strom fuer [FIX_HOLD_MS].
     */
    fun broadcastFix(fix: dev.hansel.insta360remote.location.GpsFix) {
        lastFix = fix
        if (fix.fixQuality != dev.hansel.insta360remote.location.GpsFix.FixQuality.NO_FIX) {
            lastValidFix = fix
        } else {
            // Rate-limitiert (max. alle 10 s) erklaeren, WARUM der Stream void laeuft
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastNoFixLogAt > 10000) {
                lastNoFixLogAt = now
                Diagnostics.log(TAG, String.format(java.util.Locale.US,
                    "Fix mit NO_FIX uebergangen (acc=%.0fm > 50m?) - Status-A-Basis: %s",
                    fix.horizontalAccuracyMeters,
                    if (lastValidFix != null) "letzter gueltiger Fix im Hold-Fenster" else
                        "KEIN gueltiger Fix bisher (raus gehen oder Fake-GPS nutzen!)"))
            }
        }
        startStreamLoop()
    }

    private fun ensurePlaceholderFix() {
        if (lastFix == null) {
            lastFix = dev.hansel.insta360remote.location.GpsFix(
                latitude = 0.0, longitude = 0.0, altitudeMeters = 0.0,
                speedMps = 0f, bearingDeg = 0f, horizontalAccuracyMeters = 0f,
                utcEpochMillis = System.currentTimeMillis(),
                satelliteCount = 0,
                fixQuality = dev.hansel.insta360remote.location.GpsFix.FixQuality.NO_FIX
            )
        }
    }

    /**
     * Liveness-Strom gem. X4-Spec §5/§7: ~10 Hz (100 ms), ununterbrochen solange
     * ein Client abonniert hat. Ohne echten Fix werden RMC-Voidsaetze (Status 'V')
     * gesendet - die Kamera darf den Strom nie verstummen lassen, sonst behandelt
     * sie das Remote als verschwunden (beobachteter Idle-Drop nach ~30 s).
     */
    private fun startStreamLoop() {
        ensurePlaceholderFix()
        if (streamRunning) return
        streamRunning = true
        framesSent = 0; framesVoidSent = 0; framesRejected = 0
        lastStreamVoid = true
        Diagnostics.log(TAG, "GPS-Strom gestartet (Intervall=${STREAM_INTERVAL_MS}ms, " +
            "MTU=$negotiatedMtu -> nutzbar ${negotiatedMtu - ATT_HEADER_SIZE}B/Notify)")
        streamHandler.post { pumpFrame() }
    }

    private fun stopStreamLoop() {
        val wasRunning = streamRunning
        streamRunning = false
        streamHandler.removeCallbacksAndMessages(null)
        if (wasRunning) {
            val active = framesSent - framesVoidSent
            Diagnostics.log(TAG,
                "GPS-Strom beendet: gesendet=$framesSent (aktiv(A)=$active, void(V)=$framesVoidSent, " +
                    "abgelehnt=$framesRejected)")
            if (framesVoidSent > framesSent / 2 && framesSent > 20) {
                Diagnostics.log(TAG,
                    "HINWEIS: Mehrheit VOID-Saetze - die App hatte ueberwiegend NO_FIX. " +
                        "Die Kamera bettet nur Status-A-Saetze ein!")
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun pumpFrame() {
        if (!streamRunning) return
        val characteristic = notifyCharRef ?: run {
            streamRunning = false
            return
        }
        val targets = synchronized(clients) { clients.toList() }
        if (targets.isEmpty()) {
            streamRunning = false
            return
        }

        // Fix-Hold: Den letzten GUETIGEN Fix bis zu FIX_HOLD_MS weiterverwenden,
        // bevor auf Void umgeschaltet wird (siehe Feld-Kommentar [FIX_HOLD_MS]).
        val valid = lastValidFix
        val holdOk = valid != null &&
            (System.currentTimeMillis() - valid.utcEpochMillis) <= FIX_HOLD_MS
        val nowVoid = !holdOk

        val frame = if (!nowVoid) {
            encoder.encodeGpsUpdate(valid!!, 0)
        } else {
            ensurePlaceholderFix()
            encoder.encodeGpsUpdate(lastFix!!, 0)
        }

        if (nowVoid != lastStreamVoid && framesSent > 0) {
            lastStreamVoid = nowVoid
            Diagnostics.log(TAG, if (nowVoid) {
                "GPS-Strom -> VOID (kein gueltiger Fix mehr; Hold von ${FIX_HOLD_MS / 1000}s abgelaufen). " +
                    "Die Kamera embeddet nur Status-A-Saetze!"
            } else {
                "GPS-Strom -> AKTIV (${String.format(java.util.Locale.US, "%.5f", valid!!.latitude)}, " +
                    String.format(java.util.Locale.US, "%.5f", valid!!.longitude) + ")"
            })
        }
        lastStreamVoid = nowVoid

        val isVoid = nowVoid
        framesSent++
        if (isVoid) framesVoidSent++

        // WICHTIG: Ein Frame = EIN Notify (das Original sendet die ~88-90 Bytes
        // als einzelne ATT-Notification). Kein Fragmentieren! Die Kamera handelt
        // als Central eine ausreichende MTU aus (onMtuChanged meldet sie uns).
        if (frame.size > negotiatedMtu - ATT_HEADER_SIZE && !mtuWarned) {
            mtuWarned = true
            Diagnostics.log(TAG,
                "KRITISCH: Frame (${frame.size}B) > MTU-3 (${negotiatedMtu - ATT_HEADER_SIZE}B) - " +
                    "Android stutzt jedes Notify vermutlich auf ${negotiatedMtu - ATT_HEADER_SIZE}B! " +
                    "Die Kamera bekommt dann Muell und kann kein GPS embedden.")
        }

        // Diagnose: 1. Frame + jeden 50. komplett loggen (Typ, Groesse, Hex-Kopf).
        if (framesSent == 1 || framesSent % 50 == 0) {
            val head = frame.copyOf(minOf(frame.size, 24))
            Diagnostics.log(TAG,
                "GPS-Strom #$framesSent [${if (isVoid) "V" else "A"}] ${frame.size}B MTU=$negotiatedMtu: " +
                    Diagnostics.hex(head))
        }

        var delivered = 0
        for (device in targets) {
            characteristic.value = frame
            val ok = try {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
            } catch (e: Exception) {
                Diagnostics.log(TAG, "notify fehlgeschlagen: " + e.message)
                false
            }
            if (ok) {
                delivered++
            } else {
                framesRejected++
                if (framesRejected <= 3 || framesRejected % 50 == 0) {
                    Diagnostics.log(TAG,
                        "Notify ABGELEHNT (#$framesRejected) an ${device.address} - " +
                            "Stack nimmt Frame ${frame.size}B bei MTU=$negotiatedMtu nicht an")
                }
            }
        }
        ServiceStatus.incrementNotifyCount(delivered.toLong())

        streamHandler.postDelayed({ pumpFrame() }, STREAM_INTERVAL_MS)
    }

    // ------------------------------------------------------------ GATT-Callbacks

    @android.annotation.SuppressLint("MissingPermission")
    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService?) {
            Diagnostics.log(TAG, "onServiceAdded status=" + status + " uuid=" + (service?.uuid))
            if (status == BluetoothGatt.GATT_SUCCESS && service?.uuid != null) {
                confirmedServiceAdds++
                
                // Sequentielles Hinzufuegen der Services:
                if (confirmedServiceAdds == 1) {
                    // Der erste (Primaer-)Service wurde hinzugefuegt, jetzt den zweiten (Sekundaer-)Service starten!
                    Diagnostics.log(TAG, "Primaer-Service OK. Sende addService() fuer Sekundaer-Service...")
                    gattServer?.addService(Insta360Uuids.buildSecondaryService())
                } else if (confirmedServiceAdds == pendingServiceAdds && !advertising) {
                    // Beide Services sind jetzt erfolgreich registriert. Wir koennen Advertising starten!
                    startAdvertising()
                    CameraScanner.start(context)
                    ServiceStatus.setBleState(BleConnectionState.Advertising)
                }
            } else {
                Diagnostics.log(TAG, "addService fehlgeschlagen (status=$status)")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            negotiatedMtu = mtu.coerceAtLeast(DEFAULT_MTU)
            Diagnostics.log(TAG, "MTU ausgehandelt: " + negotiatedMtu)
            if (negotiatedMtu < 91) {
                Diagnostics.log(TAG,
                    "WARNUNG: MTU=$negotiatedMtu ist zu klein fuer 88-Byte-GPS-Frames! " +
                        "Die Kamera hat keine grosse MTU angefragt - Frames werden gestoßen (truncated). " +
                        "Falls kein GPS embedded wird, ist DIES die Ursache.")
                mtuWarned = false // Frame-Log soll die Warnung erneut zeigen
            }
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
                    startAdvertising()

                    // Liveness-Strom starten - sendet bis zum ersten echten Fix
                    // automatisch Void-Frames (Spec §7.6).
                    startStreamLoop()
                    
                    // GANZ WICHTIG: Wenn die Kamera sich bei uns meldet, verbinden wir uns
                    // direkt auch als Central mit ihr, um das GPS (Architecture B) zu senden
                    // und den Keepalive aufrecht zu erhalten!
                    // Wir prüfen ob es wirklich eine Insta360 ist (MAC-Prefix oder Name),
                    // damit wir uns NICHT mit zufälligen BLE-Geräten (z.B. COROS-Watch) verbinden.
                    val isInsta360 = address.startsWith("B8:2D", ignoreCase = true) ||
                        address.startsWith("48:B6", ignoreCase = true) ||
                        device.name?.contains("insta", ignoreCase = true) == true ||
                        device.name?.contains("X2", ignoreCase = true) == true ||
                        device.name?.contains("X3", ignoreCase = true) == true ||
                        device.name?.contains("X4", ignoreCase = true) == true ||
                        device.name?.contains("X5", ignoreCase = true) == true ||
                        device.name?.contains("ONE", ignoreCase = true) == true
                    if (isInsta360) {
                        if (AppPreferences.get(context).enableDirectControl) {
                            Diagnostics.log(TAG, "Insta360-Kamera erkannt ($address) - verbinde als Central in 5s")
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                dev.hansel.insta360remote.ble.CameraClient.connect(context, device)
                            }, 5000)
                        } else {
                            // Default: NUR Remote-Rolle (Arch A). Die parallele
                            // Central-Verbindung auf be80 ist unnatuerlich fuer die
                            // Kamera (Original-Remote macht das nicht) und steht in
                            // Verdacht, die periodischen status=19-Trennungen zu
                            // verursaachen. Zusaetzlich per Prefs zuschaltbar.
                            Diagnostics.log(TAG,
                                "Insta360 erkannt ($address) - Direktkontrolle (Arch B) deaktiviert, " +
                                    "nur Remote-Rolle (Arch A)")
                        }
                    } else {
                        Diagnostics.log(TAG, "Fremdes Gerät ignoriert (kein Insta360): $address name=${device.name}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(clients) { clients.remove(device) }
                    mtuWarned = false
                    buttonSn = 0
                    val stillConnected = synchronized(clients) { clients.isNotEmpty() }
                    if (!stillConnected) {
                        stopStreamLoop()
                        resetCameraDisplayOnDisconnect()
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
                    // Spec §7.4: Bei CCCD-Subscription Button-SN auf 0 zuruecksetzen
                    // und den 10-Hz-GPS-Strom sofort anlaufen lassen.
                    buttonSn = 0
                    startStreamLoop()
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
            
            // Auf einigen Android-Versionen ist characteristic.value im Callback leer (wird vom OS neu instanziiert).
            // Daher liefern wir die statischen Werte basierend auf der UUID manuell aus!
            val value = when (characteristic.uuid) {
                Insta360Uuids.CHAR_EXTRA_UUID -> byteArrayOf(0x01, 0x02) // 0x0201
                Insta360Uuids.SEC_FFD3_READ -> byteArrayOf(0x01, 0x90.toByte(), 0x1e, 0x30)
                Insta360Uuids.SEC_FFD4_READ -> byteArrayOf(0x01, 0x20, 0x00, 0x18)
                Insta360Uuids.SEC_FFD2_READ, Insta360Uuids.SEC_FFD5_READ, 
                Insta360Uuids.SEC_FFF1_READ, Insta360Uuids.SEC_FFE0_READ -> byteArrayOf(0x00)
                else -> characteristic.value ?: ByteArray(0)
            }
            
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

    /**
     * Frame-Parser fuer ce81 (Kamera -> Remote), Format gemaess X4-Spec §3/§6:
     *
     * ```
     * FE EF FE  <TYPE>  <B4>  <LEN>  <PAYLOAD:LEN>
     * ```
     *
     * Beobachtete Typen: 0x07 Serial-Handshake (bei Connect + periodisch),
     * 0x10 Display-String (Mode/Battery/Aufnahme-Timer, ~1 Hz), 0x02 Statuswort,
     * 0x05 Status/Ack, 0x0e/0x0f selten.
     *
     * HISTORISCHER HINWEIS: Der fruehere "SYNC-Befehl 0x06" war ein Parse-Fehler -
     * es ist der Serial-Handshake (Typ 0x07, LEN=0x06 bei 6-stelliger Serial).
     * Die App antwortete darauf mit einem sofortigen GPS-Notify; das Verhalten
     * ("erste GPS-Daten direkt nach dem Handshake") ist korrekt und wird hier
     * beibehalten, indem der 10-Hz-Strom beim ersten ce81-Frame angestossen wird.
     */
    private fun handleCameraFrame(device: BluetoothDevice, chunk: ByteArray) {
        val hasMagic = chunk.size >= 6 &&
            chunk[0] == 0xFE.toByte() && chunk[1] == 0xEF.toByte() && chunk[2] == 0xFE.toByte()
        if (!hasMagic) {
            Diagnostics.log(TAG, "ce81 ohne Magic (" + device.address + "): " + Diagnostics.hex(chunk))
            return
        }

        val type = chunk[3].toInt() and 0xFF
        val len = chunk[5].toInt() and 0xFF

        when (type) {
            0x07 -> {
                // Serial-Handshake - Payload als ASCII loggen.
                val serial = runCatching {
                    String(chunk, 6, len.coerceAtMost(chunk.size - 6), Charsets.US_ASCII)
                }.getOrNull() ?: ""
                Diagnostics.log(TAG, "Serial-Handshake der Kamera: \"$serial\" - starte GPS-Strom")
                startStreamLoop()
            }
            0x05 -> { /* Keepalive/Ack - der 10-Hz-Strom laeuft bereits */ }
            0x10 -> handleDisplayString(chunk)
            0x02 -> { /* Statuswort - laut Spec kein verlaesslicher REC-Indikator */ }
            else -> {
                Diagnostics.log(TAG,
                    "ce81 Typ 0x%02X (%s)".format(type, Diagnostics.hex(chunk)))
            }
        }
    }

    /**
     * Wertet einen ce81-Display-String aus (Typ 0x10): Aufnahme-Timer,
     * Modus oder Akku-Runtime. Aktualisiert [cameraDisplay] + [ServiceStatus].
     *
     * Quelle: X4-Spec §6 - waehrend der Aufnahme zaehlt ".HH:MM:SS" hoch
     * (1 Hz), danach kehrt der String zu Modus/Akku zurueck. Das ist der
     * verlaessliche Aufnahme-Indikator (das 0x02-Statuswort ist es nicht).
     */
    private fun handleDisplayString(chunk: ByteArray) {
        val parsed = CameraDisplayParser.parse(chunk)
        if (parsed == null) {
            CameraDisplayParser.logUnparseable(chunk)
            return
        }

        val now = System.currentTimeMillis()
        val prev = cameraDisplay
        val next = when (parsed.kind) {
            CameraDisplayParser.Kind.RECORDING_TIMER ->
                prev.copy(
                    recordingElapsedSeconds = parsed.elapsedSeconds,
                    lastRaw = parsed.text,
                    updatedAtMillis = now,
                )
            CameraDisplayParser.Kind.BATTERY_RUNTIME ->
                prev.copy(
                    batteryRuntimeString = parsed.text.trim(),
                    // Timer-String verschwindet bei Stopp -> Aufnahme beendet:
                    recordingElapsedSeconds = null,
                    lastRaw = parsed.text,
                    updatedAtMillis = now,
                )
            CameraDisplayParser.Kind.MODE ->
                prev.copy(
                    modeString = parsed.text,
                    recordingElapsedSeconds = null,
                    lastRaw = parsed.text,
                    updatedAtMillis = now,
                )
            CameraDisplayParser.Kind.OTHER ->
                prev.copy(lastRaw = parsed.text, updatedAtMillis = now)
        }

        // Log nur bei relevanten Wechseln (nicht bei jedem 1-Hz-Timer-Tick).
        if (next.isRecording != prev.isRecording) {
            Diagnostics.log(TAG, if (next.isRecording) {
                "KAMERA HAT AUFNAHME GESTARTET"
            } else {
                "Aufnahme beendet (${prev.recordingElapsedSeconds ?: 0}s)"
            })
        } else if (parsed.kind == CameraDisplayParser.Kind.MODE && parsed.text != prev.modeString) {
            Diagnostics.log(TAG, "Kamera-Modus: ${parsed.text}")
        } else if (parsed.kind == CameraDisplayParser.Kind.BATTERY_RUNTIME &&
            parsed.text.trim() != prev.batteryRuntimeString
        ) {
            Diagnostics.log(TAG, "Kamera-Akku-Runtime: ${parsed.text.trim()}")
        }

        cameraDisplay = next
        ServiceStatus.setCameraDisplay(next)
    }

    /**
     * Setzt den Anzeige-Zustand bei Kamera-Trennung zurueck: Eine laufende
     * Aufnahme kann von uns nicht mehr beobachtet werden - der Notification-
     * Rec-Timer darf nicht "ewig" weiterlaufen.
     */
    private fun resetCameraDisplayOnDisconnect() {
        val prev = cameraDisplay
        if (prev.recordingElapsedSeconds != null || prev.lastRaw != null) {
            cameraDisplay = prev.copy(recordingElapsedSeconds = null, updatedAtMillis = System.currentTimeMillis())
            ServiceStatus.setCameraDisplay(cameraDisplay)
            Diagnostics.log(TAG, "Anzeige-Zustand der Kamera zurueckgesetzt (Trennung)")
        }
    }

    // -------------------------------------- Remote-Kommandos (Original-Tasten)

    /**
     * Sendet ein Original-Remote-Kommando an die Kamera (ce82-Notify),
     * Format X4-Spec §4 / xaionaro-go/insta360ctl commands_remote.go:
     *
     * ```
     * FC EF FE 86 <SN> 03 01 <ACTION> <PARAM>
     * ```
     *
     * SN startet laut Spec bei jeder Verbindung bei 0 und inkrementiert um 2
     * pro Event (Reset passiert in onDescriptorWriteRequest/onDisconnect).
     * Bekannte Kommandos: Shutter (02 00), Modus (01 00), Screen (00 00),
     * PowerOff (00 03).
     */
    fun sendRemoteCommand(action: Byte, param: Byte, label: String): Boolean {
        val characteristic = notifyCharRef ?: return false
        val targets = synchronized(clients) { clients.toList() }
        if (targets.isEmpty()) return false

        val frame = byteArrayOf(
            0xFC.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x86.toByte(),
            buttonSn.toByte(), 0x03, 0x01, action, param,
        )
        buttonSn = (buttonSn + 2) and 0xFF

        var delivered = false
        for (device in targets) {
            characteristic.value = frame
            delivered = try {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
            } catch (e: Exception) {
                Diagnostics.log(TAG, "Kommando-Notify fehlgeschlagen: " + e.message)
                false
            } || delivered
        }
        Diagnostics.log(TAG, "Remote-Kommando '$label' ($action/$param) an ${targets.size} Client(s): $delivered")
        return delivered
    }

    /** Ausloeser: Foto bzw. Start/Stopp der Aufnahme (je nach Kamera-Modus). */
    fun sendShutter(): Boolean = sendRemoteCommand(0x02, 0x00, "SHUTTER")

    /** Durchschalten der Aufnahme-Modi (Video/Photo/Timelapse...). */
    fun sendModeCycle(): Boolean = sendRemoteCommand(0x01, 0x00, "MODE")

    /** Kamera-Display aufwecken. */
    fun sendScreenWake(): Boolean = sendRemoteCommand(0x00, 0x00, "SCREEN")

    /** Kamera ausschalten. */
    fun sendPowerOff(): Boolean = sendRemoteCommand(0x00, 0x03, "POWER_OFF")

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Diagnostics.log(TAG, "Advertising aktiv")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                // Kein echter Fehler - wir liefen schon (Reconnect-Pfad startet doppelt)
                advertising = true
                Diagnostics.log(TAG, "Advertising lief bereits (Code 3) - belasse es aktiv")
                return
            }
            advertising = false
            Diagnostics.log(TAG, "Advertising fehlgeschlagen: " + errorCode)
        }
    }

    companion object {
        private const val TAG = "GattServer"
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_SIZE = 3

        /** GPS-Liveness-Strom: ~10 Hz wie das Original-Remote (X4-Spec §5). */
        private const val STREAM_INTERVAL_MS = 100L

        /**
         * Aktive Instanz solange der Foreground-Service laeuft - damit die UI
         * Remote-Kommandos (Ausloeser/Modus) senden kann. Bewusst ohne DI
         * (gleicher Stil wie [ServiceStatus]).
         */
        @Volatile
        var activeInstance: GattServerManager? = null
    }
}
