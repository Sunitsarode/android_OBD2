package com.obd2dash.core

/** Recent values of every polled PID, for the live graph. Written by the service, read by the UI. */
class PidHistory(private val capacity: Int = 600) {

    class Series(val times: LongArray, val values: FloatArray)

    private class Ring(capacity: Int) {
        val times = LongArray(capacity)
        val values = FloatArray(capacity)
        var start = 0
        var size = 0

        fun add(time: Long, value: Float) {
            val index = (start + size) % times.size
            times[index] = time
            values[index] = value
            if (size < times.size) size++ else start = (start + 1) % times.size
        }
    }

    private val rings = HashMap<Int, Ring>()

    @Synchronized
    fun add(id: Int, value: Float, time: Long) {
        rings.getOrPut(id) { Ring(capacity) }.add(time, value)
    }

    /** Samples of [id] at or after [since], oldest first. */
    @Synchronized
    fun series(id: Int, since: Long): Series? {
        val ring = rings[id] ?: return null
        val cap = ring.times.size
        var first = 0
        while (first < ring.size && ring.times[(ring.start + first) % cap] < since) first++
        val n = ring.size - first
        if (n <= 0) return null
        val times = LongArray(n)
        val values = FloatArray(n)
        for (k in 0 until n) {
            val i = (ring.start + first + k) % cap
            times[k] = ring.times[i]
            values[k] = ring.values[i]
        }
        return Series(times, values)
    }

    @Synchronized
    fun clear() = rings.clear()
}
