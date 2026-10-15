package com.winlator.star.linux

import android.util.Log
import com.winlator.star.store.LibraryTypeFilter
import com.winlator.star.store.SteamGame
import com.winlator.star.store.SteamRepository
import java.io.File

/**
 * Every Steam game the store's account owns, written for the runtime's compatibility-tool
 * registrar as one app id per line in `~/.bl-owned-apps`, before a Linux session starts.
 *
 * Valve names a Proton of its own for thousands of titles at a priority above the client's
 * default tool. A title mapped to the ARM64 tool only once it was installed - which is what the
 * registrar did - had the client fetch an x86-64 Proton, its runtime and FEX first, gigabytes that
 * cannot run here, and the game then failed to start. Mapped before install, the client fetches
 * the ARM64 tool it can use. The set is the library's own: games and demos, the same list the
 * Library tab shows, so nothing the user cannot see is touched. (After WinNative 8874cfc7.)
 */
object LinuxOwnedApps {
    private const val TAG = "LinuxOwnedApps"
    const val FILE = ".bl-owned-apps"

    @JvmStatic
    fun write(guestHome: File) {
        try {
            val repo = SteamRepository.getInstance() ?: run {
                Log.i(TAG, "no Steam library loaded; owned list not written")
                return
            }
            val ids = repo.getCachedGameRows()
                .map { SteamGame.fromGameRow(it) }
                .filter { LibraryTypeFilter.ALL.accepts(it) }
                .map { it.appId }
                .filter { it > 0 }
                .distinct()
                .sorted()
            if (!guestHome.isDirectory && !guestHome.mkdirs()) return
            File(guestHome, FILE).writeText(ids.joinToString("\n", postfix = if (ids.isEmpty()) "" else "\n"))
            Log.i(TAG, "${ids.size} owned app(s) written for the compatibility-tool registrar")
        } catch (e: Exception) {
            // A missing list only means the registrar maps installed titles, as before.
            Log.w(TAG, "owned list not written: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
