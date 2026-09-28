# OBD2 Dashboard

A real-time OBD2 dashboard for Android, talking to a Bluetooth ELM327 adapter.
Built to run both on a phone and sideloaded onto a car's Android head unit.

- **Language:** Kotlin, classic Views with custom `Canvas` gauges
- **minSdk 21** (Android 5.0) so older head units are supported
- **No network access** - the app has no internet permission and sends nothing anywhere

## Features

**Live dashboard**
- Analogue RPM and speed gauges with configurable redline, eased between samples
- Twelve readout tiles: coolant, intake air, throttle, engine load, boost/vacuum,
  fuel level, instant consumption, fuel rate, power, torque, gear, battery
- Warning colours for overheating, low fuel, and low battery voltage

**Sensors**
- All 67 standard mode 01 PIDs in the registry, filtered to what your ECU
  actually reports, with live values and units

**Trip computer**
- Distance, duration, moving vs idle time, average and max speed
- Fuel used, average economy, remaining range
- Session maxima for RPM, coolant, and power

**Performance timers**
- 0-60 km/h, 0-100 km/h, 60-100 km/h
- Quarter mile with trap speed
- 100-0 km/h braking distance
- Arms automatically at a standstill, no button press needed

**Diagnostics**
- Read stored (mode 03), pending (mode 07), and permanent (mode 0A) trouble codes
- ~195 generic SAE code descriptions built in, with a sensible fallback for
  manufacturer-specific codes
- Clear codes and turn off the check engine light, behind a confirmation
- MIL status and readiness monitors, for emissions test preparation
- VIN, ECU name, calibration ID, and detected protocol

**Console**
- Raw AT and OBD command prompt, with a live trace of adapter traffic
- Useful for probing manufacturer PIDs the registry does not cover

**Logging**
- Optional CSV of every sample, written to the app's external files directory

## Getting an APK

There is no Gradle wrapper JAR in the repo (it is a binary), so use one of these:

### Option A - Android Studio (easiest)

1. Install Android Studio (Hedgehog 2023.1.1 or newer). It bundles JDK 17 and the SDK.
2. **File > Open** this folder. Studio generates the Gradle wrapper and downloads dependencies.
3. **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

> Your machine currently has Java 8 only, which the Android Gradle Plugin 8.x
> cannot use. Android Studio's bundled JDK sidesteps that - you do not need to
> change your system Java.

### Option B - GitHub Actions (no local tooling at all)

Push this repo to GitHub. `.github/workflows/build.yml` builds both debug and
release APKs on every push and attaches them to the workflow run as artifacts.
Download from the **Actions** tab.

### Option C - local command line

Needs JDK 17 and the Android SDK installed, then:

    gradle wrapper
    ./gradlew assembleDebug

## Installing on your car's Android head unit

1. **Pair the adapter to the head unit**, not just to your phone. Go to the head
   unit's Android Bluetooth settings and pair the OBD2 dongle there. The PIN is
   usually `1234` or `0000`. An adapter can normally only hold one active
   connection, so disconnect it from your phone first.
2. **Allow unknown sources** on the head unit: Settings > Security > Unknown
   sources, or on newer Android, the per-app "Install unknown apps" permission
   for whichever file manager you use.
3. **Copy the APK across** with a USB stick, an SD card, or by downloading it
   directly in the head unit's browser.
4. **Tap the APK** in the head unit's file manager to install.
5. If the head unit has no file manager, ADB over USB works too:
   `adb install app-debug.apk`

Two things worth knowing about head units:

- Some cheap units have a single Bluetooth radio already committed to your
  phone's hands-free profile. If pairing the dongle drops your phone connection,
  that unit can only do one at a time.
- A few units hard-block sideloading. If "Unknown sources" is missing entirely,
  the APK will not install and there is no workaround from the app side.

## First run

1. Plug the ELM327 adapter into the OBD2 port (driver's side, under the dash).
2. Turn the ignition to accessory or start the engine.
3. Pair the adapter in Android Bluetooth settings.
4. Open OBD2 Dashboard, grant the Bluetooth permission, and tap your adapter.

Protocol detection takes a few seconds on the first connection. The detected
protocol is remembered and replayed on reconnects, so later connections are fast.

## Vehicle settings

Open **Settings** from the connect screen and set these, since some readings are
computed rather than reported:

| Setting | Why it matters |
| --- | --- |
| Fuel type | Sets the stoichiometric ratio and fuel density used for consumption |
| Engine displacement | Only used when the car has no MAF sensor, for the speed-density estimate |
| Tank capacity | Converts fuel level percentage into remaining range |
| Redline | Where the tachometer turns red |

Defaults are 1.5 L, petrol, 45 L. Adjust them to your car's actual figures -
check the owner's manual rather than trusting the defaults.

## How consumption is calculated

In preference order:

1. **PID 0x5E** (engine fuel rate), if the ECU reports it - this is the real number.
2. **MAF** divided by the stoichiometric air-fuel ratio, converted by fuel density.
3. **Speed-density**, from manifold pressure, intake air temperature, RPM and
   displacement, for engines with no MAF sensor.

Options 2 and 3 are estimates. Option 3 in particular assumes a fixed volumetric
efficiency and will drift from reality under load.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| "No response from vehicle ECU" | Ignition off, or the adapter is not seated fully in the port |
| Connects then drops repeatedly | Clone adapter with a flaky SPP stack; try a different one |
| Very few PIDs listed | Normal - most cars support 20-40 of the standard PIDs |
| Consumption always blank | No MAF and no fuel-rate PID; set displacement so speed-density can work |
| Gear always blank | Needs a few minutes of varied driving to learn the ratios |
| Pairing fails | Adapter is still connected to your phone; disconnect it there first |

The **Console** tab shows the raw conversation with the adapter, which is the
fastest way to see what is actually going wrong.

## Safety and legal notes

- **Do not interact with the app while driving.** Set it up parked, then leave it alone.
- **Clearing trouble codes also resets readiness monitors.** An emissions test
  will fail until the car has completed a full drive cycle. Clearing a code does
  not fix the underlying fault - it only hides the symptom.
- **Performance timing is for private roads and closed courses.** The timers exist
  to measure a car, not to encourage speeding on public roads.
- An OBD2 adapter left plugged in can slowly drain the battery on some cars.
  Unplug it if the car will sit for days.

## Architecture

    com.obd2dash
    |
    +- bluetooth/ObdSocket      RFCOMM transport, with the channel-1 reflection fallback
    +- obd/
    |    ObdResponse            Frame joining, error detection, hex extraction
    |    Pids                   67-PID registry with decode formulas and poll tiers
    |    Elm327                 Adapter configuration and the diagnostic modes
    |    Dtc / DtcLibrary       Code decoding, readiness monitors, descriptions
    +- core/
    |    ObdRepository          StateFlow hub between the service and the UI
    |    Metrics                Fuel rate, boost, power, gear estimation
    |    TripComputer/PerfTimer Integrators for trip totals and acceleration runs
    |    CsvLogger, Prefs
    +- service/ObdService       Foreground service owning the connection and poll loop
    +- ui/                      Connect screen, five dashboard tabs, settings
         view/GaugeView         Custom arc gauge
         view/TileView          Compact readout tile

The service is the only writer to `ObdRepository`. Screens observe its flows and
send one-off requests (read codes, clear codes, raw command) back through a
channel, so nothing competes with the poll loop for the socket.

Polling is tiered because an ELM327 clone manages only 10-20 queries per second
in total: RPM and speed are read every cycle, mid-priority values every fourth,
and slow-moving ones like fuel level every twentieth.
