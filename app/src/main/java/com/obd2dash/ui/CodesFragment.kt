package com.obd2dash.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.R
import com.obd2dash.core.ObdRepository
import com.obd2dash.databinding.FragmentCodesBinding
import com.obd2dash.databinding.ItemDtcBinding
import com.obd2dash.core.Prefs
import com.obd2dash.obd.Dtc
import com.obd2dash.obd.Pids
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Trouble codes, MIL state, readiness monitors, and vehicle identification. */
class CodesFragment : Fragment() {

    private var binding: FragmentCodesBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentCodesBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return

        b.readCodes.setOnClickListener {
            ObdRepository.submit(ObdRepository.Task.ReadDtcs)
            ObdRepository.submit(ObdRepository.Task.ReadVehicleInfo)
        }
        b.clearCodes.setOnClickListener { confirmClear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ObdRepository.dtcs.collectLatest { renderCodes(it) } }
                launch { ObdRepository.milStatus.collectLatest { renderStatus(it) } }
                launch { ObdRepository.freezeFrame.collectLatest { renderFreezeFrame(it) } }
                launch {
                    ObdRepository.vehicleInfo.collectLatest { info ->
                        binding?.vehicleInfo?.text = listOfNotNull(
                            info.vin?.let { "VIN " + it },
                            info.ecuName?.let { "ECU " + it },
                            info.calibrationId?.let { "Cal " + it },
                            info.protocol?.let { "Protocol " + it }
                        ).joinToString("   ").ifEmpty { "No vehicle identification read yet" }
                    }
                }
            }
        }
    }

    /**
     * Clearing codes also resets readiness monitors, which matters for emissions
     * testing, so the consequences are spelled out before it happens.
     */
    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_body)
            .setPositiveButton(R.string.clear_codes) { _, _ ->
                ObdRepository.submit(ObdRepository.Task.ClearDtcs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderStatus(status: Dtc.Status?) {
        val b = binding ?: return
        if (status == null) {
            b.milStatus.text = "Waiting for monitor status..."
            renderMonitors(emptyList())
            return
        }
        val lamp = if (status.milOn) "CHECK ENGINE LIGHT ON" else "Check engine light off"
        b.milStatus.text = lamp + "\n" + status.dtcCount + " stored code(s)   " +
                (if (status.compressionIgnition) "Compression ignition" else "Spark ignition")
        b.milStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (status.milOn) R.color.danger else R.color.ok
            )
        )
        renderMonitors(status.monitors)
    }

    private fun renderMonitors(monitors: List<Dtc.Monitor>) {
        val container = binding?.monitorContainer ?: return
        container.removeAllViews()
        if (monitors.isEmpty()) return
        monitors.filter { it.supported }.forEach { monitor ->
            val row = TextView(requireContext()).apply {
                text = (if (monitor.complete) "READY   " else "NOT READY   ") + monitor.name
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (monitor.complete) R.color.ok else R.color.amber
                    )
                )
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }
            container.addView(row)
        }
    }

    /** What the engine was doing when the fault was stored: often the best clue to its cause. */
    private fun renderFreezeFrame(frame: Dtc.FreezeFrame?) {
        val view = binding?.freezeFrame ?: return
        if (frame == null || frame.values.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        val imperial = Prefs(requireContext()).imperialUnits
        val parts = frame.values.mapNotNull { (id, value) ->
            Pids.BY_ID[id]?.let { pid ->
                val (text, unit) = Format.pidValue(pid, value, imperial)
                pid.short + " " + text + (if (unit.isEmpty()) "" else " " + unit)
            }
        }
        view.text = "Freeze frame" + (frame.dtc?.let { " for " + it } ?: "") + "\n" + parts.joinToString("\n")
        view.visibility = View.VISIBLE
    }

    private fun renderCodes(entries: List<Dtc.Entry>) {
        val container = binding?.dtcContainer ?: return
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "No trouble codes stored."
                setTextColor(ContextCompat.getColor(requireContext(), R.color.ok))
                textSize = 14f
                setPadding(0, 8, 0, 8)
            })
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        entries.forEach { entry ->
            val item = ItemDtcBinding.inflate(inflater, container, false)
            item.dtcCode.text = entry.code
            item.dtcKind.text = entry.kind.label.uppercase()
            item.dtcDescription.text = entry.description
            item.dtcSystem.text = entry.system
            container.addView(item.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
