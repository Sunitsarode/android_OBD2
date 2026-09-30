package com.obd2dash.core

import com.obd2dash.obd.Pids
import kotlin.math.roundToInt

/** Values the ECU does not report directly, computed from the PIDs it does. */
object Metrics {

    private const val SECONDS_PER_HOUR = 3600f
    private const val IDEAL_GAS_R = 8.314f
    private const val AIR_MOLAR_MASS_GRAMS = 28.97f
    private const val MAF_TO_KW = 0.9864f

    /** Fuel system status 4: open loop for engine load, or fuel cut on deceleration. */
    private const val FUEL_STATUS_LOAD_OR_DECEL = 4

    /**
     * Fuel flow in litres per hour.
     *
     * Preference order: the ECU's own fuel-rate PID, then mass airflow divided by
     * the stoichiometric ratio, then a speed-density estimate for engines with
     * no MAF sensor.
     */
    fun fuelRateLitresPerHour(readings: Map<Int, Float>, throttleClosed: Boolean?, s: Settings): Float? {
        readings[Pids.FUEL_RATE]?.let { rate ->
            // Some ECUs advertise this PID and always answer zero. Zero is only
            // believable with the throttle shut, where the engine may be in fuel cut.
            val suspicious = rate == 0f && (readings[Pids.RPM] ?: 0f) > 1500f && throttleClosed == false
            if (!suspicious) return rate
        }
        val maf = readings[Pids.MAF] ?: estimateMafFromSpeedDensity(readings, s) ?: return null
        val gramsFuelPerSecond = maf / s.fuelType.stoichAfr
        return gramsFuelPerSecond * SECONDS_PER_HOUR / s.fuelType.densityGramsPerLitre
    }

    /**
     * Speed-density airflow estimate in g/s, for engines without a MAF sensor:
     * mass flow follows from manifold pressure, charge temperature, and how much
     * swept volume the engine pumps per second.
     */
    fun estimateMafFromSpeedDensity(readings: Map<Int, Float>, s: Settings): Float? {
        val map = readings[Pids.MAP] ?: return null
        val rpm = readings[Pids.RPM] ?: return null
        val iatC = readings[Pids.INTAKE_TEMP] ?: 25f
        if (rpm <= 0f) return 0f
        val intakeKelvin = iatC + 273.15f
        // A four-stroke engine ingests its displacement once every two revolutions.
        val volumePerSecond = s.displacementLitres / 1000f * (rpm / 120f)
        val molarFlow = map * 1000f * volumePerSecond / (IDEAL_GAS_R * intakeKelvin)
        return molarFlow * AIR_MOLAR_MASS_GRAMS * s.volumetricEfficiency
    }

    /**
     * Deceleration fuel cut: the ECU shuts the injectors off when the throttle is
     * closed above idle, but air still flows, so a MAF-based estimate would keep
     * counting fuel that is not being burned.
     */
    fun isFuelCut(readings: Map<Int, Float>, throttleClosed: Boolean?): Boolean {
        val status = readings[Pids.FUEL_SYSTEM]?.toInt() ?: return false
        val rpm = readings[Pids.RPM] ?: return false
        // Status 4 also covers full-load enrichment; a closed throttle tells them apart.
        return status == FUEL_STATUS_LOAD_OR_DECEL && throttleClosed == true && rpm > 1100f
    }

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
     * Crank power in kW. Uses torque and RPM when available, otherwise the
     * airflow rule of thumb for a naturally aspirated petrol engine.
     */
    fun powerKw(readings: Map<Int, Float>): Float? {
        val rpm = readings[Pids.RPM]
        val torque = torqueNm(readings)
        if (torque != null && rpm != null) return torque * rpm * 2f * Math.PI.toFloat() / 60000f
        val maf = readings[Pids.MAF] ?: return null
        return maf * MAF_TO_KW
    }

    /** Remaining range in km from tank level and the trip's average consumption. */
    fun rangeKm(readings: Map<Int, Float>, avgL100: Float?, s: Settings): Float? {
        val levelPercent = readings[Pids.FUEL_LEVEL] ?: return null
        if (avgL100 == null || avgL100 <= 0f) return null
        return s.tankLitres * levelPercent / 100f / avgL100 * 100f
    }

    /**
     * Learns what a closed throttle reads. Absolute throttle position rarely
     * reads 0% when shut; 12-18% is typical, so a fixed threshold would never
     * see the pedal released.
     */
    class ClosedThrottle {
        private var baseline = Float.MAX_VALUE

        fun update(value: Float) {
            if (value >= 0f && value < baseline) baseline = value
        }

        fun isClosed(value: Float): Boolean = baseline != Float.MAX_VALUE && value <= baseline + MARGIN

        private companion object {
            const val MARGIN = 2.5f
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
        val sec = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun round1(v: Float) = (v * 10f).roundToInt() / 10f
}
