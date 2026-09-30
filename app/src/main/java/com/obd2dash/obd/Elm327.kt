package com.obd2dash.obd

import com.obd2dash.bluetooth.ObdSocket

/**
 * Drives an ELM327-compatible adapter: configuration, PID reads, and the
 * diagnostic modes.
 *
 * Fast mode stacks three optional speedups. Each is probed at connect time and
 * skipped if the adapter or car does not handle it:
 *  - addressing the engine ECU directly (11-bit CAN), so only one module answers;
 *  - a response-count suffix, so the adapter returns the moment that answer
 *    arrives instead of waiting out its timeout for other modules;
 *  - several PIDs per request, which ISO 15765-4 requires ECUs to accept.
 *
 * Every method blocks on the Bluetooth socket, so this runs on an IO thread.
 */
class Elm327(private val socket: ObdSocket, private val trace: (String) -> Unit) {

    var adapterId: String = "unknown"
        private set

    /** Protocol number reported by ATDPN, reusable to skip auto-detection later. */
    var protocol: String? = null
        private set

    /** Suppresses tracing of routine polling traffic, which would flood the console. */
    @Volatile
    var quietPolling: Boolean = true

    /** Total commands sent, for the queries-per-second readout. */
    var commandsSent: Long = 0L
        private set

    var batching = false
        private set
    var countSuffix = false
        private set
    var ecmTargeted = false
        private set
    private var headerIsEcm = false

    /** PIDs only another module answers; read with broadcast addressing. */
    private var otherEcuPids: Set<Int> = emptySet()

    /** PIDs that went missing from batches but answer on their own. */
    private val singleOnly = HashSet<Int>()
    private val batchStrikes = HashMap<Int, Int>()

    val isCan: Boolean get() = protocol?.let { it in CAN_PROTOCOLS } == true
    val fastModeActive: Boolean get() = batching || countSuffix || ecmTargeted

    val modeLabel: String
        get() = if (!fastModeActive) "standard" else listOfNotNull(
            if (batching) "batched" else null,
            if (countSuffix) "fast-return" else null,
            if (ecmTargeted) "ECM-direct" else null
        ).joinToString(" + ")

    fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT, quiet: Boolean = false): String {
        commandsSent++
        if (!quiet) trace(">> " + command)
        val reply = socket.request(command, timeoutMs)
        if (!quiet) trace("<< " + reply.replace('\r', ' ').trim())
        return reply
    }

    /**
     * Runs the configuration sequence. Passing a previously detected [preferred]
     * protocol skips the slow auto-detect on reconnects.
     */
    fun initialize(preferred: String?) {
        send("ATZ", 5000)
        Thread.sleep(1000)
        adapterId = send("ATI", 3000).replace('\r', ' ').trim().ifBlank { "unknown" }
        applyFormatting()
        if (preferred.isNullOrBlank()) send("ATSP0") else send("ATSP" + preferred)

        // The first real query triggers protocol detection, and a cold bus often
        // needs a couple of attempts.
        var connected = false
        for (attempt in 1..3) {
            val reply = send("0100", PROBE_TIMEOUT)
            if (ObdResponse.errorOf(reply) == null && ObdResponse.dataFor(0x01, 0x00, 4, reply) != null) {
                connected = true
                break
            }
            Thread.sleep(500)
        }
        if (!connected) throw ObdResponse.ObdError("No response from vehicle ECU")

        protocol = send("ATDPN").replace('\r', ' ').trim().removePrefix("A").take(1).ifBlank { null }
    }

    private fun applyFormatting() {
        send("ATE0")   // echo off
        send("ATL0")   // no linefeeds
        send("ATS0")   // no spaces in replies
        send("ATH0")   // no CAN headers
        send("ATAT1")  // adaptive timing
    }

    /**
     * Probes and enables whichever speedups this adapter and car support.
     * [supported] is the broadcast PID set; [fastPids] are polled every cycle.
     */
    fun configureFastMode(enabled: Boolean, supported: Set<Int>, fastPids: Set<Int>) {
        resetToSafeMode(quiet = true)
        if (!enabled || !isCan) {
            trace("-- polling mode: " + modeLabel)
            return
        }
        if (protocol == "6" || protocol == "8") tryTargetEcm(supported, fastPids)
        if (ecmTargeted && readsCleanly("010C1", 0x0C, 2)) countSuffix = true

        val probe = send("010C0D" + (if (countSuffix) "1" else ""))
        val parsed = ObdResponse.multiPid(probe, mapOf(0x0C to 2, 0x0D to 1))
        batching = parsed.containsKey(0x0C) && parsed.containsKey(0x0D)
        trace("-- polling mode: " + modeLabel)
    }

    private fun tryTargetEcm(supported: Set<Int>, fastPids: Set<Int>) {
        if (!send("ATSH7E0").uppercase().contains("OK")) return
        headerIsEcm = true
        val ecmPids = discoverSupportedPids()
        val others = supported - ecmPids
        // A fast PID living in another module would need broadcasting every cycle,
        // which costs more than targeting saves.
        if (ecmPids.isEmpty() || others.any { it in fastPids }) {
            send("ATSH7DF")
            headerIsEcm = false
            return
        }
        ecmTargeted = true
        otherEcuPids = others
    }

    /** Returns to plain broadcast polling with no speedups. */
    fun resetToSafeMode(quiet: Boolean = false) {
        if (headerIsEcm) send("ATSH7DF", quiet = quiet)
        headerIsEcm = false
        ecmTargeted = false
        countSuffix = false
        batching = false
        otherEcuPids = emptySet()
        singleOnly.clear()
        batchStrikes.clear()
    }

    private fun readsCleanly(command: String, pid: Int, len: Int): Boolean {
        val reply = send(command)
        return ObdResponse.errorOf(reply) == null && ObdResponse.dataFor(0x01, pid, len, reply) != null
    }

    /** Reads [pids] with whatever speedups are active. Only answered PIDs are returned. */
    fun readPids(pids: List<Pids.Pid>): Map<Int, Float> {
        val out = HashMap<Int, Float>(pids.size * 2)
        val batchable = ArrayList<Pids.Pid>()
        for (pid in pids) {
            when {
                pid.id in otherEcuPids -> readFromOtherEcu(pid)?.let { out[pid.id] = it }
                batching && pid.len <= MAX_BATCH_LEN && pid.id !in singleOnly -> batchable += pid
                else -> readSingle(pid)?.let { out[pid.id] = it }
            }
        }
        for (batch in planBatches(batchable)) {
            if (batch.size == 1) {
                readSingle(batch[0])?.let { out[batch[0].id] = it }
                continue
            }
            val got = readBatch(batch)
            out.putAll(got)
            for (pid in batch) {
                if (pid.id in got) continue
                // Missing from the batch: ask alone to learn whether the PID or the batching is at fault.
                val alone = readSingle(pid) ?: continue
                out[pid.id] = alone
                val strikes = (batchStrikes[pid.id] ?: 0) + 1
                batchStrikes[pid.id] = strikes
                if (strikes >= BATCH_STRIKES) singleOnly += pid.id
            }
        }
        return out
    }

    private fun readSingle(pid: Pids.Pid): Float? {
        val suffix = if (countSuffix && headerIsEcm && pid.len <= SINGLE_FRAME_MAX_LEN) "1" else ""
        val reply = send("01" + pid.hex + suffix, DEFAULT_TIMEOUT, quietPolling)
        if (ObdResponse.errorOf(reply) != null) return null
        val data = ObdResponse.dataFor(0x01, pid.id, pid.len, reply) ?: return null
        return decode(pid, data)
    }

    private fun readBatch(batch: List<Pids.Pid>): Map<Int, Float> {
        val suffix = if (countSuffix && headerIsEcm) "1" else ""
        val reply = send("01" + batch.joinToString("") { it.hex } + suffix, DEFAULT_TIMEOUT, quietPolling)
        if (ObdResponse.errorOf(reply) != null) return emptyMap()
        val out = HashMap<Int, Float>()
        for ((id, data) in ObdResponse.multiPid(reply, batch.associate { it.id to it.len })) {
            val pid = Pids.BY_ID[id] ?: continue
            decode(pid, data)?.let { out[id] = it }
        }
        return out
    }

    private fun readFromOtherEcu(pid: Pids.Pid): Float? = broadcast {
        val reply = send("01" + pid.hex, DEFAULT_TIMEOUT, quietPolling)
        if (ObdResponse.errorOf(reply) != null) null
        else ObdResponse.dataFor(0x01, pid.id, pid.len, reply)?.let { decode(pid, it) }
    }

    private fun decode(pid: Pids.Pid, data: IntArray): Float? = try {
        pid.decode(data)
    } catch (_: Exception) {
        null
    }

    /**
     * Groups PIDs into requests of at most six. With the count suffix the reply
     * must fit one CAN frame, because the adapter returns after the first frame.
     * RPM and speed always share a request, so the gear estimate sees both from
     * the same instant.
     */
    private fun planBatches(pids: List<Pids.Pid>): List<List<Pids.Pid>> {
        if (pids.isEmpty()) return emptyList()
        val capacity = if (countSuffix && headerIsEcm) SINGLE_FRAME_PAYLOAD else Int.MAX_VALUE
        val bins = ArrayList<MutableList<Pids.Pid>>()
        val used = ArrayList<Int>()
        val rest = ArrayList(pids)

        val rpm = pids.firstOrNull { it.id == Pids.RPM }
        val speed = pids.firstOrNull { it.id == Pids.SPEED }
        if (rpm != null && speed != null) {
            bins += mutableListOf(rpm, speed)
            used += (1 + rpm.len) + (1 + speed.len)
            rest.remove(rpm)
            rest.remove(speed)
        }
        // First-fit decreasing: large PIDs first, each into the first request with room.
        for (pid in rest.sortedByDescending { it.len }) {
            val cost = 1 + pid.len
            val index = bins.indices.firstOrNull { bins[it].size < MAX_PIDS_PER_REQUEST && used[it] + cost <= capacity }
            if (index != null) {
                bins[index] += pid
                used[index] = used[index] + cost
            } else {
                bins += mutableListOf(pid)
                used += cost
            }
        }
        return bins
    }

    /** Runs [block] with broadcast addressing so every module can answer. */
    private inline fun <T> broadcast(block: () -> T): T {
        if (!headerIsEcm) return block()
        send("ATSH7DF", quiet = quietPolling)
        headerIsEcm = false
        try {
            return block()
        } finally {
            send("ATSH7E0", quiet = quietPolling)
            headerIsEcm = true
        }
    }

    /** Queries the support bitmasks and returns every PID any module answers. */
    fun discoverSupportedPids(): Set<Int> {
        val supported = LinkedHashSet<Int>()
        for (base in SUPPORT_BASES) {
            val reply = send("01" + "%02X".format(base))
            if (ObdResponse.errorOf(reply) != null) break
            val masks = ObdResponse.allDataFor(0x01, base, 4, reply)
            if (masks.isEmpty()) break
            val found = masks.flatMap { Pids.decodeSupportMask(base, it) }.toSet()
            supported.addAll(found)
            // The next bitmask is only worth asking for if this one says so.
            if (!found.contains(base + 0x20)) break
        }
        return supported
    }

    /** True once the ECU answers again after the ignition was off. */
    fun ecuResponds(): Boolean {
        val reply = send("0100", 4000, quiet = true)
        return ObdResponse.errorOf(reply) == null && ObdResponse.dataFor(0x01, 0x00, 4, reply) != null
    }

    /**
     * Mode 01 PID 01. The lamp is on if any module requests it; monitors come
     * from the first reply, which on CAN is the engine ECU.
     */
    fun readStatus(): Dtc.Status? {
        val reply = send("0101", DEFAULT_TIMEOUT, quietPolling)
        if (ObdResponse.errorOf(reply) != null) return null
        val all = ObdResponse.allDataFor(0x01, 0x01, 4, reply).mapNotNull { Dtc.decodeStatus(it) }
        if (all.isEmpty()) return null
        return all.first().copy(milOn = all.any { it.milOn }, dtcCount = all.sumOf { it.dtcCount })
    }

    /** Reads stored, pending, and permanent codes from every module. */
    fun readAllDtcs(): List<Dtc.Entry> = broadcast {
        val out = ArrayList<Dtc.Entry>()
        out += readDtcs("03", Dtc.Kind.STORED)
        out += readDtcs("07", Dtc.Kind.PENDING)
        out += readDtcs("0A", Dtc.Kind.PERMANENT)
        out.distinctBy { it.code + it.kind.name }
    }

    private fun readDtcs(mode: String, kind: Dtc.Kind): List<Dtc.Entry> {
        val reply = send(mode, LONG_TIMEOUT)
        // NO DATA is the normal answer when nothing is stored.
        if (ObdResponse.errorOf(reply) != null) return emptyList()
        return ObdResponse.bodiesFor(mode.toInt(16), reply).flatMap { Dtc.parse(it, kind) }
    }

    /** Clears codes and freeze frames in every module, and turns off the MIL. */
    fun clearDtcs(): Boolean = broadcast {
        val reply = send("04", LONG_TIMEOUT)
        ObdResponse.errorOf(reply) == null && ObdResponse.messages(reply).any { it.startsWith("44") }
    }

    /** The snapshot of conditions the ECU stored alongside its first trouble code. */
    fun readFreezeFrame(): Dtc.FreezeFrame? = broadcast {
        val dtcReply = send("020200", LONG_TIMEOUT)
        val dtcBytes = if (ObdResponse.errorOf(dtcReply) == null) ObdResponse.freezeFrameData(0x02, 2, dtcReply) else null
        val code = dtcBytes?.let { if (it[0] == 0 && it[1] == 0) null else Dtc.decodeCode(it[0], it[1]) }
        if (code == null) {
            null
        } else {
            val values = LinkedHashMap<Int, Float>()
            for (id in FREEZE_FRAME_PIDS) {
                val pid = Pids.BY_ID[id] ?: continue
                val reply = send("02" + pid.hex + "00")
                if (ObdResponse.errorOf(reply) != null) continue
                val data = ObdResponse.freezeFrameData(id, pid.len, reply) ?: continue
                decode(pid, data)?.let { values[id] = it }
            }
            Dtc.FreezeFrame(code, values)
        }
    }

    fun readVin(): String? = broadcast { readMode09Ascii(0x02, 17) }

    fun readCalibrationId(): String? = broadcast { readMode09Ascii(0x04, 16) }

    fun readEcuName(): String? = broadcast { readMode09Ascii(0x0A, 20) }

    /**
     * Mode 09 text. CAN sends it as one multi-frame message; older protocols
     * send numbered four-byte lines that have to be put back in order.
     */
    private fun readMode09Ascii(pid: Int, length: Int): String? {
        val reply = send("09" + "%02X".format(pid), LONG_TIMEOUT)
        if (ObdResponse.errorOf(reply) != null) return null
        val marker = "49" + "%02X".format(pid)
        val parts = ObdResponse.messages(reply).filter { it.startsWith(marker) }.map { it.substring(marker.length) }
        if (parts.isEmpty()) return null
        val candidates =
            if (parts.size > 1 && parts.all { it.length <= 10 }) listOf(parts.sortedBy { it.take(2) }.joinToString("") { it.drop(2) })
            else parts
        for (hex in candidates) {
            val text = ObdResponse.hexToBytes(hex)
                .map { it.toChar() }
                .filter { it.code in 0x20..0x7E }
                .joinToString("")
                .trim()
            if (text.isNotEmpty()) return if (text.length > length) text.takeLast(length) else text
        }
        return null
    }

    /** Adapter-measured battery voltage. Works even when the ECU is asleep. */
    fun readBatteryVoltage(): Float? {
        val reply = send("ATRV", 3000, quietPolling)
        return reply.replace('\r', ' ').trim().removeSuffix("V").trim().toFloatOrNull()
    }

    /**
     * Sends a command typed in the console, then restores the settings polling
     * depends on, in case the command changed or reset them.
     */
    fun sendRaw(command: String) {
        send(command, 5000)
        val cmd = command.uppercase().replace(" ", "")
        if (!cmd.startsWith("AT")) return
        if (cmd == "ATZ" || cmd == "ATD" || cmd == "ATWS") {
            Thread.sleep(1000)
            applyFormatting()
            send("ATSP" + (protocol ?: "0"))
        } else {
            applyFormatting()
        }
        if (headerIsEcm) send("ATSH7E0")
    }

    fun close() = socket.close()

    companion object {
        private const val DEFAULT_TIMEOUT = 2000L
        private const val LONG_TIMEOUT = 5000L
        private const val PROBE_TIMEOUT = 12000L
        private const val MAX_PIDS_PER_REQUEST = 6

        /** Data bytes one CAN frame carries after the 0x41 service byte. */
        private const val SINGLE_FRAME_PAYLOAD = 6

        /** Longest PID whose reply still fits one frame on its own. */
        private const val SINGLE_FRAME_MAX_LEN = 5
        private const val MAX_BATCH_LEN = 4
        private const val BATCH_STRIKES = 2

        private val CAN_PROTOCOLS = setOf("6", "7", "8", "9", "A", "B", "C")
        private val SUPPORT_BASES = intArrayOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)
        private val FREEZE_FRAME_PIDS = intArrayOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11)
    }
}
