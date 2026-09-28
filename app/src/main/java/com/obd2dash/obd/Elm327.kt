package com.obd2dash.obd

import com.obd2dash.bluetooth.ObdSocket

/**
 * Drives an ELM327-compatible adapter: configuration, PID reads, and the
 * diagnostic modes.
 *
 * Every method blocks on the Bluetooth socket, so this runs on an IO thread.
 */
class Elm327(private val socket: ObdSocket, private val trace: (String) -> Unit) {

    var adapterId: String = "unknown"
        private set

    /** Protocol number reported by ATDPN, reusable to skip auto-detection later. */
    var protocol: String? = null
        private set

    fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT): String {
        trace(">> " + command)
        val reply = socket.request(command, timeoutMs)
        trace("<< " + reply.replace('\r', ' ').trim())
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

        send("ATE0")   // echo off
        send("ATL0")   // no linefeeds
        send("ATS0")   // no spaces in replies
        send("ATH0")   // no CAN headers
        send("ATAT1")  // adaptive timing

        if (preferred.isNullOrBlank()) send("ATSP0") else send("ATSP" + preferred)

        // The first real query is what actually triggers protocol detection,
        // and a cold bus often needs a couple of attempts.
        var connected = false
        for (attempt in 1..3) {
            val reply = send("0100", PROBE_TIMEOUT)
            if (ObdResponse.errorOf(reply) == null && ObdResponse.payload(reply).contains("4100")) {
                connected = true
                break
            }
            Thread.sleep(500)
        }
        if (!connected) throw ObdResponse.ObdError("No response from vehicle ECU")

        protocol = send("ATDPN").replace('\r', ' ').trim().removePrefix("A").take(1).ifBlank { null }
    }

    /** Reads one mode 01 PID, or null when the ECU does not answer. */
    fun readPid(pid: Pids.Pid): Float? {
        val reply = send("01" + pid.hex, DEFAULT_TIMEOUT)
        if (ObdResponse.errorOf(reply) != null) return null
        val data = ObdResponse.dataFor(0x01, pid.id, pid.len, reply) ?: return null
        return try {
            pid.decode(data)
        } catch (_: Exception) {
            null
        }
    }

    /** Queries the support bitmasks and returns the PIDs this vehicle answers. */
    fun discoverSupportedPids(): Set<Int> {
        val supported = LinkedHashSet<Int>()
        for (base in intArrayOf(0x00, 0x20, 0x40, 0x60, 0x80)) {
            val hex = "%02X".format(base)
            val reply = send("01" + hex, DEFAULT_TIMEOUT)
            if (ObdResponse.errorOf(reply) != null) break
            val data = ObdResponse.dataFor(0x01, base, 4, reply) ?: break
            val found = Pids.decodeSupportMask(base, data)
            supported.addAll(found)
            // The next bitmask is only worth asking for if this one says so.
            if (!found.contains(base + 0x20)) break
        }
        return supported
    }

    fun readStatus(): Dtc.Status? {
        val reply = send("0101")
        if (ObdResponse.errorOf(reply) != null) return null
        val data = ObdResponse.dataFor(0x01, 0x01, 4, reply) ?: return null
        return Dtc.decodeStatus(data)
    }

    /** Reads stored, pending, and permanent codes in one pass. */
    fun readAllDtcs(): List<Dtc.Entry> {
        val out = ArrayList<Dtc.Entry>()
        out += readDtcs("03", Dtc.Kind.STORED)
        out += readDtcs("07", Dtc.Kind.PENDING)
        out += readDtcs("0A", Dtc.Kind.PERMANENT)
        return out
    }

    private fun readDtcs(mode: String, kind: Dtc.Kind): List<Dtc.Entry> {
        val reply = send(mode, LONG_TIMEOUT)
        // NO DATA here is the normal answer when nothing is stored.
        if (ObdResponse.errorOf(reply) != null) return emptyList()
        val body = ObdResponse.bodyFor(mode.toInt(16), reply) ?: return emptyList()
        return Dtc.parse(body, kind)
    }

    /** Clears stored codes, freeze frame data, and turns off the MIL. */
    fun clearDtcs(): Boolean {
        val reply = send("04", LONG_TIMEOUT)
        return ObdResponse.errorOf(reply) == null && ObdResponse.payload(reply).contains("44")
    }

    fun readVin(): String? = readMode09Ascii(0x02, 17)

    fun readCalibrationId(): String? = readMode09Ascii(0x04, 16)

    fun readEcuName(): String? = readMode09Ascii(0x0A, 20)

    /**
     * Mode 09 returns ASCII preceded by a data-item count. Rather than trusting
     * the count byte, keep the printable characters and take the trailing run.
     */
    private fun readMode09Ascii(pid: Int, length: Int): String? {
        val reply = send("09" + "%02X".format(pid), LONG_TIMEOUT)
        if (ObdResponse.errorOf(reply) != null) return null
        val hex = ObdResponse.payload(reply)
        val marker = "49" + "%02X".format(pid)
        val idx = hex.indexOf(marker)
        if (idx < 0) return null
        var body = hex.substring(idx + marker.length)
        if (body.length % 2 == 1) body = body.dropLast(1)
        val text = ObdResponse.hexToBytes(body)
            .map { it.toChar() }
            .filter { it.code in 0x20..0x7E }
            .joinToString("")
            .trim()
        if (text.isEmpty()) return null
        return if (text.length > length) text.takeLast(length) else text
    }

    /** Adapter-measured battery voltage. Works even when the ECU is asleep. */
    fun readBatteryVoltage(): Float? {
        val reply = send("ATRV", 3000)
        val cleaned = reply.replace('\r', ' ').trim().removeSuffix("V").trim()
        return cleaned.toFloatOrNull()
    }

    fun close() = socket.close()

    companion object {
        private const val DEFAULT_TIMEOUT = 2000L
        private const val LONG_TIMEOUT = 5000L
        private const val PROBE_TIMEOUT = 12000L
    }
}
