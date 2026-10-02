package com.obd2dash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.obd2dash.R
import com.obd2dash.bluetooth.ObdSocket
import com.obd2dash.core.AlertMonitor
import com.obd2dash.core.CngTracker
import com.obd2dash.core.CsvLogger
import com.obd2dash.core.Fuel
import com.obd2dash.core.FuelDetection
import com.obd2dash.core.FuelSelector
import com.obd2dash.core.GearEstimator
import com.obd2dash.core.GearReading
import com.obd2dash.core.Metrics
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.ObdRepository.ConnState
import com.obd2dash.core.PerfTimer
import com.obd2dash.core.PollScheduler
import com.obd2dash.core.Prefs
import com.obd2dash.core.PrefsGearStore
import com.obd2dash.core.Settings
import com.obd2dash.core.TripComputer
import com.obd2dash.core.TripHistory
import com.obd2dash.obd.Dtc
import com.obd2dash.obd.Elm327
import com.obd2dash.obd.ObdResponse
import com.obd2dash.obd.Pids
import com.obd2dash.ui.DashboardActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the adapter connection and runs the polling loop for as long as the app
 * is driving, independent of which screen is visible.
 */
class ObdService : Service() {

    /** The ECU stopped answering, normally because the ignition was switched off. */
    private class EcuLostException : Exception("Vehicle ECU stopped responding")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    private lateinit var prefs: Prefs
    private val settingsRef = AtomicReference<Settings>()
    private val settings: Settings get() = settingsRef.get()

    private lateinit var gears: GearEstimator
    private lateinit var sounder: AlertSounder
    private lateinit var notifier: CarAlertNotifier
    private lateinit var tripHistory: TripHistory
    private lateinit var cng: CngTracker
    private val tripLock = Any()
    private val trip = TripComputer()
    private val perf = PerfTimer()
    private val alerts = AlertMonitor()
    private val closedThrottle = Metrics.ClosedThrottle()
    private var logger: CsvLogger? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentAddress: String? = null

