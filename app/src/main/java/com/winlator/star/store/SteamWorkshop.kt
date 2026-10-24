package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.core.WinePath
import com.winlator.star.store.blsteam.BlSteamEngine
import com.winlator.star.store.blsteam.CaBundleExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** One Steam Workshop item, as listed by the subscriptions call or a browse page. */
data class WorkshopItem(
    val id: Long,
    val appId: Int,
    val title: String,
    val fileName: String,
    /** Set for older web-hosted items, which download over plain HTTP instead of from a depot. */
    val fileUrl: String,
    val previewUrl: String,
    val sizeBytes: Long,
    /** The depot manifest id of the item's content (0 for web-hosted items). */
    val manifestId: Long,
    val timeUpdated: Long,
    val timeCreated: Long,
    val creator: Long,
    val shortDescription: String,
    val description: String,
    val subscriptions: Int,
    val votesUp: Int,
    val votesDown: Int,
    val voteScore: Float,
    val numChildren: Int,
    val banned: Boolean,
    val previews: List<String>,
    val children: List<Long>,
    val tags: List<String>,
) {
    /** The item has something we can fetch: a depot manifest or a direct file URL. */
    val downloadable: Boolean get() = manifestId != 0L || fileUrl.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("publishedFileId", id)
        .put("appId", appId)
        .put("title", title)
        .put("fileName", fileName)
        .put("fileUrl", fileUrl)
        .put("previewUrl", previewUrl)
        .put("fileSizeBytes", sizeBytes)
        .put("hcontentFile", manifestId)
        .put("timeUpdated", timeUpdated)
        .put("timeCreated", timeCreated)
        .put("creator", creator)
        .put("shortDescription", shortDescription)
        .put("votesUp", votesUp)
        .put("votesDown", votesDown)
        .put("voteScore", voteScore.toDouble())
        .put("tags", JSONArray().apply { tags.forEach { put(JSONObject().put("tag", it)) } })

    companion object {
        fun fromJson(o: JSONObject, fallbackAppId: Int): WorkshopItem {
            val tagList = ArrayList<String>()
            o.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val label = t.optString("displayName").ifEmpty { t.optString("tag") }
                    if (label.isNotEmpty()) tagList.add(label)
                }
            }
            val previewList = ArrayList<String>()
            o.optJSONArray("previews")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let(previewList::add)
            }
            val childList = ArrayList<Long>()
            o.optJSONArray("children")?.let { arr ->
                for (i in 0 until arr.length()) arr.optLong(i).takeIf { it != 0L }?.let(childList::add)
            }
            return WorkshopItem(
                id = o.optLong("publishedFileId"),
                appId = o.optInt("appId", fallbackAppId).takeIf { it != 0 } ?: fallbackAppId,
                title = o.optString("title").ifEmpty { o.optLong("publishedFileId").toString() },
                fileName = o.optString("fileName"),
                fileUrl = o.optString("fileUrl"),
                previewUrl = o.optString("previewUrl"),
                sizeBytes = o.optLong("fileSizeBytes"),
                manifestId = o.optLong("hcontentFile"),
                timeUpdated = o.optLong("timeUpdated"),
                timeCreated = o.optLong("timeCreated"),
                creator = o.optLong("creator"),
                shortDescription = o.optString("shortDescription"),
                description = o.optString("description"),
                subscriptions = o.optInt("subscriptions"),
                votesUp = o.optInt("votesUp"),
                votesDown = o.optInt("votesDown"),
                voteScore = o.optDouble("voteScore", 0.0).toFloat(),
                numChildren = o.optInt("numChildren"),
                banned = o.optBoolean("banned"),
                previews = previewList,
                children = childList,
                tags = tagList,
            )
        }
    }
}

/** The ways the browse page can rank items; [queryType] is Steam's `EPublishedFileQueryType`. */
enum class WorkshopSort(val label: String, val queryType: Int, val days: Int = 0) {
    TRENDING("Trending", 3, 7),
    MOST_SUBSCRIBED("Most subscribed", 9),
    TOP_RATED("Top rated", 0),
    NEWEST("Newest", 1),
    RECENTLY_UPDATED("Recently updated", 21),
}

