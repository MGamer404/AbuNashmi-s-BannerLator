package com.winlator.star.core

import com.winlator.star.container.Container
import com.winlator.star.xenvironment.ImageFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Heuristic discovery of a single game's save folders inside a container's Wine profile, plus a
 * per-container sidecar that remembers the confirmed folder set so a taught game is one tap next
 * time.
 *
 * Everything here works in paths *relative to the xuser profile* (drive_c/users/[ImageFs.USER]),
 * so a discovered/remembered root feeds straight into [GameSaveBackup.backup]'s scoped overload
 * and survives a container move. Pure logic where it can be; the only I/O is the filesystem scan,
 * the byte-size sums, and the JSON sidecar.
 */
object SaveLocator {

    /**
     * Roots (relative to the xuser profile) that host the vast majority of PC game saves. Scanned
     * in this order; a folder matched under an earlier root wins over the same name deeper down.
     */
    private val SAVE_ROOTS = listOf(
        "Documents/My Games",
        "Saved Games",
        "AppData/Roaming",
        "Documents",
        "AppData/Local",
        "AppData/LocalLow",
    )

    /** Keep candidates scoring at least this; below it the name match is too weak to trust. */
    private const val KEEP_THRESHOLD = 50

    /** A discovered (or remembered) save-folder candidate. [relPath] is relative to the xuser profile. */
    data class Candidate(
        val relPath: String,
        val displayName: String,
        val score: Int,
        val sizeBytes: Long,
    )

    /** A remembered mapping for one game (the sidecar's per-key value). */
    data class SaveMap(val name: String, val roots: List<String>)

    /** Normalized identifier: a compact alnum form for exact/containment/edit-distance, plus tokens. */
    private data class Ident(val norm: String, val tokens: Set<String>)

    // ------------------------------------------------------------------ discovery

    /** The Wine user profile dir this container's saves live under. */
    fun profileDir(container: Container): File =
        File(container.rootDir, ".wine/drive_c/users/${ImageFs.USER}")

    /**
     * Scans the known save roots at depth 1 and 2 (depth 2 catches
     * AppData/Roaming/&lt;Publisher&gt;/&lt;Game&gt;), scoring each dir name against the game's three
     * identifiers — display [name], exe basename of [exePath], and [wmClass]. Returns candidates
     * scoring &ge; [KEEP_THRESHOLD], ranked by score, with nested hits de-duped and byte sizes
     * computed for the survivors. Empty list ⇒ nothing matched ⇒ the UI offers manual add only.
     */
    fun discover(container: Container, name: String, exePath: String, wmClass: String): List<Candidate> {
        val profile = profileDir(container)
        if (!profile.isDirectory) return emptyList()

        val ids = listOf(name, basename(exePath), wmClass)
            .map { ident(it) }
            .filter { it.norm.isNotEmpty() }
        if (ids.isEmpty()) return emptyList()

        // Best score per relative path (a dir can match more than one root walk).
        val hits = LinkedHashMap<String, Candidate>()
        for (root in SAVE_ROOTS) {
            val rootDir = File(profile, root)
            if (!rootDir.isDirectory) continue
            rootDir.listFiles()?.forEach { d1 ->
                if (!d1.isDirectory) return@forEach
                scoreInto(hits, profile, d1, ids)                       // depth 1
                d1.listFiles()?.forEach { d2 ->
                    if (d2.isDirectory) scoreInto(hits, profile, d2, ids) // depth 2
                }
            }
        }

        // De-dupe nested hits: drop a candidate whose ancestor already survived (keep the parent).
        val ranked = hits.values.sortedByDescending { it.score }
        val kept = ArrayList<Candidate>()
        for (c in ranked) {
            if (kept.none { anc -> isDescendant(c.relPath, anc.relPath) }) kept.add(c)
        }

        // Attach real byte sizes only to survivors (cheap recursive sum), then re-rank.
        return kept
            .map { it.copy(sizeBytes = folderSize(File(profile, it.relPath))) }
            .sortedByDescending { it.score }
    }

    private fun scoreInto(out: MutableMap<String, Candidate>, profile: File, dir: File, ids: List<Ident>) {
        val d = ident(dir.name)
        if (d.norm.isEmpty()) return
        val score = ids.maxOf { score(it, d) }
        if (score < KEEP_THRESHOLD) return
        val rel = dir.relativeTo(profile).path.replace(File.separatorChar, '/')
        val existing = out[rel]
        if (existing == null || score > existing.score) {
            out[rel] = Candidate(rel, dir.name, score, 0L)
        }
    }

