package com.obd2dash.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obd2dash.R
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.databinding.ActivityDashboardBinding
import com.obd2dash.service.ObdService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hosts the five screens. Fragments are kept alive and toggled rather than
 * replaced, so switching tabs never drops a gauge's animation state.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var prefs: Prefs
    private val fragments = LinkedHashMap<Int, Fragment>()
    private var activeId = R.id.nav_dash

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        fragments[R.id.nav_dash] = DashFragment()
        fragments[R.id.nav_sensors] = SensorsFragment()
        fragments[R.id.nav_trip] = TripFragment()
        fragments[R.id.nav_codes] = CodesFragment()
        fragments[R.id.nav_console] = ConsoleFragment()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                fragments.forEach { (id, fragment) ->
                    add(R.id.container, fragment, id.toString())
                    if (id != activeId) hide(fragment)
                }
            }.commit()
        } else {
            activeId = savedInstanceState.getInt(STATE_ACTIVE, R.id.nav_dash)
            fragments.keys.forEach { id ->
                supportFragmentManager.findFragmentByTag(id.toString())?.let { fragments[id] = it }
            }
        }

        binding.bottomNav.selectedItemId = activeId
        binding.bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }

        binding.connStatus.setOnClickListener { showConnectionMenu() }
        binding.hudButton.setOnClickListener { startActivity(Intent(this, HudActivity::class.java)) }

        observe()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ACTIVE, activeId)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun show(id: Int) {
        val target = fragments[id] ?: return
        val current = fragments[activeId]
        supportFragmentManager.beginTransaction().apply {
            if (current != null && current !== target) hide(current)
            show(target)
        }.commit()
        activeId = id
    }

    private fun observe() {
        lifecycleScope.launch {
            ObdRepository.statusMessage.collectLatest { binding.connStatus.text = it }
        }
        lifecycleScope.launch {
            ObdRepository.pollRateHz.collectLatest {
                binding.pollRate.text = if (it > 0f) "%.1f q/s".format(it) else ""
            }
        }
        lifecycleScope.launch {
            ObdRepository.alerts.collectLatest { active ->
                binding.alertBanner.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
                binding.alertBanner.text = active.joinToString("   |   ") { it.title.uppercase() }
            }
        }
    }

    private fun showConnectionMenu() {
        val info = ObdRepository.vehicleInfo.value
        val details = listOfNotNull(
            info.adapterId?.let { "Adapter: " + it },
            info.protocol?.let { "Protocol: " + it },
            ObdRepository.pollMode.value.takeIf { it.isNotEmpty() }?.let { "Polling: " + it },
            info.vin?.let { "VIN: " + it }
        ).joinToString("\n").ifEmpty { "No adapter details yet" }

        AlertDialog.Builder(this)
            .setTitle("Connection")
            .setMessage(details)
            .setPositiveButton(R.string.settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.disconnect) { _, _ ->
                // Stops the connect screen from reconnecting straight away.
                ObdRepository.userDisconnected = true
                ObdService.disconnect(this)
                // Back to the picker rather than out of the app entirely.
                startActivity(Intent(this, ConnectActivity::class.java))
                finish()
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private companion object {
        const val STATE_ACTIVE = "activeTab"
    }
}
