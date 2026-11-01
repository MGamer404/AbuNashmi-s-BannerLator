package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import com.winlator.star.store.steamscript.InstallScriptExecutor
import com.winlator.star.store.steamscript.InstallScriptModel
import com.winlator.star.xenvironment.ImageFs
import java.io.File

/**
 * Ubisoft-published Steam titles ("Ubisoft support", e.g. Assassin's Creed IV Black Flag): detection, the
 * one-time Ubisoft Connect install into the prefix, and the overlay setting that keeps those games alive.
 *
 * These games start Ubisoft Connect themselves (`uplay_r1_loader.dll` reads `HKLM\Software\Wow6432Node\Ubisoft\Launcher`
 * and runs the launcher found there) and stop with "Unable to find Ubisoft Game Launcher" when it is not in the
 * prefix. Steam installs it from the depot's install script (`<appId>_install.vdf`, "Run Process" step running the
 * bundled `UbisoftConnectInstaller.exe /S`); Bannerlator used to look for that script only under the name
 * `installScript.vdf` and only ran the step for EA titles, so the launcher was never installed.
 *
 * Cross-checked with DroidDeck (Droid-Deck/DroidDeck #1449 / #1463) and GameNative (#2286): both install the bundled
 * launcher through the Steam install script, and DroidDeck turns the launcher's overlay off because it hooks
 * `dxgi.dll` and crashes the game on an Arm64EC DXVK.
 */
object UbisoftSupport {
    private const val TAG = "UbisoftSupport"

    /** Where Ubisoft Connect installs itself inside a prefix (relative to the container root). */
    private const val LAUNCHER_DIR = ".wine/drive_c/Program Files (x86)/Ubisoft/Ubisoft Game Launcher"
    private val LAUNCHER_EXES = listOf("upc.exe", "UbisoftConnect.exe", "UbisoftGameLauncher.exe")

    /** The launcher's own settings file (relative to the container root). */
    private val SETTINGS_FILE = ".wine/drive_c/users/${ImageFs.USER}/AppData/Local/Ubisoft Game Launcher/settings.yaml"

    /** Installers a depot bundles for the launcher, most current first. */
    private val INSTALLER_NAMES = listOf("UbisoftConnectInstaller.exe", "UplayInstaller.exe")

    /** Files a Ubisoft game links against to find the launcher (root of the depot). */
    private val LOADER_PREFIXES = listOf("uplay_r1_loader", "upc_r1_loader")

    private const val SETUP_NAME = "Ubisoft Connect PC Client"

    /**
     * Agent launcher-chain exe names (`WN_STEAM_LAUNCH_CHAIN`, SteamLite v6 / agent p5). Steam starts the game
     * as `<game>.exe -uplay_steam_mode`; that stub starts `UbisoftGameLauncher.exe`, exits about 11 seconds later,
     * and Ubisoft Connect (`upc.exe`) then starts the real game. Without the chain the agent reads the stub's
     * exit as "game exited" and ends the session before `upc.exe` ever starts (device log, AC4 Black Flag).
     * Only the two hand-off exes: `UplayService`/`UplayWebCore` stay resident and would hold the session.
     */
    const val CHAIN_VALUE = "UbisoftGameLauncher.exe;upc.exe"

    /**
     * `WINEDLLOVERRIDES` entry that stops Ubisoft Connect loading Wine's stubbed builtin `windows.ui.dll`. `upc.exe`
     * (a 32-bit CEF app) calls the stubbed `UISettings.TextScaleFactor` over and over, each call deeper on the stack,
     * until it dies with EXCEPTION_STACK_OVERFLOW and the game never starts; with the dll disabled the WinRT
     * activation fails cleanly instead, as the missing `ToastNotificationManager` class already does.
     */
    const val WINE_DLL_OVERRIDE = "windows.ui=d"

    /**
     * [existing] (a `WINEDLLOVERRIDES` value, entries split by `;`) with [WINE_DLL_OVERRIDE] appended once. Returned
     * unchanged when `windows.ui` is already named in it (the user's own entry wins).
     */
    @JvmStatic
    fun mergeDllOverrides(existing: String): String {
        val key = WINE_DLL_OVERRIDE.substringBefore('=')
        val named = existing.split(';').any { entry ->
            entry.substringBefore('=').split(',').any { it.trim().removeSuffix(".dll").equals(key, ignoreCase = true) }
        }
        if (named) return existing
        val kept = existing.trim().trimEnd(';')
        return if (kept.isEmpty()) WINE_DLL_OVERRIDE else "$kept;$WINE_DLL_OVERRIDE"
    }

