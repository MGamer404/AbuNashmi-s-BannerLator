package com.winlator.star.store

import android.content.Context
import android.os.Environment
import android.util.Log
import com.winlator.star.R
import com.winlator.star.core.WinePath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Where a Steam download can land: the internal default, the auto-detected SD card ([SteamSdInstall])
 * and any number of extra "library locations" the user adds, like Steam's own "Steam Library Folders".
 *
 * ── Layout ────────────────────────────────────────────────────────────────────────────────────
 * The user picks an ordinary folder `<chosen>`; games land in `<chosen>/bannerlator/steam_games/<Name>`.
 * That is deliberately the SAME tail the SD-card install uses ([SteamSdInstall.SD_SUBDIR]) and still
 * carries a `steam_games` path segment, because a lot of existing code recognises a store-installed
 * Steam game by that segment (shortcut tagging, Goldberg/SteamLite launch, EA support, install-script
 * lookup). A `<chosen>` whose own path contains "steam_games" is therefore refused in [add].
 *
 * ── What is stored ────────────────────────────────────────────────────────────────────────────
 * Only the list of folders (in [SteamPrefs]) and the pre-selected default's id. A game's real location
 * is always the `steam_games.install_dir` DB column (and, mid-download, the `steam_downloads` row), so
 * resume / update / verify / uninstall / move / launch never recompute a path from here — removing a
 * library therefore cannot orphan a game, and [remove] additionally refuses while games live in it.
 *
 * ── Wine mapping ──────────────────────────────────────────────────────────────────────────────
 * Nothing here maps drive letters. A library path must sit on a storage volume ([add] enforces it via
 * [WinePath.storageVolumeRootOf]), and at shortcut-creation time [WinePath.resolveWindowsPath] mounts
 * that WHOLE volume as one drive letter and addresses the game by its path below the volume root, so
 * any folder on shared storage or a card resolves correctly — not just a volume root.
 *
 * Most functions touch the disk, the database or the volume list: call them off the UI thread.
 */
object SteamLibraryLocations {
    private const val TAG = "SteamLibraryLocations"

    const val ID_INTERNAL = "internal"
    const val ID_SD = "sd"
    private const val ID_LIB_PREFIX = "lib_"

    /** Alias spellings of the primary shared-storage root, rewritten to the real one on [add]. */
    private val PRIMARY_ALIASES = listOf(
        "/sdcard", "/storage/self/primary", "/mnt/sdcard", "/storage/sdcard0", "/mnt/user/0/primary",
    )

    enum class Kind { INTERNAL, SD, LIBRARY }

    /** A persisted user library: [path] is the folder the user picked (not the `steam_games` base). */
    data class Library(val id: String, val label: String, val path: String)

    /**
     * One selectable install target. [base] is the `steam_games` folder a game's own folder is created
     * under; [rootPath] is what the user would recognise (the picked folder / card root, "" for the
     * internal default). [available] is false while a library's folder is unreachable (card pulled).
     */
    data class Location(
        val id: String,
        val kind: Kind,
        val label: String,
        val rootPath: String,
        val base: File,
        val available: Boolean,
        val freeBytes: Long,
    ) {
        /** The `installRoot` to hand to [SteamDepotDownloader.installApp]; null = the internal default. */
        val installRoot: String? get() = if (kind == Kind.INTERNAL) null else base.absolutePath

        fun contains(dir: String): Boolean = isUnder(dir, base)

        /** Same rule the downloader's free-space guard applies; an unknown size (<= 1) never blocks. */
        fun fits(installBytes: Long): Boolean =
            installBytes <= 1L || freeBytes >= installBytes + SteamSdInstall.FREE_SPACE_MARGIN
    }

    /** A location plus what the manager shows about it. [games] are installed or downloading there. */
    data class Entry(val location: Location, val isDefault: Boolean, val games: List<String>)

    sealed class AddResult {
        data class Added(val library: Library) : AddResult()
        data class Rejected(val reason: String) : AddResult()
    }

    sealed class RemoveResult {
        data class Removed(val library: Library) : RemoveResult()
        data class Blocked(val library: Library, val games: List<String>) : RemoveResult()
        object NotFound : RemoveResult()
    }

    // ── Persistence ──────────────────────────────────────────────────────────────────────────

