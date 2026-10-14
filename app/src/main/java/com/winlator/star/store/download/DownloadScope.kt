package com.winlator.star.store.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime coroutine scope for store downloads.
 *
 * Store detail Activities historically launched their download on `lifecycleScope`, which
 * cancels the moment the Activity is destroyed — so backgrounding the app or the OS
 * reclaiming the detail screen would kill an in-flight download. Downloads instead run on
 * this scope, which lives as long as the process. Paired with [DownloadForegroundService]
 * (which keeps the process itself alive with an ongoing notification), a download now
 * survives the detail page being closed or the app being backgrounded.
 *
 * [SupervisorJob] so one failed/cancelled download never tears down a sibling download.
 * This is intentionally NOT tied to any Activity — pass `applicationContext` (never an
 * Activity) into anything launched here.
 */
object DownloadScope {
    /** IO-dispatched, supervised, process-scoped. Use for the whole download coroutine. */
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