    /** exact = 100; one contains the other = 70; token overlap ≥50% = 50; Levenshtein ratio ≥0.85 = 40. */
    private fun score(a: Ident, b: Ident): Int {
        if (a.norm.isEmpty() || b.norm.isEmpty()) return 0
        if (a.norm == b.norm) return 100
        if (a.norm.contains(b.norm) || b.norm.contains(a.norm)) return 70
        var best = 0
        if (a.tokens.isNotEmpty() && b.tokens.isNotEmpty()) {
            val overlap = a.tokens.intersect(b.tokens).size.toDouble() /
                minOf(a.tokens.size, b.tokens.size)
            if (overlap >= 0.5) best = 50
        }
        if (best < 50 && levenshteinRatio(a.norm, b.norm) >= 0.85) best = 40
        return best
    }

    // ------------------------------------------------------------------ manual add / guard

    /**
     * Converts an absolute directory picked under the xuser profile to a path relative to it, or
     * null if it escapes the profile (the same canonical-prefix guard used on restore) or is the
     * profile root itself. The returned path is what gets persisted / handed to the backup engine.
     */
    fun relativizeUnderProfile(container: Container, dir: File): String? {
        val profile = profileDir(container).canonicalFile
        val base = profile.path + File.separator
        val canon = try { dir.canonicalFile } catch (e: Exception) { return null }
        if (canon.path == profile.path) return null
        if (!canon.path.startsWith(base)) return null
        return canon.path.substring(base.length).replace(File.separatorChar, '/')
    }

    /** Byte size of a folder relative to the profile (for a manually-added root, computed on demand). */
    fun sizeOf(container: Container, relPath: String): Long = folderSize(File(profileDir(container), relPath))

    // ------------------------------------------------------------------ sidecar

    private fun sidecarFile(container: Container): File =
        File(container.rootDir, "app_data/save_maps.json")

    /** Stable per-shortcut key: wmClass when present, else the .lnk/.desktop filename. */
    fun gameKey(wmClass: String?, shortcutFileName: String): String =
        if (!wmClass.isNullOrBlank()) wmClass else shortcutFileName

    /** Remembered mapping for [gameKey], or null if this game hasn't been taught yet. */
    fun loadMap(container: Container, gameKey: String): SaveMap? {
        val f = sidecarFile(container)
        if (!f.isFile) return null
        return try {
            val obj = JSONObject(f.readText()).optJSONObject(gameKey) ?: return null
            val arr = obj.optJSONArray("roots") ?: JSONArray()
            SaveMap(obj.optString("name"), (0 until arr.length()).map { arr.getString(it) })
        } catch (e: Exception) {
            null
        }
    }

    /** Persists (or overwrites) the confirmed folder set for [gameKey]. Other keys are preserved. */
    fun saveMap(container: Container, gameKey: String, name: String, roots: List<String>) {
        val f = sidecarFile(container)
        val root = try {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        root.put(gameKey, JSONObject().put("name", name).put("roots", JSONArray(roots)))
        f.parentFile?.mkdirs()
        f.writeText(root.toString(2))
    }

    // ------------------------------------------------------------------ helpers (pure)

    /** lowercase, strip ®™©, drop every non-alphanumeric (compact norm) + whitespace/separator tokens. */
    private fun ident(raw: String): Ident {
        val cleaned = raw.lowercase().replace("®", "").replace("™", "").replace("©", "")
        val norm = cleaned.filter { it.isLetterOrDigit() }
        val tokens = cleaned.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        return Ident(norm, tokens)
    }

    private fun basename(path: String): String {
        val cut = path.replace('\\', '/').substringAfterLast('/')
        return cut.substringBeforeLast('.', cut)
    }

    /** True if [child] is nested under [ancestor] (or equal), both profile-relative slash paths. */
    private fun isDescendant(child: String, ancestor: String): Boolean =
        child == ancestor || child.startsWith("$ancestor/")

    /** 1 − editDistance / maxLen, clamped to [0,1]. */
    private fun levenshteinRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun folderSize(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.addLast(c) else total += c.length()
            }
        }
        return total
    }
}
