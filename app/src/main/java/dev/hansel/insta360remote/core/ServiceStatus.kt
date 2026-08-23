package dev.hansel.insta360remote.core

import dev.hansel.insta360remote.location.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Verbindungsstatus des BLE-GATT-Servers. */
sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    /** Bluetooth ist am Geraet ausgeschaltet. */
    data object BluetoothOff : BleConnectionState
    /** Advertising aktiv, keine Kamera verbunden. */
    data object Advertising : BleConnectionState
    /** Kamera verbunden (GATT-Client vorhanden). */
    data class Connected(val deviceName: String?, val deviceAddress: String) : BleConnectionState
}

/**
 * Zentraler, prozessweiter Statusspeicher.
 * Bewusst ohne DI-Framework gehalten (minimale Abhaengigkeiten).
 */
object ServiceStatus {

    /** Läuft der Foreground-Service aktuell? */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _bleState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val bleState: StateFlow<BleConnectionState> = _bleState.asStateFlow()

    private val _lastFix = MutableStateFlow<GpsFix?>(null)
    val lastFix: StateFlow<GpsFix?> = _lastFix.asStateFlow()

    /** Anzahl der gesendeten GPS-Notify-Pakete seit Servicestart. */
    private val _notifyCount = MutableStateFlow(0L)
    val notifyCount: StateFlow<Long> = _notifyCount.asStateFlow()

    /** Zeitpunkt (System.currentTimeMillis) des letzten von der Kamera empfangenen Pakets. */
    private val _lastCameraPacketAt = MutableStateFlow(0L)
    val lastCameraPacketAt: StateFlow<Long> = _lastCameraPacketAt.asStateFlow()

    fun setRunning(running: Boolean) { _isRunning.value = running }
    fun setBleState(state: BleConnectionState) { _bleState.value = state }
    fun setLastFix(fix: GpsFix?) { _lastFix.value = fix }
    fun incrementNotifyCount(delta: Long = 1) { _notifyCount.value += delta }
    fun markCameraPacket() { _lastCameraPacketAt.value = System.currentTimeMillis() }

    fun resetSession() {
        _bleState.value = BleConnectionState.Idle
        _lastFix.value = null
        _notifyCount.value = 0L
        _lastCameraPacketAt.value = 0L
    }
}
