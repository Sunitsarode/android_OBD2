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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.obd2dash.R
import com.obd2dash.bluetooth.ObdSocket
import com.obd2dash.core.CsvLogger
import com.obd2dash.core.Metrics
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.ObdRepository.ConnState
import com.obd2dash.core.PerfTimer
import com.obd2dash.core.Prefs
import com.obd2dash.core.TripComputer
import com.obd2dash.obd.Elm327
import com.obd2dash.obd.Pids
import com.obd2dash.ui.DashboardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Owns the adapter connection and runs the polling loop for as long as the app
 * is driving, independent of which screen is visible.
 */
class ObdService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    private lateinit var prefs: Prefs
    private val trip = TripComputer()
    private val perf = PerfTimer()
    private val gears = Metrics.GearEstimator()
    private var logger: CsvLogger? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentAddress: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopWork()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val address = intent?.getStringExtra(EXTRA_ADDRESS)
                    ?: prefs.lastDeviceAddress
                if (address == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                enterForeground(buildNotification("Connecting..."))
                start(address)
            }
        }
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
        logger?.stop()
        releaseWakeLock()
        ObdRepository.clearLiveData()
        ObdRepository.setState(ConnState.DISCONNECTED, "Disconnected")
    }

    override fun onDestroy() {
        stopWork()
        scope.cancel()
        super.onDestroy()
    }

    /** Connects, and keeps retrying with a growing delay while the service lives. */
    private suspend fun runConnection(address: String) {
        var attempt = 0
        while (scope.isActive) {
            var socket: ObdSocket? = null
            try {
                val adapter = bluetoothAdapter()
                    ?: throw IOException("Bluetooth is not available on this device")
                if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")

                ObdRepository.setState(ConnState.CONNECTING, "Connecting to adapter...")
                updateNotification("Connecting to adapter...")

                val device = adapter.getRemoteDevice(address)
                socket = ObdSocket(device)
                socket.connect(adapter)

                ObdRepository.setState(ConnState.INITIALIZING, "Initialising ELM327...")
                updateNotification("Initialising...")

                val elm = Elm327(socket, ObdRepository::trace)
                elm.initialize(prefs.lastProtocol)
                elm.protocol?.let { prefs.lastProtocol = it }

                attempt = 0
                pollForever(elm)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                ObdRepository.trace("!! " + reason)
                ObdRepository.setState(ConnState.ERROR, reason)
                updateNotification("Reconnecting: " + reason)
                // A failed protocol replay is often the cause, so fall back to auto-detect.
                prefs.lastProtocol = null
            } finally {
                socket?.close()
                ObdRepository.clearLiveData()
            }

            attempt++
            val backoff = (2000L * attempt).coerceAtMost(15000L)
            ObdRepository.setStatusMessage("Retrying in " + (backoff / 1000) + "s...")
            delay(backoff)
        }
    }

    /**
     * Polls the supported PIDs by tier until the connection drops.
     *
     * Fast PIDs are read every cycle, medium every fourth, slow every twentieth,
     * which keeps RPM and speed responsive on adapters that manage only a
     * handful of queries per second.
     */
    private suspend fun pollForever(elm: Elm327) {
        ObdRepository.setStatusMessage("Reading supported PIDs...")
        val supported = elm.discoverSupportedPids().ifEmpty { DEFAULT_PIDS }
        val pollable = Pids.ALL.filter { supported.contains(it.id) }
        ObdRepository.publishSupported(pollable.map { it.id }.toSet())

        readVehicleInfo(elm)
        elm.readStatus()?.let { ObdRepository.publishMilStatus(it) }
        ObdRepository.publishDtcs(elm.readAllDtcs())

        if (prefs.loggingEnabled) {
            logger = CsvLogger(this).also { it.start(supported) }
        }

        trip.reset()
        perf.reset()
        gears.reset()

        ObdRepository.setState(ConnState.CONNECTED, "Connected")
        val live = HashMap<Int, Float>()
        var cycle = 0L
        var cycleStartedAt = System.currentTimeMillis()
        var queriesThisSecond = 0

        while (scope.isActive) {
            for (pid in pollable) {
                if (cycle % pid.tier.interval != 0L) continue
                val value = elm.readPid(pid)
                queriesThisSecond++
                if (value != null) live[pid.id] = value else live.remove(pid.id)
            }

            publishCycle(live)
            drainTasks(elm)

            cycle++
            val now = System.currentTimeMillis()
            if (now - cycleStartedAt >= 1000) {
                ObdRepository.publishPollRate(queriesThisSecond * 1000f / (now - cycleStartedAt))
                queriesThisSecond = 0
                cycleStartedAt = now
            }
        }
    }

    /** Recomputes everything derived from the raw readings and pushes it out. */
    private fun publishCycle(live: Map<Int, Float>) {
        val snapshot = HashMap(live)
        val fuelRate = Metrics.fuelRateLitresPerHour(snapshot, prefs)
        val speed = snapshot[Pids.SPEED]
        val boost = Metrics.boostKpa(snapshot)
        val power = Metrics.powerKw(snapshot, prefs)
        val gear = gears.update(speed, snapshot[Pids.RPM])

        val consumption = Metrics.consumptionPer100Km(fuelRate, speed)

        trip.update(snapshot, fuelRate, power, boost, prefs)
        perf.update(speed)

        ObdRepository.publishReadings(snapshot)
        ObdRepository.publishDerived(
            ObdRepository.Derived(
                fuelRateLh = fuelRate,
                consumptionL100 = consumption,
                boostKpa = boost,
                powerKw = power,
                torqueNm = Metrics.torqueNm(snapshot),
                gear = gear
            )
        )
        ObdRepository.publishTrip(trip.stats)
        ObdRepository.publishPerf(perf.results)

        logger?.write(
            snapshot,
            trip.stats.elapsedSeconds,
            fuelRate,
            consumption,
            boost,
            power,
            gear
        )

        updateNotification(
            "%.0f km/h   %.0f rpm".format(speed ?: 0f, snapshot[Pids.RPM] ?: 0f)
        )
    }

    /** Runs any screen-initiated requests between poll cycles. */
    private fun drainTasks(elm: Elm327) {
        while (true) {
            val task = ObdRepository.tasks.tryReceive().getOrNull() ?: return
            when (task) {
                is ObdRepository.Task.ReadDtcs -> {
                    ObdRepository.publishDtcs(elm.readAllDtcs())
                    ObdRepository.publishMilStatus(elm.readStatus())
                }
                is ObdRepository.Task.ClearDtcs -> {
                    val ok = elm.clearDtcs()
                    ObdRepository.trace(if (ok) "-- codes cleared" else "-- clear rejected")
                    ObdRepository.publishDtcs(elm.readAllDtcs())
                    ObdRepository.publishMilStatus(elm.readStatus())
                }
                is ObdRepository.Task.ReadVehicleInfo -> readVehicleInfo(elm)
                is ObdRepository.Task.ResetTrip -> {
                    trip.reset()
                    gears.reset()
                    ObdRepository.publishTrip(trip.stats)
                }
                is ObdRepository.Task.ResetPerf -> {
                    perf.reset()
                    ObdRepository.publishPerf(perf.results)
                }
                is ObdRepository.Task.Raw -> elm.send(task.command, 5000)
            }
        }
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

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // --- foreground notification and wake lock ---

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OBD2 connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun enterForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else 0
        )
    }

    private var lastNotifyAt = 0L

    private fun updateNotification(text: String) {
        // The notification is secondary to the UI; rate limit it to save battery.
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 2000) return
        lastNotifyAt = now
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
            context.startService(
                Intent(context, ObdService::class.java).apply { action = ACTION_DISCONNECT }
            )
        }
    }
}
