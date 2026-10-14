package com.winlator.star.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the pure command / topology builders behind the root perf tier. */
class PerfBuildersTest {

    @Test fun writeCmd_usesPrintfAndQuotesBothArgs() {
        assertEquals(
            "printf %s 'performance' > '/sys/devices/system/cpu/cpu7/cpufreq/scaling_governor'",
            PerfCmd.writeCmd("/sys/devices/system/cpu/cpu7/cpufreq/scaling_governor", "performance")
        )
    }

    @Test fun writeCmd_escapesEmbeddedSingleQuote() {
        // A stray quote in a value must not break out of the quoting.
        assertEquals("printf %s 'a'\\''b' > '/x'", PerfCmd.writeCmd("/x", "a'b"))
    }

    @Test fun readCmd_isPlainCat() {
        assertEquals("cat '/sys/class/kgsl/kgsl-3d0/max_clock_mhz'",
            PerfCmd.readCmd("/sys/class/kgsl/kgsl-3d0/max_clock_mhz"))
    }

    @Test fun normalizeRead_stripsTrailingNewline() {
        assertEquals("schedutil", PerfCmd.normalizeRead("schedutil\n"))
    }

    @Test fun roundTrip_readThenWriteIsStable() {
        // Value captured from sysfs (with newline) must re-emit byte-identically without one.
        val captured = PerfCmd.normalizeRead("825000\n")
        assertEquals("printf %s '825000' > '/n'", PerfCmd.writeCmd("/n", captured))
    }

    // ── big-core detection ──────────────────────────────────────────────────────────────────────

    @Test fun bigCores_1plus3plus4_picksPrimeAndBig() {
        // SD8Gen-style: 1 prime (highest), 3 big, 4 little. 70% of 3200 = 2240: the 3 big cores are in
        // the performance tier with the prime one; the little ones are not (the old rule kept cpu7 only).
        val freqs = intArrayOf(1800, 1800, 1800, 1800, 2500, 2500, 2500, 3200)
        assertEquals(listOf(4, 5, 6, 7), CpuTopology.bigCoreIndices(freqs))
        assertEquals("4,5,6,7", CpuTopology.bigCoreCpuList(freqs))
    }

    @Test fun bigCores_8gen3_keepsSixNonEfficiencyCores() {
        // SM8650 (Pocket FIT): 2x A520 2265, 3x A720 3148, 2x A720 2956, 1x X4 3302. 70% of 3302 = 2311.4.
        val freqs = intArrayOf(2265, 2265, 3148, 3148, 3148, 2956, 2956, 3302)
        assertEquals("2,3,4,5,6,7", CpuTopology.bigCoreCpuList(freqs))
    }

    @Test fun bigCores_allBigPart_keepsEveryCore() {
        // 8 Elite-style: 6 performance + 2 prime, no efficiency cores. 70% of 4320 = 3024.
        val freqs = intArrayOf(3532, 3532, 3532, 3532, 3532, 3532, 4320, 4320)
        assertEquals("0,1,2,3,4,5,6,7", CpuTopology.bigCoreCpuList(freqs))
    }

    @Test fun bigCores_4plus4_picksAllFourBig() {
        val freqs = intArrayOf(1800, 1800, 1800, 1800, 2800, 2800, 2800, 2800)
        assertEquals("4,5,6,7", CpuTopology.bigCoreCpuList(freqs))
    }

    @Test fun bigCores_unknownCore_isKept() {
        // A core with no reading is kept rather than risk leaving out a big one.
        val freqs = intArrayOf(0, 1800, 3000, 3000)
        assertEquals(listOf(0, 2, 3), CpuTopology.bigCoreIndices(freqs))
    }

    @Test fun bigCores_unreadable_returnsEmpty() {
        assertTrue(CpuTopology.bigCoreIndices(intArrayOf(0, 0, 0, 0)).isEmpty())
        assertEquals("", CpuTopology.bigCoreCpuList(intArrayOf()))
    }
}
