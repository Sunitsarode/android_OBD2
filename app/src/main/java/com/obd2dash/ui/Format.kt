package com.obd2dash.ui

import com.obd2dash.core.Fuel
import com.obd2dash.core.Metrics
import com.obd2dash.core.Settings
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

    /**
     * Economy for [fuel], from litres (or kg) per 100 km. India counts distance
     * per unit (km/L, km/kg), so that is the default; per 100 km is a setting.
     */
    fun economy(per100: Float?, fuel: Fuel, s: Settings): Pair<String, String> {
        val unit = economyUnit(fuel, s)
        if (per100 == null || per100 <= 0f) return "--" to unit
        val value = when {
            s.imperial && fuel.soldByMass -> 100f / per100 * 0.621371f
            s.imperial -> Metrics.l100ToMpgImperial(per100)
            s.economyPerDistance -> 100f / per100
            else -> per100
        }
        return num(value, 1) to unit
    }

    fun economyUnit(fuel: Fuel, s: Settings): String = when {
        s.imperial && fuel.soldByMass -> "mi/kg"
        s.imperial -> "mpg"
        s.economyPerDistance -> if (fuel.soldByMass) "km/kg" else "km/L"
        else -> if (fuel.soldByMass) "kg/100km" else "L/100km"
    }

    /** A quantity of fuel: kg for CNG, litres (or gallons) for liquids. */
    fun amount(value: Float?, fuel: Fuel, imperial: Boolean): Pair<String, String> = when {
        fuel.soldByMass -> num(value, 2) to "kg"
        imperial -> num(value?.times(0.219969f), 2) to "gal"
        else -> num(value, 2) to "L"
    }

    /** Fuel flow per hour, in the same units as [amount]. */
    fun rate(perHour: Float?, fuel: Fuel, imperial: Boolean): Pair<String, String> =
        amount(perHour, fuel, imperial).let { (value, unit) -> value to unit + "/h" }

    private val FUEL_TYPES = listOf(
        "Not available", "Petrol", "Methanol", "Ethanol", "Diesel", "LPG", "CNG", "Propane", "Electric",
        "Bi-fuel running petrol", "Bi-fuel running methanol", "Bi-fuel running ethanol",
        "Bi-fuel running LPG", "Bi-fuel running CNG", "Bi-fuel running propane",
        "Bi-fuel running electric", "Bi-fuel electric and engine", "Hybrid petrol", "Hybrid ethanol",
        "Hybrid diesel", "Hybrid electric", "Hybrid electric and engine", "Hybrid regenerative",
        "Bi-fuel running diesel"
    )

    /** PID 51, the SAE fuel-type code. */
    fun fuelType(code: Float?): String {
        val index = code?.toInt() ?: return "--"
        return FUEL_TYPES.getOrNull(index) ?: ("Code " + index)
    }

    /** Engine run time: "42m", or "3h 05m" past an hour. Totals can run to hundreds of hours. */
    fun runTime(seconds: Long?): String {
        if (seconds == null) return "--"
        val minutes = seconds / 60
        return if (minutes < 60) minutes.toString() + "m" else "%dh %02dm".format(minutes / 60, minutes % 60)
    }

    fun duration(seconds: Long) = Metrics.formatDuration(seconds)

    /**
     * Converts a raw PID reading into display text, applying unit conversion for
     * the value kinds that differ between metric and imperial.
     */
    fun pidValue(pid: Pids.Pid, value: Float?, imperial: Boolean): Pair<String, String> {
        if (value == null) return "--" to pid.unit
        if (pid.id == Pids.FUEL_SYSTEM) return fuelSystem(value) to ""
        if (pid.id == Pids.FUEL_TYPE) return fuelType(value) to ""
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

    const val RUPEE = "₹"

    /** Money without clutter: paise under 100, whole rupees above. */
    fun money(v: Float?): String = when {
        v == null -> "--"
        v < 100f -> RUPEE + "%.2f".format(v)
        else -> RUPEE + "%.0f".format(v)
    }

    /** PID 03 is a code, not a quantity. */
    fun fuelSystem(code: Float?): String = when (code?.toInt()) {
        null -> "--"
        1 -> "Open loop (warming up)"
        2 -> "Closed loop"
        4 -> "Open loop (load / fuel cut)"
        8 -> "Open loop (fault)"
        16 -> "Closed loop (sensor fault)"
        else -> "Code " + code?.toInt()
    }

    private fun decimalsFor(pid: Pids.Pid): Int = when (pid.unit) {
        "rpm", "km/h", "kPa", "s", "min", "km", "Nm", "" -> 0
        "V", "lam" -> 3
        else -> 1
    }
}
