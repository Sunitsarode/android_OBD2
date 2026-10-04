package com.obd2dash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.obd2dash.core.FuelDetection
import com.obd2dash.core.FuelSystem
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.core.PrefsGearStore
import com.obd2dash.databinding.ActivitySettingsBinding
import com.obd2dash.obd.Pids
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val fuelSystems = FuelSystem.values()
    private val detections = FuelDetection.values()
    private val transmissions = Prefs.Transmission.values()
    private val gearCounts = (4..8).toList()
    private val economyUnits = listOf("km/L and km/kg", "L/100km and kg/100km")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.fuelSystem.fill(fuelSystems.map { it.label }, fuelSystems.indexOf(prefs.fuelSystem))
        binding.fuelDetection.fill(detections.map { it.label }, detections.indexOf(prefs.fuelDetection))
        binding.transmission.fill(transmissions.map { it.label }, transmissions.indexOf(prefs.transmission))
        binding.gearCount.fill(gearCounts.map { "$it gears" }, gearCounts.indexOf(prefs.gearCount))
        binding.economyUnit.fill(economyUnits, if (prefs.economyPerDistance) 0 else 1)

        binding.fuelSystem.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                showFieldsFor(fuelSystems[position])

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        showFieldsFor(prefs.fuelSystem)
        showEcuFuelReport()

        binding.displacement.setText(prefs.displacementLitres.toString())
        binding.tankSize.setText(prefs.tankLitres.toString())
        binding.fuelPrice.setText(prefs.fuelPricePerLitre.toString())
        binding.cngPrice.setText(prefs.cngPricePerKg.toString())
        binding.cngCapacity.setText(prefs.cngCapacityKg.toString())
        binding.lowCng.setText(prefs.lowCngKg.toString())
        binding.cylinderTest.setText(prefs.cylinderTestDue)
        binding.lpgPrice.setText(prefs.lpgPricePerLitre.toString())
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
        // Fast polling switched off automatically for this adapter shows as unticked;
        // ticking it again gives fast mode another try.
        val fastBlocked = prefs.fastModeBlockedFor != null && prefs.fastModeBlockedFor == prefs.lastDeviceAddress
        binding.fastPolling.isChecked = prefs.fastPolling && !fastBlocked
        if (fastBlocked) {
            binding.reconnectNote.text = "Fast polling was switched off for your adapter because it stopped " +
                    "answering in fast mode. Tick it to try again. Changes apply on the next connection."
        }
        binding.logging.isChecked = prefs.loggingEnabled

        binding.logPath.text = "Logs are written to " + File(getExternalFilesDir(null), "logs").absolutePath
        showLearnedGears()
        showAndroidAutoHelp()

        binding.resetGears.setOnClickListener { confirmResetGears() }
        binding.save.setOnClickListener { save() }
    }

    private fun Spinner.fill(labels: List<String>, selected: Int) {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(selected.coerceAtLeast(0))
    }

    /**
     * Android Auto hides apps that were not installed from the Play Store until
     * its developer setting allows them, which is the usual reason the app is
     * missing from the car's launcher.
     */
    private fun showAndroidAutoHelp() {
        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(ANDROID_AUTO, 0).versionName ?: "?"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        binding.autoStatus.text = if (version == null) {
            "Android Auto is not installed on this phone. Install it from the Play Store first."
        } else {
            "Android Auto " + version + " is installed. To show OBD2 Dashboard on the car screen:"
        }
        binding.autoSteps.text = AUTO_STEPS
        binding.openAuto.isEnabled = version != null
        binding.openAuto.setOnClickListener { openAndroidAuto() }
    }

    private fun openAndroidAuto() {
        // On Android 10+ Android Auto lives inside system settings; its launch intent opens that page.
        val intent = packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ANDROID_AUTO))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings and search for Android Auto", Toast.LENGTH_LONG).show()
        }
    }

    /** Only the fields that apply to the chosen fuel system are shown. */
    private fun showFieldsFor(system: FuelSystem) {
        val tank = system.tankFuel
        binding.biFuelGroup.visibility = if (system.isBiFuel) View.VISIBLE else View.GONE
        binding.cngGroup.visibility = if (system.usesCng) View.VISIBLE else View.GONE
        binding.lpgGroup.visibility = if (system.secondary == com.obd2dash.core.Fuel.LPG) View.VISIBLE else View.GONE
        binding.tankLabel.visibility = if (tank != null) View.VISIBLE else View.GONE
        binding.tankSize.visibility = binding.tankLabel.visibility
        binding.priceLabel.visibility = binding.tankLabel.visibility
        binding.fuelPrice.visibility = binding.tankLabel.visibility
        if (tank != null) {
            binding.tankLabel.text = tank.label + " tank capacity (litres)"
            binding.priceLabel.text = tank.label + " price per litre (0 = off)"
        }
    }

    /**
     * Automatic detection only works if the ECU's fuel-type report changes when
     * the car switches fuel. Showing the live value lets the driver check that.
     */
    private fun showEcuFuelReport() {
        val code = ObdRepository.live.value.readings[Pids.FUEL_TYPE]
        binding.ecuFuelReport.text = if (code == null) {
            "Right now the ECU reports no fuel type (connect first, or it may not support it). " +
                    "If it never does, choose Manual."
        } else {
            "Right now the ECU reports: " + Format.fuelType(code) + ". If this does not change when you " +
                    "press the CNG switch, choose Manual."
        }
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
        val testDate = binding.cylinderTest.text.toString().trim()
        if (testDate.isNotEmpty() && !Regex("^[0-9]{2}-[0-9]{2}-[0-9]{4}$").matches(testDate)) {
            binding.cylinderTest.error = "Use DD-MM-YYYY, for example 15-03-2028"
            return
        }

        prefs.fuelSystem = fuelSystems[binding.fuelSystem.selectedItemPosition]
        prefs.fuelDetection = detections[binding.fuelDetection.selectedItemPosition]
        prefs.transmission = transmissions[binding.transmission.selectedItemPosition]
        prefs.gearCount = gearCounts[binding.gearCount.selectedItemPosition]
        prefs.economyPerDistance = binding.economyUnit.selectedItemPosition == 0

        prefs.displacementLitres = binding.displacement.floatOr(prefs.displacementLitres).coerceIn(0.5f, 8f)
        prefs.tankLitres = binding.tankSize.floatOr(prefs.tankLitres).coerceIn(5f, 200f)
        prefs.fuelPricePerLitre = binding.fuelPrice.floatOr(prefs.fuelPricePerLitre).coerceAtLeast(0f)
        prefs.cngPricePerKg = binding.cngPrice.floatOr(prefs.cngPricePerKg).coerceAtLeast(0f)
        prefs.cngCapacityKg = binding.cngCapacity.floatOr(prefs.cngCapacityKg).coerceIn(2f, 40f)
        prefs.lowCngKg = binding.lowCng.floatOr(prefs.lowCngKg).coerceIn(0f, 10f)
        prefs.cylinderTestDue = testDate
        prefs.lpgPricePerLitre = binding.lpgPrice.floatOr(prefs.lpgPricePerLitre).coerceAtLeast(0f)
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
        val wantFast = binding.fastPolling.isChecked
        if (wantFast) prefs.fastModeBlockedFor = null
        prefs.fastPolling = wantFast
        prefs.loggingEnabled = binding.logging.isChecked

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"

        val AUTO_STEPS = listOf(
            "1. Tap Open Android Auto settings below.",
            "2. Scroll to the bottom and tap Version about 10 times, then OK, to turn on developer mode.",
            "3. Tap the three dots at the top right, open Developer settings, and turn on Unknown sources.",
            "4. Go back, open Customize launcher, and make sure OBD2 Dashboard is ticked.",
            "5. Disconnect the phone from the car and connect it again.",
            "",
            "OBD2 Dashboard then appears in the car's app list. Open this app on the phone once " +
                    "first and connect to the adapter."
        ).joinToString("\n")
    }
}
