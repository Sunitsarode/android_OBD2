package com.obd2dash.core

import android.content.Context
import android.content.SharedPreferences

/** Persisted settings, including the vehicle constants the fuel maths needs. */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    enum class Transmission(val label: String) {
        MANUAL("Manual"),
        AUTOMATIC("Automatic / AMT"),
        CVT("CVT / e-CVT (hybrid)")
    }

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun float(key: String, def: Float) = sp.getFloat(key, def)
    private fun str(key: String): String? = sp.getString(key, null)
    private fun long(key: String, def: Long) = sp.getLong(key, def)
    private fun put(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    var imperialUnits: Boolean
        get() = bool("imperial", false)
        set(v) = put { putBoolean("imperial", v) }

    /** Engine size in litres, used by the speed-density fallback when no MAF exists. */
    var displacementLitres: Float
        get() = float("displacement", 1.5f)
        set(v) = put { putFloat("displacement", v) }

    var volumetricEfficiency: Float
        get() = float("ve", 0.85f)
        set(v) = put { putFloat("ve", v) }

    var tankLitres: Float
        get() = float("tank", 45f)
        set(v) = put { putFloat("tank", v) }

    var fuelSystem: FuelSystem
        get() {
            str("fuelSystem")?.let { saved -> runCatching { return FuelSystem.valueOf(saved) } }
            // Older versions stored a single fuel; CNG and LPG cars here are bi-fuel.
            return when (str("fuel")) {
                "DIESEL" -> FuelSystem.DIESEL
                "CNG" -> FuelSystem.PETROL_CNG
                "LPG" -> FuelSystem.PETROL_LPG
                else -> FuelSystem.PETROL
            }
        }
        set(v) = put { putString("fuelSystem", v.name) }

    var fuelDetection: FuelDetection
        get() = runCatching { FuelDetection.valueOf(str("fuelDetection") ?: "AUTO") }.getOrDefault(FuelDetection.AUTO)
        set(v) = put { putString("fuelDetection", v.name) }

    /**
     * The fuel assumed when the ECU does not say which one is burning. A bi-fuel
     * CNG car spends nearly all its time on CNG, so that is the default until the
     * driver taps the badge.
     */
    var manualFuel: Fuel
        get() = str("manualFuel")?.let { saved -> runCatching { Fuel.valueOf(saved) }.getOrNull() }
            ?: fuelSystem.secondary ?: fuelSystem.primary
        set(v) = put { putString("manualFuel", v.name) }

    /** Petrol or diesel price per litre. */
    var fuelPricePerLitre: Float
        get() = float("fuelPrice", 0f)
        set(v) = put { putFloat("fuelPrice", v) }

    var cngPricePerKg: Float
        get() = float("cngPrice", 0f)
        set(v) = put { putFloat("cngPrice", v) }

    var lpgPricePerLitre: Float
        get() = float("lpgPrice", 0f)
        set(v) = put { putFloat("lpgPrice", v) }

    var cngCapacityKg: Float
        get() = float("cngCapacity", 9f)
        set(v) = put { putFloat("cngCapacity", v) }

    /** Low-CNG alert threshold in kg. 0 turns it off. */
    var lowCngKg: Float
        get() = float("lowCng", 1f)
        set(v) = put { putFloat("lowCng", v) }

    /** Cylinder hydrostatic test due date as DD-MM-YYYY, or blank. */
    var cylinderTestDue: String
        get() = str("cylinderTest") ?: ""
        set(v) = put { putString("cylinderTest", v) }

    /** True shows economy as km/L and km/kg; false as L/100km and kg/100km. */
    var economyPerDistance: Boolean
        get() = bool("economyPerDistance", true)
        set(v) = put { putBoolean("economyPerDistance", v) }

    /** Estimated CNG left in kg, counted down from the last fill-up. Negative means unknown. */
    var cngRemainingKg: Float
        get() = float("cngRemaining", -1f)
        set(v) = put { putFloat("cngRemaining", v) }

    var cngLifetimeKm: Float
        get() = float("cngLifetimeKm", 0f)
        set(v) = put { putFloat("cngLifetimeKm", v) }

    var cngLifetimeKg: Float
        get() = float("cngLifetimeKg", 0f)
        set(v) = put { putFloat("cngLifetimeKg", v) }

    /** Engine running time on CNG, all drives. */
    var cngLifetimeMs: Long
        get() = long("cngLifetimeMs", 0L)
        set(v) = put { putLong("cngLifetimeMs", v) }

    var cngSinceFillKg: Float
        get() = float("cngSinceFillKg", 0f)
        set(v) = put { putFloat("cngSinceFillKg", v) }

    var cngSinceFillKm: Float
        get() = float("cngSinceFillKm", 0f)
        set(v) = put { putFloat("cngSinceFillKm", v) }

    var cngSinceFillMs: Long
        get() = long("cngSinceFillMs", 0L)
        set(v) = put { putLong("cngSinceFillMs", v) }

    /** Measured km/kg: distance between fill-ups divided by the kg on the receipt. Negative means none yet. */
    var cngLastFillKmPerKg: Float
        get() = float("cngLastFillKmPerKg", -1f)
        set(v) = put { putFloat("cngLastFillKmPerKg", v) }

    var redlineRpm: Int
        get() = int("redline", 6000)
        set(v) = put { putInt("redline", v) }

    var transmission: Transmission
        get() = runCatching { Transmission.valueOf(str("transmission") ?: "MANUAL") }.getOrDefault(Transmission.MANUAL)
        set(v) = put { putString("transmission", v.name) }

    var gearCount: Int
        get() = int("gearCount", 5)
        set(v) = put { putInt("gearCount", v) }

    /** RPM above which the shift-up hint appears in a manual. 0 turns it off. */
    var shiftUpRpm: Int
        get() = int("shiftUpRpm", 2500)
        set(v) = put { putInt("shiftUpRpm", v) }

    /** Learned gear ratios (km/h per rpm), comma-separated, lowest gear first. */
    var learnedGearRatios: String
        get() = str("gearRatios") ?: ""
        set(v) = put { putString("gearRatios", v) }

    /** Speed alert threshold in km/h. 0 turns it off. */
    var speedLimitKmh: Int
        get() = int("speedLimit", 0)
        set(v) = put { putInt("speedLimit", v) }

    var overheatC: Int
        get() = int("overheat", 108)
        set(v) = put { putInt("overheat", v) }

    var lowFuelPercent: Int
        get() = int("lowFuel", 10)
        set(v) = put { putInt("lowFuel", v) }

    var alertBeep: Boolean
        get() = bool("alertBeep", true)
        set(v) = put { putBoolean("alertBeep", v) }

    var alertVoice: Boolean
        get() = bool("alertVoice", false)
        set(v) = put { putBoolean("alertVoice", v) }

    var loggingEnabled: Boolean
        get() = bool("logging", false)
        set(v) = put { putBoolean("logging", v) }

    var keepScreenOn: Boolean
        get() = bool("keepScreenOn", true)
        set(v) = put { putBoolean("keepScreenOn", v) }

    var autoConnect: Boolean
        get() = bool("autoConnect", true)
        set(v) = put { putBoolean("autoConnect", v) }

    var startOnBoot: Boolean
        get() = bool("startOnBoot", false)
        set(v) = put { putBoolean("startOnBoot", v) }

    /** Batching and the other adapter speedups; off means plain one-PID-at-a-time polling. */
    var fastPolling: Boolean
        get() = bool("fastPolling", true)
        set(v) = put { putBoolean("fastPolling", v) }

    /** Dashboard tile metric keys, comma-separated. Empty means the defaults. */
    var dashTiles: String
        get() = str("dashTiles") ?: ""
        set(v) = put { putString("dashTiles", v) }

    var hudMirror: Boolean
        get() = bool("hudMirror", false)
        set(v) = put { putBoolean("hudMirror", v) }

    var lastDeviceAddress: String?
        get() = str("deviceAddress")
        set(v) = put { putString("deviceAddress", v) }

    var lastDeviceName: String?
        get() = str("deviceName")
        set(v) = put { putString("deviceName", v) }

    /** Adapter address that failed fast polling; it gets standard polling until re-enabled. */
    var fastModeBlockedFor: String?
        get() = str("fastModeBlockedFor")
        set(v) = put { putString("fastModeBlockedFor", v) }

    /** Protocol number learned from ATDPN, replayed to skip auto-detection. */
    var lastProtocol: String?
        get() = str("protocol")
        set(v) = put { putString("protocol", v) }

    fun snapshot() = Settings(
        imperial = imperialUnits,
        fuelSystem = fuelSystem,
        fuelDetection = fuelDetection,
        manualFuel = manualFuel,
        displacementLitres = displacementLitres,
        volumetricEfficiency = volumetricEfficiency,
        tankLitres = tankLitres,
        fuelPricePerLitre = fuelPricePerLitre,
        cngPricePerKg = cngPricePerKg,
        lpgPricePerLitre = lpgPricePerLitre,
        cngCapacityKg = cngCapacityKg,
        lowCngKg = lowCngKg,
        cylinderTestDueMs = parseDate(cylinderTestDue),
        economyPerDistance = economyPerDistance,
        redlineRpm = redlineRpm,
        transmission = transmission,
        gearCount = gearCount,
        shiftUpRpm = shiftUpRpm,
        speedLimitKmh = speedLimitKmh,
        overheatC = overheatC,
        lowFuelPercent = lowFuelPercent,
        alertBeep = alertBeep,
        alertVoice = alertVoice,
        fastPolling = fastPolling,
        logging = loggingEnabled
    )

    /** Parses DD-MM-YYYY to epoch millis, or 0 when blank or malformed. */
    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L
        return try {
            val format = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).apply { isLenient = false }
            format.parse(text.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val NAME = "obd2dash"
    }
}

