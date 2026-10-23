package com.winlator.star.linux

import android.content.Context
import android.os.SystemClock
import com.winlator.star.R
import com.winlator.star.core.PreloaderState
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * What the Linux session's loading screen says, read out of the session log, plus a clock so a
 * user can see time passing even when the log is quiet. The runtime script marks its milestones
 * with "== STEP"; the Steam client's own bootstrap does not, but it prints its update's download
 * progress, and lifting that out is the difference between "starting the Steam client" for three
 * minutes and a percentage that moves. Same design as the SteamDeck app's loading panel.
 *
 * Feeds [PreloaderState.linuxProgress]; the overlay itself is closed by the compositor's
 * first-frame hook, never from here.
 */
class LinuxLoadingState(context: Context) {
    private val hints = context.resources.getStringArray(R.array.linux_loading_hints)
    private var startedAt = SystemClock.elapsedRealtime()
    private var step = "Starting the session…"
    private var percent = -1

    /** The newest milestone read from the log (whatever the overlay is doing). */
    fun step(): String = step

    /** A client restart within the session: the clock starts over with the screen. */
    fun restartClock() { startedAt = SystemClock.elapsedRealtime() }

    /** Re-reads the end of the log and pushes the current line, bar, clock and hint. */
    fun update(log: File?) {
        read(log)?.let { step = it.first; percent = it.second }
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        val elapsed = String.format(java.util.Locale.US, "%d:%02d elapsed · still working", seconds / 60, seconds % 60)
        val hint = if (hints.isEmpty()) null else hints[((seconds / 8) % hints.size).toInt()]
        PreloaderState.linuxProgress(step, percent, elapsed, hint)
    }

    /** Only the tail is read: the client alone writes megabytes an hour. */
    private fun read(log: File?): Pair<String, Int>? {
        if (log == null || !log.isFile) return null
        val text = try {
            RandomAccessFile(log, "r").use { file ->
                val length = file.length()
                val want = minOf(length, TAIL_BYTES)
                file.seek(length - want)
                val bytes = ByteArray(want.toInt())
                file.readFully(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            return null
        }
        var stepAt = -1
        var stepText: String? = null
        var downloadAt = -1
        var downloadPercent = -1
        var clientDownloadAt = -1
        var clientDownload: String? = null
        var clientPercent = -1
        var offset = 0
        for (line in text.split('\n')) {
            val at = offset
            offset += line.length + 1
            val marker = line.indexOf("== STEP ")
            if (marker >= 0) {
                stepAt = at
                // drop the marker and its HH:MM:SS
                stepText = line.substring(marker + 8).trim().substringAfter(' ').trim()
                val m = INSTALL_COUNT.find(line)
                if (m != null) {
                    clientDownloadAt = at
                    clientDownload = m.groupValues[1]
                    clientPercent = m.groupValues[2].toInt() * 100 / maxOf(1, m.groupValues[3].toInt())
                }
            } else {
                val m = UPDATE_PROGRESS.find(line)
                if (m != null) {
                    downloadAt = at
                    val done = m.groupValues[1].toLong()
                    val total = maxOf(1L, m.groupValues[2].toLong())
                    downloadPercent = (done * 100 / total).toInt()
                }
            }
        }
        return when {
            downloadAt > stepAt && downloadPercent >= 0 ->
                Pair("Downloading the Steam client update · $downloadPercent%", downloadPercent)
            clientDownloadAt == stepAt && clientDownload != null ->
                Pair("Downloading the Steam client · $clientDownload", clientPercent)
            !stepText.isNullOrEmpty() -> Pair(stepText, -1)
            else -> null
        }
    }

    companion object {
        private const val TAIL_BYTES = 48L * 1024
        private val UPDATE_PROGRESS = Regex("""Downloading update \((\d+) of (\d+) KB\)""")
        private val INSTALL_COUNT = Regex("""downloading Steam: (\S+) \((\d+)/(\d+)\)""")
    }
}