    fun libraries(ctx: Context): List<Library> {
        val raw = SteamPrefs.getLibraryLocationsJson(ctx)
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val path = o.optString("path")
                if (id.isBlank() || path.isBlank()) null
                else Library(id, o.optString("label").ifBlank { File(path).name }, path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "library list unreadable, treating as empty", e)
            emptyList()
        }
    }

    private fun saveLibraries(ctx: Context, list: List<Library>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("path", it.path)) }
        SteamPrefs.setLibraryLocationsJson(ctx, arr.toString())
    }

    fun setDefault(ctx: Context, id: String) {
        SteamPrefs.setDefaultInstallLocation(ctx, if (id == ID_INTERNAL) "" else id)
    }

    /** The id the install dialog pre-selects: the saved default while it still exists and is reachable. */
    fun defaultLocationId(ctx: Context, locations: List<Location>): String {
        val saved = SteamPrefs.getDefaultInstallLocation(ctx)
        return locations.firstOrNull { it.id == saved && it.available }?.id ?: ID_INTERNAL
    }

    // ── Enumeration ──────────────────────────────────────────────────────────────────────────

    /** The default `steam_games` base: internal app-private storage. */
    fun internalBase(ctx: Context): File = MoveGameStorage.internalBase(ctx)

    /** The `steam_games` base inside a user library folder. */
    private fun baseOf(libraryPath: String): File = File(libraryPath, SteamSdInstall.SD_SUBDIR)

    /**
     * Every location a game can install to, in display order: internal, the SD card (when one is
     * mounted and isn't already added as a library), then the user's libraries. Always starts with
     * the internal default, so a size of 1 means "nothing to choose".
     */
    fun locations(ctx: Context): List<Location> {
        val out = ArrayList<Location>()
        val internal = internalBase(ctx)
        out += Location(
            ID_INTERNAL, Kind.INTERNAL, ctx.getString(R.string.steam_lib_internal_label), "",
            internal, available = true, freeBytes = SteamSdInstall.freeBytes(ctx.filesDir),
        )
        val libs = libraries(ctx)
        val libBases = libs.map { baseOf(it.path).absolutePath }.toSet()
        val sd = runCatching { SteamSdInstall.detect(ctx) }.getOrNull()
        if (sd != null && sd.steamGamesBase.absolutePath !in libBases) {
            out += Location(
                ID_SD, Kind.SD, sd.label, sd.root.absolutePath, sd.steamGamesBase,
                available = true, freeBytes = sd.freeBytes,
            )
        }
        for (lib in libs) {
            val root = File(lib.path)
            val reachable = root.isDirectory
            val base = baseOf(lib.path)
            out += Location(
                lib.id, Kind.LIBRARY, lib.label, lib.path, base, reachable,
                if (reachable) SteamSdInstall.freeBytes(if (base.isDirectory) base else root) else 0L,
            )
        }
        return out
    }

    /** [locations] with the default flag and the games in each, for the manager screen. */
    fun describe(ctx: Context): List<Entry> {
        val locs = locations(ctx)
        val def = defaultLocationId(ctx, locs)
        return locs.map { Entry(it, it.id == def, gamesIn(ctx, it.base)) }
    }

    /** The configured library bases only (no volume probing) — for defensive partial-download sweeps. */
    fun libraryBases(ctx: Context): List<File> = libraries(ctx).map { baseOf(it.path) }

    /** The location whose base holds [installDir] (the deepest match), or null when it is on none. */
    fun locationOf(locations: List<Location>, installDir: String): Location? =
        locations.filter { it.contains(installDir) }.maxByOrNull { it.base.absolutePath.length }

    /**
     * Display names of the games installed under [base] (folder really present) or still downloading
     * into it. Cheap: two small DB reads and one `isDirectory` per installed game.
     */
    fun gamesIn(ctx: Context, base: File): List<String> {
        val names = LinkedHashMap<Int, String>()
        try {
            val db = SteamDatabase.getInstance(ctx)
            for (row in db.getInstalledGames()) {
                val dir = row.installDir
                if (!dir.isNullOrBlank() && isUnder(dir, base) && File(dir).isDirectory) {
                    names[row.appId] = row.name.orEmpty().ifBlank { "App ${row.appId}" }
                }
            }
            for (dl in db.getActiveDownloads()) {
                val dir = dl.installDir
                if (!dir.isNullOrBlank() && isUnder(dir, base) && dl.appId !in names) {
                    val name = db.getGame(dl.appId)?.name.orEmpty().ifBlank { "App ${dl.appId}" }
                    names[dl.appId] = ctx.getString(R.string.steam_lib_game_downloading, name)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not list games under ${base.absolutePath}", t)
        }
        return names.values.toList()
    }

    // ── Add / remove ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates [rawPath] (a folder from the file manager) and, when usable, stores it as a library.
     * Checks, in order: exists; sits on a storage volume (so Wine can map it); path free of
     * "steam_games"; not already listed; `<path>/bannerlator/steam_games` can be created and written
     * to (a real write, since `canWrite()` lies on FUSE); at least [SteamSdInstall.FREE_SPACE_MARGIN] free.
     * Creates the `bannerlator/steam_games` folders on success — nothing else on the disk is touched.
     */
    fun add(ctx: Context, rawPath: String): AddResult {
        fun reject(res: Int, vararg args: Any) = AddResult.Rejected(ctx.getString(res, *args))

        val dir = File(normalize(rawPath))
        if (!dir.isDirectory) return reject(R.string.steam_lib_err_missing)
        val abs = dir.absolutePath.trimEnd('/')
        if (WinePath.storageVolumeRootOf(abs) == null) return reject(R.string.steam_lib_err_volume)
        if (abs.contains("steam_games", ignoreCase = true)) return reject(R.string.steam_lib_err_name)

        val libs = libraries(ctx)
        val base = baseOf(abs)
        val taken = HashSet<String>()
        taken += internalBase(ctx).absolutePath
        runCatching { SteamSdInstall.detect(ctx) }.getOrNull()?.let { taken += it.steamGamesBase.absolutePath }
        libs.forEach { taken += baseOf(it.path).absolutePath }
        if (base.absolutePath in taken) return reject(R.string.steam_lib_err_duplicate)

        if (!(base.isDirectory || base.mkdirs()) || !canWriteInto(base)) return reject(R.string.steam_lib_err_write)
        if (SteamSdInstall.freeBytes(base) < SteamSdInstall.FREE_SPACE_MARGIN) {
            return reject(R.string.steam_lib_err_space, SteamSdInstall.fmtBytes(SteamSdInstall.FREE_SPACE_MARGIN))
        }

        val baseLabel = dir.name.ifBlank { abs }
        val usedLabels = libs.map { it.label }.toSet()
        var label = baseLabel
        var n = 2
        while (label in usedLabels) label = "$baseLabel (${n++})"
        val lib = Library(ID_LIB_PREFIX + UUID.randomUUID().toString().take(8), label, abs)
        saveLibraries(ctx, libs + lib)
        Log.i(TAG, "added library '${lib.label}' at $abs")
        return AddResult.Added(lib)
    }

    /**
     * Forgets the library [id] — the config entry only, never any file. Refused ([RemoveResult.Blocked])
     * while a game is installed in, or downloading into, that library: those games keep working from
     * their recorded folder, but would silently vanish from the location lists and the move targets.
     */
    fun remove(ctx: Context, id: String): RemoveResult {
        val libs = libraries(ctx)
        val lib = libs.firstOrNull { it.id == id } ?: return RemoveResult.NotFound
        val games = gamesIn(ctx, baseOf(lib.path))
        if (games.isNotEmpty()) return RemoveResult.Blocked(lib, games)
        saveLibraries(ctx, libs.filter { it.id != id })
        if (SteamPrefs.getDefaultInstallLocation(ctx) == id) SteamPrefs.setDefaultInstallLocation(ctx, "")
        Log.i(TAG, "removed library '${lib.label}' (${lib.path}); files untouched")
        return RemoveResult.Removed(lib)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    /** True when [path] is [base] itself or lies below it ('/'-fenced, so `Foo` never matches `Foo2`). */
    fun isUnder(path: String, base: File): Boolean {
        val b = base.absolutePath.trimEnd('/')
        val p = path.trimEnd('/')
        return p == b || p.startsWith("$b/")
    }

    /** Rewrites the primary-storage alias spellings (`/sdcard`, …) to the real `/storage/emulated/N` root. */
    @Suppress("DEPRECATION")
    private fun normalize(path: String): String {
        val p = path.trim().trimEnd('/')
        val primary = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val alias = PRIMARY_ALIASES.firstOrNull { p == it || p.startsWith("$it/") } ?: return p
        return primary + p.removePrefix(alias)
    }

    /** A real create-write-delete probe; `File.canWrite()` is unreliable on FUSE-backed storage. */
    private fun canWriteInto(dir: File): Boolean = try {
        val probe = File(dir, ".bl_write_test")
        probe.writeText("ok")
        probe.delete()
        true
    } catch (e: Exception) {
        Log.w(TAG, "write probe failed in ${dir.absolutePath}", e)
        false
    }
}
