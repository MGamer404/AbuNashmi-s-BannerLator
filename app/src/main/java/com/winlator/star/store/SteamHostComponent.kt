package com.winlator.star.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.contents.Downloader
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * App Steam — Valve's own `androidarm64` Steam client library set, delivered download-on-demand
 * from **Valve's client-update CDN only** (never bundled in the APK, never re-hosted), for the
 * `launchMode=AppSteam` path: the app's genuine Steam session host ([SteamHost], the
 * `bl-steam-host` executable) loads `libsteamclient.so` from here and serves the loopback
 * listeners the Wine game's Proton `lsteamclient` bridge talks to.
 *
 * ── Source (Valve, read-only, signed KeyValues manifest) ─────────────────────────────────
 *   https://client-update.steamstatic.com/steam_client_linuxarm64
 *     "version"  "1788291500"                 ← the Valve build (our version marker)
 *     "bins_androidarm64_linuxarm64" {
 *        "file" "bins_androidarm64_linuxarm64.zip.a0be…"   ← plain zip on the same host
 *        "size" "18018236"
 *        "sha2" "d8c1a969…"                   ← SHA-256 of that zip (mandatory check)
 *        "zipvz" …                            ← Valve VZip (LZMA) variant — NOT used
 *     }
 *   The zip holds androidarm64/{libsteamclient.so, steamservice.so, libtier0_s.so,
 *   libvstdlib_s.so, libsteamnetworkingsockets.so} — bionic ELFs.
 *
 * ── Install ──────────────────────────────────────────────────────────────────────────────
 *   {filesDir}/imagefs/usr/lib/steam-host/<the five .so> + `.valve_version` (manifest version).
 *   Installed check = libsteamclient.so present. Update check = manifest version compare
 *   (bounded, never blocks a launch). The download is ~18 MB; a corrupt or tampered file
 *   (sha2 mismatch) is discarded and never extracted.
 *
 * ── Which Valve builds the host may drive (safety first, automatic where possible) ─────────
 *   1. [BUILTIN_BUILDS] — the rows of `client_iface.h` kSlotTables (offline fallback).
 *   2. The build catalog [CATALOG_URL] (`steam-host-builds.json` in winlator-contents, filled by
 *      a CI watcher that downloads each new Valve build and runs the same slot self-check the
 *      host runs). Cached in filesDir, fetched with a short timeout, never blocks a launch.
 *      Its `revoked` list withdraws a build even if it is built in.
 *   3. Anything else only on a FRESH install (or when the installed build is no longer
 *      accepted): the package is downloaded into a staging dir and `libblsteamhost.so --check`
 *      analyses it there; it is moved into place only when every slot was found and every code
 *      fingerprint matched. A working install is never replaced by an unlisted build.
 *   The host re-runs the self-check on every start regardless (bl-steam-host/slot_discovery.cpp).
 *
 * Mirrors [SteamLiteComponent]'s API shape (progress + done on the main thread) so the launch
 * popup / library screens treat it like the other on-demand components.
 */
object SteamHostComponent {

    private const val TAG = "BH_STEAMHOST"

    const val MANIFEST_URL = "https://client-update.steamstatic.com/steam_client_linuxarm64"
    private const val CDN_BASE = "https://client-update.steamstatic.com/"
    private const val PACKAGE_KEY = "bins_androidarm64_linuxarm64"
    private const val ZIP_PREFIX = "androidarm64/"

    /** The library the host dlopens — also the install marker. */
    private const val MARKER_REL = "libsteamclient.so"
    private const val VERSION_MARKER_REL = ".valve_version"

    /** The Valve builds pinned in the host itself (client_iface.h kSlotTables) — the offline fallback. */
    // 1788652215 (2026-10-03): same libsteamclient.so layout as 1788291500.
    // 1791918634 (2026-10-14): same layout again, confirmed by the structural slot analysis
    // (slot_discovery.cpp: RTTI + method-name references + code fingerprints) on all three builds.
    val BUILTIN_BUILDS: Set<String> = setOf("1788291500", "1788652215", "1791918634")

