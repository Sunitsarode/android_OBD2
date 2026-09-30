package com.obd2dash.core

import com.obd2dash.obd.Dtc
import com.obd2dash.obd.Pids
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared live state between the polling service and the UI.
 *
 * The service is the only writer. Screens observe the flows and send one-off
 * requests back through [submit], so those run on the polling thread instead
 * of competing with it for the socket.
 */
object ObdRepository {

    enum class ConnState { DISCONNECTED, CONNECTING, INITIALIZING, CONNECTED, WAITING_FOR_ECU, ERROR }

    sealed class Task {
        object ReadDtcs : Task()
        object ClearDtcs : Task()
        object ReadVehicleInfo : Task()
        object ResetTrip : Task()
        object ResetPerf : Task()
        object ResetGears : Task()
        data class Raw(val command: String) : Task()
    }

    data class Derived(
        val fuelRateLh: Float? = null,
        val consumptionL100: Float? = null,
        val boostKpa: Float? = null,
        val powerKw: Float? = null,
        val torqueNm: Float? = null,
        val adapterVoltage: Float? = null,
        val fuelCut: Boolean = false
    )

    /** Everything the live screens draw, published once per poll cycle. */
    data class Live(
        val readings: Map<Int, Float> = emptyMap(),
        val derived: Derived = Derived(),
        val gear: GearReading = GearReading.UNKNOWN,
        val at: Long = 0L
    ) {
        /** Battery voltage measured by the adapter, falling back to the ECU's own figure. */
        val batteryVolts: Float? get() = derived.adapterVoltage ?: readings[Pids.MODULE_VOLTAGE]
    }

    data class VehicleInfo(
        val vin: String? = null,
        val calibrationId: String? = null,
        val ecuName: String? = null,
        val protocol: String? = null,
        val adapterId: String? = null
    )

    private val _connectionState = MutableStateFlow(ConnState.DISCONNECTED)
    val connectionState: StateFlow<ConnState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    private val _trip = MutableStateFlow(TripStats())
    val trip: StateFlow<TripStats> = _trip.asStateFlow()

    private val _perf = MutableStateFlow(PerfResults())
    val perf: StateFlow<PerfResults> = _perf.asStateFlow()

    private val _dtcs = MutableStateFlow<List<Dtc.Entry>>(emptyList())
    val dtcs: StateFlow<List<Dtc.Entry>> = _dtcs.asStateFlow()

    private val _milStatus = MutableStateFlow<Dtc.Status?>(null)
    val milStatus: StateFlow<Dtc.Status?> = _milStatus.asStateFlow()

    private val _freezeFrame = MutableStateFlow<Dtc.FreezeFrame?>(null)
    val freezeFrame: StateFlow<Dtc.FreezeFrame?> = _freezeFrame.asStateFlow()

    private val _vehicleInfo = MutableStateFlow(VehicleInfo())
    val vehicleInfo: StateFlow<VehicleInfo> = _vehicleInfo.asStateFlow()

    private val _alerts = MutableStateFlow<Set<AlertMonitor.Alert>>(emptySet())
    val alerts: StateFlow<Set<AlertMonitor.Alert>> = _alerts.asStateFlow()

    private val _pollRateHz = MutableStateFlow(0f)
    val pollRateHz: StateFlow<Float> = _pollRateHz.asStateFlow()

    private val _pollMode = MutableStateFlow("")
    val pollMode: StateFlow<String> = _pollMode.asStateFlow()

    /** Bumped whenever a finished trip is saved, so the history list can reload. */
    private val _tripHistoryVersion = MutableStateFlow(0)
    val tripHistoryVersion: StateFlow<Int> = _tripHistoryVersion.asStateFlow()

    val history = PidHistory()

    /** One-off requests for the service loop to run between poll cycles. */
    val tasks = Channel<Task>(Channel.UNLIMITED)

    fun submit(task: Task) {
        tasks.trySend(task)
    }

    /** Set when the user disconnects, so the connect screen does not immediately reconnect. */
    @Volatile
    var userDisconnected = false

    /** Whether routine polling traffic is written to the console. */
    @Volatile
    var verboseTrace = false

    // --- console: a bounded ring buffer the console screen polls, never a flow ---

    private val consoleLock = Any()
    private val consoleLines = ArrayDeque<String>(CONSOLE_LIMIT)
    private val consoleCounter = AtomicLong()

    /** Changes whenever a line is added or the console is cleared. */
    val consoleVersion: Long get() = consoleCounter.get()

    fun trace(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        synchronized(consoleLock) {
            if (consoleLines.size >= CONSOLE_LIMIT) consoleLines.removeFirst()
            consoleLines.addLast(trimmed)
        }
        consoleCounter.incrementAndGet()
    }

    fun consoleSnapshot(): List<String> = synchronized(consoleLock) { consoleLines.toList() }

    fun clearConsole() {
        synchronized(consoleLock) { consoleLines.clear() }
        consoleCounter.incrementAndGet()
    }

    // --- writes, called from the service ---

    fun setState(state: ConnState, message: String? = null) {
        _connectionState.value = state
        message?.let { _statusMessage.value = it }
    }

    fun setStatusMessage(message: String) {
        _statusMessage.value = message
    }

    fun publishLive(live: Live) {
        _live.value = live
    }

    fun publishSupported(pids: Set<Int>) {
        _supportedPids.value = pids
    }

    fun publishTrip(stats: TripStats) {
        _trip.value = stats
    }

    fun publishPerf(results: PerfResults) {
        _perf.value = results
    }

    fun publishDtcs(entries: List<Dtc.Entry>) {
        _dtcs.value = entries
    }

    fun publishMilStatus(status: Dtc.Status?) {
        _milStatus.value = status
    }

    fun publishFreezeFrame(frame: Dtc.FreezeFrame?) {
        _freezeFrame.value = frame
    }

    fun publishVehicleInfo(info: VehicleInfo) {
        _vehicleInfo.value = info
    }

    fun publishAlerts(active: Set<AlertMonitor.Alert>) {
        _alerts.value = active
    }

    fun publishPollRate(hz: Float) {
        _pollRateHz.value = hz
    }

    fun publishPollMode(mode: String) {
        _pollMode.value = mode
    }

    fun tripSaved() {
        _tripHistoryVersion.value = _tripHistoryVersion.value + 1
    }

    /** Wipes live data when the connection drops, so stale numbers are not shown as current. */
    fun clearLiveData() {
        _live.value = Live()
        _alerts.value = emptySet()
        _pollRateHz.value = 0f
    }

    private const val CONSOLE_LIMIT = 400
}
