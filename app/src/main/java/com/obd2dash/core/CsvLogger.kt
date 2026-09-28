package com.obd2dash.core

import android.content.Context
import com.obd2dash.obd.Pids
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

    private var writer: java.io.BufferedWriter? = null
    private var columns: List<Int> = emptyList()

    var currentFile: File? = null
        private set

    val isOpen: Boolean get() = writer != null

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
            header += listOf("fuel_rate_Lh", "consumption_L100km", "boost_kPa", "power_kW", "gear")
            w.write(header.joinToString(","))
            w.newLine()
            writer = w
            currentFile = file
        } catch (_: IOException) {
            writer = null
            currentFile = null
        }
    }

    fun write(
        readings: Map<Int, Float>,
        elapsedSeconds: Long,
        fuelRateLh: Float?,
        consumption: Float?,
        boost: Float?,
        powerKw: Float?,
        gear: Int?
    ) {
        val w = writer ?: return
        try {
            val row = ArrayList<String>()
            row += System.currentTimeMillis().toString()
            row += elapsedSeconds.toString()
            columns.forEach { row += readings[it]?.let { v -> "%.2f".format(Locale.US, v) } ?: "" }
            row += fuelRateLh.fmt()
            row += consumption.fmt()
            row += boost.fmt()
            row += powerKw.fmt()
            row += gear?.toString() ?: ""
            w.write(row.joinToString(","))
            w.newLine()
        } catch (_: IOException) {
            stop()
        }
    }

    private fun Float?.fmt(): String =
        this?.let { "%.2f".format(Locale.US, it) } ?: ""

    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
    }

    fun listLogs(): List<File> {
        val dir = File(context.getExternalFilesDir(null), "logs")
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
