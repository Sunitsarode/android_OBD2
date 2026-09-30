package com.obd2dash.core

/**
 * Acceleration and braking timing.
 *
 * A run arms itself whenever the car is stationary and starts on the first
 * sample above walking pace, so no button press is needed. Timing resolution is
 * bounded by the OBD poll rate, which is typically 50-150 ms for speed.
 */
class PerfTimer {

    private var armed = false
    private var running = false
    private var runStartMs = 0L
    private var runDistanceM = 0f
    private var lastSampleAt = 0L
    private var lastSpeed = 0f

    private var t60: Float? = null
    private var t100: Float? = null
    private var quarterDone = false

    private var brakingFromMs = 0L
    private var brakingDistanceM = 0f
    private var braking = false

    var results = PerfResults()
        private set

    fun reset() {
        armed = false
        running = false
        braking = false
        results = PerfResults()
    }

    fun update(speedKmh: Float?, now: Long = System.currentTimeMillis()) {
        val speed = speedKmh ?: return
        val previousSpeed = lastSpeed
        val deltaMs = if (lastSampleAt == 0L) 0L else (now - lastSampleAt).coerceAtMost(1000L)
        lastSampleAt = now
        lastSpeed = speed

        // Average the endpoints over the interval for a trapezoidal distance step.
        val stepMetres = (speed + previousSpeed) / 2f / 3.6f * (deltaMs / 1000f)

        if (speed < 1f) {
            armed = true
            if (running) finishRun()
        }

        if (armed && !running && speed >= 1f) {
            running = true
            armed = false
            runStartMs = now
            runDistanceM = 0f
            t60 = null
            t100 = null
            quarterDone = false
        }

        if (running) {
            runDistanceM += stepMetres
            val elapsed = (now - runStartMs) / 1000f

            if (t60 == null && speed >= 60f) t60 = elapsed
            if (t100 == null && speed >= 100f) t100 = elapsed

            // Keep the best quarter mile, as with the other benchmarks, not just the first.
            if (!quarterDone && runDistanceM >= QUARTER_MILE_METRES) {
                quarterDone = true
                val best = results.quarterMileSeconds
                if (best == null || elapsed < best) {
                    results = results.copy(quarterMileSeconds = elapsed, quarterMileTrapKmh = speed)
                }
            }
            results = results.copy(running = true, currentRunSeconds = elapsed)
            publishBest()

            // A run that stops gaining speed has ended in practice.
            if (elapsed > MAX_RUN_SECONDS) finishRun()
        }

        trackBraking(speed, previousSpeed, stepMetres, now)
    }

    /** Measures the distance taken to stop from 100 km/h. */
    private fun trackBraking(speed: Float, previousSpeed: Float, stepMetres: Float, now: Long) {
        if (!braking && previousSpeed >= 100f && speed < 100f) {
            braking = true
            brakingFromMs = now
            brakingDistanceM = 0f
        }
        if (braking) {
            brakingDistanceM += stepMetres
            if (speed < 1f) {
                braking = false
                val best = results.hundredToZeroMetres
                if (best == null || brakingDistanceM < best) {
                    results = results.copy(hundredToZeroMetres = brakingDistanceM)
                }
            } else if (now - brakingFromMs > BRAKING_TIMEOUT_MS || speed > 100f) {
                braking = false
            }
        }
    }

    private fun finishRun() {
        running = false
        publishBest()
        results = results.copy(running = false, currentRunSeconds = 0f)
    }

    /** Keeps the quickest time seen so far for each benchmark. */
    private fun publishBest() {
        val best60 = minOfNullable(results.zeroTo60Kmh, t60)
        val best100 = minOfNullable(results.zeroTo100Kmh, t100)
        val span = if (t60 != null && t100 != null) t100!! - t60!! else null
        results = results.copy(
            zeroTo60Kmh = best60,
            zeroTo100Kmh = best100,
            sixtyTo100Kmh = minOfNullable(results.sixtyTo100Kmh, span)
        )
    }

    private fun minOfNullable(a: Float?, b: Float?): Float? =
        when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }

    private companion object {
        const val QUARTER_MILE_METRES = 402.336f
        const val MAX_RUN_SECONDS = 60f
        const val BRAKING_TIMEOUT_MS = 30_000L
    }
}
