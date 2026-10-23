package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.SaveLocator
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Steam Cloud (UFS) per-game save up/download manager.
 *
 * Mirrors the shape of [GogCloudSaveManager]: a stateless helper with two directional static-style
 * entry points ([downloadSaves] cloud -> local, [uploadSaves] local -> cloud) plus a [Callback].
 * Each op runs on its own background thread and reports progress via the callback.
 *
 * Speaks the real Steam UFS protocol through the engine-agnostic [SteamCloudBackend] seam — the
 * JavaSteam `SteamCloud` handler when the app runs on JavaSteam, the Rust engine's `ccloud` service
 * calls when `use_rust_steam_engine` is ON (Phase 3b-1). Either way the same four primitives:
 *   - [SteamCloudBackend.listFiles]  -> the remote file manifest for an app
 *   - [SteamCloudBackend.downloadOne] -> a signed CDN URL to GET one cloud file
 *   - [SteamCloudBackend.beginBatch] / [SteamCloudBackend.uploadOne] /
 *     [SteamCloudBackend.completeBatch] -> the upload handshake
 *
 * SAFETY — cloud saves can never be deleted by this class. See [uploadSaves]: `filesToDelete` is
 * hard-wired to an empty list on every code path (the seam does not even expose a delete), and an
 * empty local folder is refused before any batch is opened. There is no code path that computes or
 * sends a deletion to the cloud.
 */
object SteamCloudSaveManager {

    private const val TAG = "BH_STEAM_CLOUD"

    /** Blocking timeout for the PICS product-info probe in [hasCloudSupport]. */
    private const val FUTURE_TIMEOUT_SEC = 60L

    /** Length in bytes of a SHA-1 digest — Steam UFS's per-file sha and our local
     *  [SteamCloudBackend.sha1] both produce this. A cloud entry whose sha isn't exactly this long is
     *  treated as "no usable SHA" and its path is always re-uploaded (never skipped). */
    private const val SHA1_LEN = 20

    /** How many files transfer concurrently in [downloadSaves]/[uploadSaves]. Each transfer keeps
     *  its OWN independent [HttpURLConnection] (separate signed CDN URL per file) and its own
     *  jobid-keyed CM future, so per-file parallelism is safe — this is NOT single-connection HTTP/2
     *  multiplexing (the imagefs-truncation footgun was range streams of ONE file over one conn).
     *  Bounded low so we never hammer the CDN or the CM job dispatcher; tune here if needed. */
    private const val TRANSFER_CONCURRENCY = 4

    /** Honest message shown when a game has no Steam Cloud store (or an upload didn't persist).
     *  The saves are never lost — they stay in the local Library — we just don't lie about the cloud. */
    private const val NO_CLOUD_MESSAGE =
        "This game doesn't support Steam Cloud — your saves are backed up locally in the Library."

    /** Honest message when a game ACKS the upload commit but Steam retains nothing (an empty manifest
     *  right after a committed N>0 upload — old titles like FlatOut 2). Saves are safe in the Library. */
    private const val NO_RETENTION_MESSAGE =
        "This game doesn't keep Steam Cloud saves — your saves are backed up locally in the Library."

    /** Per-app cloud-support cache (PICS `ufs/savefiles` is stable per app). Only DEFINITIVE
     *  true/false is cached; an "unknown" (null) is never stored so it can be retried later. */
    private val cloudSupportCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    interface Callback {
        fun onStatus(message: String)
        fun onDone(summary: String)
        fun onError(message: String)
    }

    // ── Cloud -> local ────────────────────────────────────────────────────────
    // Only ever writes to the local filesystem. It reads the remote manifest and GETs files.
    // It is STRUCTURALLY incapable of modifying the cloud: no upload/commit/delete calls are made.

