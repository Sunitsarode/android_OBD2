package com.obd2dash.core

import com.obd2dash.obd.Pids
import kotlin.math.abs
import kotlin.math.roundToInt

/** Values the ECU does not report directly, computed from the PIDs it does. */
object Metrics {

    private const val SECONDS_PER_HOUR = 3600f
    private const val IDEAL_GAS_R = 8.314f

    /**
     * Fuel flow in litres per hour.
     *
     * Preference order: the ECU's own fuel rate PID, then mass air flow divided
     * by the stoichiometric ratio, then a speed-density estimate from MAP/IAT/RPM
     * for engines with no MAF sensor.
     */
    fun fuelRateLitresPerHour(readings: Map<Int, Float>, prefs: Prefs): Float? {
        readings[Pids.FUEL_RATE]?.let { if (it > 0f) return it }

        val fuel = prefs.fuelType
        val maf = readings[Pids.MAF] ?: estimateMafFromSpeedDensity(readings, prefs) ?: return null
        // grams of air per second -> grams of fuel -> litres per hour
        val gramsFuelPerSecond = maf / fuel.stoichAfr
        return gramsFuelPerSecond * SECONDS_PER_HOUR / fuel.densityGramsPerLitre
    }

    /**
     * Speed-density airflow estimate, in grams per second, for engines without
     * a MAF sensor: mass flow follows from manifold pressure, charge temperature,
     * and how much swept volume the engine pumps per second.
     */
    fun estimateMafFromSpeedDensity(readings: Map<Int, Float>, prefs: Prefs): Float? {
        val map = readings[Pids.MAP] ?: return null
        val rpm = readings[Pids.RPM] ?: return null
        val iatC = readings[Pids.INTAKE_TEMP] ?: 25f
        if (rpm <= 0f) return 0f

        val intakeKelvin = iatC + 273.15f
        // A four-stroke engine ingests its displacement once every two revolutions.
        val volumePerSecond = prefs.displacementLitres / 1000f * (rpm / 120f)
        val molarFlow = map * 1000f * volumePerSecond / (IDEAL_GAS_R * intakeKelvin)
        val gramsPerSecond = molarFlow * AIR_MOLAR_MASS_GRAMS
        return gramsPerSecond * prefs.volumetricEfficiency
    }

    private const val AIR_MOLAR_MASS_GRAMS = 28.97f

    /** Instantaneous consumption in L/100km. Null below walking pace, where it diverges. */
    fun consumptionPer100Km(fuelRateLh: Float?, speedKmh: Float?): Float? {
        if (fuelRateLh == null || speedKmh == null || speedKmh < 5f) return null
        return fuelRateLh / speedKmh * 100f
    }

    /** Manifold pressure relative to ambient. Positive is boost, negative is vacuum. */
    fun boostKpa(readings: Map<Int, Float>): Float? {
        val map = readings[Pids.MAP] ?: return null
        val baro = readings[Pids.BARO] ?: 101f
        return map - baro
    }

    /** Engine torque in Nm, when the ECU publishes both torque PIDs. */
    fun torqueNm(readings: Map<Int, Float>): Float? {
        val reference = readings[Pids.REF_TORQUE] ?: return null
        val percent = readings[Pids.ACTUAL_TORQUE] ?: return null
        return reference * percent / 100f
    }

    /**
     * Crank power in kW. Uses torque and RPM when available, otherwise falls back
     * to the airflow rule of thumb for a naturally aspirated petrol engine.
     */
    fun powerKw(readings: Map<Int, Float>, prefs: Prefs): Float? {
        val rpm = readings[Pids.RPM]
        val torque = torqueNm(readings)
        if (torque != null && rpm != null) {
            return torque * rpm * 2f * Math.PI.toFloat() / 60000f
        }
        val maf = readings[Pids.MAF] ?: return null
        return maf * MAF_TO_KW
    }

    private const val MAF_TO_KW = 0.9864f

    /** Remaining range in km from tank level and recent average consumption. */
    fun rangeKm(readings: Map<Int, Float>, avgL100: Float?, prefs: Prefs): Float? {
        val levelPercent = readings[Pids.FUEL_LEVEL] ?: return null
        if (avgL100 == null || avgL100 <= 0f) return null
        val litres = prefs.tankLitres * levelPercent / 100f
        return litres / avgL100 * 100f
    }

    /**
     * Learns gear ratios from the speed-to-RPM relationship.
     *
     * Ratios are collected while driving and sorted; a gear number is then just
     * the position of the current ratio in that sorted list.
     */
    class GearEstimator {
        private val ratios = ArrayList<Float>()

        fun update(speedKmh: Float?, rpm: Float?): Int? {
            if (speedKmh == null || rpm == null || speedKmh < 10f || rpm < 600f) return null
            val ratio = speedKmh / rpm
            val match = ratios.indexOfFirst { abs(it - ratio) / it < TOLERANCE }
            if (match >= 0) {
                // Nudge the learned ratio toward the observation.
                ratios[match] = ratios[match] * 0.9f + ratio * 0.1f
            } else {
                if (ratios.size >= MAX_GEARS) return null
                ratios.add(ratio)
            }
            ratios.sort()
            val index = ratios.indexOfFirst { abs(it - ratio) / it < TOLERANCE }
            return if (index >= 0) index + 1 else null
        }

        fun reset() = ratios.clear()

        private companion object {
            const val TOLERANCE = 0.12f
            const val MAX_GEARS = 8
        }
    }

    fun kmhToMph(v: Float) = v * 0.621371f
    fun kmToMiles(v: Float) = v * 0.621371f
    fun celsiusToFahrenheit(v: Float) = v * 9f / 5f + 32f
    fun l100ToMpgImperial(v: Float) = if (v <= 0f) 0f else 282.481f / v
    fun kwToHp(v: Float) = v * 1.34102f
    fun kpaToPsi(v: Float) = v * 0.145038f

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun round1(v: Float) = (v * 10f).roundToInt() / 10f
}
