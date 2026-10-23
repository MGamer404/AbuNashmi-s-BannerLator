package com.winlator.star.ui

import java.io.File

// Read-only runtime-backend diagnostic shown as a chip in the Graphics tab header.
// Nothing here touches the emulation runtime — it only reports what is already loaded.

// unixlib = FEX's native `.so` unixlib actually dlopen'd into the guest process; DLL = the
// self-contained FEX DLL path (no native unixlib); NA = not resolvable yet / not applicable.
enum class FexMode { UNIXLIB, DLL, NA }

// arch     : "arm64ec" / "x86-64"
// translator: "FEXCore" / "wowbox64" / "Box64"
// fexMode  : only meaningful for the FEX translators; Box64 never uses the FEX unixlib.
data class RuntimeBackend(
    val arch: String = "",
    val translator: String = "",
    val fexMode: FexMode = FexMode.NA
) {
    // Blank until the activity seeds arch+translator (known immediately at launch).
    val isValid: Boolean get() = arch.isNotEmpty() && translator.isNotEmpty()

    // Box64/x86-64 has no FEX unixlib, so the third segment is hidden for it.
    val showsFexMode: Boolean get() = translator != "Box64"
}

// Probes /proc/<pid>/maps for the FEX unixlib. The game process is the app's OWN child (same
// UID), so its maps are readable directly — no root, no bridge. The `.so` only appears in maps
// if FEX's dlopen actually succeeded, so the result cannot be faked. It maps a couple of seconds
// AFTER the guest is up, hence the caller polls.
object FexProbe {

    // @JvmStatic so XServerDisplayActivity (Java) can call these directly.
    @JvmStatic
    fun fexModeFor(pid: Int): FexMode {
        if (pid <= 0) return FexMode.NA
        val maps = File("/proc/$pid/maps")
        if (!maps.canRead()) return FexMode.NA
        return try {
            maps.useLines { lines ->
                var sawDll = false
                for (line in lines) {
                    if (line.contains("libarm64ecfex.so") || line.contains("libwow64fex.so"))
                        return@useLines FexMode.UNIXLIB
                    if (line.contains("libarm64ecfex.dll") || line.contains("libwow64fex.dll"))
                        sawDll = true
                }
                if (sawDll) FexMode.DLL else FexMode.NA
            }
        } catch (e: Exception) {
            FexMode.NA
        }
    }

    // The FEX unixlib maps into the wine/guest process, which is a DESCENDANT of the launcher's
    // top-level pid — so the known pid alone may not carry the token. Prefer it, then fall back to
    // scanning same-UID /proc entries (only our emulator processes carry these tokens, and other
    // apps' maps aren't readable, so the scan is both cheap and unambiguous).
    @JvmStatic
    fun detect(hintPid: Int): FexMode {
        val direct = fexModeFor(hintPid)
        if (direct != FexMode.NA) return direct

        var sawDll = false
        val entries = File("/proc").listFiles { f ->
            f.isDirectory && f.name.all { it.isDigit() }
        } ?: return FexMode.NA
        for (p in entries) {
            when (fexModeFor(p.name.toInt())) {
                FexMode.UNIXLIB -> return FexMode.UNIXLIB
                FexMode.DLL -> sawDll = true
                FexMode.NA -> {}
            }
        }
        return if (sawDll) FexMode.DLL else FexMode.NA
    }
}
