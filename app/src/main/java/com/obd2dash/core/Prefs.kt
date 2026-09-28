package com.obd2dash.core

import android.content.Context
import android.content.SharedPreferences

/** Persisted settings, including the vehicle constants the fuel maths needs. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("obd2dash", Context.MODE_PRIVATE)

    enum class FuelType(val label: String, val stoichAfr: Float, val densityGramsPerLitre: Float) {
        PETROL("Petrol", 14.7f, 745f),
        DIESEL("Diesel", 14.5f, 832f),
        CNG("CNG", 17.2f, 700f),
        LPG("LPG", 15.6f, 540f)
    }

    var imperialUnits: Boolean
        get() = sp.getBoolean("imperial", false)
        set(v) = sp.edit().putBoolean("imperial", v).apply()

    /** Engine size in litres, used by the speed-density fallback when no MAF exists. */
    var displacementLitres: Float
        get() = sp.getFloat("displacement", 1.5f)
        set(v) = sp.edit().putFloat("displacement", v).apply()

    var volumetricEfficiency: Float
        get() = sp.getFloat("ve", 0.85f)
        set(v) = sp.edit().putFloat("ve", v).apply()

    var tankLitres: Float
        get() = sp.getFloat("tank", 45f)
        set(v) = sp.edit().putFloat("tank", v).apply()

    var fuelType: FuelType
        get() = runCatching { FuelType.valueOf(sp.getString("fuel", "PETROL")!!) }
            .getOrDefault(FuelType.PETROL)
        set(v) = sp.edit().putString("fuel", v.name).apply()

    var redlineRpm: Int
        get() = sp.getInt("redline", 6000)
        set(v) = sp.edit().putInt("redline", v).apply()

    var loggingEnabled: Boolean
        get() = sp.getBoolean("logging", false)
        set(v) = sp.edit().putBoolean("logging", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", true)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()

    var lastDeviceAddress: String?
        get() = sp.getString("deviceAddress", null)
        set(v) = sp.edit().putString("deviceAddress", v).apply()

    var lastDeviceName: String?
        get() = sp.getString("deviceName", null)
        set(v) = sp.edit().putString("deviceName", v).apply()

    /** Protocol number learned from ATDPN, replayed to skip auto-detection. */
    var lastProtocol: String?
        get() = sp.getString("protocol", null)
        set(v) = sp.edit().putString("protocol", v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("autoConnect", true)
        set(v) = sp.edit().putBoolean("autoConnect", v).apply()
}
