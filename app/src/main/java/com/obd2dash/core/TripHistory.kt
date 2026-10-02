package com.obd2dash.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TripRecord(
    val startedAt: Long,
    val durationSeconds: Long,
    val distanceKm: Float,
    val fuelUse: List<FuelUse>,
    val cost: Float?,
    val maxSpeedKmh: Float,
    val ecoScore: Int?,
    val harshAccelerations: Int,
    val harshBrakings: Int
)

/** Finished trips, newest first, kept as a small JSON file in app storage. */
class TripHistory(context: Context) {

    private val file = File(context.filesDir, "trips.json")

    @Synchronized
    fun load(): List<TripRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(record: TripRecord) {
        val array = JSONArray()
        (listOf(record) + load()).take(MAX_TRIPS).forEach { array.put(toJson(it)) }
        try {
            file.writeText(array.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun toJson(r: TripRecord) = JSONObject().apply {
        put("startedAt", r.startedAt)
        put("duration", r.durationSeconds)
        put("distance", r.distanceKm.toDouble())
        put("fuelUse", JSONArray().apply {
            r.fuelUse.forEach { use ->
                put(JSONObject().apply {
                    put("fuel", use.fuel.name)
                    put("amount", use.amount.toDouble())
                    put("km", use.distanceKm.toDouble())
                })
            }
        })
        put("cost", r.cost?.toDouble() ?: JSONObject.NULL)
        put("maxSpeed", r.maxSpeedKmh.toDouble())
        put("eco", r.ecoScore ?: JSONObject.NULL)
        put("harshAccel", r.harshAccelerations)
        put("harshBrake", r.harshBrakings)
    }

    private fun fromJson(o: JSONObject) = TripRecord(
        startedAt = o.getLong("startedAt"),
        durationSeconds = o.optLong("duration"),
        distanceKm = o.optDouble("distance", 0.0).toFloat(),
        fuelUse = fuelUseFrom(o),
        cost = optFloat(o, "cost"),
        maxSpeedKmh = o.optDouble("maxSpeed", 0.0).toFloat(),
        ecoScore = if (o.isNull("eco")) null else o.optInt("eco"),
        harshAccelerations = o.optInt("harshAccel"),
        harshBrakings = o.optInt("harshBrake")
    )

    /** Reads per-fuel use, including trips saved before bi-fuel support, which were all petrol in litres. */
    private fun fuelUseFrom(o: JSONObject): List<FuelUse> {
        val array = o.optJSONArray("fuelUse")
        if (array != null) {
            return (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val fuel = runCatching { Fuel.valueOf(item.optString("fuel")) }.getOrNull() ?: return@mapNotNull null
                FuelUse(fuel, item.optDouble("amount", 0.0).toFloat(), item.optDouble("km", 0.0).toFloat())
            }
        }
        val litres = o.optDouble("fuel", 0.0).toFloat()
        return if (litres > 0f) listOf(FuelUse(Fuel.PETROL, litres, o.optDouble("distance", 0.0).toFloat())) else emptyList()
    }

    private fun optFloat(o: JSONObject, key: String): Float? =
        if (o.isNull(key)) null else o.optDouble(key).toFloat().takeIf { !it.isNaN() }

    private companion object {
        const val MAX_TRIPS = 100
    }
}
