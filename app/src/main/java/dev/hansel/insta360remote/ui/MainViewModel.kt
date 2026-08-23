package dev.hansel.insta360remote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hansel.insta360remote.core.AppPreferences
import dev.hansel.insta360remote.core.BleConnectionState
import dev.hansel.insta360remote.core.Diagnostics
import dev.hansel.insta360remote.core.ServiceStatus
import dev.hansel.insta360remote.service.GpsRemoteService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI-Zustand des Hauptbildschirms. */
data class MainUiState(
    val serviceRunning: Boolean = false,
    val bleState: BleConnectionState = BleConnectionState.Idle,
    val lastFixText: String = "-",
    val notifyCount: Long = 0,
    val batteryText: String = "-",
    val logLines: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences.get(application)
    private val appContext = application.applicationContext

    private val _autoStartEnabled = MutableStateFlow(prefs.autoStartOnBoot)
    val autoStartEnabled: StateFlow<Boolean> = _autoStartEnabled.asStateFlow()

    val uiState: StateFlow<MainUiState> = combine(
        ServiceStatus.isRunning,
        ServiceStatus.bleState,
        ServiceStatus.lastFix,
        ServiceStatus.notifyCount,
        Diagnostics.lines,
    ) { running, ble, fix, notifies, logs ->
        MainUiState(
            serviceRunning = running,
            bleState = ble,
            lastFixText = fix?.let {
                "lat=%.6f lon=%.6f alt=%.1fm speed=%.1fm/s sats=%d acc=%.0fm (%s)".format(
                    it.latitude, it.longitude, it.altitudeMeters, it.speedMps,
                    it.satelliteCount, it.horizontalAccuracyMeters, it.fixQuality
                )
            } ?: "-",
            notifyCount = notifies,
            logLines = logs.takeLast(60),
            batteryText = readBatteryStatus(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainUiState())

    fun isServiceRunning(): Boolean = ServiceStatus.isRunning.value

    fun toggleService() {
        if (ServiceStatus.isRunning.value) {
            GpsRemoteService.stop(appContext)
        } else {
            GpsRemoteService.start(appContext)
        }
    }

    fun setAutoStart(enabled: Boolean) {
        prefs.autoStartOnBoot = enabled
        _autoStartEnabled.value = enabled
        Diagnostics.log("UI", "Auto-Start nach Boot: $enabled")
    }

    /**
     * Grober Batteriestatus des Systems. Einen exakten per-App-Verbrauch liefert
     * die Plattform oeffentlich nicht - dafuer verlinkt die UI auf die
     * Systemeinstellungen der App.
     */
    private fun readBatteryStatus(): String {
        val bm = appContext.getSystemService(Application.BATTERY_SERVICE) as? android.os.BatteryManager
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (level >= 0) "$level %" else "unbekannt"
    }
}
