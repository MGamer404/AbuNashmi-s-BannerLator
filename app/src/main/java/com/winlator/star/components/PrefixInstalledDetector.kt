package com.winlator.star.components

import com.winlator.star.components.offline.WineRegistryFile
import com.winlator.star.container.Container
import java.io.File
import java.io.RandomAccessFile

/**
 * Detects which catalog components are *actually* present in a container's Wine prefix, for
 * components that Wine/Proton do NOT ship as builtins — so their footprint is a real install signal,
 * not baseline. This backfills installed-state for components installed before we recorded installs
 * (or installed by hand), which the [component installs record][ComponentsSheet] alone can't know.
 *
 * IMPORTANT: only components with a marker that is ABSENT from a fresh container are listed. Verified
 * against a pristine Proton-11 prefix (2026-07-23): OpenAL32.dll / windows\mono / GFWL registry /
 * PhysX are absent from a fresh prefix (→ detectable), whereas gecko, msvcp140/vcruntime, xactengine,
 * d3dx9, d3dcompiler and the .NET NDP registry keys ship baseline (→ NOT distinguishable, omitted).
 *
 * Pure filesystem/registry reads, best-effort (never throws). Run off the main thread.
 */
object PrefixInstalledDetector {

    fun detect(container: Container): Set<String> {
        return try {
            val wine = File(container.rootDir, ".wine")
            if (!wine.isDirectory) return emptySet()
            val out = HashSet<String>()
            if (File(wine, "drive_c/windows/system32/OpenAL32.dll").isFile) out += "oalinst"
            if (File(wine, "drive_c/windows/mono").isDirectory) out += "mono"
            if (hasPhysX(wine)) out += "physx"
            if (registryContains(wine, "Games for Windows")) out += "XLiveRedist"
            out
        } catch (_: Throwable) {
            emptySet()
        }
    }

    // Visual C++ 2015-2022: the catalog's vcredist2015/2019/2022 (and their _dll copies) and GOG's
    // MSVC20xx redists. Wine ships its own msvcp140/vcruntime140, so only Microsoft's vcruntime140.dll
    // next to mfc140u.dll (which Wine does not have) shows the runtime is really there.
    private val VC14 = Regex("""^(?:vcredist(?:2015|2019|2022)(?:_dll)?|GOG:MSVC20(?:15|17|19|22)(_x64)?)$""")
    private val WINE_DLL_MARKS = listOf("Wine builtin DLL", "Wine placeholder DLL")

    /**
     * For a Visual C++ 2015-2022 record [name]: the file the prefix is missing (e.g.
     * "system32/mfc140u.dll"), or null when the files are there or [name] is not one of those.
     */
    fun vcRuntimeMissing(container: Container, name: String): String? {
        val m = VC14.matchEntire(name) ?: return null
        val windows = File(container.rootDir, ".wine/drive_c/windows")
        if (!windows.isDirectory) return null
        val wow64 = File(windows, "syswow64").isDirectory
        val dirs = when {
            !name.startsWith("GOG:") -> listOfNotNull("system32", "syswow64".takeIf { wow64 })
            m.groupValues[1].isNotEmpty() -> listOf("system32")
            else -> listOf(if (wow64) "syswow64" else "system32")
        }
        for (d in dirs) {
            if (!isVendorDll(File(windows, "$d/vcruntime140.dll"))) return "$d/vcruntime140.dll"
            if (!File(windows, "$d/mfc140u.dll").isFile) return "$d/mfc140u.dll"
        }
        return null
    }

    /** A DLL that is not one of Wine's own (those carry a mark right after the DOS header). */
    private fun isVendorDll(f: File): Boolean = try {
        f.isFile && f.length() > 0x60 && RandomAccessFile(f, "r").use { raf ->
            val b = ByteArray(0x20)
            raf.seek(0x40)
            raf.readFully(b)
            val s = String(b, Charsets.ISO_8859_1)
            WINE_DLL_MARKS.none { s.startsWith(it) }
        }
    } catch (_: Exception) {
        false
    }

    // The Uninstall entries a detected component's own installer registers, by the start of their
    // display name. Each of these products is a Windows Installer package.
    private val PRODUCTS = mapOf(
        "physx" to listOf("NVIDIA PhysX"),
        "XLiveRedist" to listOf("Microsoft Games for Windows"),
        "mono" to listOf("Wine Mono"),
        "oalinst" to listOf("OpenAL"),
    )
    private val UNINSTALL_KEY = Regex("""(?i)^Software\\(?:Wow6432Node\\)?Microsoft\\Windows\\CurrentVersion\\Uninstall\\(\{[0-9A-F-]{36}\})$""")

    /**
     * For [name] when it was installed before Bannerlator recorded what a component changes: the
     * Windows Installer products in the prefix that are its own (display name to product code), whose
     * uninstall msiexec can run. Empty when there is none, or for a component not listed above -
     * nothing is guessed from a name alone.
     */
    fun msiProducts(container: Container, name: String): List<Pair<String, String>> {
        val wanted = PRODUCTS[name] ?: return emptyList()
        return try {
            val system = File(container.rootDir, ".wine/system.reg")
            if (!system.isFile) return emptyList()
            val byKey = HashMap<String, HashMap<String, String>>()
            for (v in WineRegistryFile(system).values()) {
                val code = UNINSTALL_KEY.find(v[0])?.groupValues?.get(1) ?: continue
                byKey.getOrPut(code.uppercase()) { HashMap() }[v[1]] = v[2]
            }
            byKey.mapNotNull { (code, values) ->
                val shown = values["displayname"]?.let { WineRegistryFile.stringOf(it) } ?: return@mapNotNull null
                val msi = values["windowsinstaller"]?.endsWith("dword:00000001") == true ||
                    values["uninstallstring"]?.let { WineRegistryFile.stringOf(it) }?.contains("msiexec", ignoreCase = true) == true
                if (msi && wanted.any { shown.startsWith(it, ignoreCase = true) }) shown to code else null
            }.distinctBy { it.second }.sortedBy { it.first }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun hasPhysX(wine: File): Boolean {
        for (pf in arrayOf("drive_c/Program Files", "drive_c/Program Files (x86)")) {
            val base = File(wine, pf)
            if (File(base, "NVIDIA Corporation/PhysX").isDirectory) return true
            base.listFiles()?.forEach { if (it.isDirectory && it.name.contains("PhysX", ignoreCase = true)) return true }
        }
        return false
    }

    /** True if [needle] appears in system.reg / user.reg (bounded, streamed, case-insensitive). */
    private fun registryContains(wine: File, needle: String): Boolean =
        arrayOf("system.reg", "user.reg").any { name ->
            val f = File(wine, name)
            try {
                f.isFile && f.length() < 32L * 1024 * 1024 &&
                    f.bufferedReader().use { r -> r.lineSequence().any { it.contains(needle, ignoreCase = true) } }
            } catch (_: Throwable) {
                false
            }
        }
}
