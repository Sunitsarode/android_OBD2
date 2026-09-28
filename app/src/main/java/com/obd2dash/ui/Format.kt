package com.obd2dash.ui

import com.obd2dash.core.Metrics
import com.obd2dash.obd.Pids

/** Formats readings for display, converting to imperial units when asked. */
object Format {

    fun num(value: Float?, decimals: Int = 1): String =
        if (value == null) "--" else "%.${decimals}f".format(value)

    fun speed(kmh: Float?, imperial: Boolean): String =
        num(kmh?.let { if (imperial) Metrics.kmhToMph(it) else it }, 0)

    fun speedUnit(imperial: Boolean) = if (imperial) "mph" else "km/h"

    fun distance(km: Float?, imperial: Boolean): String =
        num(km?.let { if (imperial) Metrics.kmToMiles(it) else it }, 1)

    fun distanceUnit(imperial: Boolean) = if (imperial) "mi" else "km"

    fun temperature(celsius: Float?, imperial: Boolean): String =
        num(celsius?.let { if (imperial) Metrics.celsiusToFahrenheit(it) else it }, 0)

    fun temperatureUnit(imperial: Boolean) = if (imperial) "F" else "C"

    fun pressure(kpa: Float?, imperial: Boolean): String =
        num(kpa?.let { if (imperial) Metrics.kpaToPsi(it) else it }, 1)

    fun pressureUnit(imperial: Boolean) = if (imperial) "psi" else "kPa"

    fun power(kw: Float?, imperial: Boolean): String =
        num(kw?.let { if (imperial) Metrics.kwToHp(it) else it }, 0)

    fun powerUnit(imperial: Boolean) = if (imperial) "hp" else "kW"

    /** Imperial economy is shown as mpg, where bigger is better, so the scale inverts. */
    fun consumption(l100: Float?, imperial: Boolean): String =
        num(l100?.let { if (imperial) Metrics.l100ToMpgImperial(it) else it }, 1)

    fun consumptionUnit(imperial: Boolean) = if (imperial) "mpg" else "L/100km"

    fun volume(litres: Float?, imperial: Boolean): String =
        num(litres?.let { if (imperial) it * 0.219969f else it }, 2)

    fun volumeUnit(imperial: Boolean) = if (imperial) "gal" else "L"

    fun duration(seconds: Long) = Metrics.formatDuration(seconds)

    /**
     * Converts a raw PID reading into display text, applying unit conversion for
     * the value kinds that differ between metric and imperial.
     */
    fun pidValue(pid: Pids.Pid, value: Float?, imperial: Boolean): Pair<String, String> {
        if (value == null) return "--" to pid.unit
        if (!imperial) return num(value, decimalsFor(pid)) to pid.unit
        return when (pid.unit) {
            "km/h" -> speed(value, true) to "mph"
            "C" -> temperature(value, true) to "F"
            "kPa" -> pressure(value, true) to "psi"
            "km" -> distance(value, true) to "mi"
            "L/h" -> num(value * 0.219969f, 2) to "gal/h"
            else -> num(value, decimalsFor(pid)) to pid.unit
        }
    }

    private fun decimalsFor(pid: Pids.Pid): Int = when (pid.unit) {
        "rpm", "km/h", "kPa", "s", "min", "km", "Nm", "" -> 0
        "V", "lam" -> 3
        else -> 1
    }
}
