package com.obd2dash.core

import com.obd2dash.obd.Pids
import kotlin.math.max

data class TripStats(
    val startedAt: Long = System.currentTimeMillis(),
    val elapsedSeconds: Long = 0,
    val movingSeconds: Long = 0,
    val idleSeconds: Long = 0,
    val distanceKm: Float = 0f,
    val fuelUsedLitres: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val avgMovingSpeedKmh: Float = 0f,
    val avgConsumptionL100: Float? = null,
    val maxSpeedKmh: Float = 0f,
    val maxRpm: Float = 0f,
    val maxCoolantC: Float = 0f,
    val maxBoostKpa: Float = 0f,
    val maxPowerKw: Float = 0f,
    val rangeKm: Float? = null
)

data class PerfResults(
    val zeroTo60Kmh: Float? = null,
    val zeroTo100Kmh: Float? = null,
    val sixtyTo100Kmh: Float? = null,
    val quarterMileSeconds: Float? = null,
    val quarterMileTrapKmh: Float? = null,
    val hundredToZeroMetres: Float? = null,
    val running: Boolean = false,
    val currentRunSeconds: Float = 0f
)

/**
 * Integrates live readings into trip totals.
 *
 * Distance and fuel both come from integrating a rate over the interval between
 * samples, so accuracy depends on polling staying reasonably steady.
 */
class TripComputer {

    private var startedAt = System.currentTimeMillis()
    private var lastSampleAt = 0L
    private var distanceKm = 0f
    private var fuelLitres = 0f
    private var movingMs = 0L
    private var idleMs = 0L
    private var maxSpeed = 0f
    private var maxRpm = 0f
    private var maxCoolant = 0f
    private var maxBoost = 0f
    private var maxPower = 0f

    var stats = TripStats()
        private set

    fun reset() {
        startedAt = System.currentTimeMillis()
        lastSampleAt = 0L
        distanceKm = 0f
        fuelLitres = 0f
        movingMs = 0L
        idleMs = 0L
        maxSpeed = 0f
        maxRpm = 0f
        maxCoolant = 0f
        maxBoost = 0f
        maxPower = 0f
        stats = TripStats(startedAt = startedAt)
    }

    /** Returns the distance covered since the previous sample, in km. */
    fun update(
        readings: Map<Int, Float>,
        fuelRateLh: Float?,
        powerKw: Float?,
        boostKpa: Float?,
        prefs: Prefs,
        now: Long = System.currentTimeMillis()
    ): Float {
        if (lastSampleAt == 0L) {
            lastSampleAt = now
            return 0f
        }
        val deltaMs = (now - lastSampleAt).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        lastSampleAt = now
        val deltaHours = deltaMs / 3_600_000f

        val speed = readings[Pids.SPEED] ?: 0f
        val stepKm = speed * deltaHours
        distanceKm += stepKm

        if (speed >= 1f) movingMs += deltaMs else idleMs += deltaMs
        if (fuelRateLh != null) fuelLitres += fuelRateLh * deltaHours

        maxSpeed = max(maxSpeed, speed)
        readings[Pids.RPM]?.let { maxRpm = max(maxRpm, it) }
        readings[Pids.COOLANT]?.let { maxCoolant = max(maxCoolant, it) }
        boostKpa?.let { maxBoost = max(maxBoost, it) }
        powerKw?.let { maxPower = max(maxPower, it) }

        val elapsedMs = now - startedAt
        val elapsedHours = elapsedMs / 3_600_000f
        val avgConsumption =
            if (distanceKm > 0.1f && fuelLitres > 0f) fuelLitres / distanceKm * 100f else null

        stats = TripStats(
            startedAt = startedAt,
            elapsedSeconds = elapsedMs / 1000,
            movingSeconds = movingMs / 1000,
            idleSeconds = idleMs / 1000,
            distanceKm = distanceKm,
            fuelUsedLitres = fuelLitres,
            avgSpeedKmh = if (elapsedHours > 0f) distanceKm / elapsedHours else 0f,
            avgMovingSpeedKmh = if (movingMs > 0) distanceKm / (movingMs / 3_600_000f) else 0f,
            avgConsumptionL100 = avgConsumption,
            maxSpeedKmh = maxSpeed,
            maxRpm = maxRpm,
            maxCoolantC = maxCoolant,
            maxBoostKpa = maxBoost,
            maxPowerKw = maxPower,
            rangeKm = Metrics.rangeKm(readings, avgConsumption, prefs)
        )
        return stepKm
    }

    private companion object {
        // Guards the integrators against a stalled connection resuming.
        const val MAX_SAMPLE_GAP_MS = 2000L
    }
}
