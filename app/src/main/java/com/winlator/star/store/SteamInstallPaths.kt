package com.winlator.star.store

import android.util.Log
import java.io.File

/**
 * The one place that knows what a store-installed Steam game's path looks like.
 *
 * Two layouts exist side by side:
 *
 *  - the **legacy** one, `…/steam_games/<folder>` — the app's own `imagefs/steam_games`, the SD card's
 *    `<sd>/bannerlator/steam_games` and libraries added before the steamapps layout existed
 *    (`<chosen>/bannerlator/steam_games`);
 *  - the **Steam** one, `…/steamapps/common/<folder>` — what a user library folder resolves to now
 *    ([SteamLibraryLocations]); the same folder every Steam client uses, so other launchers and emulators
 *    can open the same game without a second download.
 *
 * A lot of launch / save / EA / Goldberg code recognises a store-installed game by the first layout's
 * segment. Every one of those sites goes through here so both layouts are recognised the same way. For a
 * path that carries a `steam_games` segment the answers are exactly what the old inline code gave (the
 * legacy layout always wins when both could match); the `steamapps/common` rules only ever add matches.
 *
 * All path inputs may use `/` or `\` and may be a Windows (`G:\Games\steamapps\common\X\x.exe`) or a
 * host (`/storage/…/steamapps/common/X/x.exe`) form.
 */
object SteamInstallPaths {
    private const val TAG = "SteamInstallPaths"

    const val LEGACY_ROOT = "steam_games"
    const val STEAMAPPS = "steamapps"
    const val COMMON = "common"

    /** Dropped into a game folder this app created inside a shared `steamapps/common` (see [claimIfNew]). */
    const val OWNED_MARKER = ".bannerlator_owned"

    private const val LEGACY_PREFIX = "steam_games/"
    private const val STEAM_PREFIX = "steamapps/common/"

    /** Bookkeeping this app writes inside a game folder; safe to remove without touching game files. */
    private val BOOKKEEPING = listOf(".bl_depot", ".DepotDownloader", ".bannerlator_build", OWNED_MARKER)

    // ── Recognition ──────────────────────────────────────────────────────────────────────────

    /** The old gate, verbatim: the path contains `steam_games` anywhere (case-insensitive). */
    @JvmStatic
    fun isLegacyRootPath(path: String?): Boolean = path != null && path.contains(LEGACY_ROOT, ignoreCase = true)

    /** True when the path has a `steamapps/common/` pair of whole segments (any separators, any case). */
    @JvmStatic
    fun isSteamappsCommonPath(path: String?): Boolean =
        path != null && steamRootEnd(path.replace('\\', '/')) >= 0

    /** [isLegacyRootPath] or [isSteamappsCommonPath]: the path is under a store-installed Steam game's root. */
    @JvmStatic
    fun isUnderInstallRoot(path: String?): Boolean = isLegacyRootPath(path) || isSteamappsCommonPath(path)

    /**
     * As [isUnderInstallRoot], for a shortcut that may carry a `storeSource` tag: a shortcut explicitly
     * tagged as something other than Steam (a user's `custom` import of a game that happens to sit in a
     * Steam library folder, Epic, GOG, ...) is not claimed by the new `steamapps/common` rule. The legacy
     * `steam_games` rule applies regardless of the tag, exactly as before.
     */
    @JvmStatic
    fun isUnderInstallRoot(path: String?, storeSource: String?): Boolean =
        isLegacyRootPath(path) ||
            ((storeSource.isNullOrEmpty() || storeSource == "steam") && isSteamappsCommonPath(path))

    /**
     * The path text right after the install root — `"<folder>/rel/to/exe"` with `/` separators and the
     * original casing — or null when the path has no root. A `steam_games/` match (the old inline
     * `indexOf("steam_games/")`) wins over a `steamapps/common/` one.
     */
    @JvmStatic
    fun afterInstallRoot(rawPath: String?): String? {
        if (rawPath == null) return null
        val norm = rawPath.replace('\\', '/')
        val legacy = norm.indexOf(LEGACY_PREFIX, ignoreCase = true)
        if (legacy >= 0) return norm.substring(legacy + LEGACY_PREFIX.length)
        val end = steamRootEnd(norm)
        return if (end >= 0) norm.substring(end) else null
    }

    /** The game folder: the segment right after the install root, or null (no root / empty segment). */
    @JvmStatic
    fun gameFolderOf(rawPath: String?): String? {
        val after = afterInstallRoot(rawPath) ?: return null
        val slash = after.indexOf('/')
        val folder = (if (slash >= 0) after.substring(0, slash) else after).trim()
        return if (folder.isEmpty()) null else folder
    }

    /**
     * Whether an installed game's recorded [installDir] is the game folder [folder] of an exec path:
     * the install root and the folder as ADJACENT whole segments (case-insensitive, separators
     * normalised), so "Half-Life" can never match "Half-Life 2".
     */
    @JvmStatic
    fun installDirMatchesFolder(installDir: String?, folder: String?): Boolean {
        if (installDir == null || folder == null) return false
        var norm = installDir.replace('\\', '/')
        while (norm.endsWith("/")) norm = norm.substring(0, norm.length - 1)
        val segs = norm.split("/")
        for (i in 0 until segs.size - 1) {
            if (segs[i].equals(LEGACY_ROOT, ignoreCase = true) && segs[i + 1].equals(folder, ignoreCase = true)) return true
            if (i + 2 < segs.size &&
                segs[i].equals(STEAMAPPS, ignoreCase = true) && segs[i + 1].equals(COMMON, ignoreCase = true) &&
                segs[i + 2].equals(folder, ignoreCase = true)
            ) return true
        }
        return false
    }

