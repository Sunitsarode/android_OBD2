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
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.PerfResults
import com.obd2dash.core.Prefs
import com.obd2dash.core.TripStats
import com.obd2dash.databinding.FragmentTripBinding
import com.obd2dash.ui.view.TileView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Trip totals and acceleration benchmarks. */
class TripFragment : Fragment() {

    private var binding: FragmentTripBinding? = null
    private lateinit var prefs: Prefs
    private val tripTiles = ArrayList<TileView>()
    private val perfTiles = ArrayList<TileView>()

    private val tripLabels = listOf(
        "DISTANCE", "DURATION", "MOVING", "IDLE", "AVG SPEED", "MAX SPEED",
        "FUEL USED", "AVG ECONOMY", "RANGE", "MAX RPM", "MAX COOLANT", "MAX POWER"
    )

    private val perfLabels = listOf(
        "0-60 KM/H", "0-100 KM/H", "60-100 KM/H", "1/4 MILE", "TRAP SPEED", "100-0 BRAKING"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentTripBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        prefs = Prefs(requireContext())

        fill(b.tripGrid, tripLabels, tripTiles)
        fill(b.perfGrid, perfLabels, perfTiles)

        b.resetTrip.setOnClickListener {
            ObdRepository.submit(ObdRepository.Task.ResetTrip)
        }
        b.resetPerf.setOnClickListener {
            ObdRepository.submit(ObdRepository.Task.ResetPerf)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ObdRepository.trip.collectLatest { renderTrip(it) } }
                launch { ObdRepository.perf.collectLatest { renderPerf(it) } }
            }
        }
    }

    private fun fill(grid: GridLayout, labels: List<String>, into: MutableList<TileView>) {
        grid.removeAllViews()
        into.clear()
        val columns = grid.columnCount
        val margin = (4 * resources.displayMetrics.density).toInt()
        val height = (78 * resources.displayMetrics.density).toInt()

        labels.forEachIndexed { index, label ->
            val tile = TileView(requireContext()).apply {
                setLabel(label)
                setValue(null)
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                this.height = height
                columnSpec = GridLayout.spec(index % columns, 1f)
                rowSpec = GridLayout.spec(index / columns)
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(tile, params)
            into.add(tile)
        }
    }

    private fun renderTrip(stats: TripStats) {
        val imperial = prefs.imperialUnits
        val values = listOf(
            Format.distance(stats.distanceKm, imperial) to Format.distanceUnit(imperial),
            Format.duration(stats.elapsedSeconds) to "",
            Format.duration(stats.movingSeconds) to "",
            Format.duration(stats.idleSeconds) to "",
            Format.speed(stats.avgSpeedKmh, imperial) to Format.speedUnit(imperial),
            Format.speed(stats.maxSpeedKmh, imperial) to Format.speedUnit(imperial),
            Format.volume(stats.fuelUsedLitres, imperial) to Format.volumeUnit(imperial),
            Format.consumption(stats.avgConsumptionL100, imperial) to Format.consumptionUnit(imperial),
            Format.distance(stats.rangeKm, imperial) to Format.distanceUnit(imperial),
            Format.num(stats.maxRpm, 0) to "rpm",
            Format.temperature(stats.maxCoolantC, imperial) to Format.temperatureUnit(imperial),
            Format.power(stats.maxPowerKw, imperial) to Format.powerUnit(imperial)
        )
        values.forEachIndexed { index, (value, unit) ->
            tripTiles.getOrNull(index)?.setValue(if (value == "--") null else value, unit)
        }
    }

    private fun renderPerf(results: PerfResults) {
        val values = listOf(
            Format.num(results.zeroTo60Kmh, 2) to "s",
            Format.num(results.zeroTo100Kmh, 2) to "s",
            Format.num(results.sixtyTo100Kmh, 2) to "s",
            Format.num(results.quarterMileSeconds, 2) to "s",
            Format.num(results.quarterMileTrapKmh, 0) to "km/h",
            Format.num(results.hundredToZeroMetres, 1) to "m"
        )
        values.forEachIndexed { index, (value, unit) ->
            perfTiles.getOrNull(index)?.setValue(if (value == "--") null else value, unit)
        }
        binding?.perfHint?.text = if (results.running) {
            "Run in progress: %.1f s".format(results.currentRunSeconds)
        } else {
            "Timing arms itself at a standstill and starts when the car moves. Use private roads only."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        tripTiles.clear()
        perfTiles.clear()
    }
}
