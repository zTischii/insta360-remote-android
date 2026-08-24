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
│   ├── GpsPayloadEncoder.kt    # Interface + historischer Best-Guess-protobuf-Encoder
│   ├── NmeaGpsFrameEncoder.kt  # VERIFIZIERTES X4-Format: FC EF FE 83 + NMEA-RMC (10Hz),
│   │                           #   Void-Frames, ORBIT-Wake-Beacon-Builder
│   └── GattServerManager.kt    # Advertising + GATT-Server, MTU/CCCD, Reconnect,
│                               #   10-Hz-Liveness-Strom, FE-EF-FE-Frame-Parser
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

## FORSCHUNGSSTAND: GPS-Protokoll VERIFIZIERT (Stand Aug 2026)

Das GPS-Payload-Format des Original-GPS-Remotes wurde inzwischen vollstaendig
reverse-engineert und **on-air gegen eine physische X4 bestaetigt**:

**Primärquelle:** [TheAngryRaven/insta360-ble-gps-spec](https://github.com/TheAngryRaven/insta360-ble-gps-spec)
(Passiv-Sniff mit nRF52840 + Wireshark gegen X4 + Original-Remote, Juli 2026).
Konsistent mit: `tsunghowu/insta360_ble_rc_rpi_pico_w` (X4-Shutter-Remote),
`pwchalk/insta360_ble_esp32`, `xaionaro-go/insta360ctl`.

### Architektur A (ce80/ce81/ce82) - DER verifizierte GPS-Pfad

Die ce82-Payload ist **NICHT Protobuf** (die fruehere "0x0A 0x35"-Hypothese war
falsch). Das Remote streamt ~10 Hz HDLC-aehnliche Frames um **NMEA-RMC-Saetze**:

```
FC EF FE 83 00 <LEN> ",26.7," <0x07> "," $GNRMC,...*CS
└─ Header (6) ───────┘ └─ Prefix (8) ──┘   └─ NMEA ─────┘
```

- Prefix `,26.7,\x07,`: konstant in allen Captures ("26.7" vermutlich
  Temperatur, `\x07` vermutlich Satellite-Count) - exakt uebernehmen.
- RMC-Besonderheiten: Laengengrad **vorzeichenbehaftet** mit immer 'E'
  (74°W -> `-7400.0000,E`); zusaetzliches `V` zwischen Mode und Checksumme;
  Koordinaten als `ddmm.mmmm`; Talker-ID `GN`.
- Checksumme: XOR aller Zeichen zwischen `$` und `*`, zwei Hex-Digits.
- Beispiel: `$GNRMC,120000.000,A,4000.0000,N,-7400.0000,E,0.00,0.00,010126,0.0,W,A,V*6E`
- **Liveness**: Strom darf nie verstummen; ohne Fix Void-Saetze (`...V,...N,V`)
  senden, sonst gilt das Remote als verschwunden (~30 s Idle-Drop).
- ce81 (Kamera->Remote): `FE EF FE <TYP> <B4> <LEN> <PAYLOAD>` - Typen:
  0x07 Serial-Handshake, 0x10 Display-String (Mode/Battery/**Rec-Timer**),
  0x02 Statuswort, 0x05 Ack. Der frueher gedeutete "SYNC-Befehl 0x06" war ein
  Parse-Fehler (LEN-Byte des Handshakes).
- Wake-Beacon: gefälschter Apple-iBeacon (`4C 00 02 15` + ASCII `ORBIT` +
  6-Byte-Kamera-Serial), weckt schlafende Kameras. Optional via
  `AppPreferences.cameraSerial` aktiviert.
- Button-Frames: `FC EF FE 86 <SN> 03 01 <BTN> <STATE>`, SN += 2 je Event,
  Reset auf 0 bei jeder neuen Verbindung.

Implementierung: `NmeaGpsFrameEncoder` + `GattServerManager` (10-Hz-Strom,
Void-Frames, Frame-Parser, ORBIT-Advertising).

### Architektur B (be80/be81/be82) - experimentell

- Header16-Format der X-Serie benoetigt laut `xaionaro-go/insta360ctl` KEINEN
  Sync/Autorisierungs-Handshake (Sync + CheckAuthorization sind GO 2/GO 3-
  spezifisch im FF-Frame-Format).
- Cmd 0x35 UPLOAD_GPS ist nur fuer **GO 3** als funktionierend dokumentiert;
  fuer X3/X4/X5 gibt es keinen oeffentlichen Erfolgsbeleg, dass GPS hierueber
  in .insv eingebettet wird.
- `status=0` im Write-Callback bedeutet nur Link-Layer-OK (bei
  WRITE_TYPE_NO_RESPONSE gibt es keine Applikations-Antwort).
- Wir senden 0x35 weiterhin als Experiment (schadet nicht), verlassen uns aber
  auf Architektur A.

### Verbleibende offene Punkte

- [ ] MTU: Kamera muss als Central gross genug aushandeln (Original-Remote
      sendet ~88-90-Byte-Notifies). Log-Warnung vorhanden, falls MTU zu klein.
- [ ] Bedeutung von Prefix-Feldern ("26.7", 0x07) - Konstanten aus Captures.

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

- Architektur A ist auf das **verifizierte X4-Format** umgestellt
  (`NmeaGpsFrameEncoder`, 10 Hz NMEA-RMC). Der alte `BestGuessGpsPayloadEncoder`
  bleibt als Referenz im Code.
- Connection-Parameter-Requests kann ein Peripheral unter Android nicht aktiv
  senden; die Kamera diktiert das Intervall. Wir antworten nur auf MTU-Verhandlung.
- OEM-Deep-Links sind Best-Effort (nicht offiziell dokumentiert), immer mit
  Fallback auf die Standard-App-Info-Seite.
