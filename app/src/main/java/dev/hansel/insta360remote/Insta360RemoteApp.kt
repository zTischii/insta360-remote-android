package dev.hansel.insta360remote

import android.app.Application
import androidx.work.Configuration
import dev.hansel.insta360remote.watchdog.ServiceWatchdogWorker

class Insta360RemoteApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // Watchdog einmalig einplanen (KEEP-Policy, kein Rescheduling-Churn).
        ServiceWatchdogWorker.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
