package com.obd2dash.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.R
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.PerfResults
import com.obd2dash.core.Prefs
import com.obd2dash.core.TripHistory
import com.obd2dash.core.TripRecord
import com.obd2dash.core.TripStats
import com.obd2dash.databinding.FragmentTripBinding
import com.obd2dash.ui.view.TileView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Trip totals, running costs, driving style, acceleration benchmarks, and past trips. */
class TripFragment : LiveFragment() {

    private var binding: FragmentTripBinding? = null
    private lateinit var prefs: Prefs
    private lateinit var history: TripHistory
    private val tripTiles = ArrayList<TileView>()
    private val perfTiles = ArrayList<TileView>()
    private val dateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

    private val tripLabels = listOf(
        "DISTANCE", "DURATION", "MOVING", "IDLE", "AVG SPEED", "MAX SPEED",
        "FUEL USED", "AVG ECONOMY", "RANGE", "TRIP COST", "COST / KM", "ECO SCORE",
        "HARSH ACCEL", "HARSH BRAKE", "HIGH RPM TIME", "MAX RPM", "MAX COOLANT", "MAX POWER"
    )

    private val perfLabels = listOf(
        "0-60 KM/H", "0-100 KM/H", "60-100 KM/H", "1/4 MILE", "TRAP SPEED", "100-0 BRAKING"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentTripBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        prefs = Prefs(requireContext())
        history = TripHistory(requireContext())

        fill(b.tripGrid, tripLabels, tripTiles)
        fill(b.perfGrid, perfLabels, perfTiles)

        b.resetTrip.setOnClickListener {
            if (ObdRepository.connectionState.value != ObdRepository.ConnState.CONNECTED) {
                Toast.makeText(requireContext(), "Connect to the car first", Toast.LENGTH_SHORT).show()
            } else {
                ObdRepository.submit(ObdRepository.Task.ResetTrip)
            }
        }
        b.resetPerf.setOnClickListener { ObdRepository.submit(ObdRepository.Task.ResetPerf) }
        b.clearHistory.setOnClickListener { confirmClearHistory() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ObdRepository.trip.collectLatest { if (isShowing) renderTrip(it) } }
                launch { ObdRepository.perf.collectLatest { if (isShowing) renderPerf(it) } }
                launch { ObdRepository.tripHistoryVersion.collectLatest { loadHistory() } }
            }
        }
    }

    override fun onShown() {
        renderTrip(ObdRepository.trip.value)
        renderPerf(ObdRepository.perf.value)
    }

    private fun fill(grid: GridLayout, labels: List<String>, into: MutableList<TileView>) {
        grid.removeAllViews()
        into.clear()
        val columns = grid.columnCount
        val margin = (4 * resources.displayMetrics.density).toInt()
        val tileHeight = (74 * resources.displayMetrics.density).toInt()
        labels.forEachIndexed { index, label ->
            val tile = TileView(requireContext()).apply {
                setLabel(label)
                setValue(null)
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = tileHeight
                columnSpec = GridLayout.spec(index % columns, 1f)
                rowSpec = GridLayout.spec(index / columns)
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(tile, params)
            into.add(tile)
        }
    }

    private fun renderTrip(t: TripStats) {
        val imp = prefs.imperialUnits
        val values: List<Pair<String, String>> = listOf(
            Format.distance(t.distanceKm, imp) to Format.distanceUnit(imp),
            Format.duration(t.elapsedSeconds) to "",
            Format.duration(t.movingSeconds) to "",
            Format.duration(t.idleSeconds) to "",
            Format.speed(t.avgSpeedKmh, imp) to Format.speedUnit(imp),
            Format.speed(t.maxSpeedKmh, imp) to Format.speedUnit(imp),
            Format.volume(t.fuelUsedLitres, imp) to Format.volumeUnit(imp),
            Format.consumption(t.avgConsumptionL100, imp) to Format.consumptionUnit(imp),
            Format.distance(t.rangeKm, imp) to Format.distanceUnit(imp),
            Format.money(t.cost) to "",
            Format.money(t.costPerKm) to "",
            (t.ecoScore?.toString() ?: "--") to "/100",
            t.harshAccelerations.toString() to "",
            t.harshBrakings.toString() to "",
            Format.duration(t.highRpmSeconds) to "",
            Format.num(t.maxRpm, 0) to "rpm",
            Format.temperature(t.maxCoolantC, imp) to Format.temperatureUnit(imp),
            Format.power(t.maxPowerKw, imp) to Format.powerUnit(imp)
        )
        values.forEachIndexed { index, (value, unit) ->
            tripTiles.getOrNull(index)?.setValue(if (value == "--") null else value, unit)
        }
    }

    private fun renderPerf(r: PerfResults) {
        val values = listOf(
            Format.num(r.zeroTo60Kmh, 2) to "s",
            Format.num(r.zeroTo100Kmh, 2) to "s",
            Format.num(r.sixtyTo100Kmh, 2) to "s",
            Format.num(r.quarterMileSeconds, 2) to "s",
            Format.num(r.quarterMileTrapKmh, 0) to "km/h",
            Format.num(r.hundredToZeroMetres, 1) to "m"
        )
        values.forEachIndexed { index, (value, unit) ->
            perfTiles.getOrNull(index)?.setValue(if (value == "--") null else value, unit)
        }
        binding?.perfHint?.text =
            if (r.running) "Run in progress: %.1f s".format(r.currentRunSeconds)
            else "Timing arms itself at a standstill and starts when the car moves. Private roads only."
    }

    private suspend fun loadHistory() {
        val trips = withContext(Dispatchers.IO) { history.load() }
        renderHistory(trips)
    }

    private fun renderHistory(trips: List<TripRecord>) {
        val container = binding?.historyContainer ?: return
        container.removeAllViews()
        val context = requireContext()
        if (trips.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "Trips are saved when you tap Save and new trip, disconnect, or park for 5+ minutes."
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
            })
            return
        }
        val imp = prefs.imperialUnits
        val pad = (10 * resources.displayMetrics.density).toInt()
        val gap = (6 * resources.displayMetrics.density).toInt()
        for (t in trips.take(SHOWN_TRIPS)) {
            val line1 = dateFormat.format(Date(t.startedAt)) + "   " +
                    Format.distance(t.distanceKm, imp) + " " + Format.distanceUnit(imp) + "   " +
                    Format.duration(t.durationSeconds)
            val line2 = listOfNotNull(
                t.avgConsumptionL100?.let { Format.consumption(it, imp) + " " + Format.consumptionUnit(imp) },
                t.cost?.let { Format.money(it) },
                t.ecoScore?.let { "eco " + it },
                "max " + Format.speed(t.maxSpeedKmh, imp) + " " + Format.speedUnit(imp)
            ).joinToString("   ")
            container.addView(TextView(context).apply {
                text = line1 + "\n" + line2
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 13f
                setBackgroundResource(R.drawable.bg_tile)
                setPadding(pad, pad, pad, pad)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = gap }
            })
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear trip history?")
            .setMessage("Saved trips are deleted. The current trip is not affected.")
            .setPositiveButton("Clear") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { history.clear() }
                    renderHistory(emptyList())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        tripTiles.clear()
        perfTiles.clear()
    }

    private companion object {
        const val SHOWN_TRIPS = 20
    }
}
