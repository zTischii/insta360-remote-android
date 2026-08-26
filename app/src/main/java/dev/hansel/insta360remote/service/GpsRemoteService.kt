package dev.hansel.insta360remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.hansel.insta360remote.MainActivity
import dev.hansel.insta360remote.R
import dev.hansel.insta360remote.ble.CameraClient
import dev.hansel.insta360remote.ble.GattServerManager
import dev.hansel.insta360remote.ble.NmeaGpsFrameEncoder
import dev.hansel.insta360remote.core.AppPreferences
import dev.hansel.insta360remote.core.BleConnectionState
import dev.hansel.insta360remote.core.CameraStatusFormatter
import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.core.ServiceStatus
import dev.hansel.insta360remote.location.AdaptiveLocationController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground-Service: haelt BLE-GATT-Server + Advertising und die adaptive
 * GPS-Erfassung dauerhaft am Leben.
 *
 * - START_STICKY: automatischer Neustart nach OS-Kill (Doze/OEM-Manager)
 * - foregroundServiceType = connectedDevice|location (ab Android 14 Pflicht)
 * - Low-Priority-Notification ohne Sound/Vibration
 */
class GpsRemoteService : LifecycleService() {

    private var gattServerManager: GattServerManager? = null
    private var locationController: AdaptiveLocationController? = null
    private var locationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Aktive Notification-Updates (Flow-Beobachter + 1-Hz-Rec-Ticker). */
    private var notificationJob: Job? = null
    private var recTickerJob: Job? = null

    /** Dedupe-Schluessel der zuletzt angezeigten Notification (Inhalt). */
    private var lastNotificationKey: String? = null

