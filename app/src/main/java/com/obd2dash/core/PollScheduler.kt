package com.obd2dash.core

import com.obd2dash.obd.Pids
import java.util.EnumMap

/**
 * Decides what to read each cycle.
 *
 * Every cycle reads the fast PIDs, then tops up with a bounded number of slower
 * items that have fallen due, most overdue first. Capping the extras keeps each
 * cycle short, so RPM, speed and gear refresh at a steady rate instead of
 * stalling while a burst of slow PIDs goes through.
 */
class PollScheduler(pids: List<Pids.Pid>) {

    /** Adapter-level queries that are not PIDs but still need a slot. */
    enum class Special(val intervalMs: Long) {
        ADAPTER_VOLTAGE(10_000L),
        MIL_STATUS(30_000L)
    }

    /** One slow item that has fallen due: a PID or a special query. */
    class Due(val pid: Pids.Pid?, val special: Special?)

    /** Read every cycle: what the gauges and the gear estimate depend on. */
    private val primary = ArrayList<Pids.Pid>()

    /** Other fast PIDs; without batching they alternate between cycles. */
    private val secondary = ArrayList<Pids.Pid>()
    private val slow = ArrayList<Pids.Pid>()
    private val pidDue = HashMap<Int, Long>()
    private val specialDue = EnumMap<Special, Long>(Special::class.java)

    init {
        for (pid in pids) {
            when {
                pid.id in PRIMARY_IDS -> primary += pid
                pid.tier == Pids.Tier.FAST -> secondary += pid
                else -> {
                    slow += pid
                    pidDue[pid.id] = 0L
                }
            }
        }
        Special.values().forEach { specialDue[it] = 0L }
    }

    val pollableIds: Set<Int> get() = (primary + secondary + slow).map { it.id }.toSet()

    /** Fast PIDs for this cycle. Each costs a round trip without batching, so the secondary ones take turns. */
    fun fastFor(cycle: Long, batching: Boolean): List<Pids.Pid> {
        if (batching || secondary.size <= 1) return primary + secondary
        val parity = (cycle % 2).toInt()
        return primary + secondary.filterIndexed { i, _ -> i % 2 == parity }
    }

    /** Takes up to [max] due items, most overdue first, and schedules their next read. */
    fun takeDue(now: Long, max: Int): List<Due> {
        val candidates = ArrayList<Pair<Long, Due>>()
        for (pid in slow) {
            val at = pidDue[pid.id] ?: 0L
            if (at <= now) candidates += at to Due(pid, null)
        }
        for ((special, at) in specialDue) {
            if (at <= now) candidates += at to Due(null, special)
        }
        if (candidates.isEmpty()) return emptyList()
        candidates.sortBy { it.first }
        val taken = candidates.take(max).map { it.second }
        for (due in taken) {
            due.pid?.let { pidDue[it.id] = now + intervalFor(it) }
            due.special?.let { specialDue[it] = now + it.intervalMs }
        }
        return taken
    }

    fun remove(pidId: Int) {
        primary.removeAll { it.id == pidId }
        secondary.removeAll { it.id == pidId }
        slow.removeAll { it.id == pidId }
        pidDue.remove(pidId)
    }

    private fun intervalFor(pid: Pids.Pid) =
        if (pid.tier == Pids.Tier.MEDIUM) MEDIUM_INTERVAL_MS else SLOW_INTERVAL_MS

    private companion object {
        val PRIMARY_IDS = setOf(Pids.RPM, Pids.SPEED, Pids.THROTTLE)
        const val MEDIUM_INTERVAL_MS = 1_500L
        const val SLOW_INTERVAL_MS = 8_000L
    }
}