    /** Accepted-builds catalog (same repo/pattern as steamlite.json). */
    const val CATALOG_URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/steam-host-builds.json"
    private const val CATALOG_CACHE = "steam-host-builds.json"
    private const val CATALOG_TIMEOUT_MS = 4_000
    private const val CATALOG_MAX_AGE_MS = 30 * 60 * 1000L
    private const val CATALOG_MAX_BYTES = 256 * 1024
    /** Self-check record written next to a build admitted by the on-device check (case 3). */
    private const val SELFCHECK_REL = ".selfcheck.json"
    private const val SELFCHECK_TIMEOUT_MS = 30_000L

    /** Every file the host needs; the extract refuses a package missing any of them. */
    private val REQUIRED = listOf(
        "libsteamclient.so", "steamservice.so", "libtier0_s.so", "libvstdlib_s.so", "libsteamnetworkingsockets.so",
    )

    const val UPDATE_CHECK_TIMEOUT_MS = 5_000L

    fun interface ProgressCallback { fun onProgress(fraction: Float) }
    fun interface DoneCallback { fun onDone(success: Boolean, message: String) }

    /** What the Valve manifest says about the Android package. */
    data class Manifest(
        val version: String,
        val file: String,
        val size: Long,
        val sha2: String,   // lowercase hex SHA-256
    ) {
        val url: String get() = CDN_BASE + file
        val downloadMb: Long get() = (size + 512 * 1024) / (1024 * 1024)
    }

    /** Installed vs manifest. [manifest] null = couldn't reach Valve (never a block). */
    data class UpdateCheck(
        val installed: String,
        val manifest: Manifest?,
        /** The manifest build is listed (built in or catalog) — an update to it is offered. */
        val manifestVerified: Boolean = false,
        /** The installed build is accepted (built in, catalog, or admitted by the on-device self-check). */
        val installedVerified: Boolean = false,
    ) {
        val checked: Boolean get() = manifest != null
        val available: Boolean get() = manifest != null && manifest.version != installed && installed.isNotEmpty()
    }

    // ── Accepted-builds catalog ──────────────────────────────────────────────────────────────
    /** One catalog row: the Valve build, the package sha2 it was verified with, the host slot line. */
    data class CatalogBuild(val version: String, val sha2: String, val slots: String)
    data class Catalog(val builds: Map<String, CatalogBuild>, val revoked: Set<String>) {
        companion object { val EMPTY = Catalog(emptyMap(), emptySet()) }
    }

    @Volatile private var catalogMem: Catalog? = null
    @Volatile private var catalogFetchedAt = 0L

