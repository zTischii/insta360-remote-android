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

## Problem: Doppelte „GPS Remote“-Einträge in der Kamera (Adressrotation)

**Symptom:** Nach Kamera-Aus + Neustart des Handys zeigt die Kamera unter
„Meine Geräte" **zwei** GPS-Remotes (alten + neuen Eintrag). Verbinden gelingt
erst nach Löschen des alten Eintrags + Kamera-Neustart - obwohl es dasselbe
Handy ist.

**Ursache:** Die Kamera führt ihre Geräteliste pro **BLE-Adresse**. Das
Original-Remote hat eine feste Werkadresse; unser Handy advertised über den
Android-Stack, der die Absendeadresse selbst wählt - häufig als rotierende
**Resolvable Private Address (RPA)**, u. a. bei Bluetooth aus/an, Reboot und
teils periodisch (~15 min). Neue Adresse ⇒ die Kamera sieht ein „neues"
Gerät. Da kein Bonding existiert, kennt die Kamera unsere IRK und kann die
RPAs nicht auflösen - sie *kann* uns prinzipbedingt nicht als dasselbe Gerät
erkennen. Eine öffentliche Android-API, um die eigene Werbeadresse zu pinnen,
gibt es nicht.

**Experimenteller Gegenversuch (Default aus):** Settings-Switch
„Bonding-Experiment" (`enable_bonding`). Aktiviert verlangen ce81 (Write),
ce83 (Read) und der ce82-CCCD Verschlüsselung (`PERMISSION_*_ENCRYPTED_MITM`)
- die Kamera muss vor dem Subscriben/Schreiben SMP-Pairing starten und erhält
dabei unsere **IRK**, sodass sie künftige RPAs demselben Eintrag zuordnen
kann. Wirksam erst ab dem **nächsten Service-Start** (die GATT-Services werden
dort registriert). Im Diagnose-Log erscheinen `BOND NONE -> BONDING ->
BONDED`-Zeilen sowie `BONDING-EXPERIMENT AKTIV`.

**Testprozedur:**
1. Switch an → Service stoppen/starten (Log muss `BONDING-EXPERIMENT AKTIV` zeigen).
2. Kamera verbinden - Log muss `BOND ... -> BONDED` zeigen, GPS-Strom muss laufen.
3. Kamera aus, Handy-Bluetooth aus/an (oder Handy neu starten), Service neu
   starten, Kamera wieder verbinden.
4. Prüfen, ob die Kamera weiterhin nur **einen** Eintrag führt.
5. Schlägt das Verbinden grundsätzlich fehl, unterstützt die Firmware kein
   SMP → Switch wieder aus (Verhalten wie vorher).

**Erster On-Device-Test (X4, Aug 2026):**
Log zeigt `BONDING-EXPERIMENT AKTIV`, aber die Firmware startet **kein
SMP-Pairing** auf die `GATT_INSUF_AUTHENTICATION: MITM required`-Ablehnungen
hin - sie wiederholt Write-/CCCD-Versuche endlos (solange der Switch aktiv
ist, kommt daher voraussichtlich **kein GPS** bei der Kamera an). Weiterhin
zwei Einträge; nach Handy-Reboot lässt sich der alte Eintrag nicht mehr
pairen, der frische paart sofort. Fazit: Permission-Deny allein reicht
nicht, diese Firmware triggert kein Pairing daraus.

**Falls Bonding scheitert:** Alternativ-Experiment wäre `createBond()` aus
unserer Central-Rolle heraus (CameraClient). Als Alltags-Workaround bleibt
das Löschen alter Kamera-Einträge vor dem Neuverbinden.

## Remote-Features (Live-Status + Tasten)

Die App verhaelt sich jetzt naeher am Original-GPS-Remote und zeigt den
Kamera-Zustand live in **Notification** (BigText) und **Hauptbildschirm**:

### Aufnahme-Timer & Modus (verifizierter Weg, Architektur A)
- Die Kamera schreibt ~1 Hz Display-Strings auf ce81 (Typ 0x10,
  X4-Spec §6). `CameraDisplayParser` klassifiziert sie inhaltsbasiert:
  - `.HH:MM:SS` -> **Aufnahme aktiv**, Zaehler = verstrichene Sekunden.
    Das ist der zuverlaessige REC-Indikator (das 0x02-Statuswort ist es
    laut Sniff nicht).
  - `4K|30|UW` -> Modus/Aufloesung. `13h09m` -> Akku-Restlaufzeit-Schaetzung
    der Kamera.
- Notification: Titel wird bei Aufnahme zu `● REC HH:MM:SS`; ein 1-Hz-Ticker
  haelt den Timer zwischen den Display-Frames aktuell. Bei Trennung wird der
  Timer zurueckgesetzt.
- GPS-Statuszeile: `GPS Fix · N Sat · ±X m` / `kein Fix` / `Fix alt (Ns)`.

### Speicher & Kamera-Akku (experimentell, Architektur B)
- Ueber die bestehende Central-Verbindung (MTU-Bootstrap) werden nun
  optional Service-Discovery + be82-CCCD durchgefuehrt und alle 30 s zwei
  Header16-Queries gesendet (Codes laut xaionaro-go/insta360ctl, X-Serie):
  - `0x10` GetStorageInfo -> totalMB/freeMB/fileCount (uint32 LE)
  - `0x12` GetBatteryInfo -> Level % (+ Spannung mV)
- Antworten werden ueber Sequence-Nummer zugeordnet; Fehlercodes
  (400/500/501) fuehren nicht zum Abbruch - Werte bleiben dann "-".
- Abschaltbar via Pref `enable_status_queries` (Default: an, zusammen mit
  `enable_direct_control`). Da das Protokoll hierfuer nicht sniffer-
  verifiziert ist, sind die Werte als experimentell zu betrachten.

### Remote-Tasten (Ausloeser / Modus)
- Zwei Buttons im Hauptbildschirm senden die Original-Kommandoframes des
  GPS-Remotes auf ce82 (`FC EF FE 86 <SN> 03 01 <action> <param>`):
  Shutter = `02 00`, Modus = `01 00`. SN startet pro Verbindung bei 0 und
  inkrementiert um 2 je Event (Spec §4).
- Ohne verbundene Kamera gibt die UI ein Toast-Feedback statt stiller
  Fehler.