    // ---- Detection ------------------------------------------------------------------------------

    /** True for a "Run Process" exe that is Ubisoft's launcher installer (judged by the file name, not the folders). */
    @JvmStatic
    fun isInstaller(processPath: String): Boolean {
        val fileName = processPath.substringAfterLast('\\').substringAfterLast('/')
        return INSTALLER_NAMES.any { fileName.contains(it.removeSuffix(".exe"), ignoreCase = true) }
    }

    /** [processes] with Ubisoft's launcher installer first (order otherwise kept), so it is the step that gets run. */
    @JvmStatic
    fun installerFirst(processes: List<InstallScriptModel.RunProcess>): List<InstallScriptModel.RunProcess> =
        processes.sortedByDescending { isInstaller(it.process) }

    /**
     * The launcher installer a depot bundles, looked for in its root and up to three folders below it
     * (e.g. `Support\Uplay\`). Null when the depot ships none.
     */
    @JvmStatic
    fun bundledInstaller(installDir: File?): File? {
        if (installDir == null || !installDir.isDirectory) return null
        fun search(dir: File, depth: Int): File? {
            val children = dir.listFiles() ?: return null
            children.firstOrNull { f -> f.isFile && INSTALLER_NAMES.any { it.equals(f.name, ignoreCase = true) } }?.let { return it }
            if (depth >= 3) return null
            for (f in children) if (f.isDirectory) search(f, depth + 1)?.let { return it }
            return null
        }
        return search(installDir, 0)
    }

    /**
     * True when the depot's files alone say Ubisoft: a launcher loader in its root or a bundled launcher installer.
     * File-only on purpose ([InstallScriptExecutor.locateScript] uses it to accept `<appId>_install.vdf`).
     */
    @JvmStatic
    fun hasLauncherFiles(installDir: File?): Boolean {
        if (installDir == null || !installDir.isDirectory) return false
        val root = installDir.listFiles() ?: return false
        if (root.any { f -> f.isFile && LOADER_PREFIXES.any { f.name.startsWith(it, ignoreCase = true) } }) return true
        return bundledInstaller(installDir) != null
    }

    /** True for a Ubisoft title, detected from the depot's files (and its install script), so no tag is needed. */
    @JvmStatic
    fun detect(installDir: File?): Boolean {
        if (installDir == null || !installDir.isDirectory) return false
        if (hasLauncherFiles(installDir)) return true
        val script = InstallScriptExecutor.locateScript(installDir) ?: return false
        if (script.length() > 512 * 1024) return false
        return try {
            val text = script.readText()
            text.contains("Ubisoft", ignoreCase = true) || text.contains("Uplay", ignoreCase = true)
        } catch (e: Exception) { false }
    }

    /** The "Run Process" step to run when a depot bundles the installer but its script has none (or there is no script). */
    @JvmStatic
    fun fallbackRunProcesses(installDir: File): List<InstallScriptModel.RunProcess> {
        val exe = bundledInstaller(installDir) ?: return emptyList()
        val rel = exe.relativeTo(installDir).path.replace('/', '\\')
        return listOf(InstallScriptModel.RunProcess(SETUP_NAME, "%INSTALLDIR%\\$rel", "/S", null, null, null))
    }

    // ---- The prefix -----------------------------------------------------------------------------

    /** True when Ubisoft Connect is installed in [container]'s prefix. */
    @JvmStatic
    fun launcherInstalled(container: Container): Boolean {
        val dir = File(container.rootDir, LAUNCHER_DIR)
        return dir.isDirectory && LAUNCHER_EXES.any { File(dir, it).isFile }
    }

    /**
     * True when the depot has something that installs the launcher: a bundled installer, or an install-script
     * "Run Process" step that runs one. Without either, a setup session would have nothing to run.
     */
    @JvmStatic
    fun hasInstallableLauncher(installDir: File): Boolean {
        if (bundledInstaller(installDir) != null) return true
        val script = InstallScriptExecutor.locateScript(installDir) ?: return false
        if (script.length() > 512 * 1024) return false
        return try {
            InstallScriptModel.parse(script.readText()).runProcesses.any { isInstaller(it.process) }
        } catch (e: Exception) { false }
    }

