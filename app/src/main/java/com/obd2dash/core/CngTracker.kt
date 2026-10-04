package com.obd2dash.core

/** CNG use across drives, and since the last fill-up. */
data class CngTotals(
    val usedKg: Float,
    val distanceKm: Float,
    val runSeconds: Long,
    val sinceFillKg: Float,
    val sinceFillKm: Float,
    val sinceFillSeconds: Long,
    /** Estimated CNG left, or null until a fill-up has been logged. */
    val remainingKg: Float?,
    /** Measured at the last fill-up: km since the previous fill / kg on the receipt. */
    val lastFillKmPerKg: Float?
) {
    /** Long-run CNG mileage, once there is enough driving behind it to mean anything. */
    val kmPerKg: Float? get() = if (usedKg >= 1f && distanceKm >= 20f) distanceKm / usedKg else null

    val sinceFillKmPerKg: Float? get() = if (sinceFillKg >= 0.3f && sinceFillKm >= 5f) sinceFillKm / sinceFillKg else null
}

/**
 * Keeps CNG totals. Standard OBD has no PID for cylinder pressure, so the
 * level is counted down from the last fill-up the driver logged, using the
 * estimated burn. Totals are saved in batches, not every poll cycle.
 */
class CngTracker(private val prefs: Prefs) {

    private var pendingKg = 0f
    private var pendingKm = 0f
    private var pendingMs = 0L

    @Synchronized
    fun burn(kg: Float, km: Float, runMs: Long) {
        pendingKg += kg
        pendingKm += km
        pendingMs += runMs
    }

    @Synchronized
    fun remainingKg(): Float? {
        val stored = prefs.cngRemainingKg
        return if (stored < 0f) null else (stored - pendingKg).coerceAtLeast(0f)
    }

    @Synchronized
    fun totals(): CngTotals = CngTotals(
        usedKg = prefs.cngLifetimeKg + pendingKg,
        distanceKm = prefs.cngLifetimeKm + pendingKm,
        runSeconds = (prefs.cngLifetimeMs + pendingMs) / 1000,
        sinceFillKg = prefs.cngSinceFillKg + pendingKg,
        sinceFillKm = prefs.cngSinceFillKm + pendingKm,
        sinceFillSeconds = (prefs.cngSinceFillMs + pendingMs) / 1000,
        remainingKg = remainingKg(),
        lastFillKmPerKg = prefs.cngLastFillKmPerKg.takeIf { it > 0f }
    )

    /**
     * Logs a fill-up. With the receipt's [filledKg], the distance since the
     * previous fill gives a measured mileage; that assumes both fills went to
     * full, which is how CNG stations normally fill.
     */
    @Synchronized
    fun refill(levelKg: Float, filledKg: Float?) {
        flush()
        if (filledKg != null && filledKg >= MIN_FILL_KG && prefs.cngSinceFillKm >= MIN_FILL_KM) {
            prefs.cngLastFillKmPerKg = prefs.cngSinceFillKm / filledKg
        }
        prefs.cngSinceFillKg = 0f
        prefs.cngSinceFillKm = 0f
        prefs.cngSinceFillMs = 0L
        prefs.cngRemainingKg = levelKg
    }

    @Synchronized
    fun flush() {
        if (pendingKg == 0f && pendingKm == 0f && pendingMs == 0L) return
        val stored = prefs.cngRemainingKg
        if (stored >= 0f) prefs.cngRemainingKg = (stored - pendingKg).coerceAtLeast(0f)
        prefs.cngLifetimeKg = prefs.cngLifetimeKg + pendingKg
        prefs.cngLifetimeKm = prefs.cngLifetimeKm + pendingKm
        prefs.cngLifetimeMs = prefs.cngLifetimeMs + pendingMs
        prefs.cngSinceFillKg = prefs.cngSinceFillKg + pendingKg
        prefs.cngSinceFillKm = prefs.cngSinceFillKm + pendingKm
        prefs.cngSinceFillMs = prefs.cngSinceFillMs + pendingMs
        pendingKg = 0f
        pendingKm = 0f
        pendingMs = 0L
    }

    /** Clears the all-time totals. The cylinder level and since-fill-up figures stay. */
    @Synchronized
    fun resetTotals() {
        flush()
        prefs.cngLifetimeKg = 0f
        prefs.cngLifetimeKm = 0f
        prefs.cngLifetimeMs = 0L
    }

    private companion object {
        const val MIN_FILL_KG = 0.5f
        const val MIN_FILL_KM = 10f
    }
}
