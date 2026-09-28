package com.obd2dash.obd

/** Diagnostic trouble codes and the mode 01 PID 01 status/readiness word. */
object Dtc {

    enum class Kind(val label: String) {
        STORED("Stored"), PENDING("Pending"), PERMANENT("Permanent")
    }

    data class Entry(val code: String, val kind: Kind) {
        val description: String get() = DtcLibrary.describe(code)
        val system: String
            get() = when (code.firstOrNull()) {
                'P' -> "Powertrain"
                'C' -> "Chassis"
                'B' -> "Body"
                'U' -> "Network"
                else -> "Unknown"
            }
    }

    private val PREFIX = charArrayOf('P', 'C', 'B', 'U')

    /**
     * Two bytes encode one code: the top 2 bits of byte A pick the system letter,
     * the next 2 bits are the first digit, and the remaining nibbles are digits 2-4.
     */
    fun decodeCode(a: Int, b: Int): String =
        "%c%d%X%X%X".format(PREFIX[(a shr 6) and 0x03], (a shr 4) and 0x03, a and 0x0F, (b shr 4) and 0x0F, b and 0x0F)

    /**
     * Parses a mode 03/07/0A reply body into codes.
     *
     * CAN replies prefix the pairs with a count byte, older protocols do not.
     * An odd byte count means that leading count byte is present. Padding pairs
     * of 0x0000 are dropped.
     */
    fun parse(body: IntArray, kind: Kind): List<Entry> {
        var d = body
        if (d.size % 2 == 1) d = d.copyOfRange(1, d.size)
        val out = LinkedHashSet<String>()
        var i = 0
        while (i + 1 < d.size) {
            val a = d[i]
            val b = d[i + 1]
            if (a != 0 || b != 0) out.add(decodeCode(a, b))
            i += 2
        }
        return out.map { Entry(it, kind) }
    }

    data class Monitor(val name: String, val supported: Boolean, val complete: Boolean)

    data class Status(
        val milOn: Boolean,
        val dtcCount: Int,
        val compressionIgnition: Boolean,
        val monitors: List<Monitor>
    )

    private val CONTINUOUS = listOf("Misfire", "Fuel system", "Components")
    private val SPARK = listOf(
        "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
        "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system"
    )
    private val COMPRESSION = listOf(
        "NMHC catalyst", "NOx/SCR aftertreatment", "Reserved", "Boost pressure",
        "Reserved", "Exhaust gas sensor", "PM filter", "EGR/VVT system"
    )

    /**
     * Decodes mode 01 PID 01. Byte A carries the MIL flag and code count;
     * B/C/D carry the readiness monitors, whose meaning depends on ignition type.
     */
    fun decodeStatus(d: IntArray): Status? {
        if (d.size < 4) return null
        val (a, b, c) = Triple(d[0], d[1], d[2])
        val e = d[3]
        val diesel = (b shr 3) and 1 == 1

        val monitors = ArrayList<Monitor>()
        CONTINUOUS.forEachIndexed { i, name ->
            monitors.add(Monitor(name, (b shr i) and 1 == 1, (b shr (i + 4)) and 1 == 0))
        }
        (if (diesel) COMPRESSION else SPARK).forEachIndexed { i, name ->
            if (name != "Reserved") {
                monitors.add(Monitor(name, (c shr i) and 1 == 1, (e shr i) and 1 == 0))
            }
        }
        return Status(
            milOn = (a shr 7) and 1 == 1,
            dtcCount = a and 0x7F,
            compressionIgnition = diesel,
            monitors = monitors
        )
    }
}
