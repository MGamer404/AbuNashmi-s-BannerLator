package com.winlator.star.perf

import com.winlator.star.core.CPUStatus
import com.winlator.star.core.FileUtils

/**
 * CPU-cluster detection for the "Prefer big cores" preset.
 *
 * The performance tier is every core whose max frequency is at least [PERFORMANCE_TIER_PERCENT] % of
 * the fastest core's (the rule WinNative ships, `runtime/system/ProcessHelper.java` @7414b8af1). The
 * old rule kept only the cores sharing the single highest frequency, which on an 8 Gen 3 (1 prime +
 * 5 performance + 2 efficiency cores) is ONE core: the whole game crammed onto cpu7. With 70 % it is
 * the six non-efficiency cores; a 4+4 part keeps its four big cores; an all-big part keeps all.
 *
 * Ratings come from per-core `cpuinfo_max_freq` (the node [CPUStatus.getMaxClockSpeed] and the HUD
 * read); when a core has no readable frequency, the scheduler's `cpu_capacity` is used for every core
 * instead (the two units never mix in one comparison).
 *
 * The rating→indices math is a pure function so it is unit-tested on the JVM; only [readCoreRatings],
 * [detectBigCoreCpuList] and [describeBigCores] touch the device.
 */
object CpuTopology {

    /** A core is in the performance tier at or above this share of the fastest core's rating. */
    const val PERFORMANCE_TIER_PERCENT = 70

    /**
     * Indices of the performance-tier cores, given each core's rating (max frequency in kHz or MHz, or
     * cpu_capacity — only relative size matters). A core whose rating is unknown (≤ 0) is kept: leaving
     * out a core we know nothing about could leave out a big one. Empty list when no rating is positive
     * (unreadable topology), so callers keep the full affinity set.
     */
    fun bigCoreIndices(ratings: IntArray): List<Int> {
        val peak = ratings.filter { it > 0 }.maxOrNull() ?: return emptyList()
        return ratings.indices.filter {
            ratings[it] <= 0 || ratings[it].toLong() * 100L >= peak.toLong() * PERFORMANCE_TIER_PERCENT
        }
    }

    /**
     * The performance tier as a comma-separated cpuList string (e.g. "2,3,4,5,6,7"), matching the format
     * [com.winlator.star.core.ProcessHelper.getAffinityMask] and the `.container` cpuList consume.
     * Empty string when detection fails (caller keeps the existing cpuList).
     */
    fun bigCoreCpuList(ratings: IntArray): String =
        bigCoreIndices(ratings).joinToString(",")

    /**
     * Each core's rating: max frequency in MHz, or — when any core's frequency is unreadable — every
     * core's cpu_capacity, if that covers more cores. Empty when there are no cores to ask about.
     */
    fun readCoreRatings(): IntArray = readRatings().first

    /** The ratings and what they are ("max MHz" or "cpu_capacity"). */
    private fun readRatings(): Pair<IntArray, String> {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(0, 32)
        if (n <= 0) return Pair(IntArray(0), "max MHz")
        // getMaxClockSpeed returns MHz (short); 0 when the node is unreadable.
        val freqs = IntArray(n) { i -> CPUStatus.getMaxClockSpeed(i).toInt() }
        if (freqs.all { it > 0 }) return Pair(freqs, "max MHz")
        val capacities = IntArray(n) { i -> FileUtils.readInt("/sys/devices/system/cpu/cpu$i/cpu_capacity") }
        return if (capacities.count { it > 0 } > freqs.count { it > 0 }) Pair(capacities, "cpu_capacity")
               else Pair(freqs, "max MHz")
    }

    /** Read the ratings from sysfs and return the performance-tier cpuList, or "" on failure. */
    fun detectBigCoreCpuList(): String = bigCoreCpuList(readCoreRatings())

    /**
     * One line for the logs: which cores were picked and from what, e.g.
     * "cores 2,3,4,5,6,7 of 8 (max MHz per core: 2265 2265 3148 3148 3148 2956 2956 3302; tier = 70% of
     * the 3302 peak or more)".
     */
    fun describeBigCores(): String {
        val (ratings, unit) = readRatings()
        val list = bigCoreCpuList(ratings)
        val peak = ratings.filter { it > 0 }.maxOrNull() ?: 0
        return (if (list.isEmpty()) "no core ratings readable, affinity left as configured" else "cores $list of ${ratings.size}") +
            " ($unit per core: ${ratings.joinToString(" ")}; tier = $PERFORMANCE_TIER_PERCENT% of the $peak peak or more)"
    }
}
