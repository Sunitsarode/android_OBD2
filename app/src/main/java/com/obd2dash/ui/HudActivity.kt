package com.obd2dash.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.R
import com.obd2dash.core.Metrics
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.databinding.ActivityHudBinding
import com.obd2dash.obd.Pids
import kotlinx.coroutines.launch

/**
 * Head-up display: speed, gear, and an RPM bar, as large as the screen allows.
 * Mirrored, a phone lying on the dashboard reflects readably in the windscreen.
 */
class HudActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHudBinding
    private lateinit var prefs: Prefs
    private var normalColor = 0
    private var warnColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHudBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        normalColor = ContextCompat.getColor(this, R.color.accent)
        warnColor = ContextCompat.getColor(this, R.color.danger)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applyMirror()

        binding.hudRoot.setOnClickListener { finish() }
        binding.hudRoot.setOnLongClickListener {
            prefs.hudMirror = !prefs.hudMirror
            applyMirror()
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ObdRepository.live.collect { render(it) }
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyMirror() {
        binding.hudContent.scaleX = if (prefs.hudMirror) -1f else 1f
        // The hint would read backwards when mirrored, and it has done its job by then.
        binding.hudHint.visibility = if (prefs.hudMirror) View.GONE else View.VISIBLE
    }

    private fun render(live: ObdRepository.Live) {
        val imperial = prefs.imperialUnits
        val redline = prefs.redlineRpm
        val speed = live.readings[Pids.SPEED]
        val rpm = live.readings[Pids.RPM]

        binding.hudSpeed.text = if (speed == null) "--" else "%.0f".format(if (imperial) Metrics.kmhToMph(speed) else speed)
        binding.hudUnit.text = if (imperial) "mph" else "km/h"
        binding.hudGear.text = if (live.gear.shiftUp) live.gear.label + "↑" else live.gear.label

        binding.hudRpm.max = maxOf(redline + 1000, 4000)
        binding.hudRpm.progress = (rpm ?: 0f).toInt()
        val over = redline > 0 && (rpm ?: 0f) >= redline
        binding.hudRpm.progressTintList = ColorStateList.valueOf(if (over) warnColor else normalColor)
    }
}
