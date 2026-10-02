package com.obd2dash.core

import com.obd2dash.obd.Pids
import java.util.EnumMap
import java.util.EnumSet

/**
 * Turns live readings into driver alerts.
 *
 * Each alert latches with hysteresis, so a value hovering at a threshold does
 * not flap on and off. An alert sounds when it switches on and, for the ones
 * that matter while it lasts, again at its repeat interval.
 */
class AlertMonitor {

    enum class Alert(val title: String, val speech: String, val critical: Boolean, val repeatMs: Long) {
        OVERHEAT("Engine overheating", "Warning. Engine temperature high.", true, 60_000L),
        CHECK_ENGINE("Check engine light on", "Check engine light is on.", true, 0L),
        LOW_VOLTAGE("Battery voltage low", "Battery voltage low.", true, 300_000L),
        OVERSPEED("Over speed limit", "Speed limit.", false, 15_000L),
        OVER_REV("High RPM", "Engine speed high.", false, 10_000L),
        LOW_FUEL("Low fuel", "Fuel level low.", false, 600_000L),
        LOW_CNG("Low CNG (estimated)", "C N G running low.", false, 600_000L),
        SWITCHED_TO_PETROL("Switched to petrol", "Switched to petrol.", false, 0L),
        CYLINDER_TEST("CNG cylinder test due", "C N G cylinder test is due.", false, 0L)
    }

    class Result(val active: Set<Alert>, val fired: List<Alert>, val changed: Boolean)

    private val active = EnumSet.noneOf(Alert::class.java)
    private val lastFired = EnumMap<Alert, Long>(Alert::class.java)
    private var lowVoltageSince = 0L
    private var milBaselineKnown = false
    private var lastFuel: Fuel? = null
    private var switchedToPetrolAt = 0L

    fun reset() {
        active.clear()
        lastFired.clear()
        lowVoltageSince = 0L
        milBaselineKnown = false
        lastFuel = null
        switchedToPetrolAt = 0L
    }

    fun evaluate(live: ObdRepository.Live, milOn: Boolean?, trip: TripStats, s: Settings, now: Long): Result {
        val before = EnumSet.copyOf(active)
        val r = live.readings
        val speed = r[Pids.SPEED]
        val rpm = r[Pids.RPM]
        val coolant = r[Pids.COOLANT]
        val fuelLevel = r[Pids.FUEL_LEVEL]
        val volts = live.batteryVolts
        val engineRunning = (rpm ?: 0f) > 400f

        latch(Alert.OVERSPEED, s.speedLimitKmh > 0 && speed != null,
            on = (speed ?: 0f) > s.speedLimitKmh, off = (speed ?: 0f) < s.speedLimitKmh - 3)
        latch(Alert.OVERHEAT, coolant != null,
            on = (coolant ?: 0f) >= s.overheatC, off = (coolant ?: 0f) < s.overheatC - 3)
        latch(Alert.OVER_REV, s.redlineRpm > 0 && rpm != null,
            on = (rpm ?: 0f) >= s.redlineRpm, off = (rpm ?: 0f) < s.redlineRpm - 250)
        latch(Alert.LOW_FUEL, s.lowFuelPercent > 0 && fuelLevel != null,
            on = (fuelLevel ?: 100f) < s.lowFuelPercent, off = (fuelLevel ?: 0f) > s.lowFuelPercent + 3)

        // Voltage dips while cranking and sags briefly under load, so it must stay low
        // for a while before it counts. With the engine off, resting voltage is normal.
        val lowNow = engineRunning && volts != null && volts < LOW_VOLTS
        lowVoltageSince = if (lowNow) (if (lowVoltageSince == 0L) now else lowVoltageSince) else 0L
        latch(Alert.LOW_VOLTAGE, engineRunning && volts != null,
            on = lowNow && now - lowVoltageSince >= LOW_VOLTAGE_HOLD_MS, off = (volts ?: 0f) > RECOVERED_VOLTS)

        // CNG level is an estimate counted down from the last fill-up.
        val cngLeft = trip.cngRemainingKg
        latch(Alert.LOW_CNG, s.fuelSystem.usesCng && s.lowCngKg > 0f && cngLeft != null,
            on = (cngLeft ?: 99f) < s.lowCngKg, off = (cngLeft ?: 0f) > s.lowCngKg + 0.5f)

        // A bi-fuel car drops to petrol by itself when the cylinder runs dry, which is
        // easy to miss and costs more per km. Only automatic detection can see it happen;
        // in manual mode the driver made the switch.
        val fuelInUse = live.derived.fuel
        if (lastFuel == Fuel.CNG && fuelInUse == Fuel.PETROL && live.derived.fuelFromEcu && (speed ?: 0f) > 5f) {
            switchedToPetrolAt = now
        }
        lastFuel = fuelInUse
        val recentlySwitched = switchedToPetrolAt != 0L && now - switchedToPetrolAt < SWITCH_NOTICE_MS
        latch(Alert.SWITCHED_TO_PETROL, s.fuelSystem.usesCng, on = recentlySwitched, off = !recentlySwitched)

        // Indian rules require a hydrostatic test every three years; warn a month ahead.
        val testDue = s.cylinderTestDueMs > 0L && now >= s.cylinderTestDueMs - TEST_WARNING_MS
        latch(Alert.CYLINDER_TEST, s.fuelSystem.usesCng && s.cylinderTestDueMs > 0L, on = testDue, off = !testDue)

        // A lamp already on at connect is shown, but only a lamp that comes on mid-drive sounds.
        if (milOn != null) {
            if (milOn) active.add(Alert.CHECK_ENGINE) else active.remove(Alert.CHECK_ENGINE)
            if (!milBaselineKnown) {
                milBaselineKnown = true
                if (milOn) lastFired[Alert.CHECK_ENGINE] = now
            }
        }

        val fired = ArrayList<Alert>()
        for (alert in active) {
            val last = lastFired[alert]
            val risingEdge = alert !in before && (last == null || now - last > REFIRE_GUARD_MS)
            val repeatDue = last != null && alert.repeatMs > 0 && now - last >= alert.repeatMs
            if (risingEdge || repeatDue) {
                fired += alert
                lastFired[alert] = now
            }
        }
        for (alert in Alert.values()) if (alert !in active && alert != Alert.CHECK_ENGINE) lastFired.remove(alert)

        return Result(EnumSet.copyOf(active), fired, before != active)
    }

    private fun latch(alert: Alert, enabled: Boolean, on: Boolean, off: Boolean) {
        if (!enabled) {
            active.remove(alert)
            return
        }
        if (alert in active) {
            if (off) active.remove(alert)
        } else if (on) {
            active.add(alert)
        }
    }

    private companion object {
        const val LOW_VOLTS = 11.8f
        const val RECOVERED_VOLTS = 12.3f
        const val LOW_VOLTAGE_HOLD_MS = 10_000L
        const val REFIRE_GUARD_MS = 5_000L
        const val SWITCH_NOTICE_MS = 15_000L
        const val TEST_WARNING_MS = 30L * 24 * 60 * 60 * 1000
    }
}
