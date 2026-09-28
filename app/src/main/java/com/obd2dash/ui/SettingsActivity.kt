package com.obd2dash.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.obd2dash.core.CsvLogger
import com.obd2dash.core.Prefs
import com.obd2dash.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val fuelTypes = Prefs.FuelType.values()
        binding.fuelType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            fuelTypes.map { it.label }
        )
        binding.fuelType.setSelection(fuelTypes.indexOf(prefs.fuelType))

        binding.displacement.setText(prefs.displacementLitres.toString())
        binding.tankSize.setText(prefs.tankLitres.toString())
        binding.redline.setText(prefs.redlineRpm.toString())
        binding.imperial.isChecked = prefs.imperialUnits
        binding.keepScreenOn.isChecked = prefs.keepScreenOn
        binding.autoConnect.isChecked = prefs.autoConnect
        binding.logging.isChecked = prefs.loggingEnabled

        val logDir = CsvLogger(this).listLogs().firstOrNull()?.parentFile?.absolutePath
        binding.logPath.text = "Logs are written to " + (logDir ?: getExternalFilesDir(null)?.absolutePath + "/logs")

        binding.save.setOnClickListener { save() }
    }

    private fun save() {
        prefs.fuelType = Prefs.FuelType.values()[binding.fuelType.selectedItemPosition]
        binding.displacement.text.toString().toFloatOrNull()?.let { prefs.displacementLitres = it }
        binding.tankSize.text.toString().toFloatOrNull()?.let { prefs.tankLitres = it }
        binding.redline.text.toString().toIntOrNull()?.let { prefs.redlineRpm = it }
        prefs.imperialUnits = binding.imperial.isChecked
        prefs.keepScreenOn = binding.keepScreenOn.isChecked
        prefs.autoConnect = binding.autoConnect.isChecked
        prefs.loggingEnabled = binding.logging.isChecked

        Toast.makeText(this, "Saved. Reconnect to apply logging changes.", Toast.LENGTH_SHORT).show()
        finish()
    }
}
