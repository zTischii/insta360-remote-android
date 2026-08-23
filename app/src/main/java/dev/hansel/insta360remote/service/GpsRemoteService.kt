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
import dev.hansel.insta360remote.ble.BestGuessGpsPayloadEncoder
import dev.hansel.insta360remote.ble.GattServerManager
import dev.hansel.insta360remote.core.AppPreferences
import dev.hansel.insta360remote.core.BleConnectionState
import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.core.ServiceStatus
import dev.hansel.insta360remote.location.AdaptiveLocationController
import kotlinx.coroutines.Job
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

    override fun onCreate() {
        super.onCreate()
        ServiceStatus.resetSession()
        ServiceStatus.setRunning(true)
        createNotificationChannel()
        startInForeground(buildNotification(waiting = true))
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
            encoder = BestGuessGpsPayloadEncoder(), // austauschbar nach Protokoll-Verifikation
        )
        if (!gattServerManager!!.start()) {
            Diagnostics.log(TAG, "BLE-Start fehlgeschlagen (Permissions?)")
        }

        acquireWakeLock()

        locationController = AdaptiveLocationController(this, prefs)
        locationJob = lifecycleScope.launch {
            try {
                locationController!!.fixes(lifecycleScope).collect { fix ->
                    ServiceStatus.setLastFix(fix)
                    gattServerManager?.broadcastFix(fix)
                }
            } catch (e: Exception) {
                Diagnostics.log(TAG, "Location-Pipeline beendet: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        locationJob?.cancel()
        locationController?.stop()
        locationController = null
        gattServerManager?.stop()
        gattServerManager = null
        releaseWakeLock()
        ServiceStatus.setRunning(false)
        Diagnostics.log(TAG, "Foreground-Service beendet")
        super.onDestroy()
    }

    // ------------------------------------------------------------ Notification

    private fun buildNotification(waiting: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = if (waiting) {
            getString(R.string.notif_text_waiting)
        } else {
            val device = (ServiceStatus.bleState.value as? BleConnectionState.Connected)
                ?.deviceName ?: "?"
            getString(R.string.notif_text_connected, device, ServiceStatus.notifyCount.value)
        }

        // IMPORTANCE_LOW: kein Sound, keine Heads-up-Anzeige.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
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