/** Immutable copy of the settings the polling loop reads every cycle. */
data class Settings(
    val imperial: Boolean,
    val fuelSystem: FuelSystem,
    val fuelDetection: FuelDetection,
    val manualFuel: Fuel,
    val displacementLitres: Float,
    val volumetricEfficiency: Float,
    val tankLitres: Float,
    val fuelPricePerLitre: Float,
    val cngPricePerKg: Float,
    val lpgPricePerLitre: Float,
    val cngCapacityKg: Float,
    val lowCngKg: Float,
    val cylinderTestDueMs: Long,
    val economyPerDistance: Boolean,
    val redlineRpm: Int,
    val transmission: Prefs.Transmission,
    val gearCount: Int,
    val shiftUpRpm: Int,
    val speedLimitKmh: Int,
    val overheatC: Int,
    val lowFuelPercent: Int,
    val alertBeep: Boolean,
    val alertVoice: Boolean,
    val fastPolling: Boolean,
    val logging: Boolean
) {
    /** Price per litre, or per kg for CNG. 0 means not set. */
    fun priceOf(fuel: Fuel): Float = when (fuel) {
        Fuel.PETROL, Fuel.DIESEL -> fuelPricePerLitre
        Fuel.CNG -> cngPricePerKg
        Fuel.LPG -> lpgPricePerLitre
    }
}
