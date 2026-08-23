# Insta360 GPS Remote Emulator

Android-App (Kotlin, minSdk 31 / targetSdk 35), die sich gegenüber einer
Insta360-Kamera (X3/X4/RS/X5) wie das originale **Insta360 GPS Remote** verhält:
Das Smartphone ist **BLE-Peripheral (GATT-Server)**, die Kamera verbindet sich
als Central und erhält periodisch GPS-Daten (Lat/Lon/Höhe/Geschwindigkeit/
UTC/Fix-Qualität) per Notify, die in die .insv-Aufnahme eingebettet werden.

## Architektur

```
app/src/main/java/dev/hansel/insta360remote/
├── ble/
│   ├── Insta360Uuids.kt        # Service-/Charakteristik-UUIDs (be8x/ce8x-Sets umschaltbar)
│   ├── Insta360Protocol.kt     # Framing: Länge+16-Byte-Cmdblock+Payload, SN ab 0x0200,
│   │                           #   20-Byte-Fragmentierung, FrameAssembler für Empfang
│   ├── GpsPayloadEncoder.kt    # AUSTAUSCHBARES Interface + Best-Guess-protobuf-Encoder
│   └── GattServerManager.kt    # Advertising + GATT-Server, MTU/CCCD, Reconnect
├── location/
│   ├── LocationSource.kt       # Interface + Location->GpsFix-Mapping
│   ├── FusedLocationSource.kt  # Play Services, PRIORITY_BALANCED_POWER_ACCURACY
│   ├── FrameworkLocationSource.kt # Fallback ohne Play Services (GPS-Provider)
│   ├── MotionMonitor.kt        # Significant Motion + Duty-cycled Accelerometer
│   └── AdaptiveLocationController.kt # 1 Hz bewegt <-> 0,2 Hz Stillstand
├── service/
│   ├── GpsRemoteService.kt     # Foreground Service (connectedDevice|location), START_STICKY
│   └── BootReceiver.kt         # Auto-Start nur bei aktiviertem User-Flag
├── watchdog/ServiceWatchdogWorker.kt # WorkManager alle 15 min
├── system/OemBatteryHelper.kt  # MIUI/EMUI/ColorOS/Samsung-Hinweise + Deep-Links
├── core/                       # Diagnostics(Ringbuffer+Hexdump), ServiceStatus, Prefs
└── ui/                         # MainActivity + MainViewModel
```

## WICHTIG: Protokoll-Verifikation vor Erstnutzung

Das GPS-Payload-Format ist in den öffentlichen Reverse-Engineering-Quellen
**nicht vollständig dokumentiert**. Die App enthält deshalb:

1. **`GpsPayloadEncoder` als Interface** – sobald das echte Format bekannt ist,
   wird eine zweite Implementierung ergänzt und in `GpsRemoteService.ensureStarted()`
   ausgetauscht. Keine weiteren Codeänderungen nötig.
2. **Vollständiges Logging aller GATT-Pakete** (Hex-Dump) im Diagnose-Screen der App.
3. UUID-Sets `be8x` und `ce8x` sind per `Insta360Uuids.activeSet` umschaltbar.

### Setup-Kapitel: Sniffing mit echtem Hardware-Remote

**Variante A – Android HCI Snoop Log (empfohlen):**
1. Entwickleroptionen → *Bluetooth HCI snoop log aktivieren*, Bluetooth an/aus.
2. Original-GPS-Remote mit der Kamera koppeln und eine Aufnahme starten.
3. Bugreport ziehen: `adb bugreport bugreport.zip`
4. In `bugreport/FS/data/misc/bluetooth/logs/btsnoop_hci.log` öffnen (Wireshark).
5. Wireshark-Filter auf den Remote: `btcommon.eir_ad.entry.device_name` bzw. nach dem
   Service-UUID-Filter: `btatt.handle` → ATT-Write/Notify-Pakete der Verbindung suchen.

**Variante B – nRF Connect for Mobile:**
1. Kamera in den Verbindungsmodus versetzen, in nRF Connect *SCAN*.
2. Das originale Remote erscheint mit advertised Service-UUID → **UUID-Set notieren**.
3. Mit *Connect* verbinden (nRF statt Kamera) und die Charakteristiken inspizieren;
   CCCD des Notify-Charakters aktivieren und die vom Remote gesendeten Frames mitschneiden.

**Was zu verifizieren ist:**
- [ ] UUID-Set (`be80/be81/be82/be83` vs. `ce80/…`) → `Insta360Uuids.activeSet`
- [ ] Layout des 16-Byte-Kommandoblocks (SN-Position, Command-ID, Payload-Länge)
- [ ] Exaktes GPS-Payload-Format (protobuf-Feldnummern/-typen oder Rohformat)
- [ ] Notify-Intervall des originalen Remotes (Default hier: 1 Hz)

## Build & Installation

```bash
# Android Studio Ladybug+ empfohlen (AGP 8.5, Gradle 8.7)
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Erster Start (in dieser Reihenfolge)
1. **Berechtigungen erteilen** (Bluetooth Connect/Advertise, Feiner Standort,
   Benachrichtigungen, Aktivitätserkennung).
2. **Standort „Immer erlauben“** (zweiter Schritt, mit Erklärdialog).
3. **Akku-Optimierung deaktivieren** + ggf. OEM-Einstellungen (MIUI Autostart etc.).
4. Kamera in den Remote-Verbindungsmodus bringen → **Service starten**.

## Bekannte Einschränkungen

- Das GPS-Payload-Format im `BestGuessGpsPayloadEncoder` ist **unverifiziert**
  und muss nach Sniffing korrigiert werden, bis die Kamera die Daten akzeptiert.
- Connection-Parameter-Requests kann ein Peripheral unter Android nicht aktiv
  senden; die Kamera diktiert das Intervall. Wir antworten nur auf MTU-Verhandlung.
- OEM-Deep-Links sind Best-Effort (nicht offiziell dokumentiert), immer mit
  Fallback auf die Standard-App-Info-Seite.
