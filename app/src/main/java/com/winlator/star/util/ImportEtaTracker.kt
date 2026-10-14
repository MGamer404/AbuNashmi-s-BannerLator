package com.winlator.star.util

/**
 * EMA-smoothed ETA for byte-progress operations (large asset imports). Mirrors the smoothing the
 * Steam depot downloader uses (sample at most once/sec, 0.6/0.4 EMA) so import ETAs read the same as
 * download ETAs. Speed is computed internally only to derive the ETA — it is not surfaced.
 *
 * Format the returned [etaSeconds] with [com.winlator.star.store.download.formatEta].
 */
class ImportEtaTracker {
    private var smoothedBps = 0.0
    private var lastMs = 0L
    private var lastBytes = 0L
    private var started = false

    data class Progress(val pct: Int, val etaSeconds: Long)

    /** Feed cumulative [bytesRead] of [total]; returns the current percent + smoothed ETA (−1 = unknown). */
    @Synchronized
    fun update(bytesRead: Long, total: Long): Progress {
        val now = System.currentTimeMillis()
        if (!started) {
            started = true
            lastMs = now
            lastBytes = bytesRead
        } else {
            val dtMs = now - lastMs
            if (dtMs >= 1000L) {
                val delta = bytesRead - lastBytes
                if (delta > 0L) {
                    val inst = delta * 1000.0 / dtMs
                    smoothedBps = if (smoothedBps <= 0.0) inst else 0.6 * smoothedBps + 0.4 * inst
                }
                lastMs = now
                lastBytes = bytesRead
            }
        }
        val pct = if (total > 0L) ((bytesRead * 100) / total).toInt().coerceIn(0, 100) else 0
        val eta = if (smoothedBps > 0.0 && total > bytesRead) ((total - bytesRead) / smoothedBps).toLong() else -1L
        return Progress(pct, eta)
    }
}
