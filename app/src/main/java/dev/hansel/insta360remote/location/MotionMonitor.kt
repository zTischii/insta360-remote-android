package dev.hansel.insta360remote.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import androidx.core.content.ContextCompat
import dev.hansel.insta360remote.core.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Erkennt Bewegung/Stillstand energieeffizient:
 *
 * 1. Significant-Motion-Sensor (Hardware-Wachtrigger, nahezu stromlos im Ruhemodus):
 *    loest bei realer Fortbewegung aus -> moving = true.
 * 2. Duty-cycled Accelerometer (10 s hoeren / 50 s Pause) als Fallback und fuer
 *    die Rueckkehr zu "stationaer": Unterschreitet die Varianz der Beschleunigung
 *    ueber ein Fenster den Schwellwert, wird auf Stillstand zurueckgestuft.
 */
class MotionMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: () -> Unit,
) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    private var moving = true

    val isMoving: Boolean get() = moving

    private var smdRegistered = false
    private var smdSensor: Sensor? = null
    private var dutyJob: Job? = null

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            setMoving(true)
            // Trigger ist one-shot -> sofort wieder anmelden.
            registerSignificantMotion()
        }
    }

    /** Varianz-Fenster des Accelerometers. */
    private var windowStartNanos = 0L
    private var sampleCount = 0
    private var sum = 0.0
    private var sumSquares = 0.0
    private var sawStillness = false

    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val magnitude = sqrt(
                event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
            )
            if (sampleCount == 0) windowStartNanos = event.timestamp
            sampleCount++
            sum += magnitude.toDouble()
            sumSquares += magnitude.toDouble() * magnitude

            if ((event.timestamp - windowStartNanos) >= WINDOW_NANOS && sampleCount >= 4) {
                val mean = sum / sampleCount
                val variance = (sumSquares / sampleCount) - mean * mean
                if (variance < STILLNESS_VARIANCE_THRESHOLD) sawStillness = true
                sampleCount = 0; sum = 0.0; sumSquares = 0.0
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        registerSignificantMotion()
        startAccelerometerDutyCycle()
        Diagnostics.log(TAG, "MotionMonitor gestartet")
    }

    fun stop() {
        smdSensor?.let { sensor ->
            try { sensorManager.cancelTriggerSensor(triggerListener, sensor) } catch (_: Exception) {}
        }
        try { sensorManager.unregisterListener(accelerometerListener) } catch (_: Exception) {}
        smdRegistered = false
        smdSensor = null
        dutyJob?.cancel()
        Diagnostics.log(TAG, "MotionMonitor gestoppt")
    }

    private fun setMoving(newValue: Boolean) {
        if (moving != newValue) {
            moving = newValue
            Diagnostics.log(TAG, if (newValue) "Bewegung erkannt" else "Stillstand erkannt")
            onStateChanged()
        }
    }

    private fun registerSignificantMotion() {
        if (!hasActivityRecognitionPermission()) return
        val smd = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        smdRegistered = try {
            sensorManager.requestTriggerSensor(triggerListener, smd)
        } catch (_: Exception) {
            false
        }
        if (smdRegistered) smdSensor = smd
    }

    /**
     * Accelerometer nur im Tastverhoer: LISTEN_MS hoeren, PAUSE_MS schlafen.
     * Wurde im gesamten Fenster Stillstand gesehen und der SMD nicht gefeuert,
     * wird auf das langsame Intervall zurueckgestuft.
     */
    private fun startAccelerometerDutyCycle() {
        dutyJob?.cancel()
        dutyJob = scope.launch {
            while (isActive) {
                sawStillness = false
                sampleCount = 0; sum = 0.0; sumSquares = 0.0
                val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accel != null) {
                    try {
                        sensorManager.registerListener(
                            accelerometerListener, accel, SAMPLE_INTERVAL_US
                        )
                    } catch (_: Exception) {}
                    delay(LISTEN_MS)
                    try { sensorManager.unregisterListener(accelerometerListener) } catch (_: Exception) {}
                    if (sawStillness && !recentlyTriggered()) setMoving(false)
                }
                delay(PAUSE_MS)
            }
        }
    }

    private fun recentlyTriggered(): Boolean = moving

    private fun hasActivityRecognitionPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Motion"
        private const val LISTEN_MS = 10_000L           // Accelerometer-Hoerfenster
        private const val PAUSE_MS = 50_000L            // Pause zwischen Hoerfenstern
        private const val SAMPLE_INTERVAL_US = 500_000  // 2 Hz reicht fuer Varianz
        private const val WINDOW_NANOS = 8_000_000_000L // 8 s Varianz-Fenster
        private const val STILLNESS_VARIANCE_THRESHOLD = 0.35f // m^2/s^4
    }
}

