package com.winlator.star.store

import android.content.Context
import android.os.Environment
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.SaveLocator
import com.winlator.star.core.WinePath
import java.io.File

/**
 * Path translation for the three-tier Steam Cloud save model
 * (Cloud ⇄ **Library** ⇄ **Container**).
 *
 * The Library stores each file under its Steam UFS "cloud path layout": every path begins with a
 * `%Root%` placeholder segment kept verbatim as a literal folder name (see
 * [SteamCloudSaveManager.sanitizeRelative] / [SteamCloudSaveManager.remotePathOf]). This object is
 * the single place that maps that leading `%Root%` token to a concrete directory inside a Wine
 * container (Apply) and back (Collect).
 *
 * The `%Root%` → path table is derived from GameNative's `PathType.toAbsPath` (the authoritative
 * JavaSteam UFS root spellings). Everything Windows-side hangs off the container's Wine user
 * profile, which is exactly [SaveLocator.profileDir] — reused verbatim so a taught save folder and a
 * cloud-mapped one resolve to the identical base.
 *
 * SAFETY: every translation rejects `..`/escape. [toContainerPath] refuses any result that would
 * canonicalize outside its mapped root; [toLibraryRel] only ever returns a path strictly under a
 * known root. Both return null (⇒ caller skips the file) rather than guessing.
 */
object SteamCloudSavePaths {

    private const val TAG = "BH_STEAM_CLOUD"

    /**
     * Where a game's Steam API files (ISteamRemoteStorage — the cloud names that carry NO `%Root%`
     * token) live in its container. That depends on how the game is launched:
     *  - Goldberg / gbe_fork (the default): `AppData/Roaming/GSE Saves/<appId>/remote` in the profile;
     *  - SteamLite (`launchMode=RealSteam`, the genuine client in the prefix):
     *    `C:/Program Files (x86)/Steam/userdata/<accountId>/<appId>/remote`.
     */
    class RemoteTarget(val genuineClient: Boolean, val accountId: Int) {
        companion object {
            /** Goldberg layout — needs no account. */
            @JvmField val EMULATED = RemoteTarget(false, 0)
        }
    }

    // ── One UFS root: its `%Token%` placeholder + how to resolve its base dir in a container. ──
    // `install` = the game's shared Steam install dir (SteamCloudSaveManager passes it through);
    // `appId` = the game (only the per-app Steam remote-storage root needs it; 0 = unknown);
    // `remote` = the launch mode's remote-storage layout ([RemoteTarget]).
    private class Root(val token: String, val baseDir: (Container, String, Int, RemoteTarget) -> File?)

    /**
     * The game's Steam API remote storage. Cloud names without a `%Root%` token are files the game
     * wrote through ISteamRemoteStorage (Steam keeps them in `userdata/<id>/<appId>/remote`), so they
     * belong in the folder the game reads them back from — NOT the install dir. Internal: no token.
     */
    private val REMOTE = Root("") { c, _, appId, remote -> remoteStorageDir(c, appId, remote) }

