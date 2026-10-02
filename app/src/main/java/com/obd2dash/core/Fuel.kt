package com.obd2dash.core

import com.obd2dash.obd.Pids

/** A fuel the engine can burn. CNG is sold and measured by mass, the rest by volume. */
enum class Fuel(val label: String, val stoichAfr: Float, val soldByMass: Boolean, val densityGramsPerLitre: Float) {
    PETROL("Petrol", 14.7f, false, 745f),
    DIESEL("Diesel", 14.5f, false, 832f),
    CNG("CNG", 17.2f, true, 0f),
    LPG("LPG", 15.6f, false, 540f);

    /** The unit quantities of this fuel are counted in. */
    val amountUnit: String get() = if (soldByMass) "kg" else "L"

    /** Converts grams of this fuel to its amount unit. */
    fun amountFromGrams(grams: Float): Float = if (soldByMass) grams / 1000f else grams / densityGramsPerLitre
}

/** What the car can run on. Bi-fuel cars start on petrol and switch to their second fuel. */
enum class FuelSystem(val label: String, val primary: Fuel, val secondary: Fuel?) {
    PETROL("Petrol", Fuel.PETROL, null),
    DIESEL("Diesel", Fuel.DIESEL, null),
    PETROL_CNG("Petrol + CNG (bi-fuel)", Fuel.PETROL, Fuel.CNG),
    CNG_ONLY("CNG only", Fuel.CNG, null),
    PETROL_LPG("Petrol + LPG (bi-fuel)", Fuel.PETROL, Fuel.LPG);

    val isBiFuel: Boolean get() = secondary != null
    val usesCng: Boolean get() = primary == Fuel.CNG || secondary == Fuel.CNG

    /** The liquid fuel whose tank level PID 2F reports, if any. */
    val tankFuel: Fuel? get() = if (!primary.soldByMass) primary else null

    fun canBurn(fuel: Fuel) = fuel == primary || fuel == secondary
}

enum class FuelDetection(val label: String) {
    AUTO("Automatic, from the ECU's fuel-type report"),
    MANUAL("Manual, tap the fuel badge on the dashboard")
}

/** Decides which fuel is burning right now. */
object FuelSelector {

    fun active(s: Settings, readings: Map<Int, Float>): Fuel {
        val system = s.fuelSystem
        if (!system.isBiFuel) return system.primary
        if (s.fuelDetection == FuelDetection.AUTO) reported(s, readings)?.let { return it }
        return s.manualFuel.takeIf { system.canBurn(it) } ?: system.primary
    }

    /** The fuel the ECU says is in use, when it says something this car can burn. */
    fun reported(s: Settings, readings: Map<Int, Float>): Fuel? =
        fromObd(readings[Pids.FUEL_TYPE])?.takeIf { s.fuelSystem.canBurn(it) }

    /**
     * PID 51 (SAE J1979 fuel type). The bi-fuel codes name the fuel currently
     * in use, which is what makes automatic detection possible at all.
     */
    fun fromObd(code: Float?): Fuel? = when (code?.toInt()) {
        1, 9 -> Fuel.PETROL
        4, 23 -> Fuel.DIESEL
        6, 13 -> Fuel.CNG
        5, 12 -> Fuel.LPG
        else -> null
    }
}

/** One fuel's share of a trip. [amount] is litres, or kg for CNG. */
data class FuelUse(val fuel: Fuel, val amount: Float, val distanceKm: Float) {

    /** Litres or kg per 100 km, once there is enough distance to mean anything. */
    val per100Km: Float? get() = if (distanceKm >= 0.5f && amount > 0f) amount / distanceKm * 100f else null
}
