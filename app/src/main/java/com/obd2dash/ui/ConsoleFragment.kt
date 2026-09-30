package com.obd2dash.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.core.ObdRepository
import com.obd2dash.databinding.FragmentConsoleBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Raw adapter traffic, plus a prompt for sending AT or OBD commands by hand.
 * Useful for probing manufacturer PIDs the registry does not cover.
 *
 * The console is polled a few times a second rather than observed, so a busy
 * adapter cannot flood the UI thread with redraws.
 */
class ConsoleFragment : LiveFragment() {

    private var binding: FragmentConsoleBinding? = null
    private var shownVersion = -1L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentConsoleBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.sendCommand.setOnClickListener { send() }
        b.clearConsole.setOnClickListener { ObdRepository.clearConsole() }
        b.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        b.verbose.isChecked = ObdRepository.verboseTrace
        b.verbose.setOnCheckedChangeListener { _, checked -> ObdRepository.verboseTrace = checked }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (isShowing) refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    override fun onShown() {
        shownVersion = -1L
        refresh()
    }

    private fun refresh() {
        val b = binding ?: return
        val mode = ObdRepository.pollMode.value
        b.pollModeText.text = if (mode.isEmpty()) "Not connected" else "Polling mode: " + mode
        val version = ObdRepository.consoleVersion
        if (version == shownVersion) return
        shownVersion = version
        b.consoleText.text = ObdRepository.consoleSnapshot().joinToString("\n")
        b.consoleScroll.post { b.consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send() {
        val b = binding ?: return
        val command = b.commandInput.text.toString().trim().uppercase()
        if (command.isEmpty()) return
        ObdRepository.submit(ObdRepository.Task.Raw(command))
        b.commandInput.setText("")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private companion object {
        const val REFRESH_MS = 300L
    }
}
