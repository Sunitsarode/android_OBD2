package com.obd2dash.auto

import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.obd.Pids
import com.obd2dash.ui.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A list screen that redraws when [trigger] emits, at most every [refreshMs].
 * Android Auto throttles template updates, so faster refreshes would be dropped
 * and could count against the app.
 */
abstract class LiveListScreen(carContext: CarContext, trigger: Flow<Any?>, private val refreshMs: Long) : Screen(carContext) {

    private var lastRefresh = 0L
    protected val prefs = Prefs(carContext)

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                trigger.collect {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRefresh >= refreshMs) {
                        lastRefresh = now
                        invalidate()
                    }
                }
            }
        }
    }

    /** How many rows the car allows right now; fewer while driving. */
    protected fun rowLimit(): Int = try {
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else {
            DEFAULT_ROW_LIMIT
        }
    } catch (_: Exception) {
        DEFAULT_ROW_LIMIT
    }

    protected fun row(title: String, text: String): Row = Row.Builder().setTitle(title).addText(text).build()

    protected fun list(title: String, rows: List<Row>, empty: String, strip: ActionStrip? = null): Template {
        val items = ItemList.Builder().setNoItemsMessage(empty)
        rows.take(rowLimit()).forEach { items.addItem(it) }
        val builder = ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
        if (strip != null) builder.setActionStrip(strip)
        return builder.build()
    }

    private companion object {
        const val DEFAULT_ROW_LIMIT = 6
    }
}

/** Live sensor values, most useful first, since the car may only show six rows. */
class SensorsScreen(carContext: CarContext) : LiveListScreen(carContext, ObdRepository.live, 2000L) {

    override fun onGetTemplate(): Template {
        val readings = ObdRepository.live.value.readings
        val supported = ObdRepository.supportedPids.value
        val imperial = prefs.imperialUnits
        val ordered = PRIORITY.mapNotNull { Pids.BY_ID[it] }.filter { it.id in supported } +
                Pids.ALL.filter { it.id in supported && it.id !in PRIORITY }
        val rows = ordered.map { pid ->
            val (value, unit) = Format.pidValue(pid, readings[pid.id], imperial)
            row(pid.name, if (unit.isEmpty()) value else value + " " + unit)
        }
        return list("Sensors", rows, "Not connected")
    }

    private companion object {
        val PRIORITY = listOf(
            Pids.COOLANT, Pids.OIL_TEMP, Pids.INTAKE_TEMP, Pids.LOAD, Pids.THROTTLE,
            Pids.FUEL_LEVEL, Pids.MODULE_VOLTAGE, Pids.MAF, Pids.MAP, 0x0E, 0x06, 0x07,
            Pids.AMBIENT, Pids.FUEL_SYSTEM, Pids.ODOMETER
        )
    }
}

class TripScreen(carContext: CarContext) : LiveListScreen(carContext, ObdRepository.trip, 3000L) {

    override fun onGetTemplate(): Template {
        val t = ObdRepository.trip.value
        val imp = prefs.imperialUnits
        val dist = Format.distanceUnit(imp)
        val rows = listOf(
            row("Distance", Format.distance(t.distanceKm, imp) + " " + dist + " in " + Format.duration(t.elapsedSeconds) +
                    " (idle " + Format.duration(t.idleSeconds) + ")"),
            row("Fuel", Format.volume(t.fuelUsedLitres, imp) + " " + Format.volumeUnit(imp) + " used, " +
                    Format.consumption(t.avgConsumptionL100, imp) + " " + Format.consumptionUnit(imp) + " average"),
            row("Cost", if (t.cost == null) "Set the fuel price in the phone app's Settings"
                else Format.money(t.cost) + " (" + Format.money(t.costPerKm) + " per km)"),
            row("Driving", "Eco score " + (t.ecoScore?.toString() ?: "--") + ", " + t.harshAccelerations +
                    " harsh accel, " + t.harshBrakings + " harsh brake"),
            row("Speed", "Average " + Format.speed(t.avgSpeedKmh, imp) + ", max " + Format.speed(t.maxSpeedKmh, imp) +
                    " " + Format.speedUnit(imp)),
            row("Range", Format.distance(t.rangeKm, imp) + " " + dist)
        )
        return list("Trip", rows, "No trip yet")
    }
}

/** Read-only on purpose: clearing codes stays on the phone, behind its confirmation. */
class CodesScreen(carContext: CarContext) : LiveListScreen(carContext, ObdRepository.dtcs, 1000L) {

    override fun onGetTemplate(): Template {
        val rows = ArrayList<Row>()
        ObdRepository.milStatus.value?.let {
            rows += row(
                if (it.milOn) "Check engine light ON" else "Check engine light off",
                it.dtcCount.toString() + " stored code(s)"
            )
        }
        val codes = ObdRepository.dtcs.value
        if (codes.isEmpty()) rows += row("No trouble codes", "Nothing stored, pending, or permanent")
        codes.forEach { rows += row(it.code + "  " + it.kind.label, it.description) }

        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Read again")
                    .setOnClickListener {
                        ObdRepository.submit(ObdRepository.Task.ReadDtcs)
                        CarToast.makeText(carContext, "Reading codes...", CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .build()
        return list("Trouble codes", rows, "Not connected", strip)
    }
}
