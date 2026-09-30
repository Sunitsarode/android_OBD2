package com.obd2dash.obd

/**
 * Turns raw ELM327 text into usable bytes.
 *
 * A reply can hold several messages: one per responding ECU, and multi-frame
 * messages split across indexed lines. Each message is kept separate so that
 * one module's bytes are never read as part of another's.
 */
object ObdResponse {

    class ObdError(val code: String) : Exception(code)

    private val ERROR_STRINGS = listOf(
        "UNABLE TO CONNECT", "BUS INIT: ERROR", "CAN ERROR", "DATA ERROR",
        "BUFFER FULL", "BUS BUSY", "FB ERROR", "LV RESET",
        "NO DATA", "STOPPED", "ERROR"
    )

    private val FRAME_LEN = Regex("^[0-9A-F]{3}$")
    private val FRAME_IDX = Regex("^[0-9A-F]:")

    /** Returns the error token present in a reply, or null if the reply looks usable. */
    fun errorOf(raw: String): String? {
        val up = raw.uppercase()
        ERROR_STRINGS.firstOrNull { up.contains(it) }?.let { return it }
        if (up.contains('?')) return "UNKNOWN COMMAND"
        return null
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

    /**
     * One uppercase hex string per ECU message. Lines that are not pure hex
     * (OK, SEARCHING..., the adapter banner) are skipped rather than filtered,
     * so their letters can never be mistaken for data.
     */
    fun messages(raw: String): List<String> {
        val out = ArrayList<String>()
        var multi: StringBuilder? = null
        var multiBytes = 0

        fun closeMulti() {
            val m = multi ?: return
            val hex = m.toString()
            // The length header is exact; anything past it is frame padding.
            out += if (multiBytes > 0 && hex.length > multiBytes * 2) hex.substring(0, multiBytes * 2) else hex
            multi = null
        }

        for (rawLine in raw.split('\r', '\n')) {
            val line = rawLine.trim().replace(" ", "").uppercase()
            if (line.isEmpty()) continue
            when {
                FRAME_LEN.matches(line) -> {
                    closeMulti()
                    multi = StringBuilder()
                    multiBytes = line.toInt(16)
                }
                FRAME_IDX.containsMatchIn(line) -> {
                    val data = line.substring(2)
                    if (!isHex(data)) continue
                    val m = multi ?: StringBuilder().also { multi = it; multiBytes = 0 }
                    m.append(data)
                }
                isHex(line) -> {
                    closeMulti()
                    out += line
                }
            }
        }
        closeMulti()
        return out.filter { it.length >= 2 }
    }

    fun hexToBytes(hex: String): IntArray {
        val n = hex.length / 2
        return IntArray(n) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun marker(vararg bytes: Int) = bytes.joinToString("") { "%02X".format(it) }

    /** Data bytes of the first ECU's positive reply to [mode]/[pid]. */
    fun dataFor(mode: Int, pid: Int, expectedLen: Int, raw: String): IntArray? =
        allDataFor(mode, pid, expectedLen, raw).firstOrNull()

    /** Data bytes from every ECU that answered [mode]/[pid]. */
    fun allDataFor(mode: Int, pid: Int, expectedLen: Int, raw: String): List<IntArray> {
        val m = marker(mode + 0x40, pid)
        return messages(raw).mapNotNull { msg ->
            if (!msg.startsWith(m) || msg.length < m.length + expectedLen * 2) null
            else hexToBytes(msg.substring(m.length, m.length + expectedLen * 2))
        }
    }

    /** Per-ECU bodies after the service byte, for modes with no PID echo (03, 07, 0A). */
    fun bodiesFor(mode: Int, raw: String): List<IntArray> {
        val m = marker(mode + 0x40)
        return messages(raw).filter { it.startsWith(m) }.map { msg ->
            val body = msg.substring(m.length)
            hexToBytes(if (body.length % 2 == 1) body.dropLast(1) else body)
        }
    }

    /**
     * Parses a multi-PID mode 01 reply: 0x41 followed by (pid, data) groups in
     * any order. [lengths] gives each requested PID's data length, which is the
     * only way to tell where one group ends and the next begins.
     */
    fun multiPid(raw: String, lengths: Map<Int, Int>): Map<Int, IntArray> {
        val out = HashMap<Int, IntArray>()
        for (msg in messages(raw)) {
            if (!msg.startsWith("41")) continue
            val bytes = hexToBytes(msg.substring(2))
            var i = 0
            while (i < bytes.size) {
                val len = lengths[bytes[i]] ?: break
                if (i + 1 + len > bytes.size) break
                if (!out.containsKey(bytes[i])) out[bytes[i]] = bytes.copyOfRange(i + 1, i + 1 + len)
                i += 1 + len
            }
        }
        return out
    }

    /** Freeze frame reply: 0x42, the PID, the frame number, then the data. */
    fun freezeFrameData(pid: Int, expectedLen: Int, raw: String): IntArray? {
        val m = marker(0x42, pid)
        for (msg in messages(raw)) {
            if (!msg.startsWith(m)) continue
            val start = m.length + 2
            if (msg.length < start + expectedLen * 2) continue
            return hexToBytes(msg.substring(start, start + expectedLen * 2))
        }
        return null
    }
}
