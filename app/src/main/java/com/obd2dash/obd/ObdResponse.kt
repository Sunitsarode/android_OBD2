package com.obd2dash.obd

/**
 * Turns raw ELM327 text into usable bytes.
 *
 * An adapter with echo/spaces/headers off still gives us a surprising amount of
 * noise: search chatter, ISO-TP frame indices, and one reply per responding ECU.
 * Everything here is defensive on purpose - cheap clone adapters are inconsistent.
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

    /**
     * Flattens a reply to one uppercase hex string, dropping frame bookkeeping.
     * Multi-frame replies arrive as a length header plus N: indexed lines.
     */
    fun payload(raw: String): String {
        val sb = StringBuilder()
        raw.split('\r', '\n')
            .map { it.trim().replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && !it.startsWith("BUSINIT") }
            .forEach { line ->
                when {
                    FRAME_LEN.matches(line) -> Unit
                    FRAME_IDX.containsMatchIn(line) -> sb.append(line.substring(2))
                    else -> sb.append(line)
                }
            }
        return sb.filter { it in '0'..'9' || it in 'A'..'F' }.toString()
    }

    fun hexToBytes(hex: String): IntArray {
        val n = hex.length / 2
        return IntArray(n) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /**
     * Extracts the data bytes of a positive reply to [mode]/[pid].
     * Positive replies echo the mode with 0x40 added, then the PID.
     * Only the first responding ECU is used.
     */
    fun dataFor(mode: Int, pid: Int, expectedLen: Int, raw: String): IntArray? {
        val hex = payload(raw)
        val marker = "%02X%02X".format(mode + 0x40, pid)
        val idx = hex.indexOf(marker)
        if (idx < 0) return null
        val body = hex.substring(idx + marker.length)
        if (body.length < expectedLen * 2) return null
        return hexToBytes(body.substring(0, expectedLen * 2))
    }

    /** Data bytes of a reply to a mode that carries no PID echo (03, 07, 0A). */
    fun bodyFor(mode: Int, raw: String): IntArray? {
        val hex = payload(raw)
        val marker = "%02X".format(mode + 0x40)
        val idx = hex.indexOf(marker)
        if (idx < 0) return null
        val body = hex.substring(idx + marker.length)
        return hexToBytes(if (body.length % 2 == 1) body.dropLast(1) else body)
    }
}
