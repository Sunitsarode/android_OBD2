# OBD2 Dashboard

A real-time OBD2 dashboard for Android, talking to a Bluetooth ELM327 adapter.
Built to run both on a phone and sideloaded onto a car's Android head unit.

- **Language:** Kotlin, classic Views with custom `Canvas` gauges
- **minSdk 23** (Android 6.0); Android Auto needs Android 8+ on the phone
- **No network access** - the app has no internet permission and sends nothing anywhere

## Features

**Live dashboard**
- Analogue RPM and speed gauges with a redline zone, eased between samples
- Large gear indicator: `N` in neutral, `1`-`6` in gear, with a SHIFT UP hint for manuals
- Twelve tiles you choose yourself: **long-press any tile** to pick from ~15 derived
  values and every PID your car supports
- Alert banner across the top of every tab

**Head-up display**
- Tap **HUD** for full-screen speed, gear, and an RPM bar
- Long-press to mirror it, so a phone lying on the dashboard reflects in the windscreen

**Alerts** (beep, and optional spoken warnings)
- Speed limit, engine overheating, over-rev, low battery voltage, low fuel,
  and the check engine light coming on mid-drive

**Sensors**
- All 69 standard PIDs in the registry, filtered to what your ECU answers
- **Tap any sensor for a live 60-second graph**

**Trip computer**
- Distance, time moving and idling, average and max speed, fuel used, economy, range
- Trip cost in rupees and cost per km (set the fuel price in Settings)
- Eco score, harsh acceleration and braking counts, time at high revs
- **Trip history**: trips are saved when you tap *Save and new trip*, disconnect,
  or leave the car parked for 5+ minutes
- Continues across short Bluetooth dropouts instead of resetting

**Performance timers**
- 0-60, 0-100, 60-100 km/h, quarter mile with trap speed, 100-0 braking distance

**Diagnostics**
- Stored, pending, and permanent trouble codes from every module, not just the engine
- **Freeze frame**: the conditions the ECU recorded when the fault was stored
- ~195 generic code descriptions; clear codes behind a confirmation
- MIL status, readiness monitors, VIN, ECU name, calibration ID

**Console**
- Raw AT/OBD prompt; live polling traffic is optional so it cannot slow the UI

**Head unit friendly**
- Optional start on boot
- Keeps the Bluetooth link while the ignition is off and resumes within about 3 s of
  starting the car, showing resting battery voltage meanwhile

**Logging**
- Optional CSV of every sample, including gear (0 = neutral) and fuel-cut state

## How the gear indicator works

OBD2 does not report the gear, so the app infers it. In gear, road speed per
engine rpm is fixed by the gearbox, so each gear shows up as a distinct ratio.
The app learns those ratios while you drive and **saves them**, so from the
second drive onward the gear appears as soon as a shift settles.

- **First drive:** use every gear for a few seconds each. Settings shows the
  learned gears in km/h per 1000 rpm.
- **Neutral / clutch down** is detected two ways: the ratio is higher than any
  gear allows, or the engine sits at idle while the ratio keeps changing.
- **Set transmission and gear count in Settings.** Manual shows `N` when stopped;
  automatic/AMT shows `-` (it cannot tell D from N); CVT shows `D`.
- **Relearn gears** in Settings after changing tyre size.

## CNG and bi-fuel cars

Set **Fuel system** in Settings to *Petrol + CNG* (or *CNG only*, *Petrol + LPG*).

- **Which fuel is burning** comes from the ECU's fuel-type report (PID 51), whose
  bi-fuel codes say "running CNG" or "running petrol". Settings shows what your
  ECU reports right now. If it never changes when you press the CNG switch, set
  detection to **Manual** and tap the **PETROL / CNG** badge under the gear
  (or the Fuel button on the Android Auto screen) whenever you switch.
- **CNG is counted in kg.** Fuel flow is airflow divided by 17.2 (CNG's
  air-to-fuel ratio), so economy shows as **km/kg** and cost uses the
  **price per kg**. Petrol stays in km/L. A bi-fuel trip tracks the two
  separately: used, economy, and distance on each.
- **CNG left and range.** OBD has no CNG gauge, so the app counts down from
  your last fill-up. Tap **Log CNG fill-up** on the Trip tab and enter the kg
  from the receipt (it adds to what is left), or **Full cylinder**. Range uses
  this trip's km/kg, or your long-run average once you have one.