    /**
     * The UFS `%Root%` table, ordered MOST-SPECIFIC → LEAST-SPECIFIC. Order matters only for
     * [toLibraryRel]: the profile sub-roots (Documents, AppData subdirs, Saved Games) are nested under the
     * profile itself, so `%Root%` (the bare profile) MUST be matched last, and `AppData/LocalLow`
     * before `AppData/Local` for good measure. [GameInstall] / [WinProgramData] live outside the
     * profile entirely. The remote store sits under AppData/Roaming (Goldberg), so it comes before it.
     */
    private val ROOTS: List<Root> = listOf(
        // Shared Steam install dir — OUTSIDE the profile.
        Root("%GameInstall%") { _, install, _, _ -> File(install) },
        // ProgramData — OUTSIDE the profile (drive_c/ProgramData).
        Root("%WinProgramData%") { c, _, _, _ -> File(c.rootDir, ".wine/drive_c/ProgramData") },
        // The untokened remote store (see [REMOTE]) — a file there maps back to its bare cloud name.
        REMOTE,
        // Steam's per-app remote storage spelled as a token: the same folder as [REMOTE].
        Root("%SteamUserBaseStorage%") { c, _, appId, remote -> remoteStorageDir(c, appId, remote) },
        // Profile-relative roots.
        Root("%WinSavedGames%") { c, _, _, _ -> File(SaveLocator.profileDir(c), "Saved Games") },
        Root("%WinAppDataLocalLow%") { c, _, _, _ -> File(SaveLocator.profileDir(c), "AppData/LocalLow") },
        Root("%WinAppDataLocal%") { c, _, _, _ -> File(SaveLocator.profileDir(c), "AppData/Local") },
        Root("%WinAppDataRoaming%") { c, _, _, _ -> File(SaveLocator.profileDir(c), "AppData/Roaming") },
        Root("%WinMyDocuments%") { c, _, _, _ -> File(SaveLocator.profileDir(c), "Documents") },
        // The Wine user profile itself — LAST (ancestor of every Win* root above).
        Root("%Root%") { c, _, _, _ -> SaveLocator.profileDir(c) },
    )

    /**
     * Extra spellings the CM may send for a root, keyed by the PERCENT-STRIPPED lowercased token,
     * valued by the percent-stripped canonical token in [ROOTS]. [lookupRoot] strips percents before
     * consulting this, so a token resolves whether it arrives as `%root_mod%`, `ROOT_MOD`, or
     * `root_mod`. `SteamUserBaseStorage` (the per-app userdata/remote store) maps to the game's remote
     * store for its launch mode (see [RemoteTarget]); Mac/Linux-only roots (`MacHome`, `LinuxHome`, …) are not
     * mapped on purpose — a Windows container has no counterpart — so those files are skipped, never
     * guessed.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "winappdata" to "winappdataroaming",       // legacy short form of Roaming
        "windowshome" to "root",                   // GameNative alias for the profile root
        "root_mod" to "root",                      // GameNative alias for the profile root
        "steamclouddocuments" to "winmydocuments", // GameNative maps this to Documents
        "steamuserdata" to "steamuserbasestorage", // spelling seen in older ufs blocks
        "winhome" to "root",                       // %WinHome% = the user profile (Steam's own alias)
    )

    // ── Public API ───────────────────────────────────────────────────────────────

    /** Managed local Library folder for this game (canonical local copy). */
    fun libraryDir(ctx: Context, appId: Int): File =
        File(Environment.getExternalStorageDirectory(), "Bannerlator/SteamCloudSaves/$appId")

    /**
     * Where Library files moved aside by a cleanup go (`SteamCloudSaves/_moved-aside/<appId>/<stamp>/`).
     * Outside every per-app Library folder, so nothing walks, applies or uploads it; nothing deletes it.
     */
    fun movedAsideDir(appId: Int, stamp: String): File =
        File(Environment.getExternalStorageDirectory(), "Bannerlator/SteamCloudSaves/_moved-aside/$appId/$stamp")

    /** The remote-storage layout for a game launched by [shortcut] (see [RemoteTarget]). */
    fun remoteTargetFor(ctx: Context, shortcut: Shortcut?): RemoteTarget {
        val genuine = "RealSteam" == shortcut?.getExtra("launchMode", "")
        if (!genuine) return RemoteTarget.EMULATED
        val account = try { SteamPrefs.accountIdOrDerived(ctx) } catch (_: Throwable) { 0 }
        return RemoteTarget(true, account)
    }

    /** The game's Steam API remote-storage folder in [container] for [remote], or null if unresolvable. */
    fun remoteStorageDir(container: Container, appId: Int, remote: RemoteTarget): File? {
        if (appId <= 0) return null
        if (remote.genuineClient) {
            if (remote.accountId == 0) return null
            return File(container.rootDir,
                ".wine/drive_c/Program Files (x86)/Steam/userdata/${remote.accountId}/$appId/remote")
        }
        return File(SaveLocator.profileDir(container), "AppData/Roaming/GSE Saves/$appId/remote")
    }

