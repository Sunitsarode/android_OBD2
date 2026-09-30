package com.obd2dash.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** What the gear indicator shows. [gear] is 0 in neutral and null when unknown. */
data class GearReading(val gear: Int?, val label: String, val shiftUp: Boolean) {
    companion object {
        val UNKNOWN = GearReading(null, "-", false)
        val NEUTRAL = GearReading(0, "N", false)
    }
}

/**
 * Infers the engaged gear from road speed and engine speed.
 *
 * In gear, km/h per rpm is fixed by the gearbox, so each gear shows up as a
 * cluster of ratios. Clusters are learned while driving and saved, so after
 * the first drive a gear is recognised as soon as a shift settles.
 *
 * Two things break that relationship: the clutch or neutral, which lets the
 * engine fall to idle while the car keeps rolling, and the moment of a shift.
 * Both produce a ratio that is either higher than any gear allows or still
 * changing, and neither is ever learned as a gear.
 */
class GearEstimator(private val store: Store) {

    interface Store {
        fun load(): List<Float>
        fun save(ratios: List<Float>)
    }

    private class Cluster(var ratio: Float, var hits: Int, var lastSeen: Long)

    private var transmission = Prefs.Transmission.MANUAL
    private var gearCount = 5
    private var shiftUpRpm = 2500

    private val clusters = ArrayList<Cluster>()
    private val window = FloatArray(WINDOW)
    private var windowSize = 0
    private var windowNext = 0
    private var idleRpm = DEFAULT_IDLE_RPM
    private var held = GearReading.UNKNOWN
    private var heldAt = 0L
    private var dirty = false

    init {
        store.load().forEach { clusters += Cluster(it, PERSISTED_HITS, 0L) }
    }

    @Synchronized
    fun configure(transmission: Prefs.Transmission, gearCount: Int, shiftUpRpm: Int) {
        this.transmission = transmission
        this.gearCount = gearCount.coerceIn(3, 10)
        this.shiftUpRpm = shiftUpRpm
    }

    @Synchronized
    fun reset() {
        clusters.clear()
        clearWindow()
        held = GearReading.UNKNOWN
        dirty = false
        store.save(emptyList())
    }

    /** Saves learned gears if they changed. Cheap enough to call on a timer. */
    @Synchronized
    fun flush() {
        if (!dirty) return
        dirty = false
        store.save(confirmed().map { it.ratio })
    }

    @Synchronized
    fun update(speedKmh: Float?, rpm: Float?, throttleClosed: Boolean?, now: Long): GearReading {
        if (speedKmh == null || rpm == null) return GearReading.UNKNOWN
        if (rpm < ENGINE_RUNNING_RPM) {
            clearWindow()
            return GearReading.UNKNOWN
        }

        if (speedKmh < STOPPED_KMH) {
            clearWindow()
            if (throttleClosed != false && rpm in 500f..1500f) idleRpm += (rpm - idleRpm) * 0.05f
            // Stationary, a manual is almost always in neutral; an automatic sits in D.
            return if (transmission == Prefs.Transmission.MANUAL) remember(GearReading.NEUTRAL, now)
            else GearReading.UNKNOWN
        }
        if (transmission == Prefs.Transmission.CVT) return GearReading(null, "D", false)

        val ratio = speedKmh / rpm
        push(ratio)
        val closed = throttleClosed ?: true
        val atIdle = rpm < idleRpm + IDLE_BAND && closed
        val top = confirmed().lastOrNull()?.ratio

        // More road speed per rpm than any gear gives: the clutch is out of the chain.
        if (ratio > PLAUSIBLE_MAX_RATIO || (top != null && ratio > top * (1f + NEUTRAL_MARGIN))) {
            return remember(GearReading.NEUTRAL, now)
        }

        // The speed PID moves in 1 km/h steps, which is a big fraction at crawling pace.
        val quantisation = 1.2f / speedKmh
        val settled = isSteady(2, max(QUICK_TOLERANCE, quantisation))
        val learnable = isSteady(WINDOW, max(LEARN_TOLERANCE, quantisation))

        // Engine held at idle while the ratio drifts: coasting in neutral or clutch down.
        if (atIdle && !settled) return remember(GearReading.NEUTRAL, now)

        if (settled) {
            val match = nearestConfirmed(ratio, max(MATCH_TOLERANCE, quantisation))
            if (match != null) {
                if (learnable && !atIdle) refine(match, ratio, now)
                return remember(inGear(match, rpm), now)
            }
            if (learnable && !atIdle && speedKmh >= MIN_LEARN_KMH && rpm > idleRpm + DRIVE_MARGIN_RPM) {
                learn(ratio, now)
            }
        }
        // Mid-shift or clutch slipping: keep the last gear on screen for a moment.
        return if (now - heldAt <= HOLD_MS) held else GearReading.UNKNOWN
    }

    private fun remember(reading: GearReading, now: Long): GearReading {
        held = reading
        heldAt = now
        return reading
    }

    private fun inGear(match: Cluster, rpm: Float): GearReading {
        val number = numberOf(match)
        val shiftUp = transmission == Prefs.Transmission.MANUAL &&
                shiftUpRpm > 0 && number < gearCount && rpm > shiftUpRpm
        return GearReading(number, number.toString(), shiftUp)
    }

