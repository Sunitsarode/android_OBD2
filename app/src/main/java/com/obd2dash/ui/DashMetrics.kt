package com.obd2dash.ui

import com.obd2dash.core.Fuel
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Settings
import com.obd2dash.core.TripStats
import com.obd2dash.obd.Pids

/**
 * Everything a dashboard tile can show: derived values plus every PID the car
 * supports. Keys are saved in settings, so they must never change.
 *
 * Fuel tiles follow the fuel in use: on a bi-fuel car they switch between
 * km/L and km/kg as the car switches between petrol and CNG.
 */
object DashMetrics {

    class Inputs(val live: ObdRepository.Live, val trip: TripStats, val settings: Settings) {
        val fuel: Fuel get() = live.derived.fuel
    }

    class Metric(
        val key: String,
        val label: String,
        val render: (Inputs) -> Pair<String?, String>,
        val warn: (Inputs) -> Boolean = { false }
    )

    const val TILE_COUNT = 12
    private const val LOW_VOLTS = 11.8f

    private val DEFAULT_KEYS = listOf(
        "pid:05", "pid:0F", "pid:11", "pid:04",
        "boost", "pid:2F", "consumption", "avgecon",
        "power", "battery", "range", "tripdist"
    )

    /** CNG cars lead with fuel: the car screen shows the first four tiles. */
    private val CNG_DEFAULT_KEYS = listOf(
        "fuel", "cngmileage", "cngleft", "cngtime",
        "pid:05", "consumption", "cngtotal", "cngrange",
        "tripcost", "battery", "cngfillmileage", "tripdist"
    )

    fun defaultKeys(s: Settings): List<String> = if (s.fuelSystem.usesCng) CNG_DEFAULT_KEYS else DEFAULT_KEYS

    /** Tiles show a dash for missing values, so "--" becomes null here. */
    private fun v(text: String): String? = if (text == "--") null else text

    private fun pair(p: Pair<String, String>): Pair<String?, String> = v(p.first) to p.second

    private val DERIVED: List<Metric> = listOf(
        Metric("gear", "GEAR", { i -> i.live.gear.label.takeIf { it != "-" } to "" }),
        Metric("fuel", "FUEL IN USE", { i -> i.fuel.label.uppercase() to "" }),
        Metric("consumption", "ECONOMY NOW", { i ->
            pair(Format.economy(i.live.derived.consumptionPer100, i.fuel, i.settings))
        }),
        Metric("avgecon", "TRIP ECONOMY", { i ->
            pair(Format.economy(i.trip.use(i.fuel)?.per100Km, i.fuel, i.settings))
        }),
        Metric("fuelrate", "FUEL RATE", { i -> pair(Format.rate(i.live.derived.fuelRate, i.fuel, i.settings.imperial)) }),
        Metric("fuelused", "FUEL USED", { i -> pair(Format.amount(i.trip.use(i.fuel)?.amount, i.fuel, i.settings.imperial)) }),
        Metric("range", "RANGE", { i ->
            v(Format.distance(i.trip.rangeFor(i.fuel), i.settings.imperial)) to Format.distanceUnit(i.settings.imperial)
        }),
        Metric("cngleft", "CNG LEFT (EST)", { i -> v(Format.num(i.trip.cngRemainingKg, 1)) to "kg" },
            { i -> i.settings.lowCngKg > 0f && (i.trip.cngRemainingKg ?: 99f) < i.settings.lowCngKg }),
        Metric("cngrange", "CNG RANGE", { i ->
            v(Format.distance(i.trip.cngRangeKm, i.settings.imperial)) to Format.distanceUnit(i.settings.imperial)
        }),
        Metric("cngmileage", "CNG MILEAGE", { i ->
            // This trip's km/kg, or the all-time average before the trip has any CNG distance.
            val per100 = i.trip.use(Fuel.CNG)?.per100Km ?: i.trip.cng?.kmPerKg?.let { 100f / it }
            pair(Format.economy(per100, Fuel.CNG, i.settings))
        }),
        Metric("cngused", "CNG USED", { i ->
            pair(Format.amount(i.trip.use(Fuel.CNG)?.amount ?: 0f, Fuel.CNG, i.settings.imperial))
        }),
        Metric("cngtime", "CNG RUN TIME", { i -> Format.runTime(i.trip.use(Fuel.CNG)?.runSeconds ?: 0L) to "" }),
        Metric("cngtotal", "TOTAL CNG USED", { i -> pair(Format.amount(i.trip.cng?.usedKg, Fuel.CNG, i.settings.imperial)) }),
        Metric("cngtotaltime", "TOTAL CNG TIME", { i -> v(Format.runTime(i.trip.cng?.runSeconds)) to "" }),
        Metric("cngfillmileage", "FILL-UP MILEAGE", { i ->
            pair(Format.economy(i.trip.cng?.lastFillKmPerKg?.let { 100f / it }, Fuel.CNG, i.settings))
        }),
        Metric("boost", "BOOST / VACUUM", { i ->
            v(Format.pressure(i.live.derived.boostKpa, i.settings.imperial)) to Format.pressureUnit(i.settings.imperial)
        }),
        Metric("power", "POWER", { i ->
            v(Format.power(i.live.derived.powerKw, i.settings.imperial)) to Format.powerUnit(i.settings.imperial)
        }),
        Metric("torque", "TORQUE", { i -> v(Format.num(i.live.derived.torqueNm, 0)) to "Nm" }),
        Metric("battery", "BATTERY", { i -> v(Format.num(i.live.batteryVolts, 1)) to "V" },
            { i -> (i.live.batteryVolts ?: 13f) < LOW_VOLTS }),
        Metric("tripdist", "TRIP", { i ->
            v(Format.distance(i.trip.distanceKm, i.settings.imperial)) to Format.distanceUnit(i.settings.imperial)
        }),
        Metric("tripcost", "TRIP COST", { i -> v(Format.money(i.trip.cost)) to "" }),
        Metric("costkm", "COST / KM", { i -> v(Format.money(i.trip.costPerKm)) to "" }),
        Metric("eco", "ECO SCORE", { i -> i.trip.ecoScore?.toString() to "/100" })
    )

    fun pidMetric(pid: Pids.Pid) = Metric(
        "pid:" + pid.hex,
        pid.short,
        { i -> pair(Format.pidValue(pid, i.live.readings[pid.id], i.settings.imperial)) },
        { i -> warnFor(pid.id, i) }
    )

    private fun warnFor(id: Int, i: Inputs): Boolean {
        val value = i.live.readings[id] ?: return false
        val s = i.settings
        return when (id) {
            Pids.COOLANT -> value >= s.overheatC
            Pids.FUEL_LEVEL -> s.lowFuelPercent > 0 && value < s.lowFuelPercent
            Pids.RPM -> s.redlineRpm > 0 && value >= s.redlineRpm
            Pids.MODULE_VOLTAGE -> value < LOW_VOLTS
            else -> false
        }
    }

    /** What the tile picker offers: derived values first, then this car's PIDs. */
    fun available(supported: Set<Int>): List<Metric> =
        DERIVED + Pids.ALL.filter { it.id in supported }.map { pidMetric(it) }

    fun resolve(key: String): Metric? {
        DERIVED.firstOrNull { it.key == key }?.let { return it }
        if (!key.startsWith("pid:")) return null
        val id = key.removePrefix("pid:").toIntOrNull(16) ?: return null
        return Pids.BY_ID[id]?.let { pidMetric(it) }
    }
}
