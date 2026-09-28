package com.obd2dash.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.core.ObdRepository
import com.obd2dash.databinding.FragmentConsoleBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Raw adapter traffic, plus a prompt for sending AT or OBD commands by hand.
 * Useful for probing manufacturer PIDs the registry does not cover.
 */
class ConsoleFragment : Fragment() {

    private var binding: FragmentConsoleBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ObdRepository.console.collectLatest { lines ->
                    val view = binding ?: return@collectLatest
                    view.consoleText.text = lines.joinToString("\n")
                    view.consoleScroll.post { view.consoleScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
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
}
