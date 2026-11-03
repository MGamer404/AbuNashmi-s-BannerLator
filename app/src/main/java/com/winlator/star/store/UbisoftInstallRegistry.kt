package com.winlator.star.store

import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.core.WineRegistryEditor
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * Tells Ubisoft Connect that a game folder is an installed game, by writing the registry keys Connect itself writes
 * when the user picks "Locate installed game" on a game it offers to download (device-proven with Assassin's Creed
 * Syndicate from the Epic Games Store: after Locate the game launched, and the two keys below were all that Locate
 * itself changed in the prefix's `system.reg`). Without them Connect has no record of the install and offers to
 * download the game again. The product id and the language come from the `uplay_install.state` file in the game's
 * folder ([UbisoftInstallState]).
 *
 * What Locate wrote, with the Syndicate values (1875 is the state file's product id):
 *  - `Software\Wow6432Node\Ubisoft\Launcher\Installs\1875`: `InstallDir` = `Z:/epic_games/<game>/` (forward slashes,
 *    trailing slash), `Language` = `en-US`.
 *  - `Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Uplay Install 1875`: the game's "Add or
 *    remove programs" entry. Its `DisplayIcon` points at a hash-named `.ico` in Connect's `data` folder, which the
 *    state file does not give, so it is left out.
 *
 * The game's own `Software\Wow6432Node\Ubisoft\<game>` `Language` value is NOT part of Locate: it appeared about
 * twelve minutes later, written by Connect's first-launch prerequisite step, so it is not written here.
 *
 * Writing them is harmless while Connect is not installed yet: they are plain keys no one reads, and Connect's
 * installer only adds its own `Software\Wow6432Node\Ubisoft\Launcher` values, it does not clear `Installs`.
 */
object UbisoftInstallRegistry {
    private const val TAG = "UbisoftInstallRegistry"

    /** The install record Connect leaves in a game's folder. */
    const val STATE_FILE = "uplay_install.state"

    private const val LAUNCHER_KEY = "Software\\Wow6432Node\\Ubisoft\\Launcher\\Installs\\"
    private const val UNINSTALL_KEY = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Uplay Install "
    private const val UPC_EXE = "C:\\Program Files (x86)\\Ubisoft\\Ubisoft Game Launcher\\upc.exe"

    /** How a value's data is written and compared. */
    enum class Type {
        /** A string, compared as written. */
        STRING,

        /** A string holding a path: compared ignoring case, the slash direction and a trailing slash. */
        PATH,

        /** A DWORD, [RegWrite.data] is its decimal value. */
        DWORD,
    }

    /** One registry value to write into the HKLM hive: [key] below the root (`Software\...`), value [name], [type], [data]. */
    data class RegWrite(val key: String, val name: String, val type: Type, val data: String)

    /**
     * The Wine `Z:` form of [installDir] as Connect records it (`Z:/epic_games/<game>/`: forward slashes, trailing
     * slash), the same drive [com.winlator.star.store.steamscript.InstallScriptTokens] uses for `%INSTALLDIR%`:
     * `Z:` is the imagefs root [imageFsRoot]. Null when the folder is not below it (e.g. a game on an SD card).
     */
    @JvmStatic
    fun zPathOf(installDir: File, imageFsRoot: File): String? {
        zPathBelow(installDir.absolutePath, imageFsRoot.absolutePath)?.let { return it }
        // A symlinked app directory: compare the real locations too.
        return try {
            zPathBelow(installDir.canonicalPath, imageFsRoot.canonicalPath)
        } catch (e: Exception) {
            null
        }
    }

    private fun zPathBelow(dir: String, root: String): String? {
        val r = root.replace('\\', '/').trimEnd('/')
        val d = dir.replace('\\', '/').trimEnd('/')
        if (!d.startsWith("$r/")) return null
        return "Z:/" + d.substring(r.length + 1) + "/"
    }

    /**
     * Every write Locate does for [state], [installDirZ] being [zPathOf] of the game folder (see the class
     * comment). Pure. Values with a control character are left out: the file comes from a game folder and a line
     * break in a value would corrupt the `.reg` text.
     */
    @JvmStatic
    fun installWrites(state: UbisoftInstallState, installDirZ: String): List<RegWrite> {
        val id = state.productId.toString()
        val out = ArrayList<RegWrite>()
        out += RegWrite(LAUNCHER_KEY + id, "InstallDir", Type.PATH, installDirZ)
        out += RegWrite(LAUNCHER_KEY + id, "Language", Type.STRING, state.language)

        val uninstall = UNINSTALL_KEY + id
        val title = state.name.ifBlank { state.displayName }
        if (title.isNotBlank()) out += RegWrite(uninstall, "DisplayName", Type.STRING, title)
        out += RegWrite(uninstall, "DisplayVersion", Type.STRING, "")
        out += RegWrite(uninstall, "HelpLink", Type.STRING, "https://support.ubi.com")
        out += RegWrite(uninstall, "InstallLocation", Type.PATH, installDirZ)
        out += RegWrite(uninstall, "NoModify", Type.DWORD, "1")
        out += RegWrite(uninstall, "NoRepair", Type.DWORD, "1")
        out += RegWrite(uninstall, "Publisher", Type.STRING, "Ubisoft")
        out += RegWrite(uninstall, "UninstallString", Type.STRING, "\"$UPC_EXE\" uplay://uninstall/$id")
        out += RegWrite(uninstall, "URLInfoAbout", Type.STRING, "http://www.ubisoft.com/")
        return out.filter { w -> listOf(w.key, w.name, w.data).none { s -> s.any { it < ' ' } } }.distinct()
    }

    // ---- Reading the hive -----------------------------------------------------------------------

    /** The way [WineRegistryEditor] spells a key or value name inside a `.reg` file. */
    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** [w]'s data as it stands in a `.reg` file. */
    private fun rawData(w: RegWrite): String = when (w.type) {
        Type.DWORD -> "dword:" + String.format(java.util.Locale.ENGLISH, "%08x", w.data.toIntOrNull() ?: 0)
        else -> "\"" + escape(w.data) + "\""
    }

    private fun normalizedPath(raw: String): String =
        raw.trim('"').replace("\\\\", "/").replace('\\', '/').trimEnd('/').lowercase()

    private fun sameData(w: RegWrite, actual: String): Boolean = when (w.type) {
        Type.PATH -> normalizedPath(actual) == normalizedPath(rawData(w))
        Type.DWORD -> actual.equals(rawData(w), ignoreCase = true)
        Type.STRING -> actual == rawData(w)
    }

    /**
     * The [writes] a hive's text does not have yet (the key missing, the value missing, or another value), from ONE
     * pass over [reader] that stops as soon as every write is found, so the common case (everything is there) costs a
     * read of the hive and no write. Keys and value names compare ignoring case, like the registry. A `system.reg`
     * is about 4 MB, so this is a streamed read of its lines, nothing is kept.
     */
    @JvmStatic
    fun pendingWrites(reader: BufferedReader, writes: List<RegWrite>): List<RegWrite> {
        if (writes.isEmpty()) return emptyList()
        val remaining = ArrayList(writes)
        val byHeader = HashMap<String, MutableList<RegWrite>>()
        for (w in writes) byHeader.getOrPut("[" + escape(w.key).lowercase() + "]") { ArrayList() }.add(w)
        var current: List<RegWrite>? = null
        while (remaining.isNotEmpty()) {
            val line = reader.readLine() ?: break
            if (line.startsWith("[")) {
                // "[key] <timestamp>": the closing bracket is the last one on the line.
                val end = line.lastIndexOf(']')
                current = if (end > 0) byHeader[line.substring(0, end + 1).lowercase()] else null
            } else if (current != null && line.startsWith("\"")) {
                for (w in current) {
                    val prefix = "\"" + escape(w.name) + "\"="
                    if (line.startsWith(prefix, ignoreCase = true) && sameData(w, line.substring(prefix.length))) remaining.remove(w)
                }
            }
        }
        return remaining
    }

    /** [pendingWrites] over a hive's text (tests). */
    @JvmStatic
    fun pendingWrites(hive: String, writes: List<RegWrite>): List<RegWrite> =
        pendingWrites(BufferedReader(StringReader(hive)), writes)

    // ---- Registering ----------------------------------------------------------------------------

    /** How a [register] call ended, see [Outcome]. */
    enum class Result {
        /** The keys were written (the detail says how many values). */
        REGISTERED,

        /** Every value was already in the hive: nothing written. */
        ALREADY_PRESENT,

        /** Not a Ubisoft game (or no shortcut): nothing to record. */
        SKIPPED_NOT_UBISOFT,

        /** The game's install folder could not be found. */
        NO_FOLDER,

        /** The folder has no usable [STATE_FILE]. */
        NO_STATE_FILE,

        /** The folder is not below the imagefs root, so it has no `Z:` path. */
        OUTSIDE_IMAGEFS,

        /** The prefix has no `system.reg` yet (not booted). */
        NO_SYSTEM_REG,

        /** The values were not found in the hive after writing them. */
        WRITE_FAILED,

        /** Something threw. */
        ERROR,
    }

    /** What [register] did, in words for the diagnostics report and the log: [result] plus a [detail] line. */
    data class Outcome(val result: Result, val detail: String) {
        /** True when the registry was changed. */
        val changed: Boolean get() = result == Result.REGISTERED

        override fun toString(): String = if (detail.isEmpty()) result.name else "${result.name}: $detail"
    }

    @Volatile
    private var lastOutcome: Outcome? = null

    /** Remembers [outcome] as this launch's answer (see [last]) and logs it. Returns it. */
    @JvmStatic
    fun record(outcome: Outcome): Outcome {
        lastOutcome = outcome
        if (outcome.result != Result.SKIPPED_NOT_UBISOFT) Log.i(TAG, "Ubisoft install record: $outcome")
        return outcome
    }

    /** The outcome of the last [registerDetailed] / `registerEpicInstall` call in this process (one per launch), null before any. */
    @JvmStatic
    fun last(): Outcome? = lastOutcome

    /**
     * Records the game in [installDir] as installed in [container]'s prefix (see the class comment). Call on the
     * launch worker thread after the prefix is prepared and before any Wine process runs: [WineRegistryEditor]
     * rewrites `system.reg`, and a running wineserver would overwrite that when it saves its own copy.
     *
     * Cheap and idempotent: one streamed read of `system.reg`, and a write only for what is missing or different.
     * A folder without a usable [STATE_FILE], one outside the imagefs root, or a prefix without `system.reg` (not
     * booted yet) changes nothing. Returns true when the registry was changed. Never throws.
     */
    @JvmStatic
    fun register(container: Container, installDir: File, imageFsRoot: File): Boolean =
        registerDetailed(container, installDir, imageFsRoot).changed

    /** [register] with the answer in words ([Outcome]); also remembered for the launch report ([last]). Never throws. */
    @JvmStatic
    fun registerDetailed(container: Container, installDir: File, imageFsRoot: File): Outcome =
        record(
            try {
                doRegister(container, installDir, imageFsRoot)
            } catch (e: Exception) {
                Log.w(TAG, "could not register the Ubisoft install — left as is", e)
                Outcome(Result.ERROR, e.toString())
            },
        )

    private fun doRegister(container: Container, installDir: File, imageFsRoot: File): Outcome {
        val state = UbisoftInstallState.read(File(installDir, STATE_FILE))
            ?: return Outcome(Result.NO_STATE_FILE, "no usable $STATE_FILE in ${installDir.path}: Ubisoft Connect will not see the game as installed")
        val zDir = zPathOf(installDir, imageFsRoot)
            ?: return Outcome(Result.OUTSIDE_IMAGEFS, "${installDir.path} is not below the imagefs root ${imageFsRoot.path}")
        val systemReg = File(container.rootDir, ".wine/system.reg")
        if (!systemReg.isFile) {
            return Outcome(Result.NO_SYSTEM_REG, "no ${systemReg.path} yet (container ${container.id}): left for the next launch")
        }
        val writes = installWrites(state, zDir)
        val pending = systemReg.bufferedReader(Charsets.UTF_8).use { pendingWrites(it, writes) }
        if (pending.isEmpty()) {
            return Outcome(Result.ALREADY_PRESENT, "${writes.size} value(s) for product ${state.productId} (${state.name}) already in system.reg")
        }

        WineRegistryEditor(systemReg).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            for (w in pending) {
                if (w.type == Type.DWORD) reg.setDwordValue(w.key, w.name, w.data.toIntOrNull() ?: 0)
                else reg.setStringValue(w.key, w.name, w.data)
            }
        }
        // The editor swallows I/O errors, so read the hive again before saying it worked.
        val left = systemReg.bufferedReader(Charsets.UTF_8).use { pendingWrites(it, pending) }
        val written = pending.size - left.size
        if (written <= 0) {
            return Outcome(Result.WRITE_FAILED, "none of ${pending.size} value(s) found in system.reg after writing (container ${container.id})")
        }
        val missing = if (left.isEmpty()) "" else "; ${left.size} not found in the hive afterwards: " + left.joinToString { it.key.substringAfterLast('\\') + "/" + it.name }
        return Outcome(
            Result.REGISTERED,
            "$written of ${pending.size} value(s) written for ${state.name} (Ubisoft product ${state.productId}) in container ${container.id}$missing",
        )
    }
}
