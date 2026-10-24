package com.winlator.star.store

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Workshop diagnostics: logcat (tag "BL_WORKSHOP") and a small rolling file,
 * Download/Bannerlator/workshop_diag.txt, to hand over when something fails. Item ids, step names,
 * Steam result codes and paths inside the game's own folders go here; never account names or tokens.
 */
object WorkshopDiag {
    private const val TAG = "BL_WORKSHOP"
    private const val MAX_BYTES = 512 * 1024L
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun file(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bannerlator/workshop_diag.txt")

    @Synchronized fun log(message: String, error: Throwable? = null) {
        // The shared scrubber is the backstop (account names, emails, tokens).
        val line = SteamLogRedactor.redact(message)
        if (error != null) Log.w(TAG, line, error) else Log.i(TAG, line)
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) {
                val old = File(f.path + ".1"); old.delete(); f.renameTo(old)
            }
            val tail = if (error != null) " [${error.javaClass.simpleName}: ${error.message}]" else ""
            f.appendText("${time.format(Date())} [${android.os.Process.myPid()}] $line$tail\n")
        }
    }
}
