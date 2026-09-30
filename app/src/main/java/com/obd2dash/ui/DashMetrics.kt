package com.obd2dash.ui

import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Settings
import com.obd2dash.core.TripStats
import com.obd2dash.obd.Pids

/**
 * Everything a dashboard tile can show: derived values plus every PID the car
 * supports. Keys are saved in settings, so they must never change.
 */
object DashMetrics {

    class Inputs(val live: ObdRepository.Live, val trip: TripStats, val settings: Settings)

    class Metric(
        val key: String,
        val label: String,
        val render: (Inputs) -> Pair<String?, String>,
        val warn: (Inputs) -> Boolean = { false }
    )

    const val TILE_COUNT = 12
    private const val LOW_VOLTS = 11.8f

    val DEFAULT_KEYS = listOf(
        "pid:05", "pid:0F", "pid:11", "pid:04",
        "boost", "pid:2F", "consumption", "avgecon",
        "power", "battery", "range", "tripdist"
    )

    /** Tiles show a dash for missing values, so "--" becomes null here. */
    private fun v(text: String): String? = if (text == "--") null else text

    private val DERIVED: List<Metric> = listOf(
        Metric("gear", "GEAR", { i -> i.live.gear.label.takeIf { it != "-" } to "" }),
        Metric("boost", "BOOST / VACUUM", { i ->
            v(Format.pressure(i.live.derived.boostKpa, i.settings.imperial)) to Format.pressureUnit(i.settings.imperial)
        }),
        Metric("consumption", "CONSUMPTION", { i ->
            v(Format.consumption(i.live.derived.consumptionL100, i.settings.imperial)) to Format.consumptionUnit(i.settings.imperial)
        }),
        Metric("avgecon", "AVG ECONOMY", { i ->
            v(Format.consumption(i.trip.avgConsumptionL100, i.settings.imperial)) to Format.consumptionUnit(i.settings.imperial)
        }),
        Metric("fuelrate", "FUEL RATE", { i ->
            val rate = i.live.derived.fuelRateLh
            if (i.settings.imperial) v(Format.num(rate?.times(0.219969f), 2)) to "gal/h" else v(Format.num(rate, 2)) to "L/h"
        }),
        Metric("power", "POWER", { i ->
            v(Format.power(i.live.derived.powerKw, i.settings.imperial)) to Format.powerUnit(i.settings.imperial)
        }),
        Metric("torque", "TORQUE", { i -> v(Format.num(i.live.derived.torqueNm, 0)) to "Nm" }),
        Metric("battery", "BATTERY", { i -> v(Format.num(i.live.batteryVolts, 1)) to "V" },
            { i -> (i.live.batteryVolts ?: 13f) < LOW_VOLTS }),
        Metric("range", "RANGE", { i ->
            v(Format.distance(i.trip.rangeKm, i.settings.imperial)) to Format.distanceUnit(i.settings.imperial)
        }),
        Metric("tripdist", "TRIP", { i ->
            v(Format.distance(i.trip.distanceKm, i.settings.imperial)) to Format.distanceUnit(i.settings.imperial)
        }),
        Metric("tripcost", "TRIP COST", { i -> v(Format.money(i.trip.cost)) to "" }),
        Metric("costkm", "COST / KM", { i -> v(Format.money(i.trip.costPerKm)) to "" }),
        Metric("eco", "ECO SCORE", { i -> i.trip.ecoScore?.toString() to "/100" }),
        Metric("fuelused", "FUEL USED", { i ->
            v(Format.volume(i.trip.fuelUsedLitres, i.settings.imperial)) to Format.volumeUnit(i.settings.imperial)
        })
    )

    fun pidMetric(pid: Pids.Pid) = Metric(
        "pid:" + pid.hex,
        pid.short,
        { i -> Format.pidValue(pid, i.live.readings[pid.id], i.settings.imperial).let { (text, unit) -> v(text) to unit } },
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