- **Alerts:** low CNG (estimated), *switched to petrol* when the car drops
  back to petrol by itself (automatic detection only), and the cylinder
  **hydro-test** due date, which Indian rules require every 3 years. Set the
  date in Settings; the warning starts a month ahead.
- **Trouble codes:** lean-mixture (P0171/P0174) and misfire (P0300-P0304) codes
  get a CNG-specific hint, since a clogged gas filter, low regulator pressure,
  or worn spark plugs are the usual causes on gas.

Economy is shown as km/L and km/kg by default; switch to L/100km and kg/100km
in Settings.

## Faster polling

The adapter is probed at connect time for three speedups, each dropped
automatically if your adapter or car does not handle it:

1. **Several PIDs per request** (ISO 15765-4 CAN cars)
2. **Talking only to the engine ECU**, so other modules stay quiet
3. **Fast return**: the adapter replies the moment the answer arrives instead
   of waiting out its timeout

RPM and speed are read together every cycle; slower values are spread across
cycles rather than read in bursts, so the gauges never stall. The Console tab
shows which speedups are active. If readings ever freeze or go blank, turn off
**Fast polling** in Settings.

## Android Auto

The dashboard also shows on the car's own screen through Android Auto. The phone
keeps doing the OBD work over Bluetooth; Android Auto projects the result.

**On the car screen:** RPM and speed dials, a large gear indicator with the
shift-up hint, four tiles (the first four you chose on the phone), and the alert
banner. Icons in the top corner open **Sensors**, **Trip** and **Trouble codes**
lists. Clearing codes is deliberately left to the phone.

### One-time setup

Android Auto only lists apps from the Play Store unless you allow unknown sources:

1. On the phone, open **Settings > Connected devices > Connection preferences > Android Auto**
   (or search Settings for "Android Auto").
2. Scroll to the bottom and tap **Version** about 10 times, then confirm to enable developer mode.
3. Open the three-dot menu > **Developer settings** and turn on **Unknown sources**.
4. Open OBD2 Dashboard on the phone once, grant Bluetooth, and connect to the adapter.
5. Connect the phone to the car. If the app is not on the Android Auto launcher,
   use **Customize launcher** in the Android Auto settings to show it.

The app registers as a *navigation* app, because that is the only kind Android
Auto lets draw its own graphics. It never starts turn-by-turn guidance, so Google
Maps keeps navigating alongside it.

**Car shows "Not connected"?** Tap **Connect** on the car screen. If Android
refuses to start the connection from the car, open the app on the phone once.

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

Every build is signed with the same key (`app/signing/obd2dash.keystore`, committed
on purpose), so a new APK installs over the old one and keeps your settings,
learned gears and trip history. The version number is the CI run number, shown in
the phone's app info. The key is public: it gives update continuity, not proof
of who built an APK, so only install APKs from this repo's own Actions runs.

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
| Fuel system | Which fuels the car burns; sets the air-fuel ratio, units (kg for CNG) and prices |
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
    |    Pids                   69-PID registry with decode formulas and poll tiers
    |    Elm327                 Adapter configuration and the diagnostic modes
    |    Dtc / DtcLibrary       Code decoding, readiness monitors, descriptions
    +- core/
    |    ObdRepository          StateFlow hub between the service and the UI
    |    PollScheduler          What to read each cycle: fast PIDs plus a capped slice of slow ones
    |    GearEstimator          Learned, persisted gear ratios with neutral detection
    |    AlertMonitor           Latched alerts with hysteresis and repeat intervals
    |    Metrics                Fuel rate (with fuel-cut detection), boost, power, range
    |    TripComputer/PerfTimer Trip totals, cost, eco score, acceleration runs
    |    TripHistory, PidHistory Saved trips; recent values for the live graph
    |    CsvLogger, Prefs
    +- service/ObdService       Foreground service owning the connection and poll loop
    |    AlertSounder, BootReceiver
    +- auto/                    Android Auto: car app service, drawn dashboard, list screens
    +- ui/                      Connect screen, five tabs, HUD, settings, tile catalogue
         view/GaugeView         Custom arc gauge
         view/TileView          Compact readout tile
         view/ChartView         Rolling line graph

The service is the only writer to `ObdRepository`. Screens observe its flows and
send one-off requests (read codes, clear codes, raw command) back through a
channel, so nothing competes with the poll loop for the socket.

Polling is tiered because an ELM327 clone manages only 10-20 queries per second
in total: RPM and speed are read every cycle, mid-priority values every fourth,
and slow-moving ones like fuel level every twentieth.
