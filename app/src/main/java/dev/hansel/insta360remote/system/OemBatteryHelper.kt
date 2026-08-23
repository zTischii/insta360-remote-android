package dev.hansel.insta360remote.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import dev.hansel.insta360remote.core.Diagnostics

/**
 * Best-Effort-Erkennung OEM-spezifischer "App-Killer" (MIUI, EMUI/HarmonyOS,
 * ColorOS/OxygenOS, Samsung) inklusive Deep-Links zu den jeweiligen
 * Einstellungsseiten. Die Komponenten sind nicht offiziell dokumentiert und
 * koennen sich je nach OEM-Firmware-Version aendern - daher immer mit
 * Fallback auf die Standard-App-Info-Seite.
 */
object OemBatteryHelper {

    data class OemHint(val manufacturer: String, val hint: String, val intent: Intent?)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Standard-Dialog zur Battery-Optimization-Ausnahme. */
    fun buildBatteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun detectOem(context: Context): OemHint {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> OemHint(
                "Xiaomi (MIUI)",
                "MIUI killt Hintergrund-Services aggressiv. Bitte in Sicherheit -> Autostart die App aktivieren UND Akkusparmodus auf 'Keine Einschränkungen' stellen.",
                tryComponent(
                    context,
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ) ?: SettingsIntentFactory.appDetails(context)
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> OemHint(
                "Huawei/Honor (EMUI)",
                "EMUI beendet Apps im Hintergrund. Bitte in Einstellungen -> Akku -> App-Start die App auf 'Manuell verwalten' mit allen drei Optionen setzen.",
                tryComponent(
                    context,
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ) ?: tryComponent(
                    context,
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                ) ?: SettingsIntentFactory.appDetails(context)
            )
            manufacturer.contains("oppo") ||
                manufacturer.contains("oneplus") ||
                manufacturer.contains("realme") -> OemHint(
                "OPPO/OnePlus/realme (ColorOS/OxygenOS)",
                "ColorOS friert Hintergrund-Apps ein. Bitte in Einstellungen -> Akku -> App-Akkuverbrauch die App auf 'Nicht einschränken' und 'Hintergrundausführung erlauben' setzen.",
                tryComponent(
                    context,
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ) ?: tryComponent(
                    context,
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                ) ?: SettingsIntentFactory.appDetails(context)
            )
            manufacturer.contains("samsung") -> OemHint(
                "Samsung (One UI)",
                "Bitte in Einstellungen -> Akku -> Hintergrundnutzungsbeschränkungen die App aus 'Schlafende Apps' entfernen und 'Nie schlafende Apps' hinzufügen.",
                SettingsIntentFactory.appDetails(context)
            )
            else -> OemHint(
                android.os.Build.MANUFACTURER,
                "Keine speziellen Hersteller-Restriktionen bekannt. Falls der Service dennoch beendet wird, bitte die Akku-Einstellungen prüfen.",
                null
            )
        }
    }

    private fun tryComponent(context: Context, pkg: String, cls: String): Intent? = try {
        val intent = Intent().apply {
            component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Nur gueltig, wenn die Activity tatsaechlich existiert.
        if (context.packageManager.resolveActivity(intent, 0) != null) intent else null
    } catch (_: Exception) {
        null
    }

    private object SettingsIntentFactory {
        fun appDetails(context: Context): Intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
