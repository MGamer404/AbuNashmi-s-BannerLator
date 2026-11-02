package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import com.winlator.star.store.steamscript.InstallScriptExecutor
import com.winlator.star.store.steamscript.InstallScriptModel
import com.winlator.star.store.steamscript.InstallScriptTokens
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
 * Epic Games Store builds of Ubisoft titles (Assassin's Creed Syndicate) need the same launcher but have no Steam
 * script: their folder carries the loader plus `Uplay\UplayInstaller.exe`, and the shortcut's `ubisoft` stamp says
 * so (see the Epic section below). When such a folder bundles no installer at all, Ubisoft's own is downloaded
 * once, after the user agrees ([UbisoftInstallerDownload]).
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

    /**
     * Files a Ubisoft game links against to find the launcher (root of the game folder), plus the install record
     * (`uplay_install.manifest` / `.state`) Ubisoft Connect leaves beside a game it installed, which Epic builds ship too.
     */
    private val LOADER_PREFIXES = listOf(
        "uplay_r1_loader", "upc_r1_loader", "uplay_r2_loader", "upc_r2_loader", "uplay_install",
    )

    private const val SETUP_NAME = "Ubisoft Connect PC Client"

    /**
     * Agent launcher-chain exe names (`WN_STEAM_LAUNCH_CHAIN`, SteamLite v6 / agent p5). Steam starts the game
     * as `<game>.exe -uplay_steam_mode`; that stub starts `UbisoftGameLauncher.exe`, exits about 11 seconds later,
     * and Ubisoft Connect (`upc.exe`) then starts the real game. Without the chain the agent reads the stub's
     * exit as "game exited" and ends the session before `upc.exe` ever starts (device log, AC4 Black Flag).
     * Only the two hand-off exes: `UplayService`/`UplayWebCore` stay resident and would hold the session.
     */
    const val CHAIN_VALUE = "UbisoftGameLauncher.exe;upc.exe"

    // ---- Detection ------------------------------------------------------------------------------

    /**
     * How well [fileName] looks like a Ubisoft launcher installer, lower is better, null when it is not one. One rule
     * for every question that asks (the depot search, the Steam script's Run Process steps), so they cannot disagree:
     * an `.exe` whose name, lower-cased with spaces, `_`, `-` and `.` taken out, contains `installer` and names the
     * launcher: `UbisoftConnectInstaller` (0), another `ubisoft...installer` (1), `uplay...installer` or
     * `upcinstaller...` (2). `setup` is not accepted: `DXSETUP.exe` style names are too common to run silently on
     * a guess, and `upc` alone would match `upcoming`. Uninstallers are not installers.
     * Matches `Ubisoft Connect Installer.exe`, `UbisoftGameLauncherInstaller.exe`, `Uplay_Installer.exe`,
     * `UplayInstaller_x64.exe`; not `UplayLaunch.exe`, `UplayService.exe`, `upc.exe`, `UbisoftGameLauncher.exe`, a DLL.
     */
    @JvmStatic
    fun installerRank(fileName: String): Int? {
        val name = fileName.trim()
        if (!name.endsWith(".exe", ignoreCase = true)) return null
        val stem = name.dropLast(4).lowercase().filter { it != ' ' && it != '_' && it != '-' && it != '.' }
        if (!stem.contains("installer") || stem.contains("uninstall")) return null
        return when {
            stem.startsWith("ubisoftconnectinstaller") -> 0
            stem.contains("ubisoft") -> 1
            stem.contains("uplay") || stem.startsWith("upcinstaller") -> 2
            else -> null
        }
    }

    /** True when [fileName] (no folders) is a Ubisoft launcher installer, see [installerRank]. */
    @JvmStatic
    fun isInstallerName(fileName: String): Boolean = installerRank(fileName) != null

    private fun processFileName(processPath: String): String =
        processPath.trim().trim('"').trim().substringAfterLast('\\').substringAfterLast('/')

    /** True for a "Run Process" exe that is Ubisoft's launcher installer (judged by the file name, not the folders). */
    @JvmStatic
    fun isInstaller(processPath: String): Boolean = isInstallerName(processFileName(processPath))

    /** [processes] with Ubisoft's launcher installer first, best name first (order otherwise kept), so it is the step that gets run. */
    @JvmStatic
    fun installerFirst(processes: List<InstallScriptModel.RunProcess>): List<InstallScriptModel.RunProcess> =
        processes.sortedBy { installerRank(processFileName(it.process)) ?: Int.MAX_VALUE }

    /**
     * What a depot's files say about the launcher, found once and reused for a short while: the installer search
     * walks up to three folder levels, and one launch asks about the same depot from several places (the Games tab,
     * the install-script pass, the agent hand-off, the DLL override), mostly for games that are not Ubisoft's.
     * Each half is looked up on first use only, so a depot with a loader in its root never gets walked.
     */
    private class DepotScan(val installDir: File, val atMs: Long) {
        val hasLoader: Boolean by lazy {
            installDir.listFiles()?.any { f -> f.isFile && UbisoftSupport.LOADER_PREFIXES.any { f.name.startsWith(it, ignoreCase = true) } } == true
        }
        val installer: File? by lazy { UbisoftSupport.searchInstaller(installDir) }
    }

    private const val SCAN_TTL_MS = 30_000L
    private val scans = java.util.concurrent.ConcurrentHashMap<String, DepotScan>()

    private fun scanOf(installDir: File): DepotScan {
        val key = installDir.absolutePath
        val now = System.currentTimeMillis()
        scans[key]?.let { if (now - it.atMs in 0..SCAN_TTL_MS) return it }
        if (scans.size >= 16) scans.clear()
        return DepotScan(installDir, now).also { scans[key] = it }
    }

    /** Drops every remembered depot scan (the files changed on disk, or a test reuses a folder). */
    internal fun forgetScans() = scans.clear()

    /**
     * The depot's launcher installer: folders are searched level by level, so the shallowest hit wins; on one level
     * the best [installerRank] wins, then the folder path and name (children are sorted, the listing order of the
     * file system never matters). Folders are listed down to three levels below [root] and not past the first level with a hit.
     */
    private fun searchInstaller(root: File): File? {
        var level = listOf(root)
        for (depth in 0..3) {
            var best: File? = null
            var bestRank = Int.MAX_VALUE
            val next = ArrayList<File>()
            for (dir in level) {
                val children = dir.listFiles() ?: continue
                for (f in children.sortedWith(BY_NAME)) {
                    val rank = installerRank(f.name)
                    if (rank != null && f.isFile) {
                        if (rank < bestRank) { best = f; bestRank = rank }
                    } else if (depth < 3 && f.isDirectory) next += f
                }
            }
            if (best != null) return best
            level = next
        }
        return null
    }

    private val BY_NAME = compareBy<File>({ it.name.lowercase() }, { it.name })

    /**
     * The launcher installer a depot bundles, looked for in its root and up to three folders below it
     * (e.g. `Support\Uplay\`). Null when the depot ships none. The answer is reused for 30 seconds.
     */
    @JvmStatic
    fun bundledInstaller(installDir: File?): File? {
        if (installDir == null || !installDir.isDirectory) return null
        return scanOf(installDir).installer?.takeIf { it.isFile }
    }

    /**
     * True when the depot's files alone say Ubisoft: a launcher loader in its root or a bundled launcher installer.
     * File-only on purpose ([InstallScriptExecutor.locateScript] uses it to accept `<appId>_install.vdf`).
     */
    @JvmStatic
    fun hasLauncherFiles(installDir: File?): Boolean {
        if (installDir == null || !installDir.isDirectory) return false
        val scan = scanOf(installDir)
        return scan.hasLoader || scan.installer?.isFile == true
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
        // The prefix check is a few file stats; the depot checks read the game's folders, so they come second.
        if (launcherInstalled(shortcut.container)) return false
        val installDir = EaSupport.installDirOf(shortcut) ?: return false
        return detect(installDir) && hasInstallableLauncher(installDir)
    }

    /**
     * Starts the one-time Ubisoft Connect setup session for [shortcut] (the depot's bundled installer, run silently).
     * Returns null when the session was launched, or a message for the user when it could not start. Off the main thread.
     */
    @JvmStatic
    fun startSetup(context: Context, shortcut: Shortcut): String? {
        if (isEpicShortcut(shortcut)) {
            val epicDir = epicInstallDirOf(context, shortcut)
                ?: return context.getString(R.string.ubisoft_setup_no_folder, shortcut.path)
            return startSetupForInstallDir(context, shortcut.container, epicDir)
        }
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

    /**
     * Store-neutral one-time setup: runs the launcher installer [installDir] bundles (silently) in one setup session
     * for [container]. No Steam app id and no install script needed. Same contract as [startSetup]: null when the
     * session was launched, otherwise a message for the user. Off the main thread (it looks through the folder).
     */
    @JvmStatic
    fun startSetupForInstallDir(context: Context, container: Container, installDir: File): String? {
        val processes = fallbackRunProcesses(installDir)
        if (processes.isEmpty()) return context.getString(R.string.ubisoft_setup_not_started)
        val tokens = InstallScriptTokens(container, installDir, File(context.filesDir, "imagefs"))
        val started = InstallScriptExecutor.runProcessStage.run(context, container, processes, tokens, emptyList())
        return if (started) null else context.getString(R.string.ubisoft_setup_not_started)
    }

    /**
     * Setup for a game that bundles no installer: gets Ubisoft's official one ([UbisoftInstallerDownload], reused when
     * an earlier attempt already fetched it) and runs it silently in one setup session for [container]. The file
     * lives in the container's own `C:` drive, so the stage runs it by its plain `C:` path (no copy of 251 MB).
     * [onProgress] gets (bytes so far, total) on this thread. Returns null when the session was launched or [handle]
     * cancelled the download (it deleted the partial file), otherwise a message for the user. Off the main thread.
     */
    @JvmStatic
    fun startSetupWithDownload(
        context: Context, container: Container, handle: UbisoftInstallerDownload.Handle,
        onProgress: (Long, Long) -> Unit,
    ): String? {
        val exe = when (val r = UbisoftInstallerDownload.ensure(container, handle, onProgress)) {
            is UbisoftInstallerDownload.Outcome.Done -> r.file
            is UbisoftInstallerDownload.Outcome.Cancelled -> return null
            is UbisoftInstallerDownload.Outcome.Failed -> return downloadFailureMessage(context, r)
        }
        // Cancel pressed just as the file completed: keep it for the next try, start nothing.
        if (handle.cancelled) return null
        val process = UbisoftInstallerDownload.windowsPathOf(exe)
        val processes = listOf(InstallScriptModel.RunProcess(SETUP_NAME, process, "/S", null, null, null))
        // %INSTALLDIR% is not used by this step; the folder only has to exist for the tokens.
        val tokens = InstallScriptTokens(container, exe.parentFile ?: exe, File(context.filesDir, "imagefs"))
        val started = InstallScriptExecutor.runProcessStage.run(context, container, processes, tokens, emptyList())
        return if (started) null else context.getString(R.string.ubisoft_setup_not_started)
    }

    /** The user-facing line for a failed download. */
    @JvmStatic
    fun downloadFailureMessage(context: Context, failed: UbisoftInstallerDownload.Outcome.Failed): String = when (failed.reason) {
        UbisoftInstallerDownload.Reason.NETWORK -> context.getString(R.string.ubisoft_download_failed_network)
        UbisoftInstallerDownload.Reason.HTTP -> context.getString(R.string.ubisoft_download_failed_http, failed.detail)
        UbisoftInstallerDownload.Reason.SIZE -> context.getString(R.string.ubisoft_download_failed_size)
        UbisoftInstallerDownload.Reason.INVALID -> context.getString(R.string.ubisoft_download_failed_invalid)
        UbisoftInstallerDownload.Reason.NO_SPACE -> context.getString(R.string.ubisoft_download_failed_space)
        UbisoftInstallerDownload.Reason.IO -> context.getString(R.string.ubisoft_download_failed_io)
    }

    // ---- Epic Games Store titles ----------------------------------------------------------------

    /** Shortcut [Extra Data] stamp: "1" = the game's files say Ubisoft, "0" = looked and it is not, absent = not looked yet. */
    const val EXTRA_UBISOFT = "ubisoft"

    private const val EPIC_ROOT = "epic_games"

    /** [EXTRA_UBISOFT] as true / false, or null while it has not been worked out. */
    @JvmStatic
    fun stampValue(raw: String?): Boolean? = when (raw?.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** True for a shortcut the Epic store wrote (`storeSource=epic`). No disk access. */
    @JvmStatic
    fun isEpicShortcut(shortcut: Shortcut): Boolean = shortcut.getExtra("storeSource", "") == "epic"

    /** The stamp on [shortcut] (see [EXTRA_UBISOFT]). No disk access. */
    @JvmStatic
    fun epicStamp(shortcut: Shortcut): Boolean? = stampValue(shortcut.getExtra(EXTRA_UBISOFT, ""))

    /**
     * The game folder just below a [root] segment in [path] (`/.../epic_games/<game>/Bin/x.exe` gives
     * `/.../epic_games/<game>`), either separator, any case; null when [path] has no such segment.
     */
    @JvmStatic
    fun gameFolderUnder(path: String, root: String): File? {
        val parts = path.replace('\\', '/').split('/')
        val at = parts.indexOfFirst { it.equals(root, ignoreCase = true) }
        if (at < 0 || at + 1 >= parts.size || parts[at + 1].isEmpty()) return null
        return File(parts.subList(0, at + 2).joinToString("/"))
    }

    /**
     * The install folder of an Epic shortcut's game: the one the store saved for its `epicAppName`, else the folder
     * below `epic_games` on the exe's Android path. Null when neither is there (e.g. the SD card is out).
     */
    @JvmStatic
    fun epicInstallDirOf(context: Context, shortcut: Shortcut): File? {
        val appName = shortcut.getExtra("epicAppName", "")
        if (appName.isNotEmpty()) {
            EpicCloudSavePaths.installDir(context, appName)?.takeIf { it.isDirectory }?.let { return it }
        }
        val exe = runCatching { WinePath.resolveAndroidPath(shortcut.container, shortcut.path) }.getOrNull() ?: return null
        return gameFolderUnder(exe.absolutePath, EPIC_ROOT)?.takeIf { it.isDirectory }
    }

    /**
     * True when the Epic [shortcut]'s game needs Ubisoft Connect. A plain read of the [EXTRA_UBISOFT] stamp; a
     * shortcut without one (written before the stamp existed) gets it worked out from the game's files once and saved.
     * Only that first look touches the disk, so call this off the main thread.
     */
    @JvmStatic
    fun isEpicUbisoft(context: Context, shortcut: Shortcut): Boolean {
        if (!isEpicShortcut(shortcut)) return false
        epicStamp(shortcut)?.let { return it }
        // No folder to look in: say no now and look again next time instead of stamping a guess.
        val dir = epicInstallDirOf(context, shortcut) ?: return false
        val ubisoft = hasLauncherFiles(dir)
        try {
            shortcut.putExtra(EXTRA_UBISOFT, if (ubisoft) "1" else "0")
            shortcut.saveData()
        } catch (e: Exception) {
            Log.w(TAG, "could not stamp the Ubisoft tag on ${shortcut.name}", e)
        }
        return ubisoft
    }

    /** What an Epic launch has to do about Ubisoft Connect first. */
    enum class EpicSetup {
        /** Nothing: not a Ubisoft title, Connect is already in the prefix, or the game's folder is not there to look in. */
        NONE,

        /** Offer the one-time setup with the installer the game's folder bundles. */
        BUNDLED,

        /** Connect is missing and the game bundles no installer: offer to download Ubisoft's own ([UbisoftInstallerDownload]). */
        DOWNLOAD,
    }

    /**
     * What the Epic [shortcut] needs before it launches (see [EpicSetup]). A stamped "not Ubisoft" game answers
     * [EpicSetup.NONE] without touching the disk; otherwise a few file stats, plus the depot search for a Ubisoft title
     * whose container has no Connect. Call off the main thread.
     */
    @JvmStatic
    fun epicSetupFor(context: Context, shortcut: Shortcut): EpicSetup {
        if (!isEpicUbisoft(context, shortcut)) return EpicSetup.NONE
        if (launcherInstalled(shortcut.container)) {
            // Connect is in: a downloaded installer from an earlier setup is no use any more (about 251 MB).
            UbisoftInstallerDownload.deleteDownloaded(shortcut.container)
            return EpicSetup.NONE
        }
        val dir = epicInstallDirOf(context, shortcut) ?: return EpicSetup.NONE
        return if (bundledInstaller(dir) != null) EpicSetup.BUNDLED else EpicSetup.DOWNLOAD
    }

    /**
     * Launch-worker hook for an Epic game: turns the Ubisoft Connect overlay off when the game needs Connect (see
     * [disableOverlay]). A stamp read and nothing else for every other Epic game, a no-op for non-Epic shortcuts. Never throws.
     */
    @JvmStatic
    fun applyEpicPreLaunch(context: Context, container: Container, shortcut: Shortcut?) {
        if (shortcut == null) return
        try {
            if (isEpicUbisoft(context, shortcut)) disableOverlay(container)
        } catch (t: Throwable) {
            Log.w(TAG, "Epic Ubisoft pre-launch failed for ${shortcut.name}", t)
        }
    }

    /**
     * Launch-worker hook for an Epic game that needs Ubisoft Connect: records the game as installed in [container]'s
     * registry, which is what "Locate installed game" does by hand ([UbisoftInstallRegistry]); without it Connect
     * offers to download the game again. Call before any Wine process starts (it rewrites `system.reg`). A stamp
     * read and nothing else for every other Epic game, a no-op for non-Epic shortcuts. Never throws.
     */
    @JvmStatic
    fun registerEpicInstall(context: Context, container: Container, shortcut: Shortcut?) {
        if (shortcut == null) return
        try {
            if (!isEpicUbisoft(context, shortcut)) return
            val dir = epicInstallDirOf(context, shortcut) ?: return
            UbisoftInstallRegistry.register(container, dir, ImageFs.find(context).rootDir)
        } catch (t: Throwable) {
            Log.w(TAG, "Epic Ubisoft install record failed for ${shortcut.name}", t)
        }
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
