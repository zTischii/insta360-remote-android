package dev.hansel.insta360remote.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.hansel.insta360remote.core.Diagnostics

/**
 * Beobachtungs-Scan fuer die Diagnose: hoert periodisch (3 s alle 12 s) alle
 * BLE-Advertisements in der Naehe ab und loggt alles, was nach einer
 * Insta360-Kamera im Pairing-Modus aussieht (Name enthaelt "insta" oder
 * bekannte Insta360-Service-UUIDs).
 *
 * Damit sehen wir live im Diagnose-Log, was die Kamera broadcastet, wenn sie
 * nach einem Remote sucht - und umgekehrt, wonach sie evtl. filtert.
 *
 * Hinweis: Das Geraet kann seine eigenen Advertisements nicht selbst empfangen,
 * der Scan zielt ausschliesslich auf die Kamera.
 */
object CameraScanner {

    private const val TAG = "CamScan"
    private const val CYCLE_PAUSE_MS = 12_000L
    private const val CYCLE_SCAN_MS = 3_000L

    @Volatile
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (running) return
        val scanner = bluetoothLeScanner(context) ?: run {
            Diagnostics.log(TAG, "Kein BLE-Scanner verfuegbar")
            return
        }
        if (!hasScanPermission(context)) {
            Diagnostics.log(TAG, "BLUETOOTH_SCAN nicht gewaehrt - Beobachtungs-Scan inaktiv")
            return
        }
        running = true
        Diagnostics.log(TAG, "Beobachtungs-Scan aktiv - Kamera in Pairing-Modus wird erkannt")
        scheduleCycle(context, scanner)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Diagnostics.log(TAG, "Beobachtungs-Scan gestoppt")
    }

    private fun scheduleCycle(context: Context, scanner: android.bluetooth.le.BluetoothLeScanner) {
        handler.postDelayed({ cycle(context, scanner) }, CYCLE_PAUSE_MS)
    }

    private fun cycle(context: Context, scanner: android.bluetooth.le.BluetoothLeScanner) {
        if (!running) return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                inspect(result)
            }

            override fun onScanFailed(errorCode: Int) {
                Diagnostics.log(TAG, "Scan fehlgeschlagen: $errorCode")
            }
        }
        try {
            scanner.startScan(callback)
        } catch (e: Exception) {
            Diagnostics.log(TAG, "startScan fehlgeschlagen: ${e.message}")
            running = false
            return
        }
        handler.postDelayed({
            try { scanner.stopScan(callback) } catch (_: Exception) {}
            scheduleCycle(context, scanner)
        }, CYCLE_SCAN_MS)
    }

    private fun inspect(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: result.device.name
        val uuids = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() }
        val interesting = name?.contains("insta", ignoreCase = true) == true ||
            uuids?.contains("ce80", ignoreCase = true) == true ||
            uuids?.contains("be80", ignoreCase = true) == true ||
            uuids?.contains("d0ff", ignoreCase = true) == true ||
            uuids?.contains("ffe0", ignoreCase = true) == true
        if (interesting) {
            Diagnostics.log(TAG, ">>> KANDIDAT: name=$name addr=${result.device.address} rssi=${result.rssi}")
            Diagnostics.log(TAG, "    uuids=$uuids")
            Diagnostics.log(TAG, "    advBytes=${Diagnostics.hex(result.scanRecord?.bytes)}")
        }
    }

    private fun bluetoothLeScanner(context: Context): android.bluetooth.le.BluetoothLeScanner? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bm.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    }

    private fun hasScanPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_SCAN
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
