package com.obd2dash.obd

/**
 * Mode 01 PID registry. Formulas follow SAE J1979 / ISO 15031-5.
 *
 * The tier field controls polling frequency: an ELM327 clone manages roughly
 * 10-20 queries per second in total, so RPM and speed are read every cycle
 * while fuel level and ambient temperature can wait.
 */
object Pids {

    enum class Tier(val interval: Int) { FAST(1), MEDIUM(4), SLOW(20) }

    data class Pid(
        val id: Int,
        val len: Int,
        val name: String,
        val short: String,
        val unit: String,
        val min: Float,
        val max: Float,
        val tier: Tier,
        val decode: (IntArray) -> Float
    ) {
        val hex: String get() = "%02X".format(id)
    }

    private fun pct255(d: IntArray) = d[0] * 100f / 255f
    private fun trim(d: IntArray) = (d[0] - 128) * 100f / 128f
    private fun temp(d: IntArray) = (d[0] - 40).toFloat()
    private fun word(d: IntArray) = (d[0] * 256 + d[1]).toFloat()

    val ALL: List<Pid> = listOf(
        Pid(0x04, 1, "Calculated engine load", "LOAD", "%", 0f, 100f, Tier.MEDIUM, ::pct255),
        Pid(0x05, 1, "Engine coolant temp", "COOLANT", "C", -40f, 130f, Tier.MEDIUM, ::temp),
        Pid(0x06, 1, "Short term fuel trim B1", "STFT B1", "%", -100f, 99f, Tier.MEDIUM, ::trim),
        Pid(0x07, 1, "Long term fuel trim B1", "LTFT B1", "%", -100f, 99f, Tier.MEDIUM, ::trim),
        Pid(0x08, 1, "Short term fuel trim B2", "STFT B2", "%", -100f, 99f, Tier.SLOW, ::trim),
        Pid(0x09, 1, "Long term fuel trim B2", "LTFT B2", "%", -100f, 99f, Tier.SLOW, ::trim),
        Pid(0x0A, 1, "Fuel pressure", "FUEL PRES", "kPa", 0f, 765f, Tier.SLOW) { it[0] * 3f },
        Pid(0x0B, 1, "Intake manifold pressure", "MAP", "kPa", 0f, 255f, Tier.FAST) { it[0].toFloat() },
        Pid(0x0C, 2, "Engine RPM", "RPM", "rpm", 0f, 8000f, Tier.FAST) { (it[0] * 256 + it[1]) / 4f },
        Pid(0x0D, 1, "Vehicle speed", "SPEED", "km/h", 0f, 255f, Tier.FAST) { it[0].toFloat() },
        Pid(0x0E, 1, "Timing advance", "TIMING", "deg", -64f, 64f, Tier.MEDIUM) { it[0] / 2f - 64f },
        Pid(0x0F, 1, "Intake air temp", "IAT", "C", -40f, 130f, Tier.MEDIUM, ::temp),
        Pid(0x10, 2, "MAF air flow rate", "MAF", "g/s", 0f, 300f, Tier.FAST) { (it[0] * 256 + it[1]) / 100f },
        Pid(0x11, 1, "Throttle position", "THROTTLE", "%", 0f, 100f, Tier.FAST, ::pct255),
        Pid(0x14, 2, "O2 S1 voltage", "O2 S1", "V", 0f, 1.275f, Tier.MEDIUM) { it[0] / 200f },
        Pid(0x15, 2, "O2 S2 voltage", "O2 S2", "V", 0f, 1.275f, Tier.MEDIUM) { it[0] / 200f },
        Pid(0x16, 2, "O2 S3 voltage", "O2 S3", "V", 0f, 1.275f, Tier.SLOW) { it[0] / 200f },
        Pid(0x17, 2, "O2 S4 voltage", "O2 S4", "V", 0f, 1.275f, Tier.SLOW) { it[0] / 200f },
        Pid(0x18, 2, "O2 S5 voltage", "O2 S5", "V", 0f, 1.275f, Tier.SLOW) { it[0] / 200f },
        Pid(0x19, 2, "O2 S6 voltage", "O2 S6", "V", 0f, 1.275f, Tier.SLOW) { it[0] / 200f },
        Pid(0x1A, 2, "O2 S7 voltage", "O2 S7", "V", 0f, 1.275f, Tier.SLOW) { it[0] / 200f },
        Pid(0x1B, 2, "O2 S8 voltage", "O2 S8", "V", 0f, 1.275f, Tier.SLOW) { it[0] / 200f },
        Pid(0x1F, 2, "Run time since start", "RUNTIME", "s", 0f, 65535f, Tier.SLOW, ::word),
        Pid(0x21, 2, "Distance with MIL on", "MIL DIST", "km", 0f, 65535f, Tier.SLOW, ::word),
        Pid(0x22, 2, "Fuel rail pressure (vac)", "RAIL VAC", "kPa", 0f, 5177f, Tier.SLOW) { (it[0] * 256 + it[1]) * 0.079f },
        Pid(0x23, 2, "Fuel rail gauge pressure", "RAIL PRES", "kPa", 0f, 655350f, Tier.SLOW) { (it[0] * 256 + it[1]) * 10f },
        Pid(0x24, 4, "O2 S1 lambda", "LAMBDA 1", "lam", 0f, 2f, Tier.MEDIUM) { (it[0] * 256 + it[1]) / 32768f },
        Pid(0x25, 4, "O2 S2 lambda", "LAMBDA 2", "lam", 0f, 2f, Tier.SLOW) { (it[0] * 256 + it[1]) / 32768f },
        Pid(0x2C, 1, "Commanded EGR", "EGR CMD", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x2D, 1, "EGR error", "EGR ERR", "%", -100f, 99f, Tier.SLOW, ::trim),
        Pid(0x2E, 1, "Commanded evap purge", "EVAP", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x2F, 1, "Fuel tank level", "FUEL", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x30, 1, "Warm-ups since clear", "WARMUPS", "", 0f, 255f, Tier.SLOW) { it[0].toFloat() },
        Pid(0x31, 2, "Distance since clear", "DIST CLR", "km", 0f, 65535f, Tier.SLOW, ::word),
        Pid(0x32, 2, "Evap vapor pressure", "EVAP PRES", "Pa", -8192f, 8192f, Tier.SLOW) {
            val v = it[0] * 256 + it[1]
            (if (v > 32767) v - 65536 else v) / 4f
        },
        Pid(0x33, 1, "Barometric pressure", "BARO", "kPa", 0f, 255f, Tier.SLOW) { it[0].toFloat() },
        Pid(0x3C, 2, "Catalyst temp B1S1", "CAT B1S1", "C", -40f, 1000f, Tier.SLOW) { (it[0] * 256 + it[1]) / 10f - 40f },
        Pid(0x3D, 2, "Catalyst temp B2S1", "CAT B2S1", "C", -40f, 1000f, Tier.SLOW) { (it[0] * 256 + it[1]) / 10f - 40f },
        Pid(0x3E, 2, "Catalyst temp B1S2", "CAT B1S2", "C", -40f, 1000f, Tier.SLOW) { (it[0] * 256 + it[1]) / 10f - 40f },
        Pid(0x3F, 2, "Catalyst temp B2S2", "CAT B2S2", "C", -40f, 1000f, Tier.SLOW) { (it[0] * 256 + it[1]) / 10f - 40f },
        Pid(0x42, 2, "Control module voltage", "ECU VOLT", "V", 0f, 16f, Tier.MEDIUM) { (it[0] * 256 + it[1]) / 1000f },
        Pid(0x43, 2, "Absolute load value", "ABS LOAD", "%", 0f, 200f, Tier.MEDIUM) { (it[0] * 256 + it[1]) * 100f / 255f },
        Pid(0x44, 2, "Air-fuel equivalence", "AFR LAMBDA", "lam", 0f, 2f, Tier.MEDIUM) { (it[0] * 256 + it[1]) / 32768f },
        Pid(0x45, 1, "Relative throttle position", "REL THR", "%", 0f, 100f, Tier.MEDIUM, ::pct255),
        Pid(0x46, 1, "Ambient air temperature", "AMBIENT", "C", -40f, 60f, Tier.SLOW, ::temp),
        Pid(0x47, 1, "Absolute throttle B", "THR B", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x48, 1, "Absolute throttle C", "THR C", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x49, 1, "Accelerator pedal D", "PEDAL D", "%", 0f, 100f, Tier.MEDIUM, ::pct255),
        Pid(0x4A, 1, "Accelerator pedal E", "PEDAL E", "%", 0f, 100f, Tier.MEDIUM, ::pct255),
        Pid(0x4B, 1, "Accelerator pedal F", "PEDAL F", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x4C, 1, "Commanded throttle actuator", "THR ACT", "%", 0f, 100f, Tier.MEDIUM, ::pct255),
        Pid(0x4D, 2, "Time run with MIL on", "MIL TIME", "min", 0f, 65535f, Tier.SLOW, ::word),
        Pid(0x4E, 2, "Time since codes cleared", "CLR TIME", "min", 0f, 65535f, Tier.SLOW, ::word),
        Pid(0x52, 1, "Ethanol fuel percentage", "ETHANOL", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x59, 2, "Fuel rail absolute pressure", "RAIL ABS", "kPa", 0f, 655350f, Tier.SLOW) { (it[0] * 256 + it[1]) * 10f },
        Pid(0x5A, 1, "Relative accelerator pedal", "REL PEDAL", "%", 0f, 100f, Tier.MEDIUM, ::pct255),
        Pid(0x5B, 1, "Hybrid battery remaining", "HV BATT", "%", 0f, 100f, Tier.SLOW, ::pct255),
        Pid(0x5C, 1, "Engine oil temperature", "OIL TEMP", "C", -40f, 210f, Tier.MEDIUM, ::temp),
        Pid(0x5D, 2, "Fuel injection timing", "INJ TIMING", "deg", -210f, 302f, Tier.MEDIUM) { (it[0] * 256 + it[1]) / 128f - 210f },
        Pid(0x5E, 2, "Engine fuel rate", "FUEL RATE", "L/h", 0f, 100f, Tier.FAST) { (it[0] * 256 + it[1]) / 20f },
        Pid(0x61, 1, "Driver demand torque", "DEMAND TQ", "%", -125f, 130f, Tier.MEDIUM) { (it[0] - 125).toFloat() },
        Pid(0x62, 1, "Actual engine torque", "ACTUAL TQ", "%", -125f, 130f, Tier.MEDIUM) { (it[0] - 125).toFloat() },
        Pid(0x63, 2, "Engine reference torque", "REF TQ", "Nm", 0f, 65535f, Tier.SLOW, ::word),
        Pid(0x67, 3, "Engine coolant temp (alt)", "COOLANT 2", "C", -40f, 215f, Tier.SLOW) { (it[1] - 40).toFloat() },
        Pid(0x6B, 5, "EGR temperature", "EGR TEMP", "C", -40f, 215f, Tier.SLOW) { (it[1] - 40).toFloat() },
        Pid(0x73, 5, "Exhaust pressure", "EXH PRES", "kPa", 0f, 6553f, Tier.SLOW) { (it[1] * 256 + it[2]) * 0.01f },
        Pid(0x78, 9, "Exhaust gas temp B1", "EGT B1", "C", -40f, 1000f, Tier.SLOW) { (it[1] * 256 + it[2]) / 10f - 40f }
    )

