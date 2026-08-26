package dev.hansel.insta360remote

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hansel.insta360remote.core.BleConnectionState
import dev.hansel.insta360remote.system.OemBatteryHelper
import dev.hansel.insta360remote.ui.MainViewModel
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.launch

/**
 * Setup-/Status-Screen. Die App selbst ist reine Hintergrund-App;
 * hier werden nur Berechtigungen, Akku-Ausnahmen und der Service-Toggle bedient.
 *
 * Permission-Flow:
 *  1) BLUETOOTH_CONNECT + BLUETOOTH_ADVERTISE + ACCESS_FINE_LOCATION +
 *     POST_NOTIFICATIONS + ACTIVITY_RECOGNITION (einmalig zusammen)
 *  2) ACCESS_BACKGROUND_LOCATION nur im zweiten Schritt (Google-Pflicht),
 *     erklaert dem User vorher, warum "Immer erlauben" noetig ist.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }.toTypedArray()

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val essential = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    grants[Manifest.permission.BLUETOOTH_CONNECT] == true)
            if (!essential) {
                Toast.makeText(
                    this,
                    "Standort- und Bluetooth-Berechtigung sind zwingend erforderlich.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "Standort 'Immer erlauben' aktiv." else "Ohne 'Immer erlauben' liefert die App keinen GPS-Fix, wenn sie im Hintergrund läuft.",
                Toast.LENGTH_LONG
            ).show()
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            // Nach dem Einschalten kann der User den Start-Button erneut druecken.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestEssentialPermissions() }
        findViewById<Button>(R.id.btnBackgroundLocation).setOnClickListener { explainThenRequestBackgroundLocation() }
        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener { requestBatteryOptimizationExemption() }
        findViewById<Button>(R.id.btnOemSettings).setOnClickListener { openOemSettings() }
        findViewById<Button>(R.id.btnToggle).setOnClickListener { onToggleClicked() }

        // Original-Remote-Tasten (ce82-Kommandos an die Kamera):
        findViewById<Button>(R.id.btnShutter).setOnClickListener {
            feedback(
                viewModel.sendShutter(),
                okMsg = "Auslöser gesendet",
                failMsg = "Nicht verbunden - Service starten und Kamera verbinden"
            )
        }
        findViewById<Button>(R.id.btnMode).setOnClickListener {
            feedback(
                viewModel.sendModeCycle(),
                okMsg = "Modus-Befehl gesendet",
                failMsg = "Nicht verbunden - Service starten und Kamera verbinden"
            )
        }

        // Einstellungen: GPS-Prioritaet (balanced/high_accuracy) - wirkt sofort,
        // ohne dass der Service oder die BLE-Verbindung neu gestartet wird.
        setupLocationPrioritySwitch()

        observeUiState()
        showOemHint()
        maybeAutoStart()
    }

    /**
     * Deployment-Komfort: Sind alle Berechtigungen vorhanden und Bluetooth an,
     * startet der Service automatisch beim App-Oeffnen.
     */
    private fun maybeAutoStart() {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val bgLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (allGranted && bgLocation && adapter?.isEnabled == true &&
            !viewModel.isServiceRunning() && prefsAutoStartAllowed()
        ) {
            viewModel.toggleService()
        }
    }

    private fun prefsAutoStartAllowed(): Boolean =
        getSharedPreferences("insta360_remote_prefs", MODE_PRIVATE)
            .getBoolean("auto_start_on_app_open", true)

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    findViewById<TextView>(R.id.statusService).text =
                        getString(R.string.status_service, if (state.serviceRunning) "läuft" else "gestoppt")
                    findViewById<TextView>(R.id.statusBle).text = getString(
                        R.string.status_ble,
                        when (val ble = state.bleState) {
                            is BleConnectionState.Connected ->
                                "verbunden mit ${ble.deviceName ?: ble.deviceAddress} (${state.notifyCount} Sends)"
                            BleConnectionState.Advertising -> "advertising, warte auf Kamera"
                            BleConnectionState.BluetoothOff -> "BLUETOOTH IST AUS!"
                            BleConnectionState.Idle -> "inaktiv"
                        }
                    )
                    findViewById<TextView>(R.id.statusLastFix).text =
                        getString(R.string.status_last_fix, state.lastFixText)
                    findViewById<TextView>(R.id.statusCamera).text =
                        getString(R.string.status_camera, state.cameraStatusText)
                    findViewById<TextView>(R.id.statusBattery).text =
                        getString(R.string.status_battery, state.batteryText)
                    findViewById<TextView>(R.id.logText).text =
                        state.logLines.joinToString("\n")
                    findViewById<Button>(R.id.btnToggle).setText(
                        if (state.serviceRunning) R.string.btn_toggle_stop else R.string.btn_toggle_start
                    )
                }
            }
        }
    }

    private fun showOemHint() {
        val hint = OemBatteryHelper.detectOem(this)
        findViewById<TextView>(R.id.oemHint).text =
            getString(R.string.oem_hint, "${hint.manufacturer}: ${hint.hint}")
    }

    /**
     * Bindet den GPS-Prioritaet-Switch an [MainViewModel.highAccuracyLocation].
     * Der Listener wird bei jedem Render-Durchlauf frisch gesetzt, damit
     * programmatische isChecked-Aenderungen nicht als User-Eingabe zurueck-
     * laufen (Endlosschleifen-Schutz).
     */
    private fun setupLocationPrioritySwitch() {
        val sw = findViewById<SwitchCompat>(R.id.switchLocationPriority)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.highAccuracyLocation.collect { enabled ->
                    renderLocationMode(sw, enabled)
                }
            }
        }
    }

    private fun renderLocationMode(sw: SwitchCompat, enabled: Boolean) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = enabled
        findViewById<TextView>(R.id.locationModeLabel).text = getString(
            R.string.status_location_priority,
            getString(if (enabled) R.string.location_mode_high else R.string.location_mode_balanced)
        )
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked == viewModel.highAccuracyLocation.value) return@setOnCheckedChangeListener
            viewModel.setLocationHighAccuracy(checked)
            Toast.makeText(this, R.string.toast_location_priority_changed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun feedback(ok: Boolean, okMsg: String, failMsg: String) {
        Toast.makeText(this, if (ok) okMsg else failMsg, Toast.LENGTH_SHORT).show()
    }

    /** Startet den Service - aber nur mit eingeschaltetem Bluetooth. */
    private fun onToggleClicked() {
        if (viewModel.isServiceRunning()) {
            viewModel.toggleService()
            return
        }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            Toast.makeText(this, "Bitte Bluetooth einschalten - die App braucht es als Remote.", Toast.LENGTH_LONG).show()
            enableBluetoothLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bitte zuerst die Berechtigungen erteilen.", Toast.LENGTH_LONG).show()
            requestEssentialPermissions()
            return
        }
        viewModel.toggleService()
    }

    private fun requestEssentialPermissions() {
        runtimePermissionLauncher.launch(requiredPermissions)
    }

    /**
     * ACCESS_BACKGROUND_LOCATION muss nach Google-Richtlinie separat und nach
     * der Fine-Location-Berechtigung beantragt werden. Vorher Erklaerungsdialog,
     * warum "Immer erlauben" noetig ist.
     */
    private fun explainThenRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            Toast.makeText(
                this, "Bitte zuerst die normale Standortberechtigung erteilen.",
                Toast.LENGTH_LONG
            ).show()
            requestEssentialPermissions()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Standort: „Immer erlauben“")
            .setMessage(
                "Die App läuft als GPS-Remote im Hintergrund, während du aufnimmst. " +
                    "Damit GPS-Koordinaten auch bei ausgeschaltetem Bildschirm in die .insv-Datei " +
                    "eingebettet werden, wähle im nächsten Dialog bitte „Immer erlauben“.\n\n" +
                    "Es werden keine Standortdaten gespeichert oder übertragen - sie gehen nur per Bluetooth an deine Kamera."
            )
            .setPositiveButton("Weiter") { _, _ ->
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Battery-Optimization-Ausnahme mit User-Erklaerung. */
    private fun requestBatteryOptimizationExemption() {
        if (OemBatteryHelper.isIgnoringBatteryOptimizations(this)) {
            Toast.makeText(this, "Akku-Optimierung ist bereits deaktiviert.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Akku-Optimierung deaktivieren")
            .setMessage(
                "Android beendet Hintergrund-Services sonst aggressiv und unterbricht die " +
                    "BLE-Verbindung zur Kamera. Damit die GPS-Daten lückenlos in die Aufnahme " +
                    "eingebettet werden, bitte im nächsten Dialog „Zulassen“ wählen."
            )
            .setPositiveButton("Weiter") { _, _ ->
                tryStartActivity(OemBatteryHelper.buildBatteryOptimizationIntent(this))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun openOemSettings() {
        OemBatteryHelper.detectOem(this).intent?.let { tryStartActivity(it) }
    }

    private fun tryStartActivity(intent: Intent?) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {}
        }
    }
}

