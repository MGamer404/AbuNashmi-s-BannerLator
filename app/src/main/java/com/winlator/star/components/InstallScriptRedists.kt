package com.winlator.star.components

import com.winlator.star.core.VdfParser
import com.winlator.star.core.VdfParser.VdfNode
import java.io.File
import java.util.Locale

/**
 * What a game's Steam install script installs: the "Run Process" steps of every install script in
 * its folder (installscript.vdf, a repack's install.vdf, the per-redist scripts under Steam's
 * `_CommonRedist`), each process mapped to catalog components. A DirectX setup is read by the cabs
 * beside it, so only the pieces the game's own DirectX folder carries are named. The script also
 * names the game's Steam app (`HasRunKey ...\Valve\Steam\Apps\<appid>`).
 *
 * Pure filesystem, bounded (a few directory listings, scripts under 512 KB); never throws.
 */
object InstallScriptRedists {
    /** One installer the script runs: the [component] it installs and what it is ([detail], for the reason line). */
    class Hit(val component: String, val detail: String)

    /** [hits] in script order; [appIds] the Steam apps the script names. */
    class Result(val hits: List<Hit>, val appIds: List<Int>) {
        companion object { val EMPTY = Result(emptyList(), emptyList()) }
    }

    private const val MAX_SCRIPT = 512L * 1024
    private const val MAX_SCRIPTS = 24
    private val APP_KEY = Regex("""(?i)Valve\\{1,2}Steam\\{1,2}Apps\\{1,2}(\d{2,9})(?!\d)""")
    private val INSTALLDIR = Regex("""(?i)%INSTALLDIR%""")

    /**
     * The install scripts at [root]: its top-level *.vdf, those one folder down, and every one under
     * `_CommonRedist`. Only files that are install scripts count.
     */
    fun scripts(root: File): List<File> {
        val out = ArrayList<File>()
        fun vdfsIn(dir: File) = dir.listFiles { f -> f.isFile && f.name.endsWith(".vdf", true) && f.length() in 1..MAX_SCRIPT }.orEmpty()
        fun walkRedist(dir: File, depth: Int) {
            if (depth > 4 || out.size >= MAX_SCRIPTS) return
            for (f in dir.listFiles().orEmpty()) {
                if (f.isDirectory) walkRedist(f, depth + 1)
                else if (f.name.endsWith(".vdf", true) && f.length() in 1..MAX_SCRIPT) out += f
            }
        }
        out += vdfsIn(root)
        for (d in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            if (out.size >= MAX_SCRIPTS) break
            if (d.name.equals("_CommonRedist", true)) walkRedist(d, 0) else out += vdfsIn(d)
        }
        return out.take(MAX_SCRIPTS)
    }

    /** What the install scripts at [root] run. */
    fun scan(root: File): Result = try {
        val hits = ArrayList<Hit>()
        val appIds = LinkedHashSet<Int>()
        for (vdf in scripts(root)) {
            val text = runCatching { vdf.readText() }.getOrNull() ?: continue
            if (!text.contains("InstallScript", true) && !text.contains("Run Process", true)) continue
            APP_KEY.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.forEach { appIds += it }
            // %INSTALLDIR% is the game's folder; a script under _CommonRedist still means the game's.
            val installDir = commonRedistOwner(vdf) ?: root
            val doc = runCatching { VdfParser.parse(text) }.getOrNull() ?: continue
            val script = doc.node("InstallScript") ?: doc
            for (run in script.nodes("Run Process")) {
                for ((_, step) in run.childNodes()) {
                    for (process in processes(step)) hits += hitsFor(installDir, process)
                }
            }
        }
        Result(hits.distinctBy { it.component }, appIds.toList())
    } catch (_: Throwable) {
        Result.EMPTY
    }