    /** Download every cloud save file for [appId] into [localFolder], preserving the cloud path
     *  layout. Overwrites local copies; never touches the cloud. */
    fun downloadSaves(ctx: Context, appId: Int, localFolder: File, cb: Callback) {
        Thread({
            try {
                val steamCloud = requireCloud() ?: run { cb.onError("Not signed in to Steam"); return@Thread }

                cb.onStatus("Fetching cloud file list…")
                val files = steamCloud.listFiles(appId)
                // With the manifest in hand, move files the old Collect swept in (game data, logs)
                // out of the Library so they are never applied or uploaded. Best-effort.
                try { moveAsideNonSaves(ctx, appId, localFolder, files.map { it.remotePath }) }
                catch (t: Throwable) { Log.w(TAG, "Library cleanup failed for $appId", t) }
                if (files.isEmpty()) { cb.onDone("No cloud saves found for this game"); return@Thread }

                if (!localFolder.exists()) localFolder.mkdirs()

                // Resolve the safe work set first (unsafe cloud paths are skipped, counted below),
                // then GET the files CONCURRENTLY via a bounded pool. Each task runs the backend's
                // per-file downloadOne (its own download-info call + its own HttpURLConnection); no
                // shared connection, no HTTP/2 multiplexing.
                var skipped = 0
                val work = ArrayList<Triple<String, String, File>>() // (displayName, remotePath, dest)
                for (f in files) {
                    val remotePath = f.remotePath
                    val relLocal = sanitizeRelative(remotePath)
                    if (relLocal == null) {
                        Log.w(TAG, "Skipping unsafe cloud path: $remotePath")
                        skipped++
                        continue
                    }
                    work.add(Triple(remotePath.substringAfterLast('/'), remotePath, File(localFolder, relLocal)))
                }

                val downloaded = AtomicInteger(0)
                val failures = ConcurrentLinkedQueue<String>()   // filenames that failed/threw
                runConcurrently(work, "steam-cloud-dl-$appId") { (name, remotePath, dest) ->
                    try {
                        cb.onStatus("Downloading: $name")
                        if (steamCloud.downloadOne(appId, remotePath, dest)) {
                            downloaded.incrementAndGet()
                        } else {
                            failures.add(name)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Download task failed for $name", e)
                        failures.add(name)
                    }
                }

                if (failures.isNotEmpty()) {
                    val first = failures.peek()
                    val more = if (failures.size > 1) " (+${failures.size - 1} more)" else ""
                    cb.onError("Download failed for: $first$more")
                    return@Thread
                }

                // A cloud that still holds an old `%Token%/rest` upload brings that spelling back —
                // fold it into Steam's own name (newer file wins) so the Library keeps one copy.
                normalizeLibrary(localFolder, appId)

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Downloaded ${downloaded.get()} file${plural(downloaded.get())}$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "downloadSaves failed", e)
                cb.onError("Download error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-download-$appId").start()
    }

    // ── Local -> cloud ────────────────────────────────────────────────────────
    // STRICTLY ADDITIVE. Enumerates local files and uploads/overwrites them. `filesToDelete` is
    // ALWAYS an empty list, so the cloud can only gain/refresh files — never lose them. An empty
    // local folder is refused up-front, so we never even open a batch for a wipe.

    /** Upload files under [localFolder] to [appId]'s Steam Cloud — INCREMENTALLY and additively.
     *  Only NEW or CHANGED files are sent: the cloud manifest is fetched first and each local file's
     *  SHA-1 is diffed against the cloud file at the same remote path. A file is skipped iff it
     *  already exists in the cloud with a byte-identical SHA-1; otherwise it is uploaded. Never
     *  deletes anything from the cloud (`filesToDelete` is always empty). Refuses (no-op) if the
     *  local folder has no files. If a cloud SHA is unavailable (manifest fetch failed, or the
     *  entry carries no usable sha), that file is uploaded — correctness over efficiency; we never
     *  skip a file we can't prove is identical. */
    fun uploadSaves(ctx: Context, appId: Int, localFolder: File, cb: Callback) {
        Thread({
            try {
                val steamCloud = requireCloud() ?: run { cb.onError("Not signed in to Steam"); return@Thread }

                cb.onStatus("Scanning local saves…")
                normalizeLibrary(localFolder, appId)
                var localFiles = enumerateLocal(localFolder)   // List<Pair<File, cloudRelPath>>

                // ── BELT-AND-SUSPENDERS GUARD (unchanged) ──────────────────────────────
                // If there is nothing local to upload we return immediately and NEVER call
                // beginAppUploadBatch. This makes an empty/absent save folder a pure no-op and
                // removes any chance of an "upload nothing" turning into a cloud wipe.
                if (localFiles.isEmpty()) {
                    cb.onDone("No local save files found — nothing was sent to the cloud")
                    return@Thread
                }

                // ── HONESTY GUARD 0: already proven to not retain cloud uploads? ───────
                // If a prior upload committed files but Steam kept nothing, we marked this game.
                // Short-circuit before opening any batch — no work, honest message, no false success.
                if (SaveSyncStore.isMarkedNoSteamCloud(appId)) {
                    cb.onError(NO_RETENTION_MESSAGE)
                    return@Thread
                }

                // ── HONESTY GUARD 1: does this game even support Steam Cloud? ───────────
                // Old titles declare NO UFS save-file patterns → no cloud store. The upload handshake
                // would "succeed" but nothing persists, so we'd FALSELY report "Uploaded N". Read the
                // app's declared UFS config from PICS; if it says "no cloud", stop here and tell the
                // truth. NULL = couldn't determine → proceed + post-upload emptiness check catches it.
                // Reported via onError (not onDone) so the SaveSyncStore.recordAfterUpload hook does
                // NOT fire — we must not stamp a lastUploadAt "cloud sync" that never happened.
                val support: Boolean? = hasCloudSupport(ctx, appId)
                if (support == false) {
                    cb.onError(NO_CLOUD_MESSAGE)
                    return@Thread
                }

                // ── INCREMENTAL DIFF (new — still strictly additive) ───────────────────
                // Fetch the cloud manifest and map each cloud file's normalized remote path to its
                // SHA-1 (AppFileInfo.shaFile — a 20-byte digest). A failed fetch, or an entry with a
                // missing/short sha, simply leaves that path OUT of the map, so it counts as
                // "changed" and gets uploaded. We never skip a file we can't prove is byte-identical.
                cb.onStatus("Comparing with cloud…")
                // Older builds uploaded some saves as `%Token%/rest`; the cloud may still hold that
                // spelling next to Steam's own `%Token%rest`. Remember those twins (by canonical key)
                // so a changed save refreshes BOTH names — another PC that downloads either one then
                // gets the current file. Nothing is ever deleted from the cloud.
                val oldSpellingTwins = HashMap<String, MutableList<Pair<String, ByteArray>>>()
                var manifestNames: List<String>? = null
                val cloudShaByPath: Map<String, ByteArray> = try {
                    val map = HashMap<String, ByteArray>()
                    val listing = steamCloud.listFiles(appId)
                    manifestNames = listing.map { it.remotePath }
                    for (f in listing) {
                        val key = sanitizeRelative(f.remotePath) ?: continue
                        if (SteamCloudSavePaths.fuseTokenSlash(key) != null) {
                            SteamCloudSavePaths.canonicalKey(key)?.let { ck ->
                                oldSpellingTwins.getOrPut(ck) { ArrayList() }.add(key to f.sha)
                            }
                        }
                        val sha = f.sha
                        if (sha.size != SHA1_LEN) {
                            Log.w(TAG, "Cloud file has no usable SHA (${sha.size}B), will re-upload: ${f.remotePath}")
                            continue
                        }
                        map[key] = sha
                    }
                    map
                } catch (e: Exception) {
                    // Can't diff → fall back to uploading everything (today's behavior). Never a wipe.
                    Log.w(TAG, "Cloud manifest fetch failed; uploading all local files", e)
                    emptyMap()
                }

                // Never upload what isn't a save: move non-save files (game data swept in by an older
                // Collect) out of the Library first, then re-scan. Needs the manifest; best-effort.
                manifestNames?.let { names ->
                    try {
                        moveAsideNonSaves(ctx, appId, localFolder, names)
                        localFiles = enumerateLocal(localFolder)
                    } catch (t: Throwable) { Log.w(TAG, "Library cleanup failed for $appId", t) }
                }
                if (localFiles.isEmpty()) {
                    cb.onDone("No local save files found — nothing was sent to the cloud")
                    return@Thread
                }

                // Include a local file iff it is new, changed, or its cloud sha is unavailable.
                val toUpload = ArrayList<Pair<File, String>>()
                val queued = HashSet<String>()
                for (entry in localFiles) {
                    val (file, cloudPath) = entry
                    val key = sanitizeRelative(cloudPath)
                    val localSha = SteamCloudBackend.sha1(file)
                    val cloudSha = if (key != null) cloudShaByPath[key] else null
                    if (cloudSha == null || !localSha.contentEquals(cloudSha)) {
                        if (queued.add(cloudPath)) toUpload.add(entry)
                    }
                    // Refresh any old-spelling twin of this file that differs from it.
                    val ck = key?.let { SteamCloudSavePaths.canonicalKey(it) } ?: continue
                    for ((twinName, twinSha) in oldSpellingTwins[ck].orEmpty()) {
                        if (twinName == key || localSha.contentEquals(twinSha)) continue
                        if (queued.add(twinName)) toUpload.add(file to twinName)
                    }
                }

                val localNames = localFiles.mapTo(HashSet()) { it.second }
                val upToDate = localFiles.size - toUpload.count { it.second in localNames }

                // ── NOTHING-TO-UPLOAD GUARD ────────────────────────────────────────────
                // Same belt-and-suspenders as the empty-folder guard: if the diff found no new/
                // changed files we return WITHOUT ever opening a batch.
                if (toUpload.isEmpty()) {
                    cb.onDone("Uploaded 0 changed, $upToDate already up-to-date")
                    return@Thread
                }

                val filesToUpload: List<String> = toUpload.map { it.second }

                // filesToDelete is ALWAYS empty. This is the single source of truth for the
                // "never delete from cloud" guarantee — [SteamCloudBackend.beginBatch] takes no
                // deletion list at all, so nothing in this class can produce one. The incremental
                // diff only ever SHRINKS the upload set; it never produces a deletion.

                cb.onStatus("Opening cloud upload batch…")
                // clientId/appBuildId are best-effort 0L inside the backends (classic token logon
                // exposes no auth-session clientID, and we don't parse the installed build id).
                val batchId = steamCloud.beginBatch(appId, filesToUpload)
                if (batchId == 0L) {
                    cb.onError("Steam refused to open a cloud upload batch — try again in a moment")
                    return@Thread
                }

                // Upload the batch's files CONCURRENTLY (bounded pool) — WITHIN the single open
                // batch. Only the per-file uploadOne is parallelized (its own beginFileUpload +
                // block PUTs over its own HttpURLConnection(s) + commitFileUpload, all jobid-keyed);
                // the batch begin/complete calls stay single and sequential around this loop.
                val uploaded = AtomicInteger(0)
                val allOk = AtomicBoolean(true)
                runConcurrently(toUpload, "steam-cloud-ul-$appId") { (file, cloudPath) ->
                    try {
                        cb.onStatus("Uploading: ${file.name}")
                        if (steamCloud.uploadOne(appId, file, cloudPath, batchId)) {
                            uploaded.incrementAndGet()
                        } else {
                            allOk.set(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Upload task failed for ${file.name}", e)
                        allOk.set(false)
                    }
                }

                // Close the batch with the aggregate result (OK only if every file committed).
                steamCloud.completeBatch(appId, batchId, allOk.get())

                if (allOk.get()) {
                    // ── HONESTY GUARD 2: did the cloud actually KEEP the committed files? ──
                    // We committed N>0 files. Re-fetch the manifest SYNCHRONOUSLY: a COMPLETELY EMPTY
                    // manifest right after a committed upload is the no-retention signature (old games
                    // like FlatOut 2 ack the commit but store nothing). This runs regardless of the
                    // PICS verdict — FlatOut 2 fooled the PICS check by declaring UFS savefiles. It is
                    // safe vs HL2: a retaining game returns a NON-EMPTY manifest, so it never
                    // false-triggers. If the re-fetch itself fails we keep the optimistic result (the
                    // async rebaseline will still correct the pill) and do NOT mark.
                    val emptyAfterUpload = if (uploaded.get() > 0) isCloudManifestEmpty(steamCloud, appId) else null
                    if (emptyAfterUpload == true) {
                        SaveSyncStore.markNoSteamCloud(ctx, appId)   // remember → short-circuit next time
                        cb.onError(NO_RETENTION_MESSAGE)             // onError: no false success, no lastUploadAt stamp
                    } else {
                        cb.onDone("Uploaded ${uploaded.get()} changed, $upToDate already up-to-date")
                    }
                } else {
                    cb.onError("Uploaded ${uploaded.get()} of ${toUpload.size} changed; some files failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadSaves failed", e)
                cb.onError("Upload error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-upload-$appId").start()
    }

    // ── Three-tier moves (Cloud ⇄ Library ⇄ Container) ───────────────────────────
    // Library = the managed local folder [SteamCloudSavePaths.libraryDir]; canonical local copy.
    // These wrap the primitives above / add container-side copies. SAFETY is unchanged: Download and
    // Upload delegate to [downloadSaves]/[uploadSaves] (Upload stays additive, filesToDelete empty,
    // empty-Library refused). Apply/Collect are pure copies — they may overwrite the DESTINATION
    // side only and NEVER delete the source and NEVER touch the cloud.

    /** Cloud → Library. Thin wrapper over [downloadSaves] with the Library dir resolved internally. */
    fun downloadToLibrary(ctx: Context, appId: Int, cb: Callback) {
        downloadSaves(ctx, appId, SteamCloudSavePaths.libraryDir(ctx, appId),
            hooked(cb) { SaveSyncStore.recordAfterDownload(ctx, appId) })
    }

    /** Library → Cloud, strictly additive. Thin wrapper over [uploadSaves] (filesToDelete stays
     *  emptyList(); an empty Library is refused before any batch is opened). */
    fun uploadFromLibrary(ctx: Context, appId: Int, cb: Callback) {
        uploadSaves(ctx, appId, SteamCloudSavePaths.libraryDir(ctx, appId),
            hooked(cb) { SaveSyncStore.recordAfterUpload(ctx, appId) })
    }

    // ── Cloud-support detection (UFS config from PICS product info) ───────────────

    /**
     * Whether [appId] actually supports Steam Cloud, read from the app's DECLARED UFS config in PICS
     * product info (`appinfo → ufs`, see [SteamUfsConfig]). A game has a cloud store when it declares
     * Auto-Cloud `savefiles` rules OR a `quota` / `maxnumfiles` (games that save through the Steam API —
     * Left 4 Dead 2, Counter-Strike: Source — have a quota and NO savefiles rules), or when its cloud
     * manifest already holds files. Only a game with none of these has no cloud store.
     *
     * Returns:
     *  - `true`  — rules, a quota/file limit, or files already in the cloud (Half-Life 2, Left 4 Dead 2).
     *  - `false` — populated product info with no rules and no quota, and an empty/unknown manifest,
     *              or a game proven not to retain uploads (FlatOut 2, see [SaveSyncStore.markNoSteamCloud]).
     *  - `null`  — couldn't determine (not signed in, PICS query failed/timed out, or metadata-only
     *              product info with no populated KeyValues). Callers should NOT treat null as "no
     *              cloud"; upload falls back to a post-upload persistence check instead.
     *
     * Definitive results are cached per app (the UFS config is stable); `null` is never cached.
     */
    fun hasCloudSupport(ctx: Context, appId: Int): Boolean? {
        // A game we already proved doesn't retain uploads is definitively "no cloud" — this wins over
        // both the cache and PICS (FlatOut 2 declares UFS savefiles yet keeps nothing).
        if (SaveSyncStore.isMarkedNoSteamCloud(appId)) return false

        cloudSupportCache[appId]?.let { return it }

        // Files already in this game's cloud are proof by themselves (offline-safe: last observed count).
        if (SaveSyncStore.lastKnownCloudFileCount(appId) > 0) {
            cloudSupportCache[appId] = true
            return true
        }

        return try {
            // Engine-agnostic single-app product-info read (JavaSteam PICS future / Rust engine PICS
            // hop), cached by SteamUfsConfig — null when not signed in, which the caller treats as
            // "unknown".
            val config = SteamUfsConfig.get(ctx, appId, allowNetwork = true, timeoutMs = FUTURE_TIMEOUT_SEC * 1000L)
            if (config != null && config.declaresCloud) {
                cloudSupportCache[appId] = true
                return true
            }
            // PICS says no cloud (or couldn't tell): a non-empty live manifest still proves it.
            val manifestCount = try { SteamCloudBackend.current()?.listFiles(appId)?.size } catch (e: Exception) { null }
            when {
                manifestCount != null && manifestCount > 0 -> { cloudSupportCache[appId] = true; true }
                config != null -> { cloudSupportCache[appId] = false; false }   // definitive: no quota, no rules, empty cloud
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "hasCloudSupport: PICS product-info query failed for appId=$appId", e)
            null
        }
    }

    /**
     * Post-upload retention check. Re-fetches the cloud manifest and reports whether it is COMPLETELY
     * EMPTY (0 files). Returns true = empty (the just-committed upload was NOT retained → no cloud),
     * false = non-empty (retained, e.g. HL2), or null if the manifest re-fetch itself failed (⇒ can't
     * tell → caller keeps the optimistic success and does not mark).
     */
    private fun isCloudManifestEmpty(sc: SteamCloudBackend, appId: Int): Boolean? {
        return try {
            sc.listFiles(appId).isEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "post-upload emptiness check failed for appId=$appId", e)
            null
        }
    }

    // ── Combo actions (Cloud ⇄ Container in one press) ───────────────────────────
    // Each combo chains two of the moves above via callbacks: phase-1's onDone triggers phase 2.
    // onStatus from BOTH phases is forwarded to the caller; a phase-1 onError stops and propagates;
    // the final onDone composes both phases' summaries. Both require the game to be set up in a
    // container (checked up front via [SteamCloudSavePaths.resolveContainer]) — otherwise they do NO
    // work and report a single, clear error. The [SaveSyncStore] record hooks fire INSIDE the
    // underlying wrapper moves (download/apply/collect/upload); the combos add none, so nothing is
    // double-recorded. Upload stays additive (unchanged).

    /** ⬇ Sync from Cloud — Download (Cloud→Library) THEN Apply (Library→Container). No work + a
     *  single [Callback.onError] if the game isn't in a container yet. */
    fun syncFromCloud(ctx: Context, appId: Int, installDir: String, cb: Callback) {
        if (SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) == null) {
            cb.onError("This game needs to be added to a container first.")
            return
        }
        // Phase 1: Download. Its onDone chains into phase 2; its onError stops the whole combo.
        downloadToLibrary(ctx, appId, object : Callback {
            override fun onStatus(message: String) = cb.onStatus(message)
            override fun onError(message: String) = cb.onError(message)
            override fun onDone(downloadSummary: String) {
                // Phase 2: Apply. Its onDone composes the final combo summary.
                applyToContainer(ctx, appId, installDir, object : Callback {
                    override fun onStatus(message: String) = cb.onStatus(message)
                    override fun onError(message: String) = cb.onError(message)
                    override fun onDone(applySummary: String) {
                        cb.onDone("Synced from cloud ($downloadSummary; $applySummary)")
                    }
                })
            }
        })
    }

    /** ⬆ Sync to Cloud — Collect (Container→Library) THEN Upload (Library→Cloud, strictly additive).
     *  No work + a single [Callback.onError] if the game isn't in a container yet. */
    fun syncToCloud(ctx: Context, appId: Int, installDir: String, cb: Callback) {
        if (SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) == null) {
            cb.onError("This game needs to be added to a container first.")
            return
        }
        // Phase 1: Collect. Its onDone chains into phase 2; its onError stops the whole combo.
        collectFromContainer(ctx, appId, installDir, object : Callback {
            override fun onStatus(message: String) = cb.onStatus(message)
            override fun onError(message: String) = cb.onError(message)
            override fun onDone(collectSummary: String) {
                // Phase 2: Upload — additive (filesToDelete stays emptyList()); composes the summary.
                uploadFromLibrary(ctx, appId, object : Callback {
                    override fun onStatus(message: String) = cb.onStatus(message)
                    override fun onError(message: String) = cb.onError(message)
                    override fun onDone(uploadSummary: String) {
                        cb.onDone("Synced to cloud ($collectSummary; $uploadSummary)")
                    }
                })
            }
        })
    }

    // ── Blocking / gated variants (Java-friendly, for game launch/exit hooks) ─────
    // The async combos above report via a Callback on their own worker thread. The launch/exit paths
    // want a single blocking call that returns a summary String. These drive the existing combos to
    // completion with a CountDownLatch, bounded so a stalled CM/upload can't hang the game exit. Every
    // one is non-throwing at the boundary — it returns an error/summary String instead.

    /** Overall bound for a blocking cloud op (Collect+Upload / Download+Apply). Keeps game-exit snappy;
     *  on timeout the underlying additive transfer keeps running in the background and simply isn't
     *  awaited (a later sync reconciles — uploads are additive, downloads overwrite the Library). */
    private const val BLOCKING_BOUND_MS = 15_000L

    /** Result of a blocking drive of one [Callback]-based move. */
    private data class BlockingResult(val ok: Boolean, val summary: String)

    /** Start a [Callback]-based move and block up to [boundMs] for its terminal onDone/onError. */
    private fun runBlockingMove(boundMs: Long, start: (Callback) -> Unit): BlockingResult {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<BlockingResult>()
        try {
            start(object : Callback {
                override fun onStatus(message: String) {}
                override fun onDone(summary: String) {
                    holder.compareAndSet(null, BlockingResult(true, summary)); latch.countDown()
                }
                override fun onError(message: String) {
                    holder.compareAndSet(null, BlockingResult(false, message)); latch.countDown()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "runBlockingMove start failed", e)
            return BlockingResult(false, "Sync error: ${e.message ?: e.javaClass.simpleName}")
        }
        val done = try { latch.await(boundMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!done) return BlockingResult(false, "Still syncing in the background (didn't finish within ${boundMs / 1000}s)")
        return holder.get() ?: BlockingResult(false, "No result")
    }

    /**
     * [hasCloudSupport] wrapped with a persisted [SteamPrefs] cache so a launch/exit path doesn't hit
     * PICS every time. Returns the persisted verdict if known; otherwise probes once and persists a
     * DEFINITIVE result. `null` (unknown) is never cached — it can be retried later.
     */
    @JvmStatic
    fun hasCloudSupportCached(ctx: Context, appId: Int): Boolean? {
        SteamPrefs.getCloudSupportCached(ctx, appId)?.let { return it }
        val resolved = hasCloudSupport(ctx, appId)
        if (resolved != null) SteamPrefs.setCloudSupportCached(ctx, appId, resolved)
        return resolved
    }

    /**
     * Blocking Collect→Upload (additive). If [hasCloudSupportCached] is `false`, does the local Collect
     * (Container→Library) ONLY and returns a "local only" summary — no cloud batch is opened. Never
     * throws; returns an error/summary String.
     */
    @JvmStatic
    fun syncToCloudBlocking(ctx: Context, appId: Int, installDir: String): String {
        return try {
            // No-cloud games: still preserve the save locally (Collect into the Library), just don't
            // pretend to upload. Same honest posture as uploadSaves' NO_CLOUD path.
            if (hasCloudSupportCached(ctx, appId) == false) {
                val r = runBlockingMove(BLOCKING_BOUND_MS) { cb -> collectFromContainer(ctx, appId, installDir, cb) }
                return "No Steam Cloud support — saved locally only (${r.summary})"
            }
            val r = runBlockingMove(BLOCKING_BOUND_MS) { cb -> syncToCloud(ctx, appId, installDir, cb) }
            // Same post-exit sync signal the genuine client sends (`CCloud.AppExitSyncDone`) — the
            // Rust backend implements it, JavaSteam's is a no-op. Best-effort, never changes the result.
            try { requireCloud()?.signalAppExitSyncDone(appId, uploadsCompleted = r.ok, uploadsRequired = true) }
            catch (t: Throwable) { Log.w(TAG, "exit-sync signal failed", t) }
            r.summary
        } catch (e: Exception) {
            Log.e(TAG, "syncToCloudBlocking failed", e)
            "Sync error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Blocking Download→Apply with NEWEST-WINS. Downloads the cloud saves into the Library first (that
     * never touches the container), then compares the cloud copy's newest mtime (now reflected in the
     * Library) against the container's newest save mtime via [staleness]. If the container copy is
     * newer-or-equal, the Apply is SKIPPED (local kept) and that is reported. Never throws.
     */
    @JvmStatic
    fun syncFromCloudNewestWins(ctx: Context, appId: Int, installDir: String): String {
        return try {
            if (SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) == null) {
                return "This game needs to be added to a container first."
            }
            // Same pre-launch signal the genuine client sends (`CCloud.AppLaunchIntent`): Steam
            // reports pending cloud operations from another machine. Logged only — the newest-wins
            // compare below is what decides; the Rust backend implements it, JavaSteam's is a no-op.
            try {
                requireCloud()?.signalAppLaunchIntent(appId)?.let { ops ->
                    if (ops.isNotEmpty()) Log.i(TAG, "launch intent (appId $appId): pending cloud ops $ops")
                }
            } catch (t: Throwable) { Log.w(TAG, "launch-intent signal failed", t) }
            // Warm the UFS-config cache (savefiles rules) so the exit-time Collect can scope itself
            // without a network wait. Fire-and-forget.
            Thread({ try { SteamUfsConfig.get(ctx, appId, allowNetwork = true) } catch (_: Throwable) {} },
                "steam-ufs-prefetch-$appId").apply { isDaemon = true }.start()
            // Phase 1: Download cloud → Library. Populates the Library with the cloud copy (mtimes
            // preserved from the cloud timestamps), leaving the container untouched.
            val dl = runBlockingMove(BLOCKING_BOUND_MS) { cb -> downloadToLibrary(ctx, appId, cb) }
            if (!dl.ok) return dl.summary   // e.g. not signed in / download failed — nothing to apply

            // Phase 2: newest-wins. libraryNewest now == the cloud copy's newest mtime; compare to the
            // container's newest save. staleness() scopes the container side to THIS game's saves.
            val st = staleness(ctx, appId, installDir)
            if (st.libraryFileCount == 0) {
                return "Nothing in the cloud to apply (${dl.summary})"
            }
            if (st.containerFileCount > 0 && st.containerNewestMtime >= st.libraryNewestMtime) {
                return "Kept your local save — the container copy is newer than (or the same age as) " +
                    "the cloud. Cloud copy downloaded to your Library (${dl.summary})."
            }
            // Cloud is newer (or the container has no save yet) → Apply Library → Container.
            val ap = runBlockingMove(BLOCKING_BOUND_MS) { cb -> applyToContainer(ctx, appId, installDir, cb) }
            "Synced from cloud (${dl.summary}; ${ap.summary})"
        } catch (e: Exception) {
            Log.e(TAG, "syncFromCloudNewestWins failed", e)
            "Sync error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Wrap a caller [Callback] so [onSuccess] runs after a successful move (on the move's own worker
     * thread, right after the UI is notified via [Callback.onDone]). Used to fire the
     * [SaveSyncStore] record hooks without changing any move's behavior; a hook failure is swallowed
     * so it can never turn a successful move into a reported error.
     */
    private fun hooked(delegate: Callback, onSuccess: () -> Unit): Callback = object : Callback {
        override fun onStatus(message: String) = delegate.onStatus(message)
        override fun onDone(summary: String) {
            // Run the record hook's FAST/LOCAL write BEFORE delivering onDone, so the UI's post-move
            // per-row statusOf reads the freshly-stamped lastDownloadAt/lastUploadAt (was stale when
            // onDone fired first). The hook's SLOW cloud re-baseline is dispatched to its own thread
            // inside SaveSyncStore, so this does not delay onDone.
            try { onSuccess() } catch (e: Exception) { Log.w(TAG, "save-status record hook failed", e) }
            delegate.onDone(summary)
        }
        override fun onError(message: String) = delegate.onError(message)
    }

    /**
     * The game's container side, resolved once per move: its launch shortcut (→ container + launch
     * mode) and the remote-storage layout that launch mode uses for Steam API files.
     */
    private class GameTarget(
        val shortcut: Shortcut,
        val installDir: String,
        val appId: Int,
        val remote: SteamCloudSavePaths.RemoteTarget,
    ) {
        val container: Container get() = shortcut.container
        fun toContainer(rel: String): File? =
            SteamCloudSavePaths.toContainerPath(rel, container, installDir, appId, remote)
        fun toLibrary(f: File): String? =
            SteamCloudSavePaths.toLibraryRel(f, container, installDir, appId, remote)
    }

    private fun resolveTarget(ctx: Context, appId: Int, installDir: String): GameTarget? {
        val sc = resolveShortcut(ctx, installDir) ?: return null
        return GameTarget(sc, installDir, appId, SteamCloudSavePaths.remoteTargetFor(ctx, sc))
    }

    /** Library → Container. For every file in the Library, translate its cloud name to an absolute
     *  container path via [SteamCloudSavePaths.toContainerPath] and copy it in (mkdirs, mtime
     *  preserved). When two Library names land on the same container file, the NEWER one (by mtime)
     *  is the one applied — never whichever the folder listing happened to return last. Overwrites
     *  the container's copies; never deletes anything, never touches the cloud. Files whose leading
     *  root token is unknown/unsafe are skipped (logged), never guessed. */
    fun applyToContainer(ctx: Context, appId: Int, installDir: String, outerCb: Callback) {
        val cb = hooked(outerCb) { SaveSyncStore.recordAfterApply(ctx, appId) }
        Thread({
            try {
                val library = SteamCloudSavePaths.libraryDir(ctx, appId)
                val target = resolveTarget(ctx, appId, installDir)
                    ?: run { cb.onError("This game isn't set up in a container yet"); return@Thread }
                normalizeLibrary(library, appId)

                cb.onStatus("Scanning Library…")
                val files = enumerateLocal(library)
                if (files.isEmpty()) {
                    cb.onDone("Library is empty — download from the cloud first")
                    return@Thread
                }

                // Group by destination, newest source wins (ties: Steam's fused spelling, then name).
                var skipped = 0
                val byDest = LinkedHashMap<String, Triple<File, String, File>>()   // destKey -> (src, rel, dest)
                for ((file, rel) in files) {
                    val dest = target.toContainer(rel)
                    if (dest == null) {
                        Log.w(TAG, "Apply: skipping unmapped/unsafe path: $rel")
                        skipped++
                        continue
                    }
                    val key = dest.absolutePath
                    val cur = byDest[key]
                    if (cur == null || isPreferredSource(file, rel, cur.first, cur.second)) byDest[key] = Triple(file, rel, dest)
                }

                var applied = 0
                for ((file, _, dest) in byDest.values) {
                    cb.onStatus("Applying: ${file.name}")
                    copyPreserving(file, dest)
                    applied++
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Applied $applied file${plural(applied)} to " +
                    "${SteamCloudSavePaths.containerLabel(target.container)}$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "applyToContainer failed", e)
                cb.onError("Apply error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-apply-$appId").start()
    }

    /** Apply tie-break: newer mtime wins; on a tie prefer Steam's fused spelling, then the smaller name. */
    private fun isPreferredSource(a: File, aRel: String, b: File, bRel: String): Boolean {
        val am = a.lastModified(); val bm = b.lastModified()
        if (am != bm) return am > bm
        val aOld = SteamCloudSavePaths.fuseTokenSlash(aRel) != null
        val bOld = SteamCloudSavePaths.fuseTokenSlash(bRel) != null
        if (aOld != bOld) return !aOld
        return aRel < bRel
    }

    /** Container → Library. Walks the container's save scopes for this game (see
     *  [enumerateContainerSaves]), maps each file back to its cloud name via
     *  [SteamCloudSavePaths.toLibraryRel] (Steam's own `%Root%rest` spelling), and copies it into the
     *  Library (mkdirs, mtime preserved). Overwrites the Library's copies; never deletes anything from
     *  the container, never touches the cloud. */
    fun collectFromContainer(ctx: Context, appId: Int, installDir: String, outerCb: Callback) {
        val cb = hooked(outerCb) { SaveSyncStore.recordAfterCollect(ctx, appId) }
        Thread({
            try {
                val library = SteamCloudSavePaths.libraryDir(ctx, appId)
                val target = resolveTarget(ctx, appId, installDir)
                    ?: run { cb.onError("This game isn't set up in a container yet"); return@Thread }
                normalizeLibrary(library, appId)

                cb.onStatus("Scanning container saves…")
                val saves = enumerateContainerSaves(ctx, target, allowNetwork = true)
                if (saves.isEmpty()) {
                    cb.onDone("No saves found in " +
                        "${SteamCloudSavePaths.containerLabel(target.container)} to collect")
                    return@Thread
                }

                var collected = 0
                var skipped = 0
                for ((file, libraryRel) in saves) {
                    val relLocal = sanitizeRelative(libraryRel)
                    if (relLocal == null) {
                        Log.w(TAG, "Collect: skipping unsafe library path: $libraryRel")
                        skipped++
                        continue
                    }
                    cb.onStatus("Collecting: ${file.name}")
                    copyPreserving(file, File(library, relLocal))
                    collected++
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Collected $collected file${plural(collected)} into your Library$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "collectFromContainer failed", e)
                cb.onError("Collect error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-collect-$appId").start()
    }

    /** Freshness snapshot for the staleness guard the UI shows before Apply/Upload. Synchronous
     *  filesystem scan — the caller runs it off the main thread. Container side is scoped to this
     *  game's save files (the same set [collectFromContainer] would collect), so it never reflects
     *  other games' data. Never touches the network. */
    data class Staleness(
        val libraryNewestMtime: Long,   // 0 if Library empty/absent
        val containerNewestMtime: Long, // 0 if container absent or no save files
        val libraryFileCount: Int,
        val containerFileCount: Int,
    )

    fun staleness(ctx: Context, appId: Int, installDir: String): Staleness {
        val library = SteamCloudSavePaths.libraryDir(ctx, appId)
        val libraryFiles = enumerateLocal(library)
        val libraryNewest = libraryFiles.maxOfOrNull { it.first.lastModified() } ?: 0L

        val target = resolveTarget(ctx, appId, installDir)
        val containerSaves = if (target == null) emptyList() else try {
            enumerateContainerSaves(ctx, target, allowNetwork = false)
        } catch (e: Exception) {
            Log.w(TAG, "staleness: container scan failed", e); emptyList()
        }
        val containerNewest = containerSaves.maxOfOrNull { it.first.lastModified() } ?: 0L

        return Staleness(libraryNewest, containerNewest, libraryFiles.size, containerSaves.size)
    }

    /**
     * The container-side save files for this game, paired with their cloud names — scoped to what
     * Steam itself would sync, never a whole folder tree:
     *
     *  1. **Tracked files** — every file the Library already holds (its names are cloud names), taken
     *     exactly (the file itself, not its folder), keeping the Library's spelling.
     *  2. **Steam API remote store** — the game's own `…/<appId>/remote` folder for its launch mode
     *     ([SteamCloudSavePaths.remoteStorageDir]); it belongs to this game alone, so all of it.
     *  3. **Auto-Cloud rules** — when PICS declares `savefiles` rules ([SteamUfsConfig]), the files
     *     under each rule's root+path that match its pattern (recursively only when the rule says so).
     *  4. **Name-match discovery** ([SaveLocator.discover]) — only for a game with no declared cloud
     *     (a local-only backup) or whose UFS config isn't known yet.
     *
     * The old pass walked each tracked file's parent folder recursively, which for a cloud file at the
     * top of the game folder (`%GameInstall%valve/config.cfg`) swallowed the whole game (Half-Life:
     * 4,277 files). Every file is mapped through [SteamCloudSavePaths.toLibraryRel], which rejects
     * escapes and unknown roots (null ⇒ skip). [allowNetwork] = may fetch the UFS config (bounded).
     */
    private fun enumerateContainerSaves(ctx: Context, target: GameTarget, allowNetwork: Boolean): List<Pair<File, String>> {
        val shortcut = target.shortcut
        val container: Container = target.container
        val library = SteamCloudSavePaths.libraryDir(ctx, target.appId)

        val out = ArrayList<Pair<File, String>>()
        val seen = HashSet<String>()

        fun add(f: File, name: String) {
            if (!f.isFile) return
            val canon = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
            if (seen.add(canon)) out.add(f to name)
        }
        fun consider(f: File) {
            if (!f.isFile) return
            val name = target.toLibrary(f) ?: return
            add(f, name)
        }

        // ── 1. Files the Library already tracks — exact files only. ──
        for ((_, rel) in enumerateLocal(library)) {
            val dest = target.toContainer(rel) ?: continue
            if (dest.isFile) add(dest, rel)
        }

        // ── 2. The game's Steam API remote store. ──
        SteamCloudSavePaths.remoteStorageDir(container, target.appId, target.remote)
            ?.takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile }?.forEach { consider(it) }

        // ── 3. Auto-Cloud rules. ──
        val config = ufsConfigBounded(ctx, target.appId, if (allowNetwork) UFS_FETCH_WAIT_MS else 0L)
        if (config != null) for (rule in config.rules) {
            val base = SteamCloudSavePaths.ruleBaseDir(rule, container, target.installDir, target.appId, target.remote)
            if (base == null || !base.isDirectory) continue
            val regex = SteamUfsConfig.patternRegex(rule.pattern)
            val files = if (rule.recursive) base.walkTopDown().filter { it.isFile }
                        else (base.listFiles()?.asSequence() ?: emptySequence()).filter { it.isFile }
            files.filter { regex.matches(it.name) }.forEach { consider(it) }
        }

        // ── 4. Name-match discovery: local-only games, or cloud config not known yet. ──
        if (config == null || !config.declaresCloud) {
            try {
                val profile = SaveLocator.profileDir(container)
                val candidates = SaveLocator.discover(
                    container,
                    shortcut.name ?: "",
                    shortcut.path ?: "",
                    shortcut.wmClass ?: "",
                )
                for (c in candidates) {
                    val dir = File(profile, c.relPath)
                    if (!dir.isDirectory) continue
                    dir.walkTopDown().filter { it.isFile }.forEach { consider(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Collect discovery pass failed", e)
            }
        }

        return out
    }

    /** How long a Collect may wait for a not-yet-cached UFS config before going on without it. */
    private const val UFS_FETCH_WAIT_MS = 4_000L

    /**
     * The cached UFS config, else (when [waitMs] > 0) a PICS read on a side thread awaited at most
     * [waitMs] — so a slow network can never stretch the exit-time Collect past its bound. The read
     * keeps going after a timeout and lands in the cache for next time.
     */
    private fun ufsConfigBounded(ctx: Context, appId: Int, waitMs: Long): SteamUfsConfig.Config? {
        SteamUfsConfig.cached(ctx, appId)?.let { return it }
        if (waitMs <= 0L) return null
        val holder = AtomicReference<SteamUfsConfig.Config?>()
        val latch = CountDownLatch(1)
        Thread({
            try { holder.set(SteamUfsConfig.get(ctx, appId, allowNetwork = true)) } catch (_: Throwable) {}
            finally { latch.countDown() }
        }, "steam-ufs-$appId").apply { isDaemon = true }.start()
        try { latch.await(waitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return holder.get()
    }

    /**
     * Move Library files that are not saves out of the way (to [SteamCloudSavePaths.movedAsideDir],
     * never deleted). Runs after a successful cloud listing, when we know both what the cloud holds
     * ([manifestNames]) and what the game declares ([SteamUfsConfig]). A Library file stays if it is
     * in the manifest (any spelling), a Steam API remote file, or covered by an Auto-Cloud rule.
     * Otherwise it is moved aside only when that is provably safe:
     *  - the game has Auto-Cloud rules (so the rules define what a save is), or
     *  - it is an install-folder file and the cloud already holds files for this game
     *    (the Half-Life case: game data swept in by the old folder walk).
     * Games without a declared cloud are never touched — their Library is the local backup.
     */
    private fun moveAsideNonSaves(ctx: Context, appId: Int, library: File, manifestNames: List<String>) {
        val config = SteamUfsConfig.get(ctx, appId, allowNetwork = true) ?: return
        if (!config.declaresCloud) return
        val manifestKeys = HashSet<String>()
        for (n in manifestNames) SteamCloudSavePaths.canonicalKey(n)?.let { manifestKeys.add(it) }
        val lock = libraryLocks.getOrPut(appId) { Any() }
        synchronized(lock) {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            var moved = 0
            for ((file, rel) in enumerateLocal(library)) {
                if (SteamCloudSavePaths.isRemoteRel(rel)) continue
                val key = SteamCloudSavePaths.canonicalKey(rel) ?: continue
                if (key in manifestKeys) continue
                if (config.rules.any { SteamCloudSavePaths.ruleCovers(it, rel) }) continue
                val provablyNotSave = config.rules.isNotEmpty() ||
                    (SteamCloudSavePaths.isGameInstallRel(rel) && manifestKeys.isNotEmpty())
                if (!provablyNotSave) continue
                try {
                    moveFile(file, File(SteamCloudSavePaths.movedAsideDir(appId, stamp), rel)); moved++
                } catch (e: Exception) {
                    Log.w(TAG, "Library cleanup: could not move aside $rel", e)
                }
            }
            if (moved > 0) {
                pruneEmptyDirs(library)
                Log.i(TAG, "Library $appId: moved $moved non-save file(s) to _moved-aside/$appId/$stamp " +
                    "(not in the cloud manifest, not covered by the game's Steam Cloud rules)")
            }
        }
    }

    /** The game's launch shortcut (see [SteamCloudSavePaths.resolveShortcut]: drive-map + imagefs match). */
    private fun resolveShortcut(ctx: Context, installDir: String): Shortcut? =
        SteamCloudSavePaths.resolveShortcut(ctx, installDir)

    // ── Library layout migration (one spelling per file) ──────────────────────────

    private val libraryLocks = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    /**
     * Fold the `%Token%/rest` spelling older builds wrote on Collect into Steam's own `%Token%rest`,
     * so the Library holds ONE copy per save. Idempotent; runs before every move. Per duplicate pair:
     * identical content → the extra copy is dropped; different content → the NEWER file (by mtime)
     * is kept under Steam's name and the older one is moved aside to
     * [SteamCloudSavePaths.movedAsideDir] (never deleted). A file with no twin is simply renamed.
     */
    fun normalizeLibrary(library: File, appId: Int) {
        if (!library.isDirectory) return
        val lock = libraryLocks.getOrPut(appId) { Any() }
        synchronized(lock) {
            var moved = 0; var merged = 0; var asideCount = 0
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            for ((file, rel) in enumerateLocal(library)) {
                val fused = SteamCloudSavePaths.fuseTokenSlash(rel) ?: continue
                val safe = sanitizeRelative(fused) ?: continue
                val dst = File(library, safe)
                try {
                    if (!dst.exists()) {
                        moveFile(file, dst); moved++
                    } else if (sameContent(file, dst)) {
                        if (file.lastModified() > dst.lastModified()) try { dst.setLastModified(file.lastModified()) } catch (_: Exception) {}
                        if (file.delete()) merged++
                    } else if (file.lastModified() > dst.lastModified()) {
                        moveFile(dst, File(SteamCloudSavePaths.movedAsideDir(appId, stamp), safe))
                        moveFile(file, dst); merged++; asideCount++
                    } else {
                        moveFile(file, File(SteamCloudSavePaths.movedAsideDir(appId, stamp), rel))
                        merged++; asideCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Library normalize: could not fold $rel", e)
                }
            }
            if (moved + merged > 0) {
                pruneEmptyDirs(library)
                Log.i(TAG, "Library $appId: renamed $moved, merged $merged duplicate(s)" +
                    (if (asideCount > 0) ", $asideCount older copy(ies) moved to _moved-aside/$appId/$stamp" else ""))
            }
        }
    }

    private fun sameContent(a: File, b: File): Boolean =
        a.length() == b.length() && SteamCloudBackend.sha1(a).contentEquals(SteamCloudBackend.sha1(b))

    /** Rename [src] to [dst] (creating parents, replacing [dst]); copy+delete across filesystems. */
    private fun moveFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            copyPreserving(src, dst)
            if (dst.length() == src.length()) src.delete()
        }
    }

    /** Remove now-empty folders under [root] (never [root] itself). */
    private fun pruneEmptyDirs(root: File) {
        root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { d ->
            if (d.list()?.isEmpty() == true) d.delete()
        }
    }

    /** Copy [src] onto [dst] (creating parents), preserving the modified time. Overwrites [dst]. */
    private fun copyPreserving(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
        try { dst.setLastModified(src.lastModified()) } catch (_: Exception) {}
    }

    /**
     * Run [action] over [items] on a fixed-size pool of at most [TRANSFER_CONCURRENCY] daemon
     * workers and block until every item is done. [action] is expected to swallow its own failures
     * (record them in a thread-safe accumulator) — this helper only logs anything that still escapes
     * so one bad task can't strand the others. The pool is always shut down before returning.
     */
    private fun <T> runConcurrently(items: List<T>, threadLabel: String, action: (T) -> Unit) {
        if (items.isEmpty()) return
        val workers = minOf(TRANSFER_CONCURRENCY, items.size)
        val seq = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "$threadLabel-${seq.incrementAndGet()}").apply { isDaemon = true }
        }
        try {
            val futures = items.map { item -> pool.submit { action(item) } }
            for (f in futures) {
                try { f.get() } catch (e: Exception) { Log.w(TAG, "$threadLabel worker error", e) }
            }
        } finally {
            pool.shutdown()
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /** How long a cloud op waits for a reconnecting session to log back on before giving up. */
    private const val LOGON_WAIT_MS = 8_000L

    /**
     * The live session's cloud backend (JavaSteam handler or Rust engine). If the session is mid-
     * reconnect (connected but not logged on — the same race that broke depot downloads), re-logon
     * from the saved token and wait up to [LOGON_WAIT_MS] before reporting "Not signed in". Null when
     * signed out (no saved token) or the app's session is lent to a SteamLite game. Worker threads only.
     */
    private fun requireCloud(): SteamCloudBackend? {
        SteamCloudBackend.current()?.let { return it }
        val back = try { SteamRepository.getInstance().ensureLoggedIn(LOGON_WAIT_MS) } catch (t: Throwable) { false }
        if (!back) {
            Log.w(TAG, "cloud op: Steam session not logged on (waited ${LOGON_WAIT_MS / 1000}s)")
            return null
        }
        return SteamCloudBackend.current()
    }

    /** Convert a Steam cloud path into a SAFE relative filesystem path under the local folder.
     *  Normalizes '\' -> '/', strips leading slashes, and REJECTS any '..' traversal (returns null).
     *  Placeholder folders like %WinMyDocuments% are kept verbatim as literal directory names, so a
     *  later upload reconstructs the identical cloud path from the local layout. */
    private fun sanitizeRelative(path: String): String? {
        val norm = path.replace('\\', '/').trimStart('/')
        if (norm.isEmpty()) return null
        val parts = norm.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Recursively list files under [root], each paired with its cloud path (relative to root,
     *  '/'-separated). Empty list if the folder is absent/empty (upload then refuses). */
    private fun enumerateLocal(root: File): List<Pair<File, String>> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val base = root.absolutePath.trimEnd('/')
        val out = ArrayList<Pair<File, String>>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isNotEmpty()) out.add(f to rel)
        }
        return out
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
