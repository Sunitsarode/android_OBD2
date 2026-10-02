package com.obd2dash.core

/**
 * Estimates the CNG left in the cylinder.
 *
 * Standard OBD has no PID for cylinder pressure, so the level is counted down
 * from the last fill-up the driver entered, using the estimated burn. It drifts
 * with the burn estimate, so it is shown as an estimate and resets every fill.
 */
class CngTracker(private val prefs: Prefs) {

    // Burned since the last save; written to settings in batches, not every cycle.
    private var pendingKg = 0f
    private var pendingKm = 0f

    @Synchronized
    fun burn(kg: Float, km: Float) {
        pendingKg += kg
        pendingKm += km
    }

    /** Estimated kg left, or null until a fill-up has been entered. */
    @Synchronized
    fun remainingKg(): Float? {
        val stored = prefs.cngRemainingKg
        return if (stored < 0f) null else (stored - pendingKg).coerceAtLeast(0f)
    }

    /** Average km per kg across every logged drive; steadier than a single trip. */
    @Synchronized
    fun lifetimeKmPerKg(): Float? {
        val kg = prefs.cngLifetimeKg + pendingKg
        val km = prefs.cngLifetimeKm + pendingKm
        return if (kg >= MIN_LIFETIME_KG && km >= MIN_LIFETIME_KM) km / kg else null
    }

    @Synchronized
    fun refill(kg: Float) {
        flush()
        prefs.cngRemainingKg = kg
    }

    @Synchronized
    fun flush() {
        if (pendingKg == 0f && pendingKm == 0f) return
        val stored = prefs.cngRemainingKg
        if (stored >= 0f) prefs.cngRemainingKg = (stored - pendingKg).coerceAtLeast(0f)
        prefs.cngLifetimeKg = prefs.cngLifetimeKg + pendingKg
        prefs.cngLifetimeKm = prefs.cngLifetimeKm + pendingKm
        pendingKg = 0f
        pendingKm = 0f
    }

    private companion object {
        const val MIN_LIFETIME_KG = 1f
        const val MIN_LIFETIME_KM = 20f
    }
}