    /**
     * `steam-host-builds.json`:
     * `{"schemaVersion":1,"builds":[{"version":"…","sha2":"…","slots":"engine=… user=… apps=… friends=…"}],"revoked":["…"]}`
     * Unknown fields are ignored; malformed rows are skipped; a document without a `builds` array is rejected.
     */
    internal fun parseCatalog(text: String): Catalog? = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("builds") ?: return null
        val builds = LinkedHashMap<String, CatalogBuild>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = o.optString("version").trim()
            if (v.isEmpty() || !v.all { it.isDigit() }) continue
            val sha = o.optString("sha2").trim().lowercase()
            builds[v] = CatalogBuild(v, if (sha.length == 64) sha else "", o.optString("slots").trim())
        }
        val revoked = HashSet<String>()
        root.optJSONArray("revoked")?.let { r -> for (i in 0 until r.length()) r.optString(i).trim().takeIf { it.isNotEmpty() }?.let(revoked::add) }
        Catalog(builds, revoked)
    }.getOrNull()

    /** The catalog from memory or the on-disk cache — no network. Empty when never fetched. */
    fun catalog(context: Context): Catalog {
        catalogMem?.let { return it }
        val f = File(context.applicationContext.filesDir, CATALOG_CACHE)
        val c = if (f.isFile) runCatching { parseCatalog(f.readText()) }.getOrNull() else null
        catalogMem = c ?: Catalog.EMPTY
        return catalogMem!!
    }

    /** Fetch the catalog (bounded by [CATALOG_TIMEOUT_MS]); falls back to the cache. Off the main thread. */
    fun refreshCatalog(context: Context, force: Boolean = false): Catalog {
        val app = context.applicationContext
        if (!force && System.currentTimeMillis() - catalogFetchedAt < CATALOG_MAX_AGE_MS && catalogMem != null) return catalogMem!!
        val text = fetchSmallText(CATALOG_URL, CATALOG_TIMEOUT_MS)
        val parsed = text?.let { parseCatalog(it) }
        if (parsed != null) {
            catalogMem = parsed
            catalogFetchedAt = System.currentTimeMillis()
            runCatching {
                val tmp = File(app.filesDir, "$CATALOG_CACHE.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(app.filesDir, CATALOG_CACHE))
            }
            Log.i(TAG, "build catalog: ${parsed.builds.size} build(s), ${parsed.revoked.size} revoked")
            return parsed
        }
        Log.w(TAG, "build catalog unavailable — using ${if (catalogMem != null) "cached copy" else "built-in list"}")
        return catalog(app)
    }

    private fun fetchSmallText(address: String, timeoutMs: Int): String? = try {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > CATALOG_MAX_BYTES) return@use null
                }
                buf.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    } catch (t: Throwable) {
        Log.w(TAG, "fetch $address failed: ${t.message}")
        null
    }

    /** Why [version] is listed ("built-in" / "catalog"), or null. [sha2] (manifest) must match a catalog row that records one. */
    fun listedReason(context: Context, version: String, sha2: String? = null): String? {
        if (version.isEmpty()) return null
        val cat = catalog(context)
        if (version in cat.revoked) return null
        if (version in BUILTIN_BUILDS) return "built-in"
        val row = cat.builds[version] ?: return null
        if (sha2 != null && row.sha2.isNotEmpty() && row.sha2 != sha2.lowercase()) return null
        return "catalog"
    }

    /** True when [version] passed `libblsteamhost.so --check` on this device when it was installed. */
    fun selfCheckedInstall(context: Context, version: String): Boolean {
        if (version.isEmpty() || version != installedVersion(context)) return false
        val f = File(installDir(context), SELFCHECK_REL)
        if (!f.isFile) return false
        return runCatching { JSONObject(f.readText()).let { it.optBoolean("ok") && it.optString("version") == version } }.getOrDefault(false)
    }

    /** May the host drive [version]? (listed, or the installed build admitted by the self-check; never a revoked one) */
    fun isBuildAccepted(context: Context, version: String): Boolean {
        if (version.isEmpty() || version in catalog(context).revoked) return false
        return listedReason(context, version) != null || selfCheckedInstall(context, version)
    }

    /** Slot line the catalog recorded for a non-built-in build — the host cross-checks it against its own analysis. */
    fun catalogSlots(context: Context, version: String): String? =
        if (version in BUILTIN_BUILDS) null else catalog(context).builds[version]?.slots?.takeIf { it.isNotEmpty() }

    /** How the installed build is accepted, for logs ("built-in", "catalog", "self-check", or "not accepted"). */
    fun acceptanceLabel(context: Context): String {
        val v = installedVersion(context)
        return listedReason(context, v) ?: if (selfCheckedInstall(context, v) && v !in catalog(context).revoked) "self-check" else "not accepted"
    }

    /**
     * Run the host's static self-check (`libblsteamhost.so --check <lib> <version>`) on a staged
     * library. Reads the file only — nothing of Valve's is executed. Returns (ok, reason, json).
     */
    private fun runSelfCheck(context: Context, lib: File, version: String): Triple<Boolean, String, String> {
        val bin = SteamHost.binary(context)
        if (!bin.isFile) return Triple(false, "host binary missing", "")
        return try {
            val pb = ProcessBuilder(bin.absolutePath, "--check", lib.absolutePath, version)
            pb.environment().clear()
            pb.environment()["PATH"] = "/system/bin"
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread({
                runCatching { p.inputStream.bufferedReader().use { r -> r.forEachLine { if (out.length < 64 * 1024) out.append(it).append('\n') } } }
            }, "steamhost-selfcheck-out").apply { isDaemon = true; start() }
            if (!p.waitFor(SELFCHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return Triple(false, "self-check timed out", "")
            }
            reader.join(2_000)
            val line = out.lineSequence().firstOrNull { it.trimStart().startsWith("{") }?.trim().orEmpty()
            val json = runCatching { JSONObject(line) }.getOrNull()
                ?: return Triple(false, "self-check gave no result (exit ${p.exitValue()})", "")
            val ok = json.optBoolean("ok") && p.exitValue() == 0
            Log.i(TAG, "self-check of build $version: ${if (ok) "PASS" else "FAIL — " + json.optString("error")}")
            Triple(ok, json.optString("error").ifEmpty { if (ok) "ok" else "refused" }, line)
        } catch (t: Throwable) {
            Log.w(TAG, "self-check failed to run", t)
            Triple(false, "self-check could not run (${t.message})", "")
        }
    }

    fun installDir(context: Context): File = File(context.filesDir, "imagefs/usr/lib/steam-host")
    fun libSteamClient(context: Context): File = File(installDir(context), MARKER_REL)
    fun isInstalled(context: Context): Boolean = libSteamClient(context).isFile

    /** The Valve manifest version of the extracted set, or "" when unknown. */
    fun installedVersion(context: Context): String {
        val marker = File(installDir(context), VERSION_MARKER_REL)
        if (!marker.isFile) return ""
        return runCatching { marker.readText().trim() }.getOrDefault("")
    }

    fun versionLabel(version: String): String = if (version.isNotEmpty()) "build $version" else "an unknown build"

    /** Fetch + parse the Valve manifest (network only). Null on failure or when the package is absent. */
    fun loadManifest(): Manifest? {
        val text = Downloader.downloadString(MANIFEST_URL) ?: return null
        return parseManifest(text)
    }

    /**
     * Minimal Valve KeyValues (text VDF) reader for the two things we need: the root's
     * `"version"` and the [PACKAGE_KEY] block's `file` / `size` / `sha2`. Tolerates the
     * `kvsign2` / `kvsignatures` blocks and `//` comments. Returns null when either is missing.
     */
    internal fun parseManifest(text: String): Manifest? {
        val root = KeyValues.parse(text) ?: return null
        val body = root.values.values.firstOrNull { it is KeyValues.Node } as? KeyValues.Node ?: return null
        val version = (body.values["version"] as? String)?.trim().orEmpty()
        val pkg = body.values[PACKAGE_KEY] as? KeyValues.Node ?: return null
        val file = (pkg.values["file"] as? String)?.trim().orEmpty()
        val size = (pkg.values["size"] as? String)?.trim()?.toLongOrNull() ?: 0L
        val sha2 = (pkg.values["sha2"] as? String)?.trim()?.lowercase().orEmpty()
        if (version.isEmpty() || file.isEmpty() || sha2.length != 64) return null
        // Defensive: the CDN file name must be a plain name on the same host (no path tricks).
        if (file.contains('/') || file.contains("..")) return null
        return Manifest(version, file, size, sha2)
    }

    /** Compare the installed set with Valve's manifest, waiting at most [timeoutMs]. Off the main thread. */
    fun checkUpdateBlocking(context: Context, timeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS): UpdateCheck {
        val installed = installedVersion(context)
        if (!isInstalled(context)) return UpdateCheck(installed, null, installedVerified = false)
        val app = context.applicationContext
        val latch = CountDownLatch(2)
        var manifest: Manifest? = null
        Thread({
            try { manifest = loadManifest() } catch (t: Throwable) { Log.w(TAG, "manifest check failed", t) }
            finally { latch.countDown() }
        }, "steamhost-manifest-check").apply { isDaemon = true }.start()
        Thread({
            try { refreshCatalog(app) } catch (t: Throwable) { Log.w(TAG, "catalog refresh failed", t) }
            finally { latch.countDown() }
        }, "steamhost-catalog-check").apply { isDaemon = true }.start()
        val inTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val m = manifest
        return UpdateCheck(
            installed,
            m,
            manifestVerified = m != null && listedReason(app, m.version, m.sha2) != null,
            installedVerified = isBuildAccepted(app, installed),
        )
    }

    fun checkUpdateAsync(context: Context, timeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS, onResult: (UpdateCheck) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val r = checkUpdateBlocking(app, timeoutMs)
            main.post { onResult(r) }
        }, "steamhost-update-check").apply { isDaemon = true }.start()
    }

    /** Manifest → zip → sha2 verify → extract, on a worker; progress + result on the main thread. */
    fun downloadAsync(context: Context, progress: ProgressCallback, done: DoneCallback) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val (ok, msg) = try {
                downloadBlocking(app) { f -> main.post { progress.onProgress(f) } }
            } catch (e: Exception) {
                Log.e(TAG, "Valve client download failed", e)
                false to "Download failed: ${e.message}"
            }
            main.post { done.onDone(ok, msg) }
        }, "steamhost-download").start()
    }

    fun downloadBlocking(context: Context, progress: (Float) -> Unit): Pair<Boolean, String> {
        val manifest = loadManifest()
            ?: return false to "Couldn't read Valve's Steam client manifest. Check your connection."
        refreshCatalog(context)
        if (manifest.version in catalog(context).revoked) {
            Log.w(TAG, "Valve manifest build ${manifest.version} is revoked in the build catalog")
            return false to "Valve's Steam client build ${manifest.version} has been withdrawn from Headless Steam after a problem " +
                "was found with it. Use SteamLite or Goldberg for now; Headless Steam comes back with Valve's next build."
        }
        val listed = listedReason(context, manifest.version, manifest.sha2)
        if (listed == null && isInstalled(context) && isBuildAccepted(context, installedVersion(context))) {
            // Never replace a working install with a build nobody has verified yet.
            Log.w(TAG, "Valve manifest build ${manifest.version} is not listed — keeping installed ${installedVersion(context)}")
            return false to "Valve published Steam client build ${manifest.version}, which hasn't been verified yet. " +
                "Headless Steam keeps using the installed ${versionLabel(installedVersion(context))}."
        }
        Log.i(TAG, "Valve manifest build ${manifest.version}: ${listed ?: "not listed — will run the on-device self-check"}")
        val cacheDir = File(context.cacheDir, "steamhost_dl").apply { mkdirs() }
        val zip = File(cacheDir, "bins_androidarm64.zip")
        try {
            Log.i(TAG, "downloading ${manifest.file} (${manifest.size} B, build ${manifest.version}) from Valve")
            if (!Downloader.downloadFile(manifest.url, zip) { f -> progress((f * 0.9f).coerceIn(0f, 0.9f)) }) {
                return false to "Download from Valve's CDN failed. Please try again."
            }
            if (manifest.size > 0 && zip.length() != manifest.size) {
                Log.w(TAG, "size mismatch: manifest ${manifest.size} got ${zip.length()}")
                return false to "Downloaded file was incomplete (${zip.length()} of ${manifest.size} bytes). Try again."
            }
            val actual = sha256Hex(zip)
            if (actual != manifest.sha2) {
                Log.w(TAG, "sha2 mismatch: manifest ${manifest.sha2} got $actual")
                return false to "Downloaded file failed Valve's checksum. Try again."
            }
            val dest = installDir(context)
            val staging = File(dest.parentFile, "steam-host.tmp")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            val extracted = extractAndroidLibs(zip, staging)
            val missing = REQUIRED.filter { !File(staging, it).isFile }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "package missing $missing (extracted $extracted)")
                staging.deleteRecursively()
                return false to "Valve's package was missing expected files (${missing.joinToString()})."
            }
            for (name in REQUIRED) File(staging, name).setReadable(true, false)
            if (listed == null) {
                // Not on any list: the host's own static analysis decides, on the staged copy,
                // before anything that works today is touched.
                progress(0.95f)
                val (ok, why, json) = runSelfCheck(context, File(staging, MARKER_REL), manifest.version)
                if (!ok) {
                    staging.deleteRecursively()
                    return false to "Valve published Steam client build ${manifest.version}, and the app's safety check " +
                        "couldn't confirm it works with Headless Steam ($why). It will be accepted automatically once " +
                        "it has been verified — no app update needed. SteamLite and Goldberg still work."
                }
                File(staging, SELFCHECK_REL).writeText(json)
            }
            if (dest.exists()) dest.deleteRecursively()
            if (!staging.renameTo(dest)) {
                staging.deleteRecursively()
                return false to "Couldn't move the Steam client into place."
            }
            File(dest, VERSION_MARKER_REL).writeText(manifest.version)
            progress(1f)
            Log.i(TAG, "installed Valve client build ${manifest.version} (${extracted.size} files) into ${dest.absolutePath}")
            return true to "Valve Steam client (build ${manifest.version}) installed."
        } finally {
            zip.delete()
        }
    }

    /**
     * Byte offset of the first local file header. Valve's packages from build 1791918634 on carry
     * a 20-byte prefix before it, and ZipInputStream reads nothing unless it starts on a header.
     */
    private fun firstLocalHeaderOffset(zip: File): Long {
        val head = ByteArray(64 * 1024)
        val n = FileInputStream(zip).use { it.read(head) }
        for (i in 0..n - 4) {
            if (head[i] == 0x50.toByte() && head[i + 1] == 0x4b.toByte() &&
                head[i + 2] == 0x03.toByte() && head[i + 3] == 0x04.toByte()) return i.toLong()
        }
        return 0L
    }

    /** Extract only `androidarm64/<name>.so` entries, flattened, basename-only (zip-slip safe). */
    private fun extractAndroidLibs(zip: File, dest: File): List<String> {
        val out = ArrayList<String>()
        val skip = firstLocalHeaderOffset(zip)
        if (skip > 0) Log.i(TAG, "package has a $skip-byte prefix before the zip data; skipping it")
        val input = FileInputStream(zip)
        input.channel.position(skip)
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name
                if (e.isDirectory || !name.startsWith(ZIP_PREFIX)) { zin.closeEntry(); continue }
                val base = name.substringAfterLast('/')
                if (base.isEmpty() || !base.endsWith(".so") || base.contains("..")) { zin.closeEntry(); continue }
                val target = File(dest, base)
                target.outputStream().buffered().use { o -> zin.copyTo(o, 256 * 1024) }
                out.add(base)
                zin.closeEntry()
            }
        }
        return out
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** One-line status for UI/logs. */
    fun describe(context: Context): String =
        if (!isInstalled(context)) "not downloaded"
        else "installed (${versionLabel(installedVersion(context))})"

    // ── Valve KeyValues text reader (only what the manifest needs) ───────────────────────────
    internal object KeyValues {
        /** A block: ordered name → String | Node. Duplicate keys keep the last. */
        class Node { val values = LinkedHashMap<String, Any>() }

        /** Parse a KeyValues document. Returns a synthetic root whose single child is the document's root block. */
        fun parse(text: String): Node? {
            val toks = tokenize(text)
            var i = 0
            fun parseBlock(node: Node) {
                while (i < toks.size) {
                    val t = toks[i]
                    if (t == "}") { i++; return }
                    val key = if (t == "{" ) { i++; continue } else { i++; t }
                    val next = toks.getOrNull(i) ?: return
                    if (next == "{") {
                        i++
                        val child = Node()
                        parseBlock(child)
                        node.values[stripQuotes(key)] = child
                    } else if (next == "}") {
                        node.values[stripQuotes(key)] = ""
                    } else {
                        i++
                        node.values[stripQuotes(key)] = stripQuotes(next)
                    }
                }
            }
            val root = Node()
            return try { parseBlock(root); root } catch (t: Throwable) { null }
        }

        private fun stripQuotes(s: String): String =
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

        /** Quoted strings (with \" escapes) kept quoted, braces, bare words; `//` comments dropped. */
        private fun tokenize(text: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++ }
                    c == '{' || c == '}' -> { out.add(c.toString()); i++ }
                    c == '"' -> {
                        val sb = StringBuilder("\"")
                        i++
                        while (i < n && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < n) { sb.append(text[i + 1]); i += 2 } else { sb.append(text[i]); i++ }
                        }
                        i++ // closing quote
                        sb.append('"')
                        out.add(sb.toString())
                    }
                    else -> {
                        val s = i
                        while (i < n && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}') i++
                        out.add(text.substring(s, i))
                    }
                }
            }
            return out
        }
    }

    /** For the Log Manager / diagnostics: the JSON facts of the install. */
    fun factsJson(context: Context): JSONObject = JSONObject().apply {
        put("installed", isInstalled(context))
        put("version", installedVersion(context))
        put("verified", isBuildAccepted(context, installedVersion(context)))
        put("accepted_by", acceptanceLabel(context))
        put("dir", installDir(context).absolutePath)
    }
}