    val BY_ID: Map<Int, Pid> = ALL.associateBy { it.id }

    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val MAF = 0x10
    const val COOLANT = 0x05
    const val LOAD = 0x04
    const val THROTTLE = 0x11
    const val FUEL_LEVEL = 0x2F
    const val INTAKE_TEMP = 0x0F
    const val MAP = 0x0B
    const val BARO = 0x33
    const val MODULE_VOLTAGE = 0x42
    const val FUEL_RATE = 0x5E
    const val AMBIENT = 0x46
    const val OIL_TEMP = 0x5C
    const val ACTUAL_TORQUE = 0x62
    const val REF_TORQUE = 0x63
    const val RUNTIME = 0x1F

    /**
     * Decodes a "PIDs supported" bitmask (0100, 0120, ...). The MSB of the first
     * byte flags base+1, down to the LSB of the fourth byte flagging base+32.
     */
    fun decodeSupportMask(base: Int, d: IntArray): List<Int> {
        if (d.size < 4) return emptyList()
        val mask = (d[0].toLong() shl 24) or (d[1].toLong() shl 16) or
                (d[2].toLong() shl 8) or d[3].toLong()
        return (0 until 32).mapNotNull { i ->
            if ((mask shr (31 - i)) and 1L == 1L) base + i + 1 else null
        }
    }
}