    /** True when [shortcut] is a Ubisoft title whose container still has no Ubisoft Connect (run [startSetup] first). */
    @JvmStatic
    fun needsSetup(shortcut: Shortcut): Boolean {
        val installDir = EaSupport.installDirOf(shortcut) ?: return false
        return detect(installDir) && hasInstallableLauncher(installDir) && !launcherInstalled(shortcut.container)
    }

    /**
     * Starts the one-time Ubisoft Connect setup session for [shortcut] (the depot's bundled installer, run silently).
     * Returns null when the session was launched, or a message for the user when it could not start. Off the main thread.
     */
    @JvmStatic
    fun startSetup(context: Context, shortcut: Shortcut): String? {
        val installDir = EaSupport.installDirOf(shortcut)
            ?: return context.getString(R.string.ubisoft_setup_no_folder, shortcut.path)
        // runForShortcut finds the depot from the exe's steam_games/steamapps segment, so prefer a path inside it.
        val resolved = runCatching { WinePath.resolveAndroidPath(shortcut.container, shortcut.path)?.absolutePath }.getOrNull()
        val exe = resolved?.takeIf { InstallScriptExecutor.locateInstallDir(File(it)) != null }
            ?: File(installDir, shortcut.path.replace('\\', '/').substringAfterLast('/')).absolutePath
        val appId = runCatching { EaSupport.resolveSteamAppId(shortcut, installDir) }.getOrDefault(0)
        if (appId <= 0) return context.getString(R.string.ubisoft_setup_no_app_id)
        val started = InstallScriptExecutor.runForShortcut(context, shortcut.container, appId, exe, true)
        return if (started) null else context.getString(R.string.ubisoft_setup_not_started)
    }

    // ---- Overlay --------------------------------------------------------------------------------

    /**
     * Turns the Ubisoft Connect overlay off in the launcher's settings before a game starts. The overlay hooks
     * `dxgi.dll`, and on an Arm64EC DXVK that corrupts native code: the game dies about 12 seconds after its window
     * opens, or shows a black screen (DroidDeck #1463, Watch_Dogs 2). A no-op while the launcher is not installed.
     * Returns true when the file was changed. Never throws.
     */
    @JvmStatic
    fun disableOverlay(container: Container): Boolean {
        if (!File(container.rootDir, LAUNCHER_DIR).isDirectory) return false
        val file = File(container.rootDir, SETTINGS_FILE)
        return try {
            val current = if (file.isFile) file.readText(Charsets.UTF_8) else ""
            val updated = overlayOff(current)
            if (updated == current) return false
            val dir = file.parentFile ?: return false
            dir.mkdirs()
            val tmp = File(dir, ".settings-bl.tmp")
            tmp.writeText(updated, Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.writeText(updated, Charsets.UTF_8); tmp.delete() }
            Log.i(TAG, "Ubisoft Connect overlay turned off (container ${container.id})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not turn the Ubisoft Connect overlay off — left as is", e)
            false
        }
    }

    /**
     * `settings.yaml` text with `overlay: { enabled: false, warning_enabled: false }`. Line-based, not a YAML parser:
     * everything else in the file is kept as written, a missing section or key is added, and a file that already says
     * so comes back unchanged.
     */
    @JvmStatic
    fun overlayOff(text: String): String {
        val sep = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)

        val head = Regex("""^overlay:\s*(#.*)?$""")
        val start = lines.indexOfFirst { head.matches(it) }
        if (start < 0) {
            lines += "overlay:"
            lines += "  enabled: false"
            lines += "  warning_enabled: false"
            return lines.joinToString(sep) + sep
        }
        // The section runs over indented and blank lines; new keys go after its last real line.
        var last = start
        var i = start + 1
        while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
            if (lines[i].isNotBlank()) last = i
            i++
        }
        for (key in listOf("enabled", "warning_enabled")) {
            val keyLine = Regex("""^ {2}${key}:.*$""")
            val at = (start + 1..last).firstOrNull { keyLine.matches(lines[it]) }
            if (at != null) {
                if (lines[at].trimEnd() != "  $key: false") lines[at] = "  $key: false"
            } else {
                lines.add(last + 1, "  $key: false")
                last++
            }
        }
        return lines.joinToString(sep) + sep
    }
}