    override fun onCreate() {
        super.onCreate()
        ServiceStatus.resetSession()
        ServiceStatus.setRunning(true)
        createNotificationChannel()
        startInForeground(buildNotification())
        lastNotificationKey = composeNotificationKey()
        observeStatusForNotification()
        Diagnostics.log(TAG, "Foreground-Service erstellt")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: OS startet uns nach Kill automatisch neu.
        ensureStarted()
        return START_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun ensureStarted() {
        if (gattServerManager != null) return // bereits laufend

        val prefs = AppPreferences.get(this)

        gattServerManager = GattServerManager(
            context = this,
            // VERIFIZIERTES X4-Format (NMEA-RMC in FC-EF-FE-83-Frames, 10 Hz):
            encoder = NmeaGpsFrameEncoder(),
        )
        if (!gattServerManager!!.start()) {
            Diagnostics.log(TAG, "BLE-Start fehlgeschlagen (Permissions?)")
        }
        GattServerManager.activeInstance = gattServerManager

        acquireWakeLock()

        locationController = AdaptiveLocationController(this, prefs)
        locationJob = lifecycleScope.launch {
            try {
                locationController!!.fixes(lifecycleScope).collect { fix ->
                    ServiceStatus.setLastFix(fix)
                    // Weg A (PRIMAER, X4-verifiziert): 10-Hz-NMEA-Strom auf ce82
                    // (FC EF FE 83 + $GNRMC) - genau wie das Original-GPS-Remote.
                    gattServerManager?.broadcastFix(fix)
                    // Weg B (EXPERIMENTELL): Header16-Cmd 0x35 UploadGPS auf be81.
                    // Kein oeffentlicher Beleg, dass die X-Serie GPS hierueber in
                    // .insv einbettet; status=0 im Write-Callback bedeutet nur
                    // "Link-Layer-OK", nicht "Semantik akzeptiert". Schadet nicht,
                    // hilft evtl. bei anderen Modellen (GO 3 verifiziert).
                    CameraClient.sendGpsFix(fix)
                }
            } catch (e: Exception) {
                Diagnostics.log(TAG, "Location-Pipeline beendet: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        notificationJob?.cancel(); notificationJob = null
        recTickerJob?.cancel(); recTickerJob = null
        locationJob?.cancel()
        locationController?.stop()
        locationController = null
        gattServerManager?.stop()
        gattServerManager = null
        GattServerManager.activeInstance = null
        releaseWakeLock()
        ServiceStatus.setRunning(false)
        Diagnostics.log(TAG, "Foreground-Service beendet")
        super.onDestroy()
    }

    // ------------------------------------------------------------ Notification

    /**
     * Beobachtet alle Status-Flows und aktualisiert die Foreground-Notification
     * bei relevanten Aenderungen. Ein zusaetzlicher 1-Hz-Ticker laeuft WAEHREND
     * einer Aufnahme, damit der Rec-Timer auch zwischen den ~1 Hz Display-
     * Frames der Kamera weiterlaeuft.
     */
    private fun observeStatusForNotification() {
        notificationJob?.cancel()
        notificationJob = lifecycleScope.launch {
            combine(
                ServiceStatus.isRunning,
                ServiceStatus.bleState,
                ServiceStatus.lastFix,
                ServiceStatus.cameraDisplay,
                ServiceStatus.cameraStorage,
            ) { _, _, _, _, _ ->
                refreshNotificationIfChanged()
                Unit
            }.collect { }
        }

        recTickerJob?.cancel()
        recTickerJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (ServiceStatus.cameraDisplay.value.isRecording) {
                    refreshNotificationIfChanged()
                }
            }
        }
    }

    /** Baut die Notification neu, falls sich ihr Inhalt geaendert hat. */
    private fun refreshNotificationIfChanged() {
        val key = composeNotificationKey()
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Diagnostics.log(TAG, "Notification-Update fehlgeschlagen: ${e.message}")
        }
    }

    /** GPS-Statuszeile aus dem letzten Fix (Qualitaet, Satelliten, Genauigkeit). */
    private fun gpsStatusLine(): String {
        val fix = ServiceStatus.lastFix.value
        val ageMs = System.currentTimeMillis() - (fix?.utcEpochMillis ?: 0L)
        return when {
            fix == null || fix.fixQuality == dev.hansel.insta360remote.location.GpsFix.FixQuality.NO_FIX ->
                getString(R.string.notif_gps_no_fix)
            ageMs > STALE_FIX_MS ->
                getString(R.string.notif_gps_stale, (ageMs / 1000L).toInt())
            else -> getString(
                R.string.notif_gps_fix,
                fix.satelliteCount,
                fix.horizontalAccuracyMeters.toInt().coerceAtLeast(0),
            )
        }
    }

    /** Kompakter Status-Schluessel fuer das Dedupe (Vergleich vor jedem Notify). */
    private fun composeNotificationKey(): String {
        val cam = ServiceStatus.cameraDisplay.value
        val storage = ServiceStatus.cameraStorage.value
        val ble = ServiceStatus.bleState.value
        return listOf(
            ble.javaClass.simpleName,
            CameraStatusFormatter.formatRecTime(cam.recordingElapsedSeconds),
            cam.modeString ?: "",
            gpsStatusLine(),
            storage?.freeMb ?: -1L,
            ServiceStatus.notifyCount.value.toString(),
        ).joinToString("|")
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cam = ServiceStatus.cameraDisplay.value
        val storage = ServiceStatus.cameraStorage.value
        val battery = ServiceStatus.cameraBattery.value
        val deviceName = (ServiceStatus.bleState.value as? BleConnectionState.Connected)
            ?.deviceName ?: getString(R.string.notif_device_unknown)

        val title = when {
            cam.isRecording ->
                getString(R.string.notif_rec_title, CameraStatusFormatter.formatRecTime(cam.recordingElapsedSeconds))
            ServiceStatus.bleState.value is BleConnectionState.Connected ->
                getString(R.string.notif_title_connected_short, deviceName)
            else ->
                getString(R.string.notif_title_waiting_short)
        }

        val text = listOf(gpsStatusLine(), "Sends ${ServiceStatus.notifyCount.value}")
            .joinToString(" · ")

        val detailLines = buildList {
            add(getString(R.string.notif_detail_camera, deviceName))
            if (cam.isRecording) {
                add(getString(R.string.notif_detail_recording, CameraStatusFormatter.formatRecTime(cam.recordingElapsedSeconds)))
            } else {
                add(getString(R.string.notif_detail_idle))
            }
            cam.modeString?.let { add(getString(R.string.notif_detail_mode, it)) }
            if (battery != null && battery.levelPercent >= 0) {
                add(getString(R.string.notif_detail_cam_battery, battery.levelPercent))
            } else if (cam.batteryRuntimeString != null) {
                add(getString(R.string.notif_detail_cam_runtime, cam.batteryRuntimeString!!))
            }
            if (storage != null && storage.freeMb >= 0) {
                val totalPart = if (storage.totalMb > 0)
                    "/${CameraStatusFormatter.formatGb(storage.totalMb)}" else ""
                add(getString(
                    R.string.notif_detail_storage,
                    CameraStatusFormatter.formatGb(storage.freeMb), totalPart, storage.fileCount
                ))
            }
            add(getString(R.string.notif_detail_gps, gpsStatusLine()))
            add(getString(R.string.notif_detail_sends, ServiceStatus.notifyCount.value))
        }

        // IMPORTANCE_LOW: kein Sound, keine Heads-up-Anzeige.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle(title)
                .bigText(detailLines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        // Kombinierter Service-Typ ist ab Android 14 verpflichtend deklariert;
        // die Typen werden hier explizit an das System gemeldet.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------ WakeLock (minimal)

    @Suppress("WakelockTimeout") // wird in onDestroy wieder freigegeben
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "insta360remote:gpsble").apply {
            setReferenceCounted(false)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    companion object {
        private const val TAG = "GpsRemoteService"
        private const val CHANNEL_ID = "gps_remote_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.hansel.insta360remote.action.STOP"

        /** Ab diesem Fix-Alter zeigt die Notification "Fix alt" statt "Fix". */
        private const val STALE_FIX_MS = 10_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GpsRemoteService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, GpsRemoteService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
