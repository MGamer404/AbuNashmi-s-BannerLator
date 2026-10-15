package com.winlator.star.components

import com.winlator.star.components.offline.WineRegistryFile
import com.winlator.star.container.Container
import java.io.File

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
