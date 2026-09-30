package com.obd2dash.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.core.PrefsGearStore
import com.obd2dash.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val fuelTypes = Prefs.FuelType.values()
    private val transmissions = Prefs.Transmission.values()
    private val gearCounts = (4..8).toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.fuelType.fill(fuelTypes.map { it.label }, fuelTypes.indexOf(prefs.fuelType))
        binding.transmission.fill(transmissions.map { it.label }, transmissions.indexOf(prefs.transmission))
        binding.gearCount.fill(gearCounts.map { "$it gears" }, gearCounts.indexOf(prefs.gearCount).coerceAtLeast(0))

        binding.displacement.setText(prefs.displacementLitres.toString())
        binding.tankSize.setText(prefs.tankLitres.toString())
        binding.fuelPrice.setText(prefs.fuelPricePerLitre.toString())
        binding.redline.setText(prefs.redlineRpm.toString())
        binding.shiftUpRpm.setText(prefs.shiftUpRpm.toString())
        binding.speedLimit.setText(prefs.speedLimitKmh.toString())
        binding.overheat.setText(prefs.overheatC.toString())
        binding.lowFuel.setText(prefs.lowFuelPercent.toString())

        binding.alertBeep.isChecked = prefs.alertBeep
        binding.alertVoice.isChecked = prefs.alertVoice
        binding.imperial.isChecked = prefs.imperialUnits
        binding.keepScreenOn.isChecked = prefs.keepScreenOn
        binding.autoConnect.isChecked = prefs.autoConnect
        binding.startOnBoot.isChecked = prefs.startOnBoot
        binding.fastPolling.isChecked = prefs.fastPolling
        binding.logging.isChecked = prefs.loggingEnabled

        binding.logPath.text = "Logs are written to " + File(getExternalFilesDir(null), "logs").absolutePath
        showLearnedGears()

        binding.resetGears.setOnClickListener { confirmResetGears() }
        binding.save.setOnClickListener { save() }
    }

    private fun Spinner.fill(labels: List<String>, selected: Int) {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(selected.coerceAtLeast(0))
    }

    /** Shows what the gear estimator has learned, in the units a gearbox spec sheet uses. */
    private fun showLearnedGears() {
        val ratios = PrefsGearStore(prefs).load().sorted()
        binding.learnedGears.text = if (ratios.isEmpty()) {
            "No gears learned yet. Drive a few minutes using each gear and they are learned and saved."
        } else {
            "Learned " + ratios.size + " gear(s), km/h per 1000 rpm:\n" +
                    ratios.mapIndexed { i, r -> (i + 1).toString() + ": " + "%.1f".format(r * 1000f) }.joinToString("   ")
        }
    }

    private fun confirmResetGears() {
        AlertDialog.Builder(this)
            .setTitle("Relearn gears?")
            .setMessage("Use this after changing tyre size, or if the gear shown is consistently wrong. The next drive relearns each gear.")
            .setPositiveButton("Relearn") { _, _ ->
                prefs.learnedGearRatios = ""
                ObdRepository.submit(ObdRepository.Task.ResetGears)
                showLearnedGears()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun EditText.floatOr(fallback: Float) = text.toString().trim().toFloatOrNull() ?: fallback
    private fun EditText.intOr(fallback: Int) = text.toString().trim().toIntOrNull() ?: fallback

    private fun save() {
        prefs.fuelType = fuelTypes[binding.fuelType.selectedItemPosition]
        prefs.transmission = transmissions[binding.transmission.selectedItemPosition]
        prefs.gearCount = gearCounts[binding.gearCount.selectedItemPosition]

        prefs.displacementLitres = binding.displacement.floatOr(prefs.displacementLitres).coerceIn(0.5f, 8f)
        prefs.tankLitres = binding.tankSize.floatOr(prefs.tankLitres).coerceIn(5f, 200f)
        prefs.fuelPricePerLitre = binding.fuelPrice.floatOr(prefs.fuelPricePerLitre).coerceAtLeast(0f)
        prefs.redlineRpm = binding.redline.intOr(prefs.redlineRpm).coerceIn(0, 12000)
        prefs.shiftUpRpm = binding.shiftUpRpm.intOr(prefs.shiftUpRpm).coerceIn(0, 12000)
        prefs.speedLimitKmh = binding.speedLimit.intOr(prefs.speedLimitKmh).coerceIn(0, 300)
        prefs.overheatC = binding.overheat.intOr(prefs.overheatC).coerceIn(80, 150)
        prefs.lowFuelPercent = binding.lowFuel.intOr(prefs.lowFuelPercent).coerceIn(0, 50)

        prefs.alertBeep = binding.alertBeep.isChecked
        prefs.alertVoice = binding.alertVoice.isChecked
        prefs.imperialUnits = binding.imperial.isChecked
        prefs.keepScreenOn = binding.keepScreenOn.isChecked
        prefs.autoConnect = binding.autoConnect.isChecked
        prefs.startOnBoot = binding.startOnBoot.isChecked
        prefs.fastPolling = binding.fastPolling.isChecked
        prefs.loggingEnabled = binding.logging.isChecked

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
