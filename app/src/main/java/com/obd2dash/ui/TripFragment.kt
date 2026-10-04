package com.obd2dash.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.R
import com.obd2dash.core.CngTotals
import com.obd2dash.core.CngTracker
import com.obd2dash.core.Fuel
import com.obd2dash.core.FuelSystem
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.PerfResults
import com.obd2dash.core.Prefs
import com.obd2dash.core.Settings
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

    /** A trip tile: its caption and how to read its value from the trip. */
    private class TripTile(val label: String, val value: (TripStats, Settings) -> Pair<String, String>)

    private var tripSpecs: List<TripTile> = emptyList()
    private var builtFor: FuelSystem? = null
    private var settings: Settings? = null
    private val cngTiles = ArrayList<TileView>()

    private val cngLabels = listOf(
        "TOTAL CNG USED", "TOTAL CNG DISTANCE", "AVG CNG MILEAGE",
        "TOTAL CNG RUN TIME", "FILL-UP MILEAGE", "SINCE FILL: TIME",
        "SINCE FILL: DISTANCE", "SINCE FILL: CNG USED", "SINCE FILL: MILEAGE"
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

        fill(b.perfGrid, perfLabels, perfTiles)
        b.fillCng.setOnClickListener { logCngFill() }
        fill(b.cngGrid, cngLabels, cngTiles)
        b.resetCngTotals.setOnClickListener { confirmResetCngTotals() }

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

    /** The tile set depends on the fuel system, which may have changed in Settings. */
    override fun onResume() {
        super.onResume()
        val b = binding ?: return
        val s = prefs.snapshot()
        settings = s
        if (s.fuelSystem != builtFor) {
            builtFor = s.fuelSystem
            tripSpecs = tripTiles(s)
            fill(b.tripGrid, tripSpecs.map { it.label }, tripTiles)
        }
        val cngVisibility = if (s.fuelSystem.usesCng) View.VISIBLE else View.GONE
        b.fillCng.visibility = cngVisibility
        b.cngTotalsHeader.visibility = cngVisibility
        b.cngGrid.visibility = cngVisibility
        onShown()
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

    private fun tripTiles(s: Settings): List<TripTile> {
        val system = s.fuelSystem
        val bi = system.isBiFuel
        val tiles = ArrayList<TripTile>()
        tiles += TripTile("DISTANCE") { t, st -> Format.distance(t.distanceKm, st.imperial) to Format.distanceUnit(st.imperial) }
        tiles += TripTile("DURATION") { t, _ -> Format.duration(t.elapsedSeconds) to "" }
        tiles += TripTile("MOVING") { t, _ -> Format.duration(t.movingSeconds) to "" }
        tiles += TripTile("IDLE") { t, _ -> Format.duration(t.idleSeconds) to "" }
        tiles += TripTile("AVG SPEED") { t, st -> Format.speed(t.avgSpeedKmh, st.imperial) to Format.speedUnit(st.imperial) }
        tiles += TripTile("MAX SPEED") { t, st -> Format.speed(t.maxSpeedKmh, st.imperial) to Format.speedUnit(st.imperial) }

        // A bi-fuel trip is really two trips: CNG and petrol are tracked separately.
        for (fuel in listOfNotNull(system.secondary, system.primary)) {
            val name = if (bi) fuel.label.uppercase() + " " else "FUEL "
            tiles += TripTile(name + "USED") { t, st -> Format.amount(t.use(fuel)?.amount ?: 0f, fuel, st.imperial) }
            tiles += TripTile(if (bi) name + "MILEAGE" else "MILEAGE") { t, st -> Format.economy(t.use(fuel)?.per100Km, fuel, st) }
            if (bi) {
                tiles += TripTile(name + "DISTANCE") { t, st ->
                    Format.distance(t.use(fuel)?.distanceKm ?: 0f, st.imperial) to Format.distanceUnit(st.imperial)
                }
                tiles += TripTile(name + "RUN TIME") { t, _ -> Format.runTime(t.use(fuel)?.runSeconds ?: 0L) to "" }
            }
        }
        if (system.usesCng) {
            tiles += TripTile("CNG LEFT (EST)") { t, _ -> Format.num(t.cngRemainingKg, 1) to "kg" }
            tiles += TripTile("CNG RANGE") { t, st -> Format.distance(t.cngRangeKm, st.imperial) to Format.distanceUnit(st.imperial) }
        }
        system.tankFuel?.let { tank ->
            tiles += TripTile(if (bi) tank.label.uppercase() + " RANGE" else "RANGE") { t, st ->
                Format.distance(t.rangeKm, st.imperial) to Format.distanceUnit(st.imperial)
            }
        }
        tiles += TripTile("TRIP COST") { t, _ -> Format.money(t.cost) to "" }
        tiles += TripTile("COST / KM") { t, _ -> Format.money(t.costPerKm) to "" }
        tiles += TripTile("ECO SCORE") { t, _ -> (t.ecoScore?.toString() ?: "--") to "/100" }
        tiles += TripTile("HARSH ACCEL") { t, _ -> t.harshAccelerations.toString() to "" }
        tiles += TripTile("HARSH BRAKE") { t, _ -> t.harshBrakings.toString() to "" }
        tiles += TripTile("HIGH RPM TIME") { t, _ -> Format.duration(t.highRpmSeconds) to "" }
        tiles += TripTile("MAX RPM") { t, _ -> Format.num(t.maxRpm, 0) to "rpm" }
        tiles += TripTile("MAX COOLANT") { t, st -> Format.temperature(t.maxCoolantC, st.imperial) to Format.temperatureUnit(st.imperial) }
        tiles += TripTile("MAX POWER") { t, st -> Format.power(t.maxPowerKw, st.imperial) to Format.powerUnit(st.imperial) }
        return tiles
    }

    private fun renderTrip(t: TripStats) {
        val s = settings ?: return
        tripSpecs.forEachIndexed { index, spec ->
            val (value, unit) = spec.value(t, s)
            tripTiles.getOrNull(index)?.setValue(if (value == "--") null else value, unit)
        }
        if (s.fuelSystem.usesCng) renderCngTotals(t.cng ?: CngTracker(prefs).totals(), s)
    }

    /** All-time CNG figures, plus the stretch since the last logged fill-up. */
    private fun renderCngTotals(c: CngTotals, s: Settings) {
        val imp = s.imperial
        val dist = Format.distanceUnit(imp)
        val values: List<Pair<String, String>> = listOf(
            Format.amount(c.usedKg, Fuel.CNG, imp),
            Format.distance(c.distanceKm, imp) to dist,
            Format.economy(c.kmPerKg?.let { 100f / it }, Fuel.CNG, s),
            Format.runTime(c.runSeconds) to "",
            Format.economy(c.lastFillKmPerKg?.let { 100f / it }, Fuel.CNG, s),
            Format.runTime(c.sinceFillSeconds) to "",
            Format.distance(c.sinceFillKm, imp) to dist,
            Format.amount(c.sinceFillKg, Fuel.CNG, imp),
            Format.economy(c.sinceFillKmPerKg?.let { 100f / it }, Fuel.CNG, s)
        )
        values.forEachIndexed { index, (value, unit) ->
            cngTiles.getOrNull(index)?.setValue(if (value == "--") null else value, unit)
        }
    }

    private fun confirmResetCngTotals() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset CNG totals?")
            .setMessage("All-time CNG used, distance and run time go back to zero. The cylinder level and fill-up figures stay.")
            .setPositiveButton("Reset") { _, _ ->
                if (serviceRunning()) ObdRepository.submit(ObdRepository.Task.ResetCngTotals)
                else CngTracker(prefs).resetTotals()
                onShown()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** While polling runs, the service owns the CNG count; otherwise settings change directly. */
    private fun serviceRunning() = ObdRepository.connectionState.value != ObdRepository.ConnState.DISCONNECTED

    /**
     * Pump receipts show the kg dispensed, so a top-up adds to what is left.
     * The first time, Full cylinder is the accurate choice.
     */
    private fun logCngFill() {
        val s = settings ?: return
        val context = requireContext()
        val current = ObdRepository.trip.value.cngRemainingKg ?: prefs.cngRemainingKg.takeIf { it >= 0f }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "kg filled (from the receipt)"
        }
        val known = current?.let { "About %.1f kg was left. ".format(it) } ?: ""
        AlertDialog.Builder(context)
            .setTitle("CNG fill-up")
            .setMessage(
                known + "Enter the kg on the receipt; that also measures your real mileage since the last fill. " +
                        "Or tap Full cylinder (" + Format.num(s.cngCapacityKg, 1) + " kg)."
            )
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val added = input.text.toString().trim().toFloatOrNull() ?: return@setPositiveButton
                recordCngLevel(((current ?: 0f) + added).coerceAtMost(s.cngCapacityKg), added)
            }
            .setNeutralButton("Full cylinder") { _, _ -> recordCngLevel(s.cngCapacityKg, null) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recordCngLevel(levelKg: Float, filledKg: Float?) {
        if (serviceRunning()) ObdRepository.submit(ObdRepository.Task.CngFilled(levelKg, filledKg))
        else CngTracker(prefs).refill(levelKg, filledKg)
        Toast.makeText(requireContext(), "CNG level set to %.1f kg".format(levelKg), Toast.LENGTH_SHORT).show()
        onShown()
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
        val s = settings ?: prefs.snapshot()
        val imp = s.imperial
        val pad = (10 * resources.displayMetrics.density).toInt()
        val gap = (6 * resources.displayMetrics.density).toInt()
        for (t in trips.take(SHOWN_TRIPS)) {
            val line1 = dateFormat.format(Date(t.startedAt)) + "   " +
                    Format.distance(t.distanceKm, imp) + " " + Format.distanceUnit(imp) + "   " +
                    Format.duration(t.durationSeconds)
            val economies = t.fuelUse.mapNotNull { use ->
                use.per100Km?.let {
                    val (value, unit) = Format.economy(it, use.fuel, s)
                    (if (t.fuelUse.size > 1) use.fuel.label + " " else "") + value + " " + unit
                }
            }
            val line2 = listOfNotNull(
                economies.joinToString("   ").ifEmpty { null },
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
        cngTiles.clear()
    }

    private companion object {
        const val SHOWN_TRIPS = 20
    }
}