    /**
     * Index in [parts] (a path split on `/`) of the game-folder segment: the one after `steam_games`
     * (the first such segment, as before) or, when there is none, after a `steamapps/common` pair.
     * -1 when there is no root or nothing follows it.
     */
    @JvmStatic
    fun gameFolderIndex(parts: List<String>): Int {
        val legacy = parts.indexOf(LEGACY_ROOT)
        if (legacy >= 0) return if (legacy + 1 < parts.size) legacy + 1 else -1
        for (i in 0 until parts.size - 2) {
            if (parts[i].equals(STEAMAPPS, ignoreCase = true) && parts[i + 1].equals(COMMON, ignoreCase = true)) return i + 2
        }
        return -1
    }

    /** End index (exclusive) of the first segment-bounded `steamapps/common/` in [norm], else -1. */
    private fun steamRootEnd(norm: String): Int {
        var from = 0
        while (true) {
            val i = norm.indexOf(STEAM_PREFIX, from, ignoreCase = true)
            if (i < 0) return -1
            if (i == 0 || norm[i - 1] == '/') return i + STEAM_PREFIX.length
            from = i + 1
        }
    }

    // ── Library layout ───────────────────────────────────────────────────────────────────────

    /**
     * The `steamapps` folder for a library folder the user picked: the folder itself when it is already
     * named `steamapps` (or is the `steamapps/common` inside one), otherwise `<chosen>/steamapps`.
     */
    @JvmStatic
    fun steamappsDirOf(chosen: File): File {
        if (chosen.name.equals(STEAMAPPS, ignoreCase = true)) return chosen
        val parent = chosen.parentFile
        if (chosen.name.equals(COMMON, ignoreCase = true) && parent != null &&
            parent.name.equals(STEAMAPPS, ignoreCase = true)
        ) return parent
        return File(chosen, STEAMAPPS)
    }

    /** `<steamapps>/common` of a picked library folder: where its games install. */
    @JvmStatic
    fun commonDirOf(chosen: File): File = File(steamappsDirOf(chosen), COMMON)

    /** For `<x>/steamapps/common/<game>` the `<x>/steamapps` folder, else null (every other layout). */
    @JvmStatic
    fun steamappsOfInstallDir(installDir: File): File? {
        val common = installDir.parentFile ?: return null
        return steamappsOfCommon(common)
    }

    /** For `<x>/steamapps/common` the `<x>/steamapps` folder, else null. */
    @JvmStatic
    fun steamappsOfCommon(common: File): File? {
        if (!common.name.equals(COMMON, ignoreCase = true)) return null
        val steamapps = common.parentFile ?: return null
        return if (steamapps.name.equals(STEAMAPPS, ignoreCase = true)) steamapps else null
    }

    // ── Shared-folder ownership ──────────────────────────────────────────────────────────────
    //
    // A game folder inside a steamapps/common may have been put there by another Steam client; this app
    // builds on it rather than owning it. Only a folder this app created itself carries the marker, and
    // only such a folder is ever wiped wholesale by "Cancel & delete download". Legacy layouts (the app's
    // own steam_games trees) are always the app's, exactly as before.

    /** Marks [installDir] as created by this app when it is a steamapps game folder that does not exist
     *  yet (or is empty). A pre-existing, non-empty folder is left alone. Never throws. */
    @JvmStatic
    fun claimIfNew(installDir: File) {
        try {
            if (steamappsOfInstallDir(installDir) == null) return
            val fresh = !installDir.exists() || (installDir.isDirectory && installDir.list().let { it == null || it.isEmpty() })
            if (!fresh) return
            if (!installDir.isDirectory && !installDir.mkdirs()) return
            File(installDir, OWNED_MARKER).writeText("1")
        } catch (t: Throwable) {
            Log.w(TAG, "claimIfNew(${installDir.absolutePath}) failed: ${t.message}")
        }
    }

    /** Marks [installDir] as ours (a folder this app just created by copying a game into it). Never throws. */
    @JvmStatic
    fun markOwned(installDir: File) {
        try {
            if (steamappsOfInstallDir(installDir) == null || !installDir.isDirectory) return
            File(installDir, OWNED_MARKER).writeText("1")
        } catch (t: Throwable) {
            Log.w(TAG, "markOwned(${installDir.absolutePath}) failed: ${t.message}")
        }
    }

    /** True when deleting the whole [installDir] can only remove what this app put there. */
    @JvmStatic
    fun mayDeleteWholeFolder(installDir: File): Boolean =
        steamappsOfInstallDir(installDir) == null || File(installDir, OWNED_MARKER).exists()

    /**
     * Removes only this app's own bookkeeping (download journal, resume state, build stamp) from
     * [installDir] and leaves every game file where it is. Used instead of a wipe for a folder this app
     * did not create. Never throws.
     */
    @JvmStatic
    fun removeBookkeeping(installDir: File) {
        for (name in BOOKKEEPING) {
            try {
                val f = File(installDir, name)
                if (f.exists()) f.deleteRecursively()
            } catch (t: Throwable) {
                Log.w(TAG, "removeBookkeeping: $name in ${installDir.absolutePath}: ${t.message}")
            }
        }
    }

    /**
     * The folder a FRESH install of [appId] goes to under [installRoot]. A `steamapps/common` root takes
     * the Steam layout's folder name ([SteamAppManifest.installFolderName]: an existing manifest's
     * `installdir`, else the PICS `installdir`, else [safeName]); every other root keeps the legacy
     * `<root>/<safeName>`.
     */
    @JvmStatic
    fun freshInstallDir(installRoot: File, appId: Int, safeName: String): File {
        val steamapps = steamappsOfCommon(installRoot) ?: return File(installRoot, safeName)
        return File(installRoot, SteamAppManifest.installFolderName(steamapps, appId, safeName))
    }
}