    /**
     * The game's launch shortcut — the one whose exec target sits under [installDir] — across every
     * container's `.desktop` shortcuts ([ContainerManager.loadShortcuts]). Null if no shortcut points
     * into the game's install dir (⇒ game not set up in a container yet).
     */
    fun resolveShortcut(ctx: Context, installDir: String): Shortcut? {
        if (installDir.isBlank()) return null

        val manager = ContainerManager(ctx)
        val shortcuts = try { manager.loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "loadShortcuts failed", e); return null
        }

        // Build the comparison keys: the install dir made relative to the imagefs root (what a
        // Winlator "Z:\…" exec path maps to) plus the absolute install path, both '/'-normalized,
        // lowercased, and fenced with '/' so "…/Game/" never matches "…/Game2/".
        val imageFsRoot = File(ctx.filesDir, "imagefs").absolutePath.replace('\\', '/').trimEnd('/')
        val instAbs = installDir.replace('\\', '/').trimEnd('/')
        val instRel = if (instAbs.lowercase().startsWith(imageFsRoot.lowercase()))
            instAbs.substring(imageFsRoot.length).trimStart('/') else instAbs.trimStart('/')
        val keys = listOf("/${instRel.lowercase()}/", "/${instAbs.trimStart('/').lowercase()}/")

        // The game's real Android install dir, for the drive-map match below.
        val instAbsFile = File(installDir).absolutePath.replace('\\', '/').trimEnd('/')

