package com.winlator.star.container

import android.content.Context
import android.util.Log
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.FileUtils
import com.winlator.star.core.ProcessHelper
import com.winlator.star.core.WineInfo
import com.winlator.star.xenvironment.ImageFs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * In-place Proton/Wine layer swap for an existing container.
 *
 * A container records its layer as the contents entry name (`Proton-11.0-6-arm64ec-4`) and, at
 * creation, receives a COPY of every builtin PE from that layer in system32/syswow64. Nothing
 * refreshes those copies later, so pointing the container at a newer build of the same line needs
 * (a) the `wineVersion` swap, (b) a refresh of the builtin copies (native overrides such as DXVK or
 * FEX/wowbox64 are left alone — see [ContainerManager.refreshCommonDlls]) and (c) the app-side
 * registry-tweak cache cleared so `applyGeneralPatches` re-runs. Wine's own `wine.inf` update runs
 * on the next launch by itself (`.update-timestamp` no longer matches the new layer's `wine.inf`).
 *
 * Before touching anything the config file and the three registry hives are snapshotted under
 * `<container>/.layer-update-backup/<epoch>/` so [revert] can put the container back on the old
 * layer (which stays installed — the updater never removes layers).
 *
 * Scope: same `type` + same profile `versionName` (hence same arch), strictly higher `verCode`.
 * DroidDeck-Proton layers are one line across their versions (11.0-9.1 -> 11.0-11.1): see
 * [isDroidDeck]; same arch, newer by version then `verCode`.
 * [findNewerInstalled] covers layers already on the device; [findNewerInCatalog] covers a newer
 * build that only exists in the online catalog (the caller downloads it through the normal
 * contents path first, then runs [update] against the INSTALLED entry). Moving between
 * lines (11.0-2 → 11.0-6, GE ↔ plain) is not offered as an "update" — registry defaults,
 * DirectAudio support and game fixes differ — but [switchLine] does it, with the same snapshot,
 * when the user explicitly sets a layer as the default for existing containers.
 */
class ContainerLayerUpdater(private val context: Context) {

    data class Snapshot(val dir: File, val oldEntry: String, val newEntry: String, val time: Long)

    /**
     * A newer build of a container's layer line that is NOT installed yet. [entryName] is the entry
     * the download will create (`Proton-11.0-6-arm64ec-6`); [profile] is the catalog row itself,
     * the thing the contents downloader takes. [sizeBytes] is filled by [probeRemoteSize] when known.
     */
    data class CatalogCandidate(
        val entryName: String,
        val remoteUrl: String,
        val verCode: Int,
        val profile: ContentProfile,
        val sizeBytes: Long? = null,
    )

    /** A parsed contents entry name `<Type>-<versionName>-<verCode>`. */
    private data class Entry(val type: ContentProfile.ContentType, val versionName: String, val verCode: Int)

    /**
     * Entry name of the newest INSTALLED layer of the same line as [container]'s, or null when the
     * container already runs the newest one (or is on the bundled Wine / an unparseable entry).
     * Pure directory scan — no network. [contentsManager] must already be synced.
     */
    fun findNewerInstalled(contentsManager: ContentsManager, container: Container): String? {
        val current = container.wineVersion
        if (WineInfo.isMainWineVersion(current)) return null
        val entry = parseEntry(current) ?: return null
        val dd = isDroidDeckEntry(contentsManager, current)
        val candidates = contentsManager.getProfiles(entry.type) ?: return null
        val best = candidates
            .filter { sameLine(entry.versionName, dd, it.verName, isDroidDeck(it)) && isNewer(entry.versionName, entry.verCode, it.verName, it.verCode) }
            .filter { ContentsManager.getInstallDir(context, it).isDirectory }
            .maxWithOrNull(compareBy<ContentProfile, List<Int>>(VERSION_ORDER) { versionOrder(it.verName) }.thenBy { it.verCode })
            ?: return null
        return ContentsManager.getEntryName(best)
    }

    /**
     * The best catalog row for [container]'s line that is newer than BOTH the container's layer and
     * anything installed ([findNewerInstalled]) — so an installed newer layer always wins and takes
     * the plain installed path. Only rows carrying the catalog's `versionName` field take part;
     * a display label is never parsed into a line. Same type, same line, same arch (the arch is the
     * line's suffix, so an equal line implies it — checked anyway as the guardrail it is), and the
     * row's install dir must not exist (an installed copy is [findNewerInstalled]'s business).
     * [contentsManager] must have its remote profiles set and be synced; no network here.
     */
    fun findNewerInCatalog(contentsManager: ContentsManager, container: Container): CatalogCandidate? {
        val current = container.wineVersion
        if (WineInfo.isMainWineVersion(current)) return null
        val entry = parseEntry(current) ?: return null
        val dd = isDroidDeckEntry(contentsManager, current)
        // Baseline = the newest installed candidate, else the container's own layer.
        val floor = findNewerInstalled(contentsManager, container)?.let { parseEntry(it) } ?: entry
        val arch = archOf(entry.versionName)
        val best = (contentsManager.getProfiles(entry.type) ?: return null)
            .filter { !it.remoteUrl.isNullOrEmpty() && it.type == entry.type }
            .filter { sameLine(entry.versionName, dd, it.versionName, isDroidDeck(it)) && archOf(it.versionName) == arch }
            .filter { isNewer(floor.versionName, floor.verCode, it.versionName, it.verCode) }
            .filter { !ContentsManager.getInstallDir(context, it).isDirectory }
            .maxWithOrNull(compareBy<ContentProfile, List<Int>>(VERSION_ORDER) { versionOrder(it.versionName) }.thenBy { it.verCode })
            ?: return null
        // Entry the wcp will install as: its profile.json carries versionName + verCode, which is what
        // the catalog row mirrors (the profile is re-read after download — see the ViewModel).
        val entryName = "${entry.type}-${best.versionName}-${best.verCode}"
        return CatalogCandidate(entryName, best.remoteUrl, best.verCode, best)
    }

    /** The most recent snapshot taken by [update] for [container], or null. */
    fun latestSnapshot(container: Container): Snapshot? {
        val root = File(container.rootDir, BACKUP_DIR)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return null
        return dirs.mapNotNull { readManifest(it) }.maxByOrNull { it.time }
    }

    /**
     * Whether a Wine session is currently running INSIDE this container. Only one container can be
     * active at a time — `home/xuser` is re-pointed at it by [ContainerManager.activateContainer] —
     * so "this container is the active one" + "wine processes alive" is the running test.
     */
    fun isRunning(container: Container): Boolean {
        val home = container.rootDir.parentFile ?: return false
        val active = File(home, ImageFs.USER)
        val activeName = try {
            active.canonicalFile.name
        } catch (e: Exception) {
            return false
        }
        if (activeName != container.rootDir.name) return false
        return ProcessHelper.listRunningWineProcesses().isNotEmpty()
    }

    /**
     * Move [container] onto [targetEntry]. Returns a one-line user-facing result; failure = the
     * container was NOT modified (all guardrails run before the first write).
     */
    fun update(contentsManager: ContentsManager, container: Container, targetEntry: String): Result<String> {
        val oldEntry = container.wineVersion
        val target = contentsManager.getProfileByEntryName(targetEntry)
            ?: return fail("Layer $targetEntry is not installed")
        val targetDir = ContentsManager.getInstallDir(context, target)
        if (!File(targetDir, "lib/wine").isDirectory) return fail("Layer $targetEntry is incomplete (no lib/wine)")
        val targetParsed = parseEntry(targetEntry) ?: return fail("Unrecognised layer name $targetEntry")
        val oldParsed = parseEntry(oldEntry) ?: return fail("Container is not on a contents layer ($oldEntry)")
        val sameDdLine = isDroidDeckEntry(contentsManager, oldEntry) && isDroidDeck(target)
        if (oldParsed.type != targetParsed.type || !sameLine(oldParsed.versionName, sameDdLine, targetParsed.versionName, sameDdLine))
            return fail("Only updates within the same layer line are supported (${oldParsed.versionName})")
        if (!isNewer(oldParsed.versionName, oldParsed.verCode, targetParsed.versionName, targetParsed.verCode))
            return fail("$targetEntry is not newer than $oldEntry")
        val targetWine = WineInfo.fromIdentifier(context, contentsManager, targetEntry)
        if (contentsManager.getProfileByEntryName(oldEntry) != null) {
            val oldWine = WineInfo.fromIdentifier(context, contentsManager, oldEntry)
            if (oldWine.isArm64EC != targetWine.isArm64EC || oldWine.isWin64 != targetWine.isWin64)
                return fail("Architecture differs between $oldEntry and $targetEntry")
        }
        if (isRunning(container)) return fail("Close the running session in \"${container.name}\" first")

        return swap(container, oldEntry, targetEntry, targetDir, targetWine.isArm64EC).map { (snapshot, written) ->
            val msg = "Updated \"${container.name}\": $oldEntry -> $targetEntry ($written builtin files refreshed)"
            Log.i(TAG, "$msg; snapshot ${snapshot.dir.absolutePath}")
            msg
        }
    }

    /**
     * Move [container] onto [targetEntry] ACROSS layer lines (11.0-2 -> 11.0-6, GE <-> plain, Wine <->
     * Proton) — the "Set as default" path in the Contents hub. Same steps and the same snapshot as
     * [update], so the container's Revert layer action puts it back; what [update] guards against
     * (registry defaults, DirectAudio support and game fixes differ between lines) is the caller's
     * explicit choice here. Only the architecture must match: an arm64ec prefix can't run on an
     * x86_64 layer or the reverse. Returns the snapshot taken (for an in-session undo).
     */
    fun switchLine(contentsManager: ContentsManager, container: Container, targetEntry: String): Result<Snapshot> {
        val oldEntry = container.wineVersion
        if (oldEntry == targetEntry) return Result.failure(IllegalStateException("\"${container.name}\" is already on $targetEntry"))
        val target = contentsManager.getProfileByEntryName(targetEntry)
            ?: return Result.failure(IllegalStateException("Layer $targetEntry is not installed"))
        val targetDir = ContentsManager.getInstallDir(context, target)
        if (!File(targetDir, "lib/wine").isDirectory)
            return Result.failure(IllegalStateException("Layer $targetEntry is incomplete (no lib/wine)"))
        // The old layer must be a contents entry: revert needs it by name (no bundled Wine any more).
        if (parseEntry(oldEntry) == null)
            return Result.failure(IllegalStateException("\"${container.name}\" is not on a contents layer ($oldEntry)"))
        val targetWine = WineInfo.fromIdentifier(context, contentsManager, targetEntry)
        if (contentsManager.getProfileByEntryName(oldEntry) != null) {
            val oldWine = WineInfo.fromIdentifier(context, contentsManager, oldEntry)
            if (oldWine.isArm64EC != targetWine.isArm64EC || oldWine.isWin64 != targetWine.isWin64)
                return Result.failure(IllegalStateException("Architecture differs between $oldEntry and $targetEntry"))
        }
        if (isRunning(container))
            return Result.failure(IllegalStateException("\"${container.name}\" is running"))
        return swap(container, oldEntry, targetEntry, targetDir, targetWine.isArm64EC).map { (snapshot, written) ->
            Log.i(TAG, "Switched \"${container.name}\": $oldEntry -> $targetEntry ($written builtin files refreshed); " +
                "snapshot ${snapshot.dir.absolutePath}")
            snapshot
        }
    }

    /**
     * The shared write half of [update] and [switchLine]: snapshot the config + hives, swap
     * `wineVersion`, clear the tweak cache, refresh the builtin copies from [targetDir]. Every
     * guardrail has run before this is called.
     */
    private fun swap(
        container: Container, oldEntry: String, targetEntry: String, targetDir: File, targetArm64EC: Boolean,
    ): Result<Pair<Snapshot, Int>> {
        val time = System.currentTimeMillis()
        val snapshotDir = File(container.rootDir, "$BACKUP_DIR/$time")
        if (!snapshotDir.mkdirs()) return fail("Couldn't create the backup folder")
        for (rel in SNAPSHOT_FILES) {
            val src = File(container.rootDir, rel)
            if (src.isFile && !FileUtils.copy(src, File(snapshotDir, rel))) {
                FileUtils.delete(snapshotDir)
                return fail("Couldn't back up $rel")
            }
        }
        writeManifest(snapshotDir, oldEntry, targetEntry)

        container.wineVersion = targetEntry
        CLEARED_EXTRAS.forEach { container.putExtra(it, null) }
        container.saveData()

        val written = ContainerManager.refreshCommonDlls(targetDir, targetArm64EC, container.rootDir)
        return Result.success(Snapshot(snapshotDir, oldEntry, targetEntry, time) to written)
    }

    /**
     * Put [container] back on [snapshot]'s old layer: registry hives restored from the snapshot,
     * `wineVersion` reverted, builtin copies refreshed from the OLD layer, tweak cache cleared. The
     * snapshot folder is removed on success. The container's current settings (`.container`) are
     * kept — only the layer field changes; the snapshotted copy stays on disk until the folder goes.
     */
    fun revert(contentsManager: ContentsManager, container: Container, snapshot: Snapshot): Result<String> {
        val old = contentsManager.getProfileByEntryName(snapshot.oldEntry)
            ?: return fail("Layer ${snapshot.oldEntry} is no longer installed — reinstall it to revert")
        val oldDir = ContentsManager.getInstallDir(context, old)
        if (isRunning(container)) return fail("Close the running session in \"${container.name}\" first")

        for (rel in SNAPSHOT_FILES) {
            if (rel == ".container") continue
            val src = File(snapshot.dir, rel)
            if (src.isFile && !FileUtils.copy(src, File(container.rootDir, rel)))
                return fail("Couldn't restore $rel")
        }

        val previous = container.wineVersion
        container.wineVersion = snapshot.oldEntry
        CLEARED_EXTRAS.forEach { container.putExtra(it, null) }
        container.saveData()

        val oldWine = WineInfo.fromIdentifier(context, contentsManager, snapshot.oldEntry)
        val written = ContainerManager.refreshCommonDlls(oldDir, oldWine.isArm64EC, container.rootDir)
        FileUtils.delete(snapshot.dir)
        // Drop the (now empty) backup root too so the container dir stays clean after a revert.
        snapshot.dir.parentFile?.takeIf { it.name == BACKUP_DIR && (it.list()?.isEmpty() == true) }?.delete()
        val msg = "Reverted \"${container.name}\": $previous -> ${snapshot.oldEntry} ($written builtin files refreshed)"
        Log.i(TAG, msg)
        return Result.success(msg)
    }

    private fun <T> fail(reason: String): Result<T> {
        Log.w(TAG, reason)
        return Result.failure(IllegalStateException(reason))
    }

    private fun writeManifest(dir: File, oldEntry: String, newEntry: String) {
        val json = JSONObject()
            .put("oldEntry", oldEntry)
            .put("newEntry", newEntry)
            .put("time", System.currentTimeMillis())
        FileUtils.writeString(File(dir, MANIFEST), json.toString())
    }

    private fun readManifest(dir: File): Snapshot? {
        val file = File(dir, MANIFEST)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(FileUtils.readString(file))
            Snapshot(dir, json.getString("oldEntry"), json.getString("newEntry"), json.getLong("time"))
        } catch (e: Exception) {
            null
        }
    }

    /** Whether the INSTALLED layer [entryName] is a DroidDeck-Proton build (unknown when not installed = false). */
    private fun isDroidDeckEntry(contentsManager: ContentsManager, entryName: String): Boolean =
        contentsManager.getProfileByEntryName(entryName)?.let { isDroidDeck(it) } == true

    /** Mirrors ContentsManager.getProfileByEntryName's split; Wine/Proton entries only. */
    private fun parseEntry(entryName: String?): Entry? {
        if (entryName.isNullOrEmpty()) return null
        val first = entryName.indexOf('-')
        val last = entryName.lastIndexOf('-')
        if (first <= 0 || last <= first) return null
        val type = ContentProfile.ContentType.getTypeByName(entryName.substring(0, first)) ?: return null
        if (type != ContentProfile.ContentType.CONTENT_TYPE_WINE && type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) return null
        val code = entryName.substring(last + 1).toIntOrNull() ?: return null
        return Entry(type, entryName.substring(first + 1, last), code)
    }

    companion object {
        private const val TAG = "ContainerLayerUpdater"
        const val BACKUP_DIR = ".layer-update-backup"
        private val ARCH_SUFFIXES = listOf("-arm64ec", "-x86_64", "-x86")
        private val remoteSizes = ConcurrentHashMap<String, Long>()

        /** `arm64ec` / `x86_64` / `x86` from a line name's suffix, or "" when it carries none. */
        fun archOf(versionName: String?): String =
            ARCH_SUFFIXES.firstOrNull { versionName?.endsWith(it) == true }?.substring(1) ?: ""

        /**
         * Content-Length of [url] via one HEAD (redirects followed), cached per url for the process;
         * null when unknown or on any error. Short timeouts — this only feeds the "(~size)" hint.
         */
        fun probeRemoteSize(url: String): Long? {
            remoteSizes[url]?.let { return it }
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                val len = conn.contentLengthLong
                conn.disconnect()
                len.takeIf { it > 0 }?.also { remoteSizes[url] = it }
            } catch (e: Exception) {
                null
            }
        }

        /** "1.2 GB" / "340 MB" for the download hint. */
        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1L shl 20 -> "${bytes / (1024 * 1024)} MB"
            else -> "${(bytes + 1023) / 1024} KB"
        }
        private const val MANIFEST = "manifest.json"
        private val SNAPSHOT_FILES = listOf(".container", ".wine/system.reg", ".wine/user.reg", ".wine/userdef.reg")

        // patternVersion: re-runs applyGeneralPatches (app-side registry tweaks) on the next launch.
        // dxwrapper: re-applies the wrapper DLLs — cheap insurance, the refresh never touches them.
        private val CLEARED_EXTRAS = listOf("patternVersion", "dxwrapper")

        /** "v5" for `Proton-11.0-6-arm64ec-5` — the short label used by the badge and menu. */
        fun codeLabel(entryName: String): String = "v" + entryName.substringAfterLast('-')

        /**
         * [codeLabel] when [entryName] is the same line as [otherEntry]; otherwise the line is named
         * too ("11.0-11.1 v15"), for DroidDeck-Proton moves between versions.
         */
        fun layerLabel(entryName: String, otherEntry: String?): String {
            val line = lineOf(entryName)
            if (otherEntry == null || line == lineOf(otherEntry)) return codeLabel(entryName)
            val arch = archOf(line)
            return (if (arch.isEmpty()) line else line.removeSuffix("-$arch")) + " " + codeLabel(entryName)
        }

        private fun lineOf(entryName: String): String =
            entryName.substringAfter('-').substringBeforeLast('-')

        private const val DROIDDECK_LABEL = "DroidDeck-Proton"

        /**
         * DroidDeck-Proton layers share version names with GE builds (`11.0-7.1` vs `11.0-9.1`), so
         * they are told apart by their own label: an installed layer's profile description and a
         * catalog row's display name both start with "DroidDeck-Proton".
         */
        fun isDroidDeck(profile: ContentProfile): Boolean =
            profile.desc?.startsWith(DROIDDECK_LABEL) == true || profile.verName?.startsWith(DROIDDECK_LABEL) == true

        /** Same line: identical version name, or two DroidDeck-Proton layers of the same arch. */
        private fun sameLine(curName: String?, curDd: Boolean, candName: String?, candDd: Boolean): Boolean =
            curName != null && (curName == candName || (curDd && candDd && archOf(curName) == archOf(candName)))

        /** Newer by version (`11.0-11.1` > `11.0-9.1`), then by verCode; within one name, verCode only. */
        private fun isNewer(curName: String?, curCode: Int, candName: String?, candCode: Int): Boolean {
            if (curName == candName) return candCode > curCode
            val c = VERSION_ORDER.compare(versionOrder(candName), versionOrder(curName))
            return c > 0 || (c == 0 && candCode > curCode)
        }

        /** `11.0-11.1-arm64ec` -> [11, 0, 11, 1]; non-numeric parts end the list. */
        private fun versionOrder(versionName: String?): List<Int> {
            if (versionName == null) return emptyList()
            val arch = archOf(versionName)
            val base = if (arch.isEmpty()) versionName else versionName.removeSuffix("-$arch")
            return base.split('.', '-').map { it.toIntOrNull() }.takeWhile { it != null }.map { it!! }
        }

        private val VERSION_ORDER = Comparator<List<Int>> { a, b ->
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (d != 0) return@Comparator d
            }
            0
        }
    }
}