    /** Every "process N" of a step (a step can run several, e.g. the x86 and x64 VC++ setups). */
    private fun processes(step: VdfNode): List<String> =
        step.stringEntries().filter { (k, _) -> k.replace(" ", "").startsWith("process", true) }.map { it.second }.filter { it.isNotBlank() }

    /** The game folder above a script that sits in its `_CommonRedist`, or null. */
    private fun commonRedistOwner(vdf: File): File? {
        var d: File? = vdf.parentFile
        while (d != null) {
            if (d.name.equals("_CommonRedist", true)) return d.parentFile
            d = d.parentFile
        }
        return null
    }

    /** The components one process installs; a DirectX setup by the cabs in its folder. */
    private fun hitsFor(installDir: File, process: String): List<Hit> {
        val rel = INSTALLDIR.replace(process.trim().trim('"'), "").replace('\\', '/').trimStart('/')
        val name = rel.substringAfterLast('/')
        // Under the game's folder (%INSTALLDIR% or relative); another token (%WinDir%) or a drive is not ours to read.
        val file = if (rel.startsWith("%") || Regex("^[A-Za-z]:").containsMatchIn(rel)) null else resolve(installDir, rel)
        if (name.equals("DXSETUP.exe", true)) {
            val cabs = file?.parentFile?.listFiles { f -> f.isFile && f.name.endsWith(".cab", true) }.orEmpty()
            val fromCabs = cabs.sortedBy { it.name.lowercase(Locale.US) }
                .flatMap { cab -> cabComponents(cab.name).map { Hit(it, "DirectX setup: ${cab.name}") } }
                .distinctBy { it.component }
            // A DirectX setup with no cabs to read: the June 2010 runtime (what Steam's DirectX depot installs).
            return fromCabs.ifEmpty { if (cabs.isEmpty()) listOf(Hit("d3dx9", "DirectX setup")) else emptyList() }
        }
        return DependencyDetector.componentsFor(rel).ifEmpty { ueOrNone(rel) }.map { Hit(it, name) }
    }

    /** Unreal's prerequisite setup (UE4PrereqSetup_x64.exe): VC++ 2015-2022 and DirectX June 2010. */
    private fun ueOrNone(path: String): List<String> =
        if (Regex("""(?i)ue\d?prereqsetup""").containsMatchIn(path)) listOf("vcredist2022", "d3dx9") else emptyList()

    /**
     * A DirectX redist cab (Jun2010_d3dx11_43_x86.cab) to the catalog components it carries.
     * XInput is never named: the app ships its own xinput for controller support, and a native one
     * would take its place. d3dx10 has no catalog component (Wine's own is used).
     */
    fun cabComponents(cab: String): List<String> {
        val n = cab.lowercase(Locale.US)
        val x64 = n.contains("_x64")
        return when {
            n.contains("xinput") -> emptyList()
            n.contains("d3dx9_") -> listOf("d3dx9")
            n.contains("d3dx11_") -> listOf("d3dx11")
            n.contains("d3dcompiler_43") -> listOf("d3dcompiler_43")
            n.contains("d3dcompiler_42") -> listOf("d3dcompiler_42")
            n.contains("_xact_") || n.contains("x3daudio") -> listOf(if (x64) "xact_x64" else "xact")
            // June 2010's XAudio cab is XAudio 2.7; older ones ship with the XACT runtime.
            n.contains("_xaudio_") -> listOf(if (n.startsWith("jun2010")) "xaudio2.7" else if (x64) "xact_x64" else "xact")
            else -> emptyList()
        }
    }

    /** [rel] under [dir], matching each part's case as the disk spells it; null when it isn't there. */
    private fun resolve(dir: File, rel: String): File? {
        var cur = dir
        for (part in rel.split('/').filter { it.isNotEmpty() && it != "." }) {
            val exact = File(cur, part)
            cur = if (exact.exists()) exact else cur.listFiles()?.firstOrNull { it.name.equals(part, true) } ?: return null
        }
        return cur.takeIf { it.isFile }
    }
}
