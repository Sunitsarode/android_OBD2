package com.obd2dash.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.databinding.FragmentSensorsBinding
import com.obd2dash.databinding.ItemSensorBinding
import com.obd2dash.obd.Pids
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Every PID this vehicle answers, with its live value. Tap one to graph it. */
class SensorsFragment : LiveFragment() {

    private var binding: FragmentSensorsBinding? = null
    private lateinit var prefs: Prefs
    private var pids: List<Pids.Pid> = emptyList()
    private var readings: Map<Int, Float> = emptyMap()
    private var chartPid: Pids.Pid? = null
    private val listAdapter = SensorAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentSensorsBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        prefs = Prefs(requireContext())
        b.sensorList.layoutManager = LinearLayoutManager(requireContext())
        b.sensorList.adapter = listAdapter
        // Values change every poll; animating each change would only flicker.
        b.sensorList.itemAnimator = null
        b.chart.setOnClickListener {
            chartPid = null
            b.chart.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ObdRepository.supportedPids.collectLatest { supported ->
                        pids = Pids.ALL.filter { supported.contains(it.id) }
                        binding?.sensorSummary?.text =
                            pids.size.toString() + " of " + Pids.ALL.size + " known PIDs supported. Tap one to graph it."
                        listAdapter.refresh()
                    }
                }
                launch {
                    ObdRepository.live.collectLatest {
                        readings = it.readings
                        if (isShowing) onShown()
                    }
                }
            }
        }
    }

    override fun onShown() {
        listAdapter.refresh()
        updateChart()
    }

    private fun updateChart() {
        val b = binding ?: return
        val pid = chartPid ?: return
        val now = System.currentTimeMillis()
        val since = now - CHART_WINDOW_MS
        b.chart.setData(pid.name, pid.unit, ObdRepository.history.series(pid.id, since), since, now)
    }

    private fun showChart(pid: Pids.Pid) {
        chartPid = pid
        binding?.chart?.visibility = View.VISIBLE
        updateChart()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private inner class SensorAdapter : RecyclerView.Adapter<SensorAdapter.Holder>() {

        inner class Holder(val item: ItemSensorBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSensorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val pid = pids[position]
            val (value, unit) = Format.pidValue(pid, readings[pid.id], prefs.imperialUnits)
            holder.item.sensorName.text = pid.name
            holder.item.sensorPid.text = "Mode 01 PID " + pid.hex
            holder.item.sensorValue.text = value
            holder.item.sensorUnit.text = unit
            holder.item.root.setOnClickListener { showChart(pid) }
        }

        override fun getItemCount() = pids.size

        @SuppressLint("NotifyDataSetChanged")
        fun refresh() = notifyDataSetChanged()
    }

    private companion object {
        const val CHART_WINDOW_MS = 60_000L
    }
}