/** One page of browse results. [total] is every match, not just this page. */
data class WorkshopPage(val total: Int, val items: List<WorkshopItem>)

/** What a subscribe / unsubscribe attempt came to. [eresult] is Steam's code (1 = OK). */
data class WorkshopSubscribeResult(val ok: Boolean, val eresult: Int, val message: String)

/** What an install attempt came to. */
data class WorkshopInstallResult(val ok: Boolean, val message: String)

/** An item that is waiting for or in a download. [active] is false while it waits its turn. */
data class WorkshopDownload(val done: Long, val total: Long, val active: Boolean)

/**
 * Steam Workshop for the Steam store games: browse and subscribe through the signed-in Steam session,
 * download items into the game's library, and tell Goldberg (gbe_fork) where they are.
 *
 * ── Layout ────────────────────────────────────────────────────────────────────────────────────
 * Items live beside the game, in its library: `<library>/steamapps/workshop/content/<appid>/<itemid>/`
 * (the folder every Steam client uses). A game in the older `steam_games` layout keeps them in
 * `<root>/steam_workshop/content/<appid>/<itemid>/`, outside the folder the library sweeps for games.
 * An item folder holds the item's files, a `.workshop_item.json` (what the list showed, for offline use)
 * and a `.workshop_complete` marker holding the `time_updated` that was downloaded. A download goes to
 * `<itemid>.partial` first and is renamed once complete, so a failed update never loses the old files.
 *
 * ── Goldberg ──────────────────────────────────────────────────────────────────────────────────
 * gbe_fork answers `ISteamUGC` from `steam_settings/mods.json`: [syncGoldbergMods] writes it beside
 * each steam_api dll at launch, with each item's Windows path, so a game that asks Steam where its
 * subscribed items are is told. Games that scan their own folder for mods are not covered.
 *
 * All functions that touch the network or the disk are blocking or suspending: call them off the UI thread.
 */
object SteamWorkshop {
    private const val TAG = "BL_WORKSHOP"
    private const val PAGE_SIZE = 30
    private const val MARKER = ".workshop_complete"
    private const val META = ".workshop_item.json"
    private const val PARTIAL = ".partial"

    /** The folder the native depot engine keeps its resume journal in; removed from a finished item. */
    private const val DEPOT_JOURNAL = ".bl_depot"

    /** Free space kept in reserve on top of an item's size. */
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    /** One Workshop download at a time: they share the Steam connection and the disk. */
    private val installLock = Mutex()

    /**
     * Downloads outlive the screen that started them, so the queue lives here, not in a composable:
     * switching tabs or leaving the page does not cancel one.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadsFlow = MutableStateFlow<Map<Long, WorkshopDownload>>(emptyMap())
    private val failuresFlow = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val installedVersionFlow = MutableStateFlow(0)

    /** Queued and running downloads by item id. */
    val downloads: StateFlow<Map<Long, WorkshopDownload>> = downloadsFlow

    /** Why the last download of an item failed; cleared when it is queued again. */
    val failures: StateFlow<Map<Long, String>> = failuresFlow

    /** Counts up after every finished download, so a screen knows to re-read what is installed. */
    val installedVersion: StateFlow<Int> = installedVersionFlow

    /** True when a Steam session is logged on, which every call here needs. */
    fun available(): Boolean = BlSteamEngine.isLoggedOn()

    // ── Network: subscriptions, browse, subscribe ────────────────────────────────────────────

    /** Your subscribed items for [appId], or null when Steam did not answer (distinct from "none"). */
    suspend fun subscribed(appId: Int): List<WorkshopItem>? = withContext(Dispatchers.IO) {
        val session = BlSteamEngine.session() ?: return@withContext null
        val json = session.getSubscribedWorkshopItems(appId)
        if (json == null) {
            Log.w(TAG, "subscriptions app=$appId: Steam did not answer")
            return@withContext null
        }
        parseItems(JSONArray(json), appId).also { Log.i(TAG, "subscriptions app=$appId: ${it.size} item(s)") }
    }

