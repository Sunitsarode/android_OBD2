package com.obd2dash.core

import android.content.Context
import com.obd2dash.obd.Pids
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends each poll cycle to a CSV file under the app's external files dir,
 * where a file manager or USB connection can pick it up.
 */
class CsvLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var columns: List<Int> = emptyList()

    @Synchronized
    fun start(supportedPids: Set<Int>) {
        stop()
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "obd-" + stamp + ".csv")
        columns = Pids.ALL.map { it.id }.filter { supportedPids.contains(it) }
        try {
            val w = file.bufferedWriter()
            val header = ArrayList<String>()
            header += "timestamp"
            header += "elapsed_s"
            columns.forEach { id ->
                val pid = Pids.BY_ID[id] ?: return@forEach
                header += pid.short.replace(' ', '_') + "_" + pid.unit.replace('/', '_')
            }
            header += listOf(
                "fuel_rate_Lh", "consumption_L100km", "boost_kPa", "power_kW",
                "gear_0_is_neutral", "adapter_V", "fuel_cut"
            )
            w.write(header.joinToString(","))
            w.newLine()
            writer = w
        } catch (_: IOException) {
            writer = null
        }
    }

    @Synchronized
    fun write(live: ObdRepository.Live, elapsedSeconds: Long) {
        val w = writer ?: return
        try {
            val d = live.derived
            val row = ArrayList<String>()
            row += live.at.toString()
            row += elapsedSeconds.toString()
            columns.forEach { row += live.readings[it].fmt() }
            row += d.fuelRateLh.fmt()
            row += d.consumptionL100.fmt()
            row += d.boostKpa.fmt()
            row += d.powerKw.fmt()
            row += live.gear.gear?.toString() ?: ""
            row += d.adapterVoltage.fmt()
            row += if (d.fuelCut) "1" else "0"
            w.write(row.joinToString(","))
            w.newLine()
        } catch (_: IOException) {
            stop()
        }
    }

    private fun Float?.fmt(): String = this?.let { "%.2f".format(Locale.US, it) } ?: ""

    @Synchronized
    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
    }
}
