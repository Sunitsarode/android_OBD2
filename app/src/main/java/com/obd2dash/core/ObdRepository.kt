package com.obd2dash.core

import com.obd2dash.obd.Dtc
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared live state between the polling service and the UI.
 *
 * The service is the only writer. Screens observe the flows, and send one-off
 * requests back through [submit] so they are executed on the polling thread
 * rather than competing with it for the socket.
 */
object ObdRepository {

    enum class ConnState { DISCONNECTED, CONNECTING, INITIALIZING, CONNECTED, ERROR }

    sealed class Task {
        object ReadDtcs : Task()
        object ClearDtcs : Task()
        object ReadVehicleInfo : Task()
        object ResetTrip : Task()
        object ResetPerf : Task()
        data class Raw(val command: String) : Task()
    }

    data class Derived(
        val fuelRateLh: Float? = null,
        val consumptionL100: Float? = null,
        val boostKpa: Float? = null,
        val powerKw: Float? = null,
        val torqueNm: Float? = null,
        val gear: Int? = null,
        val adapterVoltage: Float? = null
    )

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

    private val _readings = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val readings: StateFlow<Map<Int, Float>> = _readings.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    private val _derived = MutableStateFlow(Derived())
    val derived: StateFlow<Derived> = _derived.asStateFlow()

    private val _trip = MutableStateFlow(TripStats())
    val trip: StateFlow<TripStats> = _trip.asStateFlow()

    private val _perf = MutableStateFlow(PerfResults())
    val perf: StateFlow<PerfResults> = _perf.asStateFlow()

    private val _dtcs = MutableStateFlow<List<Dtc.Entry>>(emptyList())
    val dtcs: StateFlow<List<Dtc.Entry>> = _dtcs.asStateFlow()

    private val _milStatus = MutableStateFlow<Dtc.Status?>(null)
    val milStatus: StateFlow<Dtc.Status?> = _milStatus.asStateFlow()

    private val _vehicleInfo = MutableStateFlow(VehicleInfo())
    val vehicleInfo: StateFlow<VehicleInfo> = _vehicleInfo.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _pollRateHz = MutableStateFlow(0f)
    val pollRateHz: StateFlow<Float> = _pollRateHz.asStateFlow()

    /** One-off requests for the service loop to run between poll cycles. */
    val tasks = Channel<Task>(Channel.UNLIMITED)

    fun submit(task: Task) {
        tasks.trySend(task)
    }

    // --- writes, called from the service ---

    fun setState(state: ConnState, message: String? = null) {
        _connectionState.value = state
        message?.let { _statusMessage.value = it }
    }

    fun setStatusMessage(message: String) {
        _statusMessage.value = message
    }

    fun publishReadings(readings: Map<Int, Float>) {
        _readings.value = readings
    }

    fun publishSupported(pids: Set<Int>) {
        _supportedPids.value = pids
    }

    fun publishDerived(derived: Derived) {
        _derived.value = derived
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

    fun publishVehicleInfo(info: VehicleInfo) {
        _vehicleInfo.value = info
    }

    fun publishPollRate(hz: Float) {
        _pollRateHz.value = hz
    }

    fun trace(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        // Bounded so a long drive cannot grow the console without limit.
        val next = _console.value + trimmed
        _console.value = if (next.size > CONSOLE_LIMIT) next.takeLast(CONSOLE_LIMIT) else next
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    /** Wipes live data on disconnect so stale numbers are not shown as current. */
    fun clearLiveData() {
        _readings.value = emptyMap()
        _derived.value = Derived()
        _pollRateHz.value = 0f
    }

    private const val CONSOLE_LIMIT = 400
}