    /** One page of [appId]'s Workshop, ranked by [sort], or by text relevance when [search] is set. */
    suspend fun browse(
        appId: Int,
        sort: WorkshopSort,
        page: Int,
        search: String,
        tags: List<String>,
    ): WorkshopPage? = withContext(Dispatchers.IO) {
        val session = BlSteamEngine.session() ?: return@withContext null
        val text = search.trim()
        val queryType = if (text.isNotEmpty()) QUERY_TEXT_SEARCH else sort.queryType
        val days = if (text.isNotEmpty()) 0 else sort.days
        val json = session.queryWorkshopItems(appId, queryType, page, PAGE_SIZE, text, tags.joinToString(","), days)
        if (json == null) {
            Log.w(TAG, "browse app=$appId type=$queryType page=$page: Steam did not answer")
            return@withContext null
        }
        val o = JSONObject(json)
        WorkshopPage(o.optInt("total"), parseItems(o.optJSONArray("items") ?: JSONArray(), appId)).also {
            Log.i(TAG, "browse app=$appId type=$queryType page=$page: ${it.items.size} of ${it.total}")
        }
    }

    /**
     * Subscribes to / unsubscribes from [item] on the account. Steam's protocol files don't say which
     * `list_type` its subscribed list is, so an unset one is tried first and then 1, the value the
     * subscribed list uses in the client; the codes are logged so a device log shows which one took.
     */
    suspend fun setSubscribed(appId: Int, item: WorkshopItem, subscribe: Boolean): WorkshopSubscribeResult =
        withContext(Dispatchers.IO) {
            val session = BlSteamEngine.session()
                ?: return@withContext WorkshopSubscribeResult(false, -1, "Not signed in to Steam.")
            var last = -1
            for (listType in intArrayOf(0, 1)) {
                last = session.setWorkshopSubscription(appId, item.id, subscribe, listType)
                Log.i(TAG, "subscribe=$subscribe item=${item.id} app=$appId list_type=$listType -> eresult=$last")
                if (last == 1) return@withContext WorkshopSubscribeResult(true, 1, "")
                if (last <= 0) break // no session / no answer: a second try would only wait again
            }
            val why = when (last) {
                0 -> "Steam did not answer in time."
                -1 -> "Not signed in to Steam."
                else -> "Steam refused it (code $last)."
            }
            WorkshopSubscribeResult(false, last, why)
        }

    /** EPublishedFileQueryType.RankedByTextSearch. */
    private const val QUERY_TEXT_SEARCH = 12