        for (sc in shortcuts) {
            val raw = sc.path ?: continue

            // PRIMARY (drive-agnostic): resolve the shortcut's exec back through ITS container's drive
            // map to a real Android path, then check it lives under the install dir. This is what makes
            // a game parked OFF imagefs resolve — the "Install to SD card" option puts it on the card as
            // F:\… (or an auto letter) — which the string match below misses: that match strips the
            // drive letter ("f:") off the exec, leaving "/bannerlator/…", while the key built from the
            // absolute SD install path is "/storage/<uuid>/bannerlator/…", so they never overlap.
            // resolveAndroidPath returns null for a Z:\ imagefs game (Z: isn't in the drive map), which
            // falls through to the original string match — so internal games are unaffected.
            val android = runCatching { WinePath.resolveAndroidPath(sc.container, raw) }.getOrNull()
            if (android != null) {
                val ap = android.absolutePath.replace('\\', '/').trimEnd('/')
                if (ap.equals(instAbsFile, ignoreCase = true) ||
                    ap.startsWith("$instAbsFile/", ignoreCase = true)) return sc
            }

            // FALLBACK (imagefs string match, unchanged): normalize the Winlator exec target: '\'→'/',
            // lowercase, drop a leading drive letter ("z:"), fence with a leading '/' so the key's
            // boundary matches.
            var exec = raw.replace('\\', '/').lowercase().trim()
            exec = exec.replaceFirst(Regex("^[a-z]:"), "")
            if (!exec.startsWith("/")) exec = "/$exec"
            if (keys.any { it.length > 2 && exec.contains(it) }) return sc
        }
        return null
    }

    /** The game's launch container (the container of [resolveShortcut]), or null if not set up. */
    fun resolveContainer(ctx: Context, appId: Int, installDir: String): Container? =
        resolveShortcut(ctx, installDir)?.container

    /** Human label for dialogs, e.g. "Container 2 — Default". */
    fun containerLabel(container: Container): String {
        val name = container.name
        return if (!name.isNullOrBlank()) "Container ${container.id} — $name"
        else "Container ${container.id}"
    }

    /**
     * Library-relative cloud name (`%Root%rest`, `%Root%/rest`, `ROOT_MOD/rest` or an untokened API
     * name) → absolute [File] in [container]. Null if the leading root token is unknown/unsupported,
     * if the remainder is unsafe (`..`), or if the result would escape its mapped root.
     */
    fun toContainerPath(
        libraryRel: String, container: Container, installDir: String, appId: Int = 0,
        remote: RemoteTarget = RemoteTarget.EMULATED,
    ): File? {
        val (root, remainder) = parseRoot(libraryRel) ?: return null
        val base = root.baseDir(container, installDir, appId, remote) ?: run {
            Log.w(TAG, "Root '${root.token}' can't be resolved (app id / account unknown) — skipping: $libraryRel"); return null
        }
        if (remainder.isEmpty()) return null   // a root alone is a folder, never a save file
        val dest = File(base, remainder.joinToString("/"))

        // Escape guard: the canonicalized destination must be strictly under the root.
        val baseCanon = try { base.canonicalPath } catch (e: Exception) { return null }
        val destCanon = try { dest.canonicalPath } catch (e: Exception) { return null }
        if (!destCanon.startsWith(baseCanon + File.separator)) {
            Log.w(TAG, "Rejecting container path escaping '${root.token}': $libraryRel")
            return null
        }
        return dest
    }

    /**
     * Absolute file under [container] → its cloud name in Steam's own spelling: the root token fused
     * straight onto the path (`%WinMyDocuments%Electronic Arts/The Sims 4/…`, exactly what the cloud
     * manifest and a download use), or the bare relative name for a file in the API remote store.
     * Null if [abs] is under no known root. Roots are tested most-specific first so a file in
     * Documents maps to `%WinMyDocuments%…`, never `%Root%Documents/…`.
     */
    fun toLibraryRel(
        abs: File, container: Container, installDir: String, appId: Int = 0,
        remote: RemoteTarget = RemoteTarget.EMULATED,
    ): String? {
        val target = try { abs.canonicalPath } catch (e: Exception) { return null }
        for (root in ROOTS) {
            val base = try {
                (root.baseDir(container, installDir, appId, remote) ?: continue).canonicalPath
            } catch (e: Exception) { continue }
            if (target.startsWith(base + File.separator)) {
                val rel = target.substring(base.length + 1).replace(File.separatorChar, '/')
                if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
                // Steam fuses every root token onto the path with no separator (confirmed from real
                // manifests: "%GameInstall%hl2/save/…", "%WinMyDocuments%Electronic Arts/…"). Using the
                // same shape keeps ONE Library copy per file and makes upload names match the manifest.
                return root.token + rel
            }
        }
        return null
    }

    /**
     * The `%Token%/rest` spelling an older build wrote on Collect → Steam's fused `%Token%rest`, or
     * null if [rel] isn't in that form (or the token isn't a root we know).
     */
    fun fuseTokenSlash(rel: String): String? {
        val m = Regex("^(%[^%/]+%)/+(.+)$").find(rel.replace('\\', '/')) ?: return null
        if (lookupRoot(m.groupValues[1]) == null) return null
        return m.groupValues[1] + m.groupValues[2]
    }

    /**
     * Spelling-independent identity of a cloud name: canonical root + the path segments. Two names
     * with the same key are the same file (`%WinMyDocuments%X/a` ≡ `%WinMyDocuments%/X/a`). Null if
     * the name is unsafe or uses a root we don't map.
     */
    fun canonicalKey(rel: String): String? {
        val (root, parts) = parseRoot(rel) ?: return null
        if (parts.isEmpty()) return null
        return root.token.lowercase() + "|" + parts.joinToString("/")
    }

    /** True if [rel] resolves to the install-dir root (`%GameInstall%…`). */
    fun isGameInstallRel(rel: String): Boolean = parseRoot(rel)?.first?.token == "%GameInstall%"

    /** True if [rel] resolves to the Steam API remote store (untokened or `%SteamUserBaseStorage%`). */
    fun isRemoteRel(rel: String): Boolean = parseRoot(rel)?.first?.let { it === REMOTE || it.token == "%SteamUserBaseStorage%" } == true

    /** The folder an Auto-Cloud [rule] covers in [container], or null if its root isn't one we map. */
    fun ruleBaseDir(
        rule: SteamUfsConfig.Rule, container: Container, installDir: String, appId: Int, remote: RemoteTarget,
    ): File? {
        val root = lookupRoot(rule.root) ?: return null
        val base = root.baseDir(container, installDir, appId, remote) ?: return null
        val parts = rule.path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return if (parts.isEmpty()) base else File(base, parts.joinToString("/"))
    }

    /**
     * Whether the cloud name [rel] is covered by Auto-Cloud [rule] (same root, under the rule's path —
     * directly, or at any depth when the rule is recursive — and the file name matches its pattern).
     * Pure string logic: no container needed. Path segments compare case-insensitively (Windows).
     */
    fun ruleCovers(rule: SteamUfsConfig.Rule, rel: String): Boolean {
        val (root, parts) = parseRoot(rel) ?: return false
        val ruleRoot = lookupRoot(rule.root) ?: return false
        if (root !== ruleRoot) return false
        val ruleParts = rule.path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size <= ruleParts.size) return false
        for (i in ruleParts.indices) if (!parts[i].equals(ruleParts[i], ignoreCase = true)) return false
        val remainder = parts.size - ruleParts.size
        if (!rule.recursive && remainder != 1) return false
        return SteamUfsConfig.patternRegex(rule.pattern).matches(parts.last())
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /**
     * Split a library-relative path into its UFS root + safe remainder segments. Real Steam cloud
     * paths arrive in three shapes — all handled here (HL2 uses all three):
     *   - `%GameInstall%hl2/save/x.sav` — root fused onto the path (leading `%…%`, NO separator)
     *   - `ROOT_MOD/cfg/config.cfg`     — root as a bare leading segment (no percents)
     *   - `cfg/config.cfg`              — no root token at all → the Steam API remote store ([REMOTE]):
     *     Steam keeps such files in `userdata/<id>/<appId>/remote`, and the game reads them back
     *     through ISteamRemoteStorage, so that is where they go (Goldberg / genuine-client folder).
     * (Steam's own client sometimes embeds `%GameInstall%` in the filename instead of splitting it —
     * see GameNative SteamAutoCloud's identical work-around.) The older `%Token%/rest` spelling is
     * accepted too. Rejects any `..` in the remainder. Null ⇒ empty/unsafe, or a `%…%` token we don't
     * map (⇒ caller skips + logs).
     */
    private fun parseRoot(path: String): Pair<Root, List<String>>? {
        val norm = path.replace('\\', '/').trimStart('/')
        if (norm.isEmpty()) return null

        val root: Root
        val rest: String
        val leadingPct = Regex("^%[^%]+%").find(norm)   // fused OR standalone %Token%
        if (leadingPct != null) {
            root = lookupRoot(leadingPct.value) ?: run {
                Log.w(TAG, "Unknown UFS root token '${leadingPct.value}' in: $path"); return null
            }
            rest = norm.substring(leadingPct.value.length).trimStart('/')
        } else {
            val firstSeg = norm.substringBefore('/')
            val bareRoot = if (norm.contains('/')) lookupRoot(firstSeg) else null
            if (bareRoot != null) {
                root = bareRoot
                rest = norm.substringAfter('/', "")
            } else {
                root = REMOTE                           // no root token → Steam API remote store
                rest = norm
            }
        }

        val parts = rest.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return root to parts
    }

    /** Resolve a root token — with or without surrounding `%…%`, case-insensitively, aliases applied. */
    private fun lookupRoot(token: String): Root? {
        val bare = token.lowercase().trim('%')
        if (bare.isEmpty()) return null
        val canonical = ALIASES[bare] ?: bare
        return ROOTS.firstOrNull { it !== REMOTE && it.token.lowercase().trim('%') == canonical }
    }
}
