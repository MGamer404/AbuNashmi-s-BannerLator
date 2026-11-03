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

    /**
     * True when a SteamLite launch plan's agent env ([env], `RealSteamLauncher.Plan.env`) carries the Ubisoft chain, i.e.
     * [RealSteamLauncher] armed it for this launch. A map lookup: the decision was already made when the plan was built.
     */
    @JvmStatic
    fun steamChainArmed(env: Map<String, String>?): Boolean = env?.get(EaSupport.CHAIN_ENV) == CHAIN_VALUE

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

    /** Which of Connect's launcher exes ([launcherInstalled] wants one) are in [container]'s prefix, name to present. For the diagnostics report. */
    @JvmStatic
    fun launcherFileStatus(container: Container): List<Pair<String, Boolean>> {
        val dir = File(container.rootDir, LAUNCHER_DIR)
        return LAUNCHER_EXES.map { it to File(dir, it).isFile }
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

    internal const val EPIC_ROOT = "epic_games"

    /** [EXTRA_UBISOFT] as true / false, or null while it has not been worked out. */
    @JvmStatic
    fun stampValue(raw: String?): Boolean? = when (raw?.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /**
     * True for a shortcut the Epic store wrote (`storeSource=epic`), nothing wider. No disk access. This is the check
     * for the Epic-only features that are not about Ubisoft (EOS overlay, launch arguments, cloud saves); the Ubisoft
     * paths use [isEpicShortcut].
     */
    @JvmStatic
    fun isEpicStoreShortcut(shortcut: Shortcut): Boolean = shortcut.getExtra("storeSource", "") == "epic"

    /**
     * [isEpicLike] with the reason in words (for the launch decisions note and the install-record skip line): the
     * answer and why. Pure string checks, no disk access.
     */
    @JvmStatic
    fun epicLikeReason(storeSource: String, path: String): Pair<Boolean, String> {
        val source = storeSource.trim().lowercase()
        if (source == "epic") return true to "storeSource=epic"
        if (source.isNotEmpty() && source != "custom") return false to "storeSource='$storeSource' is another store's tag"
        val shown = if (source.isEmpty()) "empty" else "custom"
        if (SteamInstallPaths.isUnderInstallRoot(path, source)) return false to "storeSource is $shown and the exe path is in a Steam install folder"
        val folder = gameFolderUnder(path, EPIC_ROOT)
            ?: return false to "storeSource is $shown and the exe path is not under epic_games"
        return true to "storeSource is $shown and the exe path is under epic_games (game folder '${folder.name}')"
    }

    /**
     * True for a game that is an Epic install: the Epic store tagged it (`epic`), or it carries no store tag at all /
     * `custom` (an exe import, a folder scan, the Download Manager's launch button: none of them write Epic extras) and
     * its Windows exe [path] sits in a game folder below `epic_games`. Any other tag (steam, gog, amazon, xbox,
     * android...) is never Epic, whatever the path says. Pure string checks, no disk access.
     */
    @JvmStatic
    fun isEpicLike(storeSource: String, path: String): Boolean = epicLikeReason(storeSource, path).first

    /**
     * True for an Epic game for the Ubisoft paths (setup offer, overlay, install record, launch report): [isEpicLike] on
     * the shortcut's `storeSource` and exe path. No disk access.
     */
    @JvmStatic
    fun isEpicShortcut(shortcut: Shortcut): Boolean =
        isEpicLike(shortcut.getExtra("storeSource", ""), shortcut.path.orEmpty())

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
    fun epicInstallDirOf(context: Context, shortcut: Shortcut): File? = epicInstallDirRoute(context, shortcut).first

    /**
     * [epicInstallDirOf] with the way it was found in words (for the launch decisions note). A shortcut without an
     * `epicAppName` (one the store did not write) goes straight to the exe path route.
     */
    @JvmStatic
    fun epicInstallDirRoute(context: Context, shortcut: Shortcut): Pair<File?, String> {
        val appName = shortcut.getExtra("epicAppName", "")
        if (appName.isNotEmpty()) {
            val saved = EpicCloudSavePaths.installDir(context, appName)?.takeIf { it.isDirectory }
            if (saved != null) return saved to "the folder the Epic store saved for epicAppName='$appName'"
        }
        val exe = runCatching { WinePath.resolveAndroidPath(shortcut.container, shortcut.path) }.getOrNull()
            ?: return null to "no saved folder (epicAppName='$appName') and the exe path '${shortcut.path}' does not map to a host path"
        val below = gameFolderUnder(exe.absolutePath, EPIC_ROOT)?.takeIf { it.isDirectory }
        return if (below != null) below to "the game folder below epic_games on the exe path ${exe.absolutePath}"
        else null to "no saved folder (epicAppName='$appName') and no existing game folder below epic_games on ${exe.absolutePath}"
    }

    /**
     * True when a stamp of [stamp] should be looked at again: only a "not Ubisoft" (`0`) that has not been
     * re-checked yet in this app run. A `1` stands, an unset stamp is worked out the normal way.
     */
    @JvmStatic
    fun stampNeedsRecheck(stamp: Boolean?, alreadyChecked: Boolean): Boolean = stamp == false && !alreadyChecked

    private const val RECHECKED_MAX = 512

    /** Shortcut files whose `0` stamp was re-checked in this app run (bounded: past the cap it starts over). */
    private val rechecked: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** True once [shortcut]'s `0` stamp was re-checked in this app run (see [isEpicUbisoft]). No disk access. */
    @JvmStatic
    fun epicStampRechecked(shortcut: Shortcut): Boolean = rechecked.contains(shortcut.file.path)

    /** Takes [shortcut]'s one re-check of this run; false when it was taken already. */
    private fun claimRecheck(shortcut: Shortcut): Boolean {
        if (rechecked.size >= RECHECKED_MAX) rechecked.clear()
        return rechecked.add(shortcut.file.path)
    }

    /** Forgets every re-check (a test reuses a shortcut path). */
    internal fun forgetRechecks() = rechecked.clear()

    /**
     * The stamp to write for a game folder: "1" when its files say Ubisoft, "0" only when it holds files and none
     * of them do, null (leave the stamp unset, look again at launch) for an empty or unreadable folder.
     */
    @JvmStatic
    fun stampFor(folderHasFiles: Boolean, ubisoft: Boolean): String? = when {
        ubisoft -> "1"
        folderHasFiles -> "0"
        else -> null
    }

    /** [stampFor] for the game folder [dir]; null when it is missing, unreadable or has no file in its root. */
    @JvmStatic
    fun stampForFolder(dir: File?): String? {
        if (dir == null || !dir.isDirectory) return null
        val ubisoft = hasLauncherFiles(dir)
        return stampFor(ubisoft || dir.listFiles()?.any { it.isFile } == true, ubisoft)
    }

    /**
     * Drops a stale `ubisoft=0` line from a copied shortcut's text so the copy works it out again (a `0` may have been
     * stamped while the game folder was empty or partial). A `ubisoft=1` and every other line stay as they are.
     */
    @JvmStatic
    fun dropStaleStamp(text: String): String {
        if (!text.contains("$EXTRA_UBISOFT=")) return text
        return text.split('\n').filterNot { line ->
            val t = line.trim()
            t.startsWith("$EXTRA_UBISOFT=") && stampValue(t.substring(EXTRA_UBISOFT.length + 1)) == false
        }.joinToString("\n")
    }

    /**
     * True when the Epic [shortcut]'s game needs Ubisoft Connect. A plain read of the [EXTRA_UBISOFT] stamp; a
     * shortcut without one (written before the stamp existed) gets it worked out from the game's files once and saved.
     * A `0` is looked at once more per app run (it may have been stamped on an empty or partial folder): when the files
     * now say Ubisoft the stamp becomes `1`, otherwise the `0` stays and is not looked at again until the app restarts.
     * Only those looks touch the disk, so call this off the main thread.
     */
    @JvmStatic
    fun isEpicUbisoft(context: Context, shortcut: Shortcut): Boolean {
        if (!isEpicShortcut(shortcut)) return false
        epicStamp(shortcut)?.let { stamp ->
            if (!stampNeedsRecheck(stamp, epicStampRechecked(shortcut)) || !claimRecheck(shortcut)) return stamp
            // Folder not found or still no loader / installer: the 0 stays.
            val dir = epicInstallDirOf(context, shortcut) ?: return false
            scans.remove(dir.absolutePath) // a scan remembered from the stale look would answer the same again
            if (!hasLauncherFiles(dir)) return false
            stampEpic(shortcut, true)
            return true
        }
        // No folder to look in: say no now and look again next time instead of stamping a guess.
        val dir = epicInstallDirOf(context, shortcut) ?: return false
        val ubisoft = hasLauncherFiles(dir)
        stampEpic(shortcut, ubisoft)
        return ubisoft
    }

    private fun stampEpic(shortcut: Shortcut, ubisoft: Boolean) {
        try {
            shortcut.putExtra(EXTRA_UBISOFT, if (ubisoft) "1" else "0")
            shortcut.saveData()
        } catch (e: Exception) {
            Log.w(TAG, "could not stamp the Ubisoft tag on ${shortcut.name}", e)
        }
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
     * read and nothing else for every other Epic game, a no-op for non-Epic shortcuts. Never throws. Returns what
     * happened in words ([UbisoftInstallRegistry.Outcome], also kept for the launch report), never null.
     */
    @JvmStatic
    fun registerEpicInstall(context: Context, container: Container, shortcut: Shortcut?): UbisoftInstallRegistry.Outcome {
        if (shortcut == null) return UbisoftInstallRegistry.record(skippedNotUbisoft("no shortcut"))
        return try {
            if (!isEpicUbisoft(context, shortcut)) {
                val skipped = skippedNotUbisoft(skipReason(shortcut))
                // record() stays quiet about skips; say so for an Epic game, where a skip is worth knowing about.
                if (isEpicShortcut(shortcut)) Log.i(TAG, "Ubisoft install record skipped for ${shortcut.name}: ${skipped.detail}")
                return UbisoftInstallRegistry.record(skipped)
            }
            val dir = epicInstallDirOf(context, shortcut)
                ?: return UbisoftInstallRegistry.record(
                    UbisoftInstallRegistry.Outcome(
                        UbisoftInstallRegistry.Result.NO_FOLDER,
                        "no install folder for epicAppName='${shortcut.getExtra("epicAppName", "")}' and none below epic_games on '${shortcut.path}'",
                    ),
                )
            UbisoftInstallRegistry.registerDetailed(container, dir, ImageFs.find(context).rootDir)
        } catch (t: Throwable) {
            Log.w(TAG, "Epic Ubisoft install record failed for ${shortcut.name}", t)
            UbisoftInstallRegistry.record(UbisoftInstallRegistry.Outcome(UbisoftInstallRegistry.Result.ERROR, t.toString()))
        }
    }

    private fun skippedNotUbisoft(why: String) =
        UbisoftInstallRegistry.Outcome(UbisoftInstallRegistry.Result.SKIPPED_NOT_UBISOFT, why)

    /** Why [isEpicUbisoft] said no for [shortcut]: its `storeSource`, whether the exe is under `epic_games`, the stamp. No disk access. */
    private fun skipReason(shortcut: Shortcut): String {
        val source = shortcut.getExtra("storeSource", "")
        val path = shortcut.path.orEmpty()
        val (epicLike, why) = epicLikeReason(source, path)
        val underEpic = gameFolderUnder(path, EPIC_ROOT) != null
        val stamp = shortcut.getExtra(EXTRA_UBISOFT, "")
        return "storeSource='$source', exe under epic_games=$underEpic, ubisoft stamp='$stamp' => " +
            if (epicLike) "an Epic game, but not stamped Ubisoft (no loader or installer was found in its folder, or the folder was not found)"
            else "not treated as an Epic game: $why"
    }

    // ---- Launch report trigger ------------------------------------------------------------------

    /**
     * True when an Epic-like game gets the Ubisoft launch report: its stamp says Ubisoft, or this launch's install
     * record found something to record ([last] is anything but "not Ubisoft"), so a wrong stamp cannot hide the report.
     */
    @JvmStatic
    fun epicRouteDecision(epicLike: Boolean, stamp: Boolean?, last: UbisoftInstallRegistry.Result?): Boolean =
        epicLike && (stamp == true || (last != null && last != UbisoftInstallRegistry.Result.SKIPPED_NOT_UBISOFT))

    /**
     * [epicRouteDecision] for [shortcut], from what this launch already worked out (the stamp, the install record's
     * outcome). No disk access: call it after [registerEpicInstall] has run.
     */
    @JvmStatic
    fun epicReportRoute(shortcut: Shortcut): Boolean =
        epicRouteDecision(isEpicShortcut(shortcut), epicStamp(shortcut), UbisoftInstallRegistry.last()?.result)

    // ---- Launch decisions note ------------------------------------------------------------------

    /** The note's file name in the game's log folder (listed in `LogRotation.isOurRunLog`). */
    const val DECISIONS_NAME = "launch_decisions.txt"

    /** File names that give a Ubisoft game away when they are part of its exe path: the loaders, the launcher and its installers. */
    private val PATH_MARKERS = LOADER_PREFIXES + listOf("ubisoftgamelauncher", "ubisoftconnect", "uplayinstaller", "uplaylaunch", "/upc.exe")

    /** True when [path] (either separator, any case) itself carries a Ubisoft marker file name. A string check, no disk access. */
    @JvmStatic
    fun pathHasUbisoftMarker(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        return PATH_MARKERS.any { p.contains(it) }
    }

    /**
     * What makes a launch worth a decisions note, in words, or null when none of it applies (the usual launch).
     * Booleans and a string in, so the common case is a few field reads.
     */
    @JvmStatic
    fun decisionsTrigger(epicLike: Boolean, storeSourceEpic: Boolean, steamChain: Boolean, pathMarker: Boolean, diagRoute: String?): String? {
        val parts = ArrayList<String>()
        if (epicLike) parts += "Epic-like shortcut"
        if (storeSourceEpic) parts += "storeSource=epic"
        if (steamChain) parts += "Ubisoft launcher chain armed"
        if (pathMarker) parts += "Ubisoft file name in the exe path"
        if (diagRoute != null) parts += "Ubisoft launch report route '$diagRoute'"
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /** Why a launch got no Ubisoft launch report, in one line. Pure. */
    @JvmStatic
    fun noRouteReason(storeSource: String, path: String, stampRaw: String, steamChain: Boolean, registrationSkipped: Boolean): String {
        if (steamChain) return "the Ubisoft launcher chain is armed but the report was not started"
        val (epicLike, why) = epicLikeReason(storeSource, path)
        if (!epicLike) return "$why, and no Ubisoft launcher chain in a Steam launch plan"
        val stamp = stampValue(stampRaw)
        val stampText = when (stamp) {
            true -> "1"
            false -> "0 after recheck"
            null -> "unset"
        }
        return when {
            stamp == true -> "Epic-like and stamped Ubisoft but the report was not started"
            registrationSkipped -> "$why; ubisoft stamp=$stampText and the install record was SKIPPED_NOT_UBISOFT"
            else -> "$why; ubisoft stamp=$stampText"
        }
    }

    /** Everything the decisions note prints, already worked out (see [launchDecisionsText]). */
    class LaunchFacts(
        val timestamp: String, val trigger: String,
        val app: String, val flavor: String, val debug: Boolean, val commit: String,
        val shortcutFile: String, val name: String, val exe: String,
        val storeSource: String, val epicAppName: String, val epicSandboxId: String, val epicEos: String,
        val ubisoft: String, val launchMode: String, val steamAppId: String,
        val strictEpic: Boolean, val epicLike: Boolean, val epicLikeWhy: String,
        val installDir: String, val installRoute: String,
        val hasLauncherFiles: String, val stateFile: String,
        val launcherInstalled: String, val registration: String,
        val route: String?, val routeWhy: String,
    )

    /** The text of the decisions note for [f]: a few lines, plain, one fact per line. Pure. */
    @JvmStatic
    fun launchDecisionsText(f: LaunchFacts): String = buildString {
        append("launch decisions (rewritten on every launch of an Epic-like, Ubisoft-marked or Ubisoft-chain shortcut)\n")
        append("timestamp: ").append(f.timestamp).append('\n')
        append("written because: ").append(f.trigger).append('\n')
        append("app: ").append(f.app).append(" flavor=").append(f.flavor).append(" debug=").append(f.debug).append('\n')
        append("commit: ").append(f.commit).append('\n')
        append("shortcut file: ").append(f.shortcutFile).append('\n')
        append("name: ").append(f.name).append('\n')
        append("exe (Windows path in the shortcut): ").append(f.exe).append('\n')
        append("storeSource='").append(f.storeSource).append("'  epicAppName='").append(f.epicAppName).append("'  epicSandboxId='").append(f.epicSandboxId)
            .append("'  epicEos='").append(f.epicEos).append("'  ubisoft='").append(f.ubisoft).append("'  launchMode='").append(f.launchMode)
            .append("'  steamAppId='").append(f.steamAppId).append("'\n")
        append("strict Epic check (storeSource=epic, the old isEpicShortcut): ").append(f.strictEpic).append('\n')
        append("Epic-like (storeSource epic, or empty/custom with the exe under epic_games): ").append(f.epicLike).append(" - ").append(f.epicLikeWhy).append('\n')
        append("install folder: ").append(f.installDir).append("  (found by: ").append(f.installRoute).append(")\n")
        append("hasLauncherFiles(install folder): ").append(f.hasLauncherFiles).append('\n')
        append("uplay_install.state: ").append(f.stateFile).append('\n')
        append("Ubisoft Connect in the prefix: ").append(f.launcherInstalled).append('\n')
        append("install record (UbisoftInstallRegistry.last): ").append(f.registration).append('\n')
        append("Ubisoft launch report route: ").append(f.route ?: "none").append(if (f.route == null) " - " + f.routeWhy else "").append('\n')
    }

    /**
     * Builds the decisions note for a launch of [shortcut], or null when the launch is none of the kinds that get one
     * (see [decisionsTrigger]; the check is a few string tests). [steamChain] = the Steam plan armed the Ubisoft chain,
     * [diagRoute] = the report route this launch chose (null = none). Looks at a few files for the launches that do
     * get a note. Call on the launch worker, after [registerEpicInstall] has run. Never throws.
     */
    @JvmStatic
    fun launchDecisionsFor(context: Context, shortcut: Shortcut?, steamChain: Boolean, diagRoute: String?): String? {
        return try {
            val source = shortcut?.getExtra("storeSource", "").orEmpty()
            val exe = shortcut?.path.orEmpty()
            val strict = shortcut != null && isEpicStoreShortcut(shortcut)
            val (epicLike, why) = epicLikeReason(source, exe)
            val trigger = decisionsTrigger(epicLike, strict, steamChain, pathHasUbisoftMarker(exe), diagRoute) ?: return null

            var found: Pair<File?, String> = null to "no shortcut"
            if (shortcut != null) {
                found = epicInstallDirRoute(context, shortcut)
                if (found.first == null && steamChain) {
                    val steamDir = runCatching { EaSupport.installDirOf(shortcut) }.getOrNull()
                    if (steamDir != null) found = steamDir to "the Steam install folder of the shortcut (${found.second})"
                }
            }
            val dir = found.first
            val stateFile = if (dir == null) "no install folder" else {
                val f = File(dir, UbisoftInstallRegistry.STATE_FILE)
                if (!f.isFile) "absent (${f.path})" else {
                    val st = UbisoftInstallState.read(f)
                    if (st == null) "present but not usable (${f.length()} bytes)" else "present, product id ${st.productId} (${st.name})"
                }
            }
            val launcher = if (shortcut == null) "unknown (no shortcut)" else {
                val files = launcherFileStatus(shortcut.container)
                "launcherInstalled=${launcherInstalled(shortcut.container)} (" +
                    files.joinToString(", ") { it.first + "=" + (if (it.second) "present" else "missing") } + ")"
            }
            val last = UbisoftInstallRegistry.last()
            val facts = LaunchFacts(
                timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
                trigger = trigger,
                app = "${com.winlator.star.BuildConfig.VERSION_NAME} (versionCode ${com.winlator.star.BuildConfig.VERSION_CODE})",
                flavor = com.winlator.star.BuildConfig.FLAVOR,
                debug = com.winlator.star.BuildConfig.DEBUG,
                commit = "none (BuildConfig has no commit field; versionName + versionCode identify the CI build)",
                shortcutFile = shortcut?.file?.path ?: "<no shortcut>",
                name = shortcut?.name ?: "<no shortcut>",
                exe = exe,
                storeSource = source,
                epicAppName = shortcut?.getExtra("epicAppName", "").orEmpty(),
                epicSandboxId = shortcut?.getExtra("epicSandboxId", "").orEmpty(),
                epicEos = shortcut?.getExtra("epicEos", "").orEmpty(),
                ubisoft = shortcut?.getExtra(EXTRA_UBISOFT, "").orEmpty(),
                launchMode = shortcut?.getExtra("launchMode", "").orEmpty(),
                steamAppId = shortcut?.getExtra("steamAppId", "").orEmpty(),
                strictEpic = strict,
                epicLike = epicLike,
                epicLikeWhy = why,
                installDir = dir?.path ?: "<none>",
                installRoute = found.second,
                hasLauncherFiles = if (dir == null) "n/a (no folder)" else hasLauncherFiles(dir).toString(),
                stateFile = stateFile,
                launcherInstalled = launcher,
                registration = last?.toString() ?: "none recorded (registerEpicInstall did not run for this launch)",
                route = diagRoute,
                routeWhy = noRouteReason(
                    source, exe, shortcut?.getExtra(EXTRA_UBISOFT, "").orEmpty(), steamChain,
                    last?.result == UbisoftInstallRegistry.Result.SKIPPED_NOT_UBISOFT,
                ),
            )
            launchDecisionsText(facts)
        } catch (t: Throwable) {
            Log.w(TAG, "could not build the launch decisions note", t)
            null
        }
    }

    /** Overwrites the note in [logDir] with [text]. Returns true when it was written. Never throws. */
    @JvmStatic
    fun writeLaunchDecisions(logDir: File, text: String): Boolean = try {
        logDir.mkdirs()
        File(logDir, DECISIONS_NAME).writeText(text, Charsets.UTF_8)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "could not write $DECISIONS_NAME", t)
        false
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
