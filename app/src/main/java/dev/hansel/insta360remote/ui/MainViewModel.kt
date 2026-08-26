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
    /** Mehrzeiliger Kamera-Status (REC/Modus/Akku/Speicher). */
    val cameraStatusText: String = "-",
    val logLines: List<String> = emptyList(),
)

/** Intermediates Combine-Ergebnis (Kotlin combine unterstuetzt max. 5 Flows). */
private data class UiCore(
    val running: Boolean,
    val bleState: BleConnectionState,
    val fix: dev.hansel.insta360remote.location.GpsFix?,
    val notifyCount: Long,
    val cameraStatusText: String,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences.get(application)
    private val appContext = application.applicationContext

    private val _autoStartEnabled = MutableStateFlow(prefs.autoStartOnBoot)
    val autoStartEnabled: StateFlow<Boolean> = _autoStartEnabled.asStateFlow()

    /** true = GPS-Prioritaet "high_accuracy", false = "balanced" (Default). */
    private val _highAccuracyLocation = MutableStateFlow(
        prefs.locationPriority == dev.hansel.insta360remote.location.FusedLocationSource.PRIORITY_HIGH_ACCURACY
    )
    val highAccuracyLocation: StateFlow<Boolean> = _highAccuracyLocation.asStateFlow()

    /**
     * true = Bonding-Experiment aktiv (GATT-Server verlangt Verschluesselung,
     * damit die Kamera pairen muss und unsere IRK erhaelt). Default aus.
     */
    private val _bondingEnabled = MutableStateFlow(prefs.enableBonding)
    val bondingEnabled: StateFlow<Boolean> = _bondingEnabled.asStateFlow()

    /** Kamera-Status (REC/Modus/Akku/Speicher) als mehrzeiliger Text. */
    private val cameraInfoFlow = combine(
        ServiceStatus.cameraDisplay,
        ServiceStatus.cameraStorage,
        ServiceStatus.cameraBattery,
    ) { display, storage, battery ->
        buildString {
            if (display.isRecording) {
                append("● REC ")
                append(dev.hansel.insta360remote.core.CameraStatusFormatter.formatRecTime(display.recordingElapsedSeconds))
            } else {
                append("Keine Aufnahme")
            }
            display.modeString?.let { append("\nModus: ").append(it) }
            when {
                battery != null && battery.levelPercent >= 0 -> {
                    append("\nKamera-Akku: ").append(battery.levelPercent).append('%')
                    if (battery.voltageMv > 0) append(" (").append(battery.voltageMv).append(" mV)")
                }
                display.batteryRuntimeString != null ->
                    append("\nKamera-Restlaufzeit: ").append(display.batteryRuntimeString)
            }
            if (storage != null && storage.freeMb >= 0) {
                val total = if (storage.totalMb > 0)
                    " von " + dev.hansel.insta360remote.core.CameraStatusFormatter.formatGb(storage.totalMb)
                else ""
                append("\nSpeicher: ")
                    .append(dev.hansel.insta360remote.core.CameraStatusFormatter.formatGb(storage.freeMb))
                    .append(total).append(" frei · ")
                    .append(storage.fileCount).append(" Dateien")
            } else {
                append("\nSpeicher: -")
            }
        }
    }

    val uiState: StateFlow<MainUiState> = combine(
        combine(
            ServiceStatus.isRunning,
            ServiceStatus.bleState,
            ServiceStatus.lastFix,
            ServiceStatus.notifyCount,
            cameraInfoFlow,
        ) { running, ble, fix, notifies, camInfo ->
            UiCore(running, ble, fix, notifies, camInfo)
        },
        Diagnostics.lines,
    ) { core, logs ->
        MainUiState(
            serviceRunning = core.running,
            bleState = core.bleState,
            lastFixText = core.fix?.let {
                "lat=%.6f lon=%.6f alt=%.1fm speed=%.1fm/s sats=%d acc=%.0fm (%s)".format(
                    it.latitude, it.longitude, it.altitudeMeters, it.speedMps,
                    it.satelliteCount, it.horizontalAccuracyMeters, it.fixQuality
                )
            } ?: "-",
            notifyCount = core.notifyCount,
            logLines = logs.takeLast(60),
            batteryText = readBatteryStatus(),
            cameraStatusText = core.cameraStatusText,
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

    /**
     * Remote-Tasten (wirken wie die physischen Buttons des Original-GPS-
     * Remotes): Kommandos gehen als ce82-Notify an die verbundene Kamera.
     * false = kein Service/keine Kamera verbunden.
     */
    fun sendShutter(): Boolean =
        dev.hansel.insta360remote.ble.GattServerManager.activeInstance?.sendShutter() ?: false

    fun sendModeCycle(): Boolean =
        dev.hansel.insta360remote.ble.GattServerManager.activeInstance?.sendModeCycle() ?: false

    fun setAutoStart(enabled: Boolean) {
        prefs.autoStartOnBoot = enabled
        _autoStartEnabled.value = enabled
        Diagnostics.log("UI", "Auto-Start nach Boot: $enabled")
    }

    /**
     * GPS-Prioritaet-Switch (Settings): Persistiert das Pref und stoesst ueber
     * den ServiceStatus-Konfigurationszaehler einen sofortigen Neustart der
     * Standortversorgung an (falls der Service laeuft). BLE bleibt unberuehrt.
     */
    fun setLocationHighAccuracy(enabled: Boolean) {
        val value = if (enabled) {
            dev.hansel.insta360remote.location.FusedLocationSource.PRIORITY_HIGH_ACCURACY
        } else {
            dev.hansel.insta360remote.location.FusedLocationSource.PRIORITY_BALANCED
        }
        if (prefs.locationPriority == value && _highAccuracyLocation.value == enabled) return
        prefs.locationPriority = value
        _highAccuracyLocation.value = enabled
        Diagnostics.log(
            "UI",
            "GPS-Prioritaet: ${if (enabled) "high_accuracy" else "balanced"}"
        )
        dev.hansel.insta360remote.core.ServiceStatus.bumpLocationConfigVersion()
    }

    /**
     * Bonding-Switch (Experiment gegen doppelte Kamera-Eintraege durch
     * Adressrotation): Persistiert das Pref. Wirkt erst beim naechsten
     * Service-Start, da die GATT-Services dort registriert werden.
     */
    fun setBondingEnabled(enabled: Boolean) {
        if (prefs.enableBonding == enabled && _bondingEnabled.value == enabled) return
        prefs.enableBonding = enabled
        _bondingEnabled.value = enabled
        Diagnostics.log("UI", "Bonding-Experiment: $enabled (wirkt beim naechsten Service-Start)")
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
