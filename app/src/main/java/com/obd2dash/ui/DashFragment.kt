package com.obd2dash.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.R
import com.obd2dash.core.Metrics
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.core.Settings
import com.obd2dash.core.TripStats
import com.obd2dash.databinding.FragmentDashBinding
import com.obd2dash.obd.Pids
import com.obd2dash.ui.view.TileView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Primary driving view: two gauges, a large gear indicator, and a grid of
 * tiles. Long-press a tile to choose what it shows.
 */
class DashFragment : LiveFragment() {

    private var binding: FragmentDashBinding? = null
    private lateinit var prefs: Prefs
    private var settings: Settings? = null
    private val tiles = ArrayList<TileView>()
    private var metrics: List<DashMetrics.Metric> = emptyList()
    private var tileKeys: List<String> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentDashBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ObdRepository.live, ObdRepository.trip) { live, trip -> live to trip }
                    .collect { (live, trip) -> if (isShowing) render(live, trip) }
            }
        }
    }

    /** Settings may have changed while another screen was open. */
    override fun onResume() {
        super.onResume()
        applySettings()
    }

    override fun onShown() = render(ObdRepository.live.value, ObdRepository.trip.value)

    private fun applySettings() {
        val b = binding ?: return
        val s = prefs.snapshot()
        settings = s
        b.gaugeRpm.redline = s.redlineRpm.toFloat()
        b.gaugeRpm.maxValue = if (s.redlineRpm > 7000) 10000f else 8000f
        b.gaugeSpeed.unit = if (s.imperial) "mph" else "km/h"
        b.gaugeSpeed.maxValue = if (s.imperial) 140f else 200f

        val saved = prefs.dashTiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val keys = if (saved.size == DashMetrics.TILE_COUNT) saved else DashMetrics.DEFAULT_KEYS
        if (keys != tileKeys) {
            tileKeys = keys
            buildTiles(b.tileGrid)
        }
        onShown()
    }

    private fun buildTiles(grid: GridLayout) {
        grid.removeAllViews()
        tiles.clear()
        metrics = tileKeys.map { DashMetrics.resolve(it) ?: DashMetrics.resolve(DashMetrics.DEFAULT_KEYS[0])!! }
        val columns = grid.columnCount
        val margin = (4 * resources.displayMetrics.density).toInt()

        metrics.forEachIndexed { index, metric ->
            val tile = TileView(requireContext())
            tile.setLabel(metric.label)
            tile.setValue(null)
            tile.setOnLongClickListener {
                pickMetric(index)
                true
            }
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

    private fun pickMetric(index: Int) {
        val options = DashMetrics.available(ObdRepository.supportedPids.value)
        AlertDialog.Builder(requireContext())
            .setTitle("Show in this tile")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val keys = tileKeys.toMutableList()
                keys[index] = options[which].key
                prefs.dashTiles = keys.joinToString(",")
                applySettings()
            }
            .setNeutralButton("Reset all tiles") { _, _ ->
                prefs.dashTiles = ""
                applySettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render(live: ObdRepository.Live, trip: TripStats) {
        val b = binding ?: return
        val s = settings ?: return

        b.gaugeRpm.setValue(live.readings[Pids.RPM])
        val speed = live.readings[Pids.SPEED]
        b.gaugeSpeed.setValue(if (speed == null) null else if (s.imperial) Metrics.kmhToMph(speed) else speed)

        b.gearValue.text = live.gear.label
        b.gearValue.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (live.gear.gear) {
                    null -> R.color.text_secondary
                    0 -> R.color.amber
                    else -> R.color.text_primary
                }
            )
        )
        b.shiftHint.visibility = if (live.gear.shiftUp) View.VISIBLE else View.INVISIBLE

        val inputs = DashMetrics.Inputs(live, trip, s)
        metrics.forEachIndexed { index, metric ->
            val (value, unit) = metric.render(inputs)
            tiles.getOrNull(index)?.setValue(value, unit, metric.warn(inputs))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        tiles.clear()
        tileKeys = emptyList()
    }
}
