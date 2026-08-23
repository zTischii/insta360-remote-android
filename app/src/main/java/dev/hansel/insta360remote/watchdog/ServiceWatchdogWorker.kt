package dev.hansel.insta360remote.watchdog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.hansel.insta360remote.core.AppPreferences
import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.core.ServiceStatus
import dev.hansel.insta360remote.service.GpsRemoteService
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Watchdog: prueft alle 15 Minuten (Android-Minimum fuer
 * PeriodicWorkRequest), ob der Foreground-Service noch laeuft, und startet
 * ihn notfalls neu. Ueberlebt damit auch OEM-Kills, die START_STICKY
 * umgehen (z.B. aggressive Xiaomi/Huawei Battery-Manager).
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences.get(applicationContext)

        // Respektiert den User-Willen: nie gegen eine explizit gestoppte App arbeiten.
        if (!prefs.autoStartOnBoot && !ServiceStatus.isRunning.value) {
            return Result.success()
        }

        if (!ServiceStatus.isRunning.value) {
            Diagnostics.log(TAG, "Service laeuft nicht - Neustart durch Watchdog")
            try {
                GpsRemoteService.start(applicationContext)
            } catch (e: Exception) {
                Diagnostics.log(TAG, "Watchdog-Neustart fehlgeschlagen: ${e.message}")
                return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "Watchdog"
        private const val UNIQUE_WORK_NAME = "gps_remote_watchdog"

        /** Periodisches Scheduling; KEEP verhindert Rescheduling-Churn bei jedem App-Start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
