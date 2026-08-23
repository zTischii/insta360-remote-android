package dev.hansel.insta360remote.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistente App-Einstellungen (UX-Flags wie Auto-Start nach Boot,
 * Location-Prioritaet, Notify-Intervall).
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("insta360_remote_prefs", Context.MODE_PRIVATE)

    /** Nach Reboot automatisch den Service starten? (User muss das explizit aktivieren.) */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /**
     * true  -> FusedLocationProviderClient (Google Play Services)
     * false -> direkter Fallback ueber LocationManager/GPS-Provider
     */
    var useFusedLocation: Boolean
        get() = prefs.getBoolean(KEY_USE_FUSED, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_FUSED, value).apply()

    /**
     * Standortprioritaet als String ("balanced" | "high_accuracy").
     * Default: PRIORITY_BALANCED_POWER_ACCURACY (akkuschonend).
     */
    var locationPriority: String
        get() = prefs.getString(KEY_LOCATION_PRIORITY, "balanced") ?: "balanced"
        set(value) = prefs.edit().putString(KEY_LOCATION_PRIORITY, value).apply()

    /** Notify-Intervall in ms im bewegten Zustand (Default 1000 = 1 Hz). */
    var activeIntervalMs: Long
        get() = prefs.getLong(KEY_ACTIVE_INTERVAL, 1000L)
        set(value) = prefs.edit().putLong(KEY_ACTIVE_INTERVAL, value).apply()

    /** Notify-Intervall in ms im Stillstand (Default 5000 = 0,2 Hz). */
    var idleIntervalMs: Long
        get() = prefs.getLong(KEY_IDLE_INTERVAL, 5000L)
        set(value) = prefs.edit().putLong(KEY_IDLE_INTERVAL, value).apply()

    companion object {
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_USE_FUSED = "use_fused_location"
        private const val KEY_LOCATION_PRIORITY = "location_priority"
        private const val KEY_ACTIVE_INTERVAL = "active_interval_ms"
        private const val KEY_IDLE_INTERVAL = "idle_interval_ms"

        @Volatile private var instance: AppPreferences? = null

        fun get(context: Context): AppPreferences =
            instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
    }
}
