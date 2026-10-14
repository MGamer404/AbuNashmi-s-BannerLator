package com.winlator.star.linux

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The Linux runtime's install/update state, shared by the Contents tab that drives it and the Games
 * tab's Steam card that mirrors it. A plain object rather than a ViewModel because both screens are
 * looking at one global thing — there is a single runtime, and only one install can run at a time.
 *
 * The percentage comes from the installer; the ETA is derived here. The installer reports a stage
 * and a percent, not bytes, so the estimate is progress-over-elapsed extrapolated to 100% — good
 * enough to tell someone whether to wait, which is the whole point of showing it.
 */
object LinuxRuntimeUpdate {

    data class State(
        val installed: String? = null,
        val available: String? = null,
        val sizeBytes: Long = 0L,
        val busy: Boolean = false,
        val stage: String = "",
        /** 0..100, or -1 when the work has no measurable progress (verify, extract). */
        val percent: Int = -1,
        /** Seconds remaining, or -1 when it cannot be estimated yet. */
        val etaSeconds: Long = -1,
    ) {
        /** An update is worth offering only when we know both versions and they differ. */
        val updateAvailable: Boolean
            get() = available != null && installed != null && installed != available
        val notInstalled: Boolean get() = installed == null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Elapsed-time origin for the ETA, reset whenever a run starts. */
    private var startedAtMs = 0L
    private var lastPercent = 0
    /** The build the catalog is offering, kept so either screen can start the install itself. */
    private var pending: LinuxRuntimeInstaller.Release? = null

    fun refreshInstalled(context: Context) {
        _state.value = _state.value.copy(installed = LinuxRuntimeInstaller.installedVersion(context))
    }

    fun setAvailable(release: LinuxRuntimeInstaller.Release?) {
        pending = release
        _state.value = _state.value.copy(available = release?.version, sizeBytes = release?.size ?: 0L)
    }

    /**
     * Runs the install for whatever the catalog last offered, driving [state] as it goes. Returns
     * false if there is nothing to install, one is already running, or it failed - the installer
     * leaves the existing runtime alone in that case. Safe to call from either screen; the busy
     * flag is what stops two of them overlapping.
     */
    suspend fun runInstall(context: Context): Boolean {
        val r = pending ?: return false
        if (_state.value.busy) return false
        begin()
        val ok = withContext(Dispatchers.IO) {
            LinuxRuntimeInstaller.install(context, r) { stage, pct -> progress(stage, pct) }
        }
        finish(context)
        return ok
    }

    fun begin() {
        startedAtMs = SystemClock.elapsedRealtime()
        lastPercent = 0
        _state.value = _state.value.copy(busy = true, stage = "Starting…", percent = -1, etaSeconds = -1)
    }

    fun progress(stage: String, percent: Int) {
        var eta = -1L
        // Only extrapolate once there is enough of a sample to be meaningful; below 2% the estimate
        // swings wildly and showing it is worse than showing nothing.
        if (percent in 2..99 && startedAtMs > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            if (elapsed > 0) eta = (elapsed * (100 - percent) / percent) / 1000L
        }
        lastPercent = percent
        _state.value = _state.value.copy(stage = stage, percent = percent, etaSeconds = eta)
    }

    fun finish(context: Context) {
        startedAtMs = 0L
        _state.value = _state.value.copy(
            busy = false, stage = "", percent = -1, etaSeconds = -1,
            installed = LinuxRuntimeInstaller.installedVersion(context),
        )
    }

    /** "45% · about 2 min left", or just the percentage when there is no usable estimate. */
    fun line(s: State): String {
        val pct = if (s.percent in 0..100) "${s.percent}%" else s.stage
        val eta = s.etaSeconds
        if (eta < 0) return if (s.percent in 0..100) "${s.stage} $pct" else pct
        val left = when {
            eta < 60 -> "${eta}s left"
            eta < 3600 -> "about ${(eta + 30) / 60} min left"
            else -> "over an hour left"
        }
        return "${s.stage} $pct · $left"
    }
}