    // Poll-loop state, touched only on the worker thread.
    private var adapterVoltage: Float? = null
    private var milOn: Boolean? = null
    private var lastGear = GearReading.UNKNOWN
    private var lastGearFlushAt = 0L
    private var lastNotifyAt = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        settingsRef.set(prefs.snapshot())
        gears = GearEstimator(PrefsGearStore(prefs))
        sounder = AlertSounder(this)
        notifier = CarAlertNotifier(this)
        tripHistory = TripHistory(this)
        cng = CngTracker(prefs)
        applySettings()
        prefs.registerListener(prefsListener)
        createChannel()
    }

    /** Settings take effect live; only fast polling and logging wait for the next connection. */
    private fun applySettings() {
        val s = prefs.snapshot()
        settingsRef.set(s)
        gears.configure(s.transmission, s.gearCount, s.shiftUpRpm)
        sounder.setVoiceEnabled(s.alertVoice)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopWork()
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: prefs.lastDeviceAddress
        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground(buildNotification("Connecting..."))
        start(address)
        return START_STICKY
    }

    private fun start(address: String) {
        if (worker?.isActive == true) {
            if (address == currentAddress) return
            // The user picked a different adapter, so drop the existing session.
            worker?.cancel()
        }
        currentAddress = address
        acquireWakeLock()
        worker = scope.launch { runConnection(address) }
    }

    private fun stopWork() {
        worker?.cancel()
        worker = null
        currentAddress = null
        saveTrip()
        synchronized(tripLock) { trip.reset() }
        gears.flush()
        cng.flush()
        logger?.stop()
        logger = null
        releaseWakeLock()
        clearLive()
        ObdRepository.setState(ConnState.DISCONNECTED, "Disconnected")
    }

    override fun onDestroy() {
        stopWork()
        prefs.unregisterListener(prefsListener)
        sounder.release()
        scope.cancel()
        super.onDestroy()
    }

    /** Connects, and keeps retrying with a growing delay while the service lives. */
    private suspend fun runConnection(address: String) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            var socket: ObdSocket? = null
            var replayedProtocol = false
            try {
                val adapter = bluetoothAdapter()
                    ?: throw IOException("Bluetooth is not available on this device")
                if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")

                ObdRepository.setState(ConnState.CONNECTING, "Connecting to adapter...")
                updateNotification("Connecting to adapter...")
                socket = ObdSocket(adapter.getRemoteDevice(address))
                socket.connect(adapter)

                ObdRepository.setState(ConnState.INITIALIZING, "Initialising ELM327...")
                updateNotification("Initialising...")
                val elm = Elm327(socket, ObdRepository::trace)
                val preferred = prefs.lastProtocol
                replayedProtocol = preferred != null
                elm.initialize(preferred)
                elm.protocol?.let { prefs.lastProtocol = it }

                attempt = 0
                runSession(elm)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                ObdRepository.trace("!! " + reason)
                ObdRepository.setState(ConnState.ERROR, reason)
                updateNotification("Reconnecting: " + reason)
                // A remembered protocol that no longer answers is the usual culprit; auto-detect next time.
                if (e is ObdResponse.ObdError && replayedProtocol) prefs.lastProtocol = null
            } finally {
                logger?.stop()
                logger = null
                socket?.close()
                clearLive()
            }

            attempt++
            val backoff = (2000L * attempt).coerceAtMost(15000L)
            ObdRepository.setStatusMessage("Retrying in " + backoff / 1000 + "s...")
            delay(backoff)
        }
    }

    /**
     * One adapter connection. Survives the ignition going off and on again: the
     * adapter stays powered from the OBD port, so there is no need to reconnect
     * Bluetooth, only to wait for the ECU.
     */
    private suspend fun runSession(elm: Elm327) {
        ObdRepository.setStatusMessage("Reading supported PIDs...")
        val advertised = elm.discoverSupportedPids().ifEmpty { DEFAULT_PIDS }
        val pollable = Pids.ALL.filter { it.id in advertised }
        ObdRepository.publishSupported(pollable.map { it.id }.toSet())

        readVehicleInfo(elm)
        refreshDiagnostics(elm)

        val fastIds = pollable.filter { it.tier == Pids.Tier.FAST }.map { it.id }.toSet()
        ObdRepository.setStatusMessage("Tuning polling speed...")
        elm.configureFastMode(settings.fastPolling, advertised, fastIds)
        ObdRepository.publishPollMode(elm.modeLabel)

        if (settings.logging) logger = CsvLogger(this).also { it.start(advertised) }

        // PIDs the ECU advertises but never answers, dropped for the rest of the session.
        val dropped = HashSet<Int>()
        while (currentCoroutineContext().isActive) {
            try {
                pollLoop(elm, pollable, dropped)
            } catch (lost: EcuLostException) {
                ObdRepository.trace("-- " + lost.message)
                clearLive()
                waitForEcu(elm)
                // Modules can wake in a different state, so probe the speedups again.
                elm.configureFastMode(settings.fastPolling, advertised, fastIds)
                ObdRepository.publishPollMode(elm.modeLabel)
            }
        }
    }

    private suspend fun waitForEcu(elm: Elm327) {
        ObdRepository.setState(ConnState.WAITING_FOR_ECU, "Waiting for ignition...")
        updateNotification("Waiting for ignition")
        gears.flush()
        cng.flush()
        while (currentCoroutineContext().isActive) {
            // The adapter reads battery voltage off the port even with the car off.
            adapterVoltage = elm.readBatteryVoltage()
            ObdRepository.publishLive(
                ObdRepository.Live(
                    derived = ObdRepository.Derived(adapterVoltage = adapterVoltage),
                    at = System.currentTimeMillis()
                )
            )
            drainTasks(elm)
            if (elm.ecuResponds()) return
            delay(ECU_PROBE_INTERVAL_MS)
        }
    }

    /** Polls until the ECU stops answering, which it signals by throwing [EcuLostException]. */
    private suspend fun pollLoop(elm: Elm327, pollable: List<Pids.Pid>, dropped: MutableSet<Int>) {
        val scheduler = PollScheduler(pollable.filter { it.id !in dropped })
        val live = HashMap<Int, Float>()
        val updatedAt = HashMap<Int, Long>()
        val misses = HashMap<Int, Int>()
        var emptyCycles = 0
        var cycle = 0L
        var rateWindowStart = System.currentTimeMillis()
        var rateCommands = elm.commandsSent

        startTripIfNeeded()
        alerts.reset()
        ObdRepository.setState(ConnState.CONNECTED, "Connected")

        while (currentCoroutineContext().isActive) {
            elm.quietPolling = !ObdRepository.verboseTrace
            val cycleStart = System.currentTimeMillis()
            val fast = scheduler.fastFor(cycle, elm.batching)
            val due = scheduler.takeDue(cycleStart, if (elm.batching) BATCHED_EXTRAS else SINGLE_EXTRAS)
            val requested = fast + due.mapNotNull { it.pid }
            val results = elm.readPids(requested)
            for (item in due) {
                when (item.special) {
                    PollScheduler.Special.ADAPTER_VOLTAGE -> adapterVoltage = elm.readBatteryVoltage()
                    PollScheduler.Special.MIL_STATUS -> elm.readStatus()?.let {
                        milOn = it.milOn
                        ObdRepository.publishMilStatus(it)
                    }
                    null -> Unit
                }
            }

            val now = System.currentTimeMillis()
            val fresh = HashSet<Int>()
            for (pid in requested) {
                val value = results[pid.id]
                if (value != null) {
                    live[pid.id] = value
                    updatedAt[pid.id] = now
                    misses.remove(pid.id)
                    fresh += pid.id
                    ObdRepository.history.add(pid.id, value, now)
                } else {
                    misses[pid.id] = (misses[pid.id] ?: 0) + 1
                }
            }
            // A value that stops arriving stays on screen briefly, then blanks,
            // so one dropped reply does not make a tile flicker.
            live.keys.retainAll { now - (updatedAt[it] ?: 0L) <= STALE_MS }

            if (fresh.isEmpty() && requested.isNotEmpty()) {
                emptyCycles++
                if (emptyCycles == SAFE_MODE_AFTER && elm.fastModeActive) {
                    ObdRepository.trace("-- no answers in fast mode; falling back to standard polling")
                    elm.resetToSafeMode()
                    ObdRepository.publishPollMode(elm.modeLabel)
                }
                if (emptyCycles >= ECU_LOST_AFTER) throw EcuLostException()
            } else {
                emptyCycles = 0
                dropSilentPids(misses, scheduler, dropped, live)
            }

            publishCycle(live, fresh, now)
            drainTasks(elm)
            cycle++

            if (now - rateWindowStart >= 1000) {
                ObdRepository.publishPollRate((elm.commandsSent - rateCommands) * 1000f / (now - rateWindowStart))
                rateCommands = elm.commandsSent
                rateWindowStart = now
            }
            if (now - lastGearFlushAt >= GEAR_FLUSH_MS) {
                gears.flush()
                cng.flush()
                lastGearFlushAt = now
            }
        }
    }

    /** Stops polling PIDs the ECU advertises but never answers, while others still do. */
    private fun dropSilentPids(
        misses: MutableMap<Int, Int>,
        scheduler: PollScheduler,
        dropped: MutableSet<Int>,
        live: MutableMap<Int, Float>
    ) {
        val silent = misses.filter { it.value >= DROP_AFTER_MISSES && it.key !in NEVER_DROP }.keys
        if (silent.isEmpty()) return
        for (id in silent) {
            scheduler.remove(id)
            dropped += id
            misses.remove(id)
            live.remove(id)
            ObdRepository.trace("-- PID " + "%02X".format(id) + " advertised but never answers; no longer polled")
        }
        ObdRepository.publishSupported(scheduler.pollableIds)
    }

    /**
     * Continues the trip across short dropouts. A long gap means the car was
     * parked, so the old trip goes to history and a new one starts.
     */
    private fun startTripIfNeeded() {
        val now = System.currentTimeMillis()
        val last = synchronized(tripLock) { trip.lastSampleAt }
        if (last != 0L && now - last < TRIP_GAP_MS) return
        saveTrip()
        synchronized(tripLock) { trip.reset() }
        perf.reset()
    }

    private fun saveTrip() {
        val record = synchronized(tripLock) { trip.toRecord() } ?: return
        tripHistory.add(record)
        ObdRepository.tripSaved()
    }

    /** Recomputes everything derived from the raw readings and publishes one snapshot. */
    private fun publishCycle(live: Map<Int, Float>, fresh: Set<Int>, now: Long) {
        val s = settings
        val readings = HashMap(live)

        val throttle = readings[Pids.THROTTLE]
        if (throttle != null && Pids.THROTTLE in fresh) closedThrottle.update(throttle)
        val throttleClosed = throttle?.let { closedThrottle.isClosed(it) }

        val fuel = FuelSelector.active(s, readings)
        val fuelFromEcu = s.fuelSystem.isBiFuel && s.fuelDetection == FuelDetection.AUTO &&
                FuelSelector.reported(s, readings) != null
        val fuelCut = Metrics.isFuelCut(readings, throttleClosed)
        val fuelRate = if (fuelCut) 0f else Metrics.fuelRatePerHour(readings, fuel, throttleClosed, s)
        val speed = readings[Pids.SPEED]
        val rpm = readings[Pids.RPM]
        val boost = Metrics.boostKpa(readings)
        val power = Metrics.powerKw(readings)

        // Only a fresh pair of RPM and speed describes one instant; a held value would skew the ratio.
        lastGear = when {
            speed == null || rpm == null -> GearReading.UNKNOWN
            Pids.SPEED in fresh && Pids.RPM in fresh -> gears.update(speed, rpm, throttleClosed, now)
            else -> lastGear
        }

        val speedFresh = Pids.SPEED in fresh
        val tripStats = synchronized(tripLock) {
            val step = trip.update(
                readings, fuel, fuelRate, power, boost, speedFresh, s,
                cng.remainingKg(), cng.lifetimeKmPerKg(), now
            )
            if (step != null && step.fuel == Fuel.CNG) cng.burn(step.amount, step.km)
            trip.stats
        }
        if (speedFresh) perf.update(speed, now)

        val derived = ObdRepository.Derived(
            fuel = fuel,
            fuelFromEcu = fuelFromEcu,
            fuelRate = fuelRate,
            consumptionPer100 = Metrics.consumptionPer100Km(fuelRate, speed),
            boostKpa = boost,
            powerKw = power,
            torqueNm = Metrics.torqueNm(readings),
            adapterVoltage = adapterVoltage,
            fuelCut = fuelCut
        )
        val snapshot = ObdRepository.Live(readings, derived, lastGear, now)

        val alertResult = alerts.evaluate(snapshot, milOn, tripStats, s, now)
        alertResult.fired.forEach {
            sounder.play(it, s.alertBeep, s.alertVoice)
            // Pops up over Google Maps or any other app when no dashboard is on screen.
            notifier.post(it, snapshot, tripStats, s)
        }
        if (alertResult.changed) {
            ObdRepository.publishAlerts(alertResult.active)
            notifier.retain(alertResult.active)
        }

        ObdRepository.publishLive(snapshot)
        ObdRepository.publishTrip(tripStats)
        ObdRepository.publishPerf(perf.results)
        logger?.write(snapshot, tripStats.elapsedSeconds)

        if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS) {
            lastNotifyAt = now
            val gearText = if (lastGear.label == "-") "" else "   gear " + lastGear.label
            val fuelText = if (s.fuelSystem.isBiFuel) "   " + fuel.label else ""
            updateNotification("%.0f km/h   %.0f rpm".format(speed ?: 0f, rpm ?: 0f) + gearText + fuelText)
        }
    }

    /** Runs screen-initiated requests between poll cycles. */
    private fun drainTasks(elm: Elm327) {
        while (true) {
            val task = ObdRepository.tasks.tryReceive().getOrNull() ?: return
            when (task) {
                is ObdRepository.Task.ReadDtcs -> refreshDiagnostics(elm)
                is ObdRepository.Task.ClearDtcs -> {
                    val ok = elm.clearDtcs()
                    ObdRepository.trace(if (ok) "-- codes cleared" else "-- clear rejected")
                    refreshDiagnostics(elm)
                }
                is ObdRepository.Task.ReadVehicleInfo -> readVehicleInfo(elm)
                is ObdRepository.Task.ResetTrip -> {
                    saveTrip()
                    val stats = synchronized(tripLock) {
                        trip.reset()
                        trip.stats
                    }
                    ObdRepository.publishTrip(stats)
                }
                is ObdRepository.Task.ResetPerf -> {
                    perf.reset()
                    ObdRepository.publishPerf(perf.results)
                }
                is ObdRepository.Task.ResetGears -> {
                    gears.reset()
                    lastGear = GearReading.UNKNOWN
                    ObdRepository.trace("-- learned gears cleared")
                }
                is ObdRepository.Task.CngFilled -> {
                    cng.refill(task.kg)
                    ObdRepository.trace("-- CNG fill-up logged: %.1f kg".format(task.kg))
                }
                is ObdRepository.Task.Raw -> elm.sendRaw(task.command)
            }
        }
    }

    private fun refreshDiagnostics(elm: Elm327) {
        elm.readStatus()?.let {
            milOn = it.milOn
            ObdRepository.publishMilStatus(it)
        }
        val codes = elm.readAllDtcs()
        ObdRepository.publishDtcs(codes)
        val hasStored = codes.any { it.kind == Dtc.Kind.STORED }
        ObdRepository.publishFreezeFrame(if (hasStored) elm.readFreezeFrame() else null)
    }

    private fun readVehicleInfo(elm: Elm327) {
        ObdRepository.publishVehicleInfo(
            ObdRepository.VehicleInfo(
                vin = elm.readVin(),
                calibrationId = elm.readCalibrationId(),
                ecuName = elm.readEcuName(),
                protocol = elm.protocol,
                adapterId = elm.adapterId
            )
        )
    }

    /** Blanks live data and withdraws alert pop-ups, so nothing stale is left showing. */
    private fun clearLive() {
        ObdRepository.clearLiveData()
        notifier.cancelAll()
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // --- foreground notification and wake lock ---

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "OBD2 connection", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DashboardActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD2 Dashboard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun enterForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obd2dash:poll").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_CONNECT = "com.obd2dash.CONNECT"
        const val ACTION_DISCONNECT = "com.obd2dash.DISCONNECT"
        const val EXTRA_ADDRESS = "address"
        private const val CHANNEL_ID = "obd_connection"
        private const val NOTIFICATION_ID = 42

        /** How long a value that stopped arriving stays on screen. */
        private const val STALE_MS = 5_000L

        /** Empty cycles before fast mode is abandoned, then before the ECU counts as gone. */
        private const val SAFE_MODE_AFTER = 3
        private const val ECU_LOST_AFTER = 8

        private const val DROP_AFTER_MISSES = 10
        private const val BATCHED_EXTRAS = 6
        private const val SINGLE_EXTRAS = 2
        private const val ECU_PROBE_INTERVAL_MS = 3_000L
        private const val TRIP_GAP_MS = 5 * 60_000L
        private const val GEAR_FLUSH_MS = 60_000L
        private const val NOTIFY_INTERVAL_MS = 2_000L

        /** The gauges and the gear estimate depend on these, so they are never dropped. */
        private val NEVER_DROP = setOf(Pids.RPM, Pids.SPEED)

        /** Used when an ECU refuses to report its support bitmask. */
        private val DEFAULT_PIDS = setOf(
            0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1F, 0x2F, 0x42, 0x46
        )

        fun connect(context: Context, address: String) {
            val intent = Intent(context, ObdService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_ADDRESS, address)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, ObdService::class.java).apply { action = ACTION_DISCONNECT })
        }
    }
}