    private fun parseItems(arr: JSONArray, appId: Int): List<WorkshopItem> {
        val out = ArrayList<WorkshopItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = WorkshopItem.fromJson(o, appId)
            if (item.id != 0L && !item.banned) out.add(item)
        }
        return out
    }

    // ── Disk: where items live and what is installed ─────────────────────────────────────────

    /** The `…/workshop/content/<appid>` folder for the game installed at [installDir]. */
    fun contentDir(installDir: File, appId: Int): File {
        val steamapps = SteamInstallPaths.steamappsOfInstallDir(installDir)
        val base = if (steamapps != null) {
            File(steamapps, "workshop/content")
        } else {
            // The older steam_games layout: <root>/steam_games/<Game> -> <root>/steam_workshop/content.
            val root = installDir.parentFile?.parentFile ?: installDir.parentFile ?: installDir
            File(root, "steam_workshop/content")
        }
        return File(base, appId.toString())
    }

    /** Where the game [appId] is installed, from the store database, or null when it is not. */
    fun installDirOf(ctx: Context, appId: Int): File? = try {
        SteamDatabase.getInstance(ctx).getGame(appId)?.installDir?.takeIf { it.isNotBlank() }?.let(::File)
    } catch (_: Throwable) { null }

    /** Installed item ids mapped to the `time_updated` they were downloaded at. */
    fun installed(contentDir: File): Map<Long, Long> {
        val out = LinkedHashMap<Long, Long>()
        val dirs = contentDir.listFiles() ?: return out
        for (d in dirs) {
            val id = d.name.toLongOrNull() ?: continue
            if (!d.isDirectory) continue
            val stamp = runCatching { File(d, MARKER).readText().trim().toLong() }.getOrNull() ?: continue
            out[id] = stamp
        }
        return out
    }

    /** True when the installed copy is older than what Steam lists now. */
    fun needsUpdate(item: WorkshopItem, installedStamp: Long?): Boolean =
        installedStamp != null && item.timeUpdated > installedStamp

    /** Items installed in [contentDir], read from their saved `.workshop_item.json` (works offline). */
    fun installedItems(contentDir: File, appId: Int): List<WorkshopItem> =
        installed(contentDir).keys.mapNotNull { id ->
            runCatching {
                WorkshopItem.fromJson(JSONObject(File(File(contentDir, id.toString()), META).readText()), appId)
            }.getOrNull()
        }

    fun remove(contentDir: File, id: Long) {
        File(contentDir, id.toString()).deleteRecursively()
        File(contentDir, "$id$PARTIAL").deleteRecursively()
    }

    /** Free-space shortfall message for downloading [item] into [contentDir], or null when it fits. */
    fun spaceProblem(contentDir: File, item: WorkshopItem): String? {
        if (item.sizeBytes <= 0L) return null
        var probe: File? = contentDir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        val free = probe?.usableSpace ?: return null
        val need = item.sizeBytes + SPACE_MARGIN
        return if (free < need) "Not enough free space: needs ${mb(need)} MB, ${mb(free)} MB free." else null
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    // ── Download ─────────────────────────────────────────────────────────────────────────────

    /** Queues [item] for download into [contentDir]; progress shows in [downloads], a failure in [failures]. */
    fun enqueue(ctx: Context, item: WorkshopItem, contentDir: File) {
        if (downloadsFlow.value.containsKey(item.id)) return
        failuresFlow.update { it - item.id }
        downloadsFlow.update { it + (item.id to WorkshopDownload(0L, item.sizeBytes, false)) }
        val app = ctx.applicationContext
        scope.launch {
            SteamDepotDownloader.acquireDownloadWakelock(app)
            try {
                val result = install(app, item, contentDir) { done, total ->
                    downloadsFlow.update { it + (item.id to WorkshopDownload(done, total, true)) }
                }
                if (!result.ok) failuresFlow.update { it + (item.id to result.message) }
            } finally {
                SteamDepotDownloader.releaseDownloadWakelock()
                downloadsFlow.update { it - item.id }
                installedVersionFlow.update { it + 1 }
            }
        }
    }

    /**
     * Downloads (or updates) [item] into [contentDir]. [onProgress] gets (bytes so far, total) a few
     * times a second. Items download one at a time; a second call waits for the first.
     */
    suspend fun install(
        ctx: Context,
        item: WorkshopItem,
        contentDir: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ): WorkshopInstallResult = installLock.withLock {
        withContext(Dispatchers.IO) {
            if (!item.downloadable) {
                return@withContext WorkshopInstallResult(false, "This item has no downloadable content.")
            }
            spaceProblem(contentDir, item)?.let { return@withContext WorkshopInstallResult(false, it) }
            contentDir.mkdirs()
            if (!contentDir.isDirectory) {
                return@withContext WorkshopInstallResult(false, "Couldn't create the Workshop folder.")
            }
            val partial = File(contentDir, "${item.id}$PARTIAL")
            val itemDir = File(contentDir, item.id.toString())
            try {
                partial.mkdirs()
                val error = if (item.fileUrl.isNotEmpty()) {
                    downloadHttp(item, partial, onProgress)
                } else {
                    downloadDepot(ctx, item, partial, onProgress)
                }
                if (error != null) return@withContext WorkshopInstallResult(false, error)

                File(partial, DEPOT_JOURNAL).deleteRecursively()
                if (payloadFiles(partial).none()) {
                    partial.deleteRecursively()
                    return@withContext WorkshopInstallResult(false, "The download finished but left no files.")
                }
                File(partial, META).writeText(item.toJson().toString())
                downloadPreview(item, partial)
                // Swap in the finished copy. The old one (an update) goes only now, so a failure above
                // never costs the files that already worked.
                itemDir.deleteRecursively()
                if (!partial.renameTo(itemDir)) {
                    partial.deleteRecursively()
                    return@withContext WorkshopInstallResult(false, "Couldn't move the finished download into place.")
                }
                File(itemDir, MARKER).writeText(item.timeUpdated.toString())
                onProgress(item.sizeBytes, item.sizeBytes)
                WorkshopInstallResult(true, "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "install failed for item ${item.id}", e)
                WorkshopInstallResult(false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The depot path. The native call blocks and reports nothing, so progress follows the folder size. */
    private suspend fun downloadDepot(
        ctx: Context,
        item: WorkshopItem,
        partial: File,
        onProgress: (Long, Long) -> Unit,
    ): String? = coroutineScope {
        val session = BlSteamEngine.session() ?: return@coroutineScope "Not signed in to Steam."
        val ca = CaBundleExtractor.ensureBundle(ctx)
        val poller = launch {
            while (isActive) {
                onProgress(SteamDepotDownloader.dirSizeBytes(partial), item.sizeBytes)
                delay(500)
            }
        }
        try {
            val written = withContext(Dispatchers.IO) {
                session.downloadWorkshopItem(item.appId, item.manifestId, partial.absolutePath, ca)
            }
            Log.i(TAG, "depot download item=${item.id} manifest=${item.manifestId} -> $written bytes")
            if (written < 0L) "Steam would not give out this item's files (are you subscribed, and do you own the game?)" else null
        } finally {
            poller.cancel()
        }
    }

    /** The web-hosted path (older items). Keeps the original file name, which games look for by extension. */
    private suspend fun downloadHttp(item: WorkshopItem, partial: File, onProgress: (Long, Long) -> Unit): String? {
        val name = item.fileName.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { item.fileUrl.substringAfterLast('/').substringBefore('?') }
            .ifEmpty { item.id.toString() }
        val target = File(partial, name)
        val have = if (target.isFile) target.length() else 0L
        val conn = (URL(item.fileUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (have > 0L) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && have > 0L) return null // the partial file is already the whole file
            if (code != 200 && code != 206) return "The download server answered HTTP $code."
            val resuming = code == 206 && have > 0L
            val total = if (resuming) have + conn.contentLengthLong else conn.contentLengthLong
            var done = if (resuming) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(target, resuming).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            onProgress(done, if (total > 0L) total else item.sizeBytes)
                            lastReport = now
                        }
                    }
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Best-effort preview picture beside the item, for Goldberg's `mod_images`. */
    private fun downloadPreview(item: WorkshopItem, dir: File) {
        if (item.previewUrl.isEmpty()) return
        try {
            val ext = item.previewUrl.substringBefore('?').substringAfterLast('.', "jpg").lowercase()
                .takeIf { it in setOf("jpg", "jpeg", "png", "gif") } ?: "jpg"
            val target = File(dir, "preview.$ext")
            val conn = (URL(item.previewUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            try {
                if (conn.responseCode != 200) return
                conn.inputStream.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "preview for item ${item.id} failed: $e")
        }
    }

    /** An item's own files: not the bookkeeping dotfiles, nor our preview picture. */
    private fun payloadFiles(dir: File): Sequence<File> =
        dir.walkTopDown().filter { f ->
            f.isFile && f.name != MARKER && f.name != META && !f.name.startsWith(".") &&
                !(f.parentFile == dir && f.nameWithoutExtension == "preview")
        }

    private fun previewOf(dir: File): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == "preview" }

    // ── Goldberg ─────────────────────────────────────────────────────────────────────────────

    /**
     * Writes `steam_settings/mods.json` beside each steam_api dll of the game at [installRoot], listing
     * the installed Workshop items with their Windows paths, so gbe_fork can tell the game where they
     * are. A `mods.json` that is not ours is never touched, and ours is removed when no item is installed.
     * Local files only: safe to run on every launch. Returns how many items were written.
     */
    fun syncGoldbergMods(ctx: Context, container: Container?, appId: Int, installRoot: File): Int {
        try {
            val targets = GoldbergPatcher.analyze(installRoot)
            if (targets.isEmpty()) return 0
            val contentDir = contentDir(installRoot, appId)
            val installedIds = installed(contentDir).keys
            val mods = JSONObject()
            for (id in installedIds) {
                val dir = File(contentDir, id.toString())
                val winPath = if (container != null) {
                    runCatching { WinePath.resolveWindowsPath(container, dir.absolutePath) }.getOrNull()
                } else null
                if (winPath == null) {
                    Log.w(TAG, "mods.json: no Windows path for item $id — container missing or no free drive letter")
                    continue
                }
                mods.put(id.toString(), modsEntry(dir, id, appId, winPath))
            }
            var written = 0
            for (t in targets) {
                val settings = File(t.dir, "steam_settings")
                val file = File(settings, "mods.json")
                if (mods.length() == 0) {
                    if (file.isFile && isOurs(file)) file.delete()
                    File(settings, "mod_images").takeIf { it.isDirectory }?.deleteRecursively()
                    continue
                }
                if (file.isFile && !isOurs(file)) {
                    Log.w(TAG, "mods.json beside ${t.dll.name} is not ours — left alone")
                    continue
                }
                settings.mkdirs()
                val tmp = File(settings, "mods.json.tmp")
                tmp.writeText(mods.toString(2))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
                copyPreviews(contentDir, mods, File(settings, "mod_images"))
                written = mods.length()
            }
            Log.i(TAG, "mods.json: $written item(s) for app $appId in ${targets.size} folder(s)")
            return written
        } catch (e: Throwable) {
            Log.w(TAG, "syncGoldbergMods failed for app $appId", e)
            return 0
        }
    }

    private fun modsEntry(dir: File, id: Long, appId: Int, winPath: String): JSONObject {
        val meta = runCatching { WorkshopItem.fromJson(JSONObject(File(dir, META).readText()), appId) }.getOrNull()
        val files = payloadFiles(dir).toList()
        val primary = files.firstOrNull { it.name == meta?.fileName?.substringAfterLast('/') }
            ?: files.minByOrNull { it.relativeTo(dir).path }
        val entry = JSONObject()
        entry.put("title", meta?.title ?: id.toString())
        if (primary != null) {
            entry.put("primary_filename", primary.relativeTo(dir).path.replace('\\', '/'))
            entry.put("primary_filesize", primary.length())
        }
        // Numbers must stay JSON numbers: gbe_fork skips the whole entry on a string where it wants one.
        entry.put("total_files_sizes", files.sumOf { it.length() })
        if (meta != null && meta.timeUpdated > 0L) entry.put("time_updated", meta.timeUpdated)
        if (meta != null && meta.timeCreated > 0L) entry.put("time_created", meta.timeCreated)
        entry.put("path", winPath)
        previewOf(dir)?.let { entry.put("preview_filename", it.name) }
        if (meta != null) {
            if (meta.shortDescription.isNotEmpty()) entry.put("description", meta.shortDescription)
            if (meta.tags.isNotEmpty()) entry.put("tags", meta.tags.joinToString(","))
            if (meta.previewUrl.isNotEmpty()) entry.put("preview_url", meta.previewUrl)
            entry.put("upvotes", meta.votesUp)
            entry.put("downvotes", meta.votesDown)
        }
        entry.put("workshop_item_url", "$ITEM_URL$id")
        return entry
    }

    /** `mods.json` is ours when every entry carries the Workshop item URL we write. */
    private fun isOurs(file: File): Boolean = runCatching {
        val o = JSONObject(file.readText())
        o.length() > 0 && o.keys().asSequence().all { k ->
            o.optJSONObject(k)?.optString("workshop_item_url")?.startsWith(ITEM_URL) == true
        }
    }.getOrDefault(false)

    private fun copyPreviews(contentDir: File, mods: JSONObject, imagesDir: File) {
        for (key in mods.keys()) {
            val preview = previewOf(File(contentDir, key)) ?: continue
            val dest = File(File(imagesDir, key), preview.name)
            if (dest.isFile && dest.length() == preview.length()) continue
            runCatching {
                dest.parentFile?.mkdirs()
                preview.copyTo(dest, overwrite = true)
            }
        }
    }

    private const val ITEM_URL = "https://steamcommunity.com/sharedfiles/filedetails/?id="
}
