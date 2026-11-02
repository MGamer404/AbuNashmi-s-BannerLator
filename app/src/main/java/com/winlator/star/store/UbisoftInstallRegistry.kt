package com.winlator.star.store

import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.core.WineRegistryEditor
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells Ubisoft Connect that a game folder is an installed game, by writing the registry keys Connect itself writes
 * when the user picks "Locate installed game" on a game it offers to download (device-proven with Assassin's Creed
 * Syndicate from the Epic Games Store: after Locate the game launched, and the keys below were all that changed in
 * the prefix's `system.reg`). Without them Connect has no record of the install and offers to download the game
 * again. The product id and the language values come from the `uplay_install.state` file in the game's folder
 * ([UbisoftInstallState]).
 *
 * What Locate wrote, with the Syndicate values (1875 is the state file's product id):
 *  - `Software\Wow6432Node\Ubisoft\Launcher\Installs\1875`: `InstallDir` = `Z:/epic_games/<game>/` (forward slashes,
 *    trailing slash), `Language` = `en-US`.
 *  - `Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Uplay Install 1875`: the game's "Add or
 *    remove programs" entry. Its `DisplayIcon` points at a hash-named `.ico` in Connect's `data` folder, which the
 *    state file does not give, so it is left out.
 *  - `Software\Wow6432Node\Ubisoft\<game>`: `Language` = `English(US)`, the state file's value for the install
 *    language. The same value is also written to the native `Software\Ubisoft\<game>` view.
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
    private const val HKLM_SOFTWARE = "HKEY_LOCAL_MACHINE\\SOFTWARE\\"

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

        // The game's own language value: the entry for the install language, in both registry views.
        val entry = state.registry.firstOrNull { it.language.equals(state.language, ignoreCase = true) }
        val game = entry?.let { gameValue(it.path) }
        if (entry != null && game != null) {
            out += RegWrite("Software\\Wow6432Node\\" + game.first, game.second, Type.STRING, entry.value)
            out += RegWrite("Software\\" + game.first, game.second, Type.STRING, entry.value)
        }
        return out.filter { w -> listOf(w.key, w.name, w.data).none { s -> s.any { it < ' ' } } }.distinct()
    }

    /** `HKEY_LOCAL_MACHINE\SOFTWARE\Ubisoft\<game>\Language` as (`Ubisoft\<game>`, `Language`), null for any other shape. */
    private fun gameValue(path: String): Pair<String, String>? {
        val p = path.trim()
        if (!p.startsWith(HKLM_SOFTWARE, ignoreCase = true)) return null
        val rest = p.substring(HKLM_SOFTWARE.length)
        val cut = rest.lastIndexOf('\\')
        if (cut <= 0 || cut == rest.length - 1) return null
        val key = rest.substring(0, cut)
        if (key.split('\\').any { it.isBlank() }) return null
        return key to rest.substring(cut + 1)
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

    private val noted: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Logs [message] at info once per process for [key]: a game that cannot be registered says so on every launch otherwise. */
    private fun noteOnce(key: String, message: String) {
        if (noted.add(key)) Log.i(TAG, message)
    }

    /**
     * Records the game in [installDir] as installed in [container]'s prefix (see the class comment). Call on the
     * launch worker thread after the prefix is prepared and before any Wine process runs: [WineRegistryEditor]
     * rewrites `system.reg`, and a running wineserver would overwrite that when it saves its own copy.
     *
     * Cheap and idempotent: one streamed read of `system.reg`, and a write only for what is missing or different.
     * A folder without a usable [STATE_FILE], one outside the imagefs root, or a prefix without `system.reg` (not
     * booted yet) changes nothing and logs once. Returns true when the registry was changed. Never throws.
     */
    @JvmStatic
    fun register(container: Container, installDir: File, imageFsRoot: File): Boolean {
        return try {
            val state = UbisoftInstallState.read(File(installDir, STATE_FILE))
            if (state == null) {
                noteOnce("state:" + installDir.path, "no usable $STATE_FILE in ${installDir.path}: Ubisoft Connect will not see the game as installed")
                return false
            }
            val zDir = zPathOf(installDir, imageFsRoot)
            if (zDir == null) {
                noteOnce("outside:" + installDir.path, "${installDir.path} is not below the imagefs root: not registering it with Ubisoft Connect")
                return false
            }
            val systemReg = File(container.rootDir, ".wine/system.reg")
            if (!systemReg.isFile) {
                noteOnce("noreg:" + systemReg.path, "no system.reg yet (container ${container.id}): Ubisoft install record left for the next launch")
                return false
            }
            val writes = installWrites(state, zDir)
            val pending = systemReg.bufferedReader(Charsets.UTF_8).use { pendingWrites(it, writes) }
            if (pending.isEmpty()) return false

            WineRegistryEditor(systemReg).use { reg ->
                reg.setCreateKeyIfNotExist(true)
                for (w in pending) {
                    if (w.type == Type.DWORD) reg.setDwordValue(w.key, w.name, w.data.toIntOrNull() ?: 0)
                    else reg.setStringValue(w.key, w.name, w.data)
                }
            }
            // The editor swallows I/O errors, so read the hive again before saying it worked.
            val left = systemReg.bufferedReader(Charsets.UTF_8).use { pendingWrites(it, pending) }
            if (left.isEmpty()) Log.i(TAG, "registered ${state.name} (Ubisoft product ${state.productId}) in container ${container.id}: ${pending.size} value(s)")
            else Log.w(TAG, "could not write ${left.size} of ${pending.size} Ubisoft install value(s) in container ${container.id}")
            left.size < pending.size
        } catch (e: Exception) {
            Log.w(TAG, "could not register the Ubisoft install — left as is", e)
            false
        }
    }
}
