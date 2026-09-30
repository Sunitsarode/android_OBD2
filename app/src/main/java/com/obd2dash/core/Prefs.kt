package com.obd2dash.core

import android.content.Context
import android.content.SharedPreferences

/** Persisted settings, including the vehicle constants the fuel maths needs. */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    enum class FuelType(val label: String, val stoichAfr: Float, val densityGramsPerLitre: Float) {
        PETROL("Petrol", 14.7f, 745f),
        DIESEL("Diesel", 14.5f, 832f),
        CNG("CNG", 17.2f, 700f),
        LPG("LPG", 15.6f, 540f)
    }

    enum class Transmission(val label: String) {
        MANUAL("Manual"),
        AUTOMATIC("Automatic / AMT"),
        CVT("CVT / e-CVT (hybrid)")
    }

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun float(key: String, def: Float) = sp.getFloat(key, def)
    private fun str(key: String): String? = sp.getString(key, null)
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

    var fuelType: FuelType
        get() = runCatching { FuelType.valueOf(str("fuel") ?: "PETROL") }.getOrDefault(FuelType.PETROL)
        set(v) = put { putString("fuel", v.name) }

    var fuelPricePerLitre: Float
        get() = float("fuelPrice", 0f)
        set(v) = put { putFloat("fuelPrice", v) }

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

    /** Protocol number learned from ATDPN, replayed to skip auto-detection. */
    var lastProtocol: String?
        get() = str("protocol")
        set(v) = put { putString("protocol", v) }

    fun snapshot() = Settings(
        imperial = imperialUnits,
        fuelType = fuelType,
        displacementLitres = displacementLitres,
        volumetricEfficiency = volumetricEfficiency,
        tankLitres = tankLitres,
        fuelPricePerLitre = fuelPricePerLitre,
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
    val fuelType: Prefs.FuelType,
    val displacementLitres: Float,
    val volumetricEfficiency: Float,
    val tankLitres: Float,
    val fuelPricePerLitre: Float,
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
)
