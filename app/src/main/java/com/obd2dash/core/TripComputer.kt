package com.obd2dash.core

import com.obd2dash.obd.Pids
import kotlin.math.max
import kotlin.math.roundToInt

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
    val rangeKm: Float? = null,
    val cost: Float? = null,
    val costPerKm: Float? = null,
    val harshAccelerations: Int = 0,
    val harshBrakings: Int = 0,
    val highRpmSeconds: Long = 0,
    val ecoScore: Int? = null
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
 * Distance and fuel come from integrating a rate over the interval between
 * samples, so accuracy depends on polling staying reasonably steady.
 */
class TripComputer {

    private var startedAt = System.currentTimeMillis()
    private var lastSample = 0L
    private var distanceKm = 0f
    private var fuelLitres = 0f
    private var movingMs = 0L
    private var idleMs = 0L
    private var highRpmMs = 0L
    private var maxSpeed = 0f
    private var maxRpm = 0f
    private var maxCoolant = 0f
    private var maxBoost = 0f
    private var maxPower = 0f
    private var harshAccel = 0
    private var harshBrake = 0
    private var lastHarshAt = 0L

    private val speedTimes = LongArray(SPEED_HISTORY)
    private val speedValues = FloatArray(SPEED_HISTORY)
    private var speedCount = 0
    private var speedNext = 0

    var stats = TripStats()
        private set

    /** When the trip last received a sample, or 0 if it has none yet. */
    val lastSampleAt: Long get() = lastSample

    fun reset() {
        startedAt = System.currentTimeMillis()
        lastSample = 0L
        distanceKm = 0f
        fuelLitres = 0f
        movingMs = 0L
        idleMs = 0L
        highRpmMs = 0L
        maxSpeed = 0f
        maxRpm = 0f
        maxCoolant = 0f
        maxBoost = 0f
        maxPower = 0f
        harshAccel = 0
        harshBrake = 0
        lastHarshAt = 0L
        speedCount = 0
        speedNext = 0
        stats = TripStats(startedAt = startedAt)
    }

    fun update(
        readings: Map<Int, Float>,
        fuelRateLh: Float?,
        powerKw: Float?,
        boostKpa: Float?,
        speedFresh: Boolean,
        s: Settings,
        now: Long
    ) {
        if (lastSample == 0L) {
            lastSample = now
            return
        }
        val deltaMs = (now - lastSample).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        lastSample = now
        val deltaHours = deltaMs / 3_600_000f

        val speed = readings[Pids.SPEED] ?: 0f
        val rpm = readings[Pids.RPM]
        distanceKm += speed * deltaHours
        if (speed >= 1f) movingMs += deltaMs else idleMs += deltaMs
        if (fuelRateLh != null) fuelLitres += fuelRateLh * deltaHours
        val highRpm = if (s.fuelType == Prefs.FuelType.DIESEL) 2500f else 3000f
        if ((rpm ?: 0f) > highRpm) highRpmMs += deltaMs
        if (speedFresh) trackHarshEvents(speed, now)

        maxSpeed = max(maxSpeed, speed)
        rpm?.let { maxRpm = max(maxRpm, it) }
        readings[Pids.COOLANT]?.let { maxCoolant = max(maxCoolant, it) }
        boostKpa?.let { maxBoost = max(maxBoost, it) }
        powerKw?.let { maxPower = max(maxPower, it) }

        val elapsedMs = now - startedAt
        val elapsedHours = elapsedMs / 3_600_000f
        val avgConsumption = if (distanceKm > 0.1f && fuelLitres > 0f) fuelLitres / distanceKm * 100f else null
        val cost = if (s.fuelPricePerLitre > 0f) fuelLitres * s.fuelPricePerLitre else null

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
            rangeKm = Metrics.rangeKm(readings, avgConsumption, s),
            cost = cost,
            costPerKm = if (cost != null && distanceKm > 0.5f) cost / distanceKm else null,
            harshAccelerations = harshAccel,
            harshBrakings = harshBrake,
            highRpmSeconds = highRpmMs / 1000,
            ecoScore = ecoScore()
        )
    }

    /**
     * Counts hard launches and hard stops. Speed arrives in 1 km/h steps, so
     * acceleration is measured over about a second rather than between two
     * neighbouring samples, which would mostly measure rounding.
     */
    private fun trackHarshEvents(speed: Float, now: Long) {
        speedTimes[speedNext] = now
        speedValues[speedNext] = speed
        speedNext = (speedNext + 1) % SPEED_HISTORY
        if (speedCount < SPEED_HISTORY) speedCount++

        var reference = -1
        for (k in 1 until speedCount) {
            val i = (speedNext - 1 - k + SPEED_HISTORY) % SPEED_HISTORY
            if (now - speedTimes[i] >= HARSH_WINDOW_MS) {
                reference = i
                break
            }
        }
        if (reference < 0) return
        val seconds = (now - speedTimes[reference]) / 1000f
        if (seconds > 2.5f || now - lastHarshAt < HARSH_REFRACTORY_MS) return

        val accel = (speed - speedValues[reference]) / 3.6f / seconds
        if (accel >= HARSH_ACCEL_MS2) {
            harshAccel++
            lastHarshAt = now
        } else if (accel <= -HARSH_BRAKE_MS2) {
            harshBrake++
            lastHarshAt = now
        }
    }

    /**
     * 0-100, starting from 100 and losing points for harsh events (per 10 km),
     * idling, and time spent at high revs. A rough guide, not a standard.
     */
    private fun ecoScore(): Int? {
        if (distanceKm < 1f) return null
        val drivingMs = (movingMs + idleMs).coerceAtLeast(1L).toFloat()
        val eventsPer10Km = (harshAccel + harshBrake) / (distanceKm / 10f).coerceAtLeast(1f)
        val penalty = minOf(30f, eventsPer10Km * 6f) +
                minOf(20f, idleMs / drivingMs * 40f) +
                minOf(25f, highRpmMs / drivingMs * 80f)
        return (100f - penalty).roundToInt().coerceIn(0, 100)
    }

    /** A summary for trip history, or null if the trip was too short to keep. */
    fun toRecord(): TripRecord? {
        val s = stats
        if (s.distanceKm < MIN_RECORD_KM) return null
        return TripRecord(
            startedAt = s.startedAt,
            durationSeconds = s.elapsedSeconds,
            distanceKm = s.distanceKm,
            fuelLitres = s.fuelUsedLitres,
            avgConsumptionL100 = s.avgConsumptionL100,
            cost = s.cost,
            maxSpeedKmh = s.maxSpeedKmh,
            ecoScore = s.ecoScore,
            harshAccelerations = s.harshAccelerations,
            harshBrakings = s.harshBrakings
        )
    }

    private companion object {
        // Guards the integrators against a stalled connection resuming.
        const val MAX_SAMPLE_GAP_MS = 2000L
        const val SPEED_HISTORY = 16
        const val HARSH_WINDOW_MS = 900L
        const val HARSH_REFRACTORY_MS = 3000L

        /** About 0.3 g: a hard launch, not ordinary brisk driving. */
        const val HARSH_ACCEL_MS2 = 3.0f

        /** About 0.36 g: firmer than any planned stop. */
        const val HARSH_BRAKE_MS2 = 3.5f
        const val MIN_RECORD_KM = 0.2f
    }
}