    /**
     * The gears: the [gearCount] best-supported clusters, lowest ratio first.
     * A stray cluster from a slipping clutch cannot displace a real gear, because
     * real gears keep collecting hits every drive and the stray one does not.
     */
    private fun confirmed(): List<Cluster> =
        clusters.filter { it.hits >= CONFIRM_HITS }
            .sortedByDescending { it.hits }
            .take(gearCount)
            .sortedBy { it.ratio }

    /**
     * Numbers a gear lowest-first. Until every gear has been driven in, a missing
     * gear shows up as a gap about twice the normal step, or as a lowest cluster
     * too tall to be first; either shifts the numbering up by one.
     */
    private fun numberOf(target: Cluster): Int {
        val list = confirmed()
        val complete = list.size >= gearCount
        var number = if (!complete && list.first().ratio > FIRST_GEAR_MAX_RATIO) 2 else 1
        for (i in list.indices) {
            if (i > 0) {
                val step = list[i].ratio / list[i - 1].ratio
                number += if (!complete && step > MISSING_GEAR_STEP) 2 else 1
            }
            if (list[i] === target) break
        }
        return number.coerceAtMost(gearCount)
    }

    private fun nearestConfirmed(ratio: Float, tolerance: Float): Cluster? =
        confirmed().minByOrNull { abs(it.ratio - ratio) }
            ?.takeIf { abs(it.ratio - ratio) / it.ratio <= tolerance }

    private fun refine(cluster: Cluster, ratio: Float, now: Long) {
        cluster.ratio += (ratio - cluster.ratio) * 0.05f
        cluster.hits = (cluster.hits + 1).coerceAtMost(MAX_HITS)
        cluster.lastSeen = now
        dirty = true
    }

    private fun learn(ratio: Float, now: Long) {
        val near = clusters.minByOrNull { abs(it.ratio - ratio) }
        if (near != null && abs(near.ratio - ratio) / near.ratio <= MERGE_TOLERANCE) {
            // Running mean, capped so a cluster keeps adapting slowly.
            near.ratio += (ratio - near.ratio) / (near.hits + 1).coerceAtMost(20)
            near.hits = (near.hits + 1).coerceAtMost(MAX_HITS)
            near.lastSeen = now
            dirty = true
            return
        }
        clusters += Cluster(ratio, 1, now)
        pruneCandidates()
    }

    /** Drops the least recently seen unconfirmed cluster once there are too many. */
    private fun pruneCandidates() {
        if (clusters.size <= gearCount + MAX_EXTRA_CANDIDATES) return
        val gears = confirmed().toSet()
        clusters.filter { it !in gears }.minByOrNull { it.lastSeen }?.let { clusters.remove(it) }
    }

    private fun isSteady(samples: Int, tolerance: Float): Boolean {
        if (windowSize < samples) return false
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (k in 0 until samples) {
            val v = window[(windowNext - 1 - k + WINDOW) % WINDOW]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return (hi - lo) / lo <= tolerance
    }

    private fun push(ratio: Float) {
        window[windowNext] = ratio
        windowNext = (windowNext + 1) % WINDOW
        if (windowSize < WINDOW) windowSize++
    }

    private fun clearWindow() {
        windowSize = 0
        windowNext = 0
    }

    private companion object {
        const val WINDOW = 3
        const val QUICK_TOLERANCE = 0.03f
        const val LEARN_TOLERANCE = 0.025f
        const val MATCH_TOLERANCE = 0.06f
        const val MERGE_TOLERANCE = 0.08f
        const val CONFIRM_HITS = 6
        const val PERSISTED_HITS = 12
        const val MAX_HITS = 200
        const val MAX_EXTRA_CANDIDATES = 8

        /** No gearbox reaches 65 km/h per 1000 rpm; beyond it the car must be coasting. */
        const val PLAUSIBLE_MAX_RATIO = 0.065f
        const val NEUTRAL_MARGIN = 0.12f

        /** Tallest plausible first gear: 12.5 km/h per 1000 rpm. */
        const val FIRST_GEAR_MAX_RATIO = 0.0125f
        const val MISSING_GEAR_STEP = 2.0f

        const val DEFAULT_IDLE_RPM = 850f
        const val IDLE_BAND = 180f
        const val DRIVE_MARGIN_RPM = 250f
        const val ENGINE_RUNNING_RPM = 300f
        const val STOPPED_KMH = 2.5f
        const val MIN_LEARN_KMH = 10f
        const val HOLD_MS = 1500L
    }
}

/** Keeps learned gear ratios in settings so they survive app restarts. */
class PrefsGearStore(private val prefs: Prefs) : GearEstimator.Store {

    override fun load(): List<Float> =
        prefs.learnedGearRatios.split(',').mapNotNull { it.trim().toFloatOrNull() }.filter { it > 0f }

    override fun save(ratios: List<Float>) {
        prefs.learnedGearRatios = ratios.joinToString(",") { String.format(Locale.US, "%.6f", it) }
    }
}
