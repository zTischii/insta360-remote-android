package dev.hansel.insta360remote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hansel.insta360remote.core.AppPreferences
import dev.hansel.insta360remote.core.Diagnostics

/**
 * Startet den Foreground-Service nach Reboot oder App-Update automatisch neu -
 * aber NUR, wenn der User den Auto-Start explizit in der App aktiviert hat
 * (UX-Flag in SharedPreferences).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!AppPreferences.get(context).autoStartOnBoot) {
            Diagnostics.log("BootReceiver", "Auto-Start deaktiviert - kein Service-Start")
            return
        }
        Diagnostics.log("BootReceiver", "Auto-Start aktiv - starte Service ($action)")
        GpsRemoteService.start(context)
    }
}
