package com.winlator.star.store

import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreDownloadHooks
import com.winlator.star.store.download.formatDownloadSize
import com.winlator.star.store.download.formatDownloadSpeed
import com.winlator.star.store.download.formatEta
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feeds one GOG base-game download into the Download Manager registry. [GogDownloadManager] calls
 * `onBytes` right before the matching `onProgress`; every registry tick carries the last byte pair,
 * speed and time left (a pct-only tick would reset them to unknown). [percent] is the share of the
 * game's bytes on disk, the same number the Download Manager card and notification show: 0 during
 * the setup steps, capped at 99 until the install completes, never going back.
 */
internal class GogDownloadTicker(private val gameId: String) {
    @Volatile var done = 0L
        private set
    @Volatile var total = 0L
        private set
    @Volatile var speedBps = 0L
        private set
    @Volatile var etaSeconds = -1L
        private set
    private val pct = AtomicInteger(0)

    val percent: Int get() = pct.get()

    fun onBytes(done: Long, total: Long, speedBps: Long, etaSeconds: Long) {
        this.done = done
        this.total = total
        this.speedBps = speedBps
        this.etaSeconds = etaSeconds
        if (total > 0) {
            val p = (minOf(done, total) * 100 / total).toInt().coerceAtMost(99)
            pct.accumulateAndGet(p) { a, b -> maxOf(a, b) }
        }
    }

    fun tick() = StoreDownloadHooks.tick(
        Store.GOG, gameId, percent,
        installDone = done, installTotal = total,
        speedBps = speedBps, etaSeconds = etaSeconds,
    )

    fun label(): String = gogProgressLabel(percent, done, total, speedBps, etaSeconds)
}

/** "44%  ·  37.5 GB to go  ·  65.3 MB/s  ·  ~9 min left" (same layout as the Xbox detail page). */
internal fun gogProgressLabel(pct: Int, done: Long, total: Long, speedBps: Long, etaSeconds: Long): String =
    listOf(
        if (total > 0) "$pct%  ·  ${formatDownloadSize((total - done).coerceAtLeast(0))} to go" else "Downloading… $pct%",
        formatDownloadSpeed(speedBps), formatEta(etaSeconds),
    ).filter { it.isNotEmpty() }.joinToString("  ·  ")

internal fun gogProgressLabel(e: DownloadEntry): String =
    gogProgressLabel(e.pct, e.installDone, e.installTotal, e.speedBps, e.etaSeconds)
