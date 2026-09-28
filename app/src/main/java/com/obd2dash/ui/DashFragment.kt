package com.obd2dash.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.core.Metrics
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.databinding.FragmentDashBinding
import com.obd2dash.obd.Pids
import com.obd2dash.ui.view.TileView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Primary driving view: two gauges plus a reflowing grid of readouts. */
class DashFragment : Fragment() {

    private var binding: FragmentDashBinding? = null
    private lateinit var prefs: Prefs
    private val tiles = ArrayList<TileView>()

    /** A tile's caption and the function that turns current data into its text. */
    private class Spec(
        val label: String,
        val render: (Map<Int, Float>, ObdRepository.Derived, Boolean) -> Pair<String?, String>
    )

    private val specs = listOf(
        Spec("COOLANT") { r, _, imp ->
            Format.temperature(r[Pids.COOLANT], imp) to Format.temperatureUnit(imp)
        },
        Spec("INTAKE AIR") { r, _, imp ->
            Format.temperature(r[Pids.INTAKE_TEMP], imp) to Format.temperatureUnit(imp)
        },
        Spec("THROTTLE") { r, _, _ -> Format.num(r[Pids.THROTTLE], 0) to "%" },
        Spec("ENGINE LOAD") { r, _, _ -> Format.num(r[Pids.LOAD], 0) to "%" },
        Spec("BOOST") { _, d, imp -> Format.pressure(d.boostKpa, imp) to Format.pressureUnit(imp) },
        Spec("FUEL LEVEL") { r, _, _ -> Format.num(r[Pids.FUEL_LEVEL], 0) to "%" },
        Spec("CONSUMPTION") { _, d, imp ->
            Format.consumption(d.consumptionL100, imp) to Format.consumptionUnit(imp)
        },
        Spec("FUEL RATE") { _, d, imp ->
            (if (imp) Format.num(d.fuelRateLh?.times(0.219969f), 2) else Format.num(d.fuelRateLh, 2)) to
                    (if (imp) "gal/h" else "L/h")
        },
        Spec("POWER") { _, d, imp -> Format.power(d.powerKw, imp) to Format.powerUnit(imp) },
        Spec("TORQUE") { _, d, _ -> Format.num(d.torqueNm, 0) to "Nm" },
        Spec("GEAR") { _, d, _ -> (d.gear?.toString() ?: "--") to "" },
        Spec("BATTERY") { r, _, _ -> Format.num(r[Pids.MODULE_VOLTAGE], 1) to "V" }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentDashBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        prefs = Prefs(requireContext())

        b.gaugeRpm.redline = prefs.redlineRpm.toFloat()
        if (prefs.imperialUnits) {
            b.gaugeSpeed.unit = "mph"
            b.gaugeSpeed.maxValue = 140f
        }

        buildTiles(b.tileGrid)
        observe()
    }

    private fun buildTiles(grid: GridLayout) {
        grid.removeAllViews()
        tiles.clear()
        val columns = grid.columnCount
        val margin = (4 * resources.displayMetrics.density).toInt()

        specs.forEachIndexed { index, spec ->
            val tile = TileView(requireContext())
            tile.setLabel(spec.label)
            tile.setValue(null)
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(index % columns, 1f)
                rowSpec = GridLayout.spec(index / columns, 1f)
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(tile, params)
            tiles.add(tile)
        }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ObdRepository.readings, ObdRepository.derived) { r, d -> r to d }
                    .collect { (readings, derived) -> render(readings, derived) }
            }
        }
    }

    private fun render(readings: Map<Int, Float>, derived: ObdRepository.Derived) {
        val b = binding ?: return
        val imperial = prefs.imperialUnits

        b.gaugeRpm.setValue(readings[Pids.RPM])
        val speed = readings[Pids.SPEED]
        b.gaugeSpeed.setValue(
            if (speed == null) null else if (imperial) Metrics.kmhToMph(speed) else speed
        )

        specs.forEachIndexed { index, spec ->
            val (value, unit) = spec.render(readings, derived, imperial)
            val tile = tiles.getOrNull(index) ?: return@forEachIndexed
            tile.setValue(if (value == "--") null else value, unit, warn = isWarning(spec.label, readings))
        }
    }

    /** Highlights the handful of readings worth noticing at a glance. */
    private fun isWarning(label: String, readings: Map<Int, Float>): Boolean = when (label) {
        "COOLANT" -> (readings[Pids.COOLANT] ?: 0f) > 105f
        "FUEL LEVEL" -> (readings[Pids.FUEL_LEVEL] ?: 100f) < 12f
        "BATTERY" -> (readings[Pids.MODULE_VOLTAGE] ?: 13f) < 11.8f
        else -> false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        tiles.clear()
    }
}
