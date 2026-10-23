package com.winlator.star.communityconfigs

import android.util.Log
import org.json.JSONObject

/**
 * Phase 2 — fetch the actual config JSON for a chosen game+device.
 *
 * Everything goes through the first-party worker (CDN/KV-cached, no rate limit) — NOT the GitHub
 * contents API. Both the per-device best-match ([fetchForDevice]) and the exact-file path
 * ([fetchForFile]) list + download via [CommunityConfigWorker], and both read BOTH namespaces
 * (BannerHub + our own `bannerlator` repo) so a Bannerlator-shared config resolves too.
 *
 * All calls are blocking — invoke from a background thread. Every failure returns null (offline / not
 * found), which the UI turns into a clear message rather than a crash.
 */
object CommunityConfigFetcher {

    private const val TAG = "CommunityConfigs"

    /** Result of resolving + fetching a config: the parsed JSON plus the file it came from. */
    data class Fetched(val json: JSONObject, val fileName: String)

    /**
     * Resolve and download the best-matching config for [device] across the game's folders. For every
     * folder we merge the BannerHub listing AND our own `bannerlator` listing, score each entry's
     * filename (which encodes `-Mfr-Model-Soc-`) against the device tokens, pick the best match across
     * ALL merged results, then download it from the namespace it came from. Returns null when nothing
     * lists / the picked file can't be fetched or parsed.
     */
    fun fetchForDevice(game: CanonicalGame, device: CanonicalDevice): Fetched? {
        val deviceTokens = tokenizeDevice(device)
        val folders = game.folders.ifEmpty { listOf(game.name) }.distinct()

        // Every candidate paired with the folder (`/list` key) it lives under, so the download hits the
        // same bucket + namespace it was listed from.
        val candidates = ArrayList<Pair<String, WorkerConfigEntry>>()
        for (folder in folders) {
            for (entry in CommunityConfigWorker.list(folder)) candidates.add(folder to entry)
            for (entry in CommunityConfigWorker.list(folder, "bannerlator")) candidates.add(folder to entry)
        }
        if (candidates.isEmpty()) return null

        val (folder, best) = pickBest(candidates, deviceTokens) ?: return null
        val ns = if (best.appSource == "bannerlator") "bannerlator" else ""
        val body = CommunityConfigWorker.download(folder, best.filename, best.sha.ifBlank { null }, ns)
            ?: return null
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Config JSON parse failed for ${best.filename}", e)
            return null
        }
        return Fetched(json, best.filename)
    }

    /**
     * Download ONE exact config file by name (the per-uploaded-config path). Goes through the worker's
     * `GET /download?game=&file=` — which returns the raw config JSON (and bumps the sampled download
     * count) — so it lands on the same file the `/list` entry named, with no GitHub-contents walking.
     * [ns] selects the repo the file lives in ("" = BannerHub, "bannerlator" = our own repo).
     * Returns null when the fetch fails or the body isn't valid JSON (offline / removed).
     */
    fun fetchForFile(workerGame: String, filename: String, ns: String = ""): Fetched? {
        val body = CommunityConfigWorker.download(workerGame, filename, ns = ns) ?: return null
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Config JSON parse failed for $filename", e)
            return null
        }
        return Fetched(json, filename)
    }

    /** Pick the (folder, entry) whose filename shares the most tokens with the device (model + GPU). */
    private fun pickBest(
        candidates: List<Pair<String, WorkerConfigEntry>>,
        deviceTokens: Set<String>,
    ): Pair<String, WorkerConfigEntry>? {
        if (candidates.isEmpty()) return null
        if (deviceTokens.isEmpty()) return candidates.first()
        return candidates.maxByOrNull { (_, entry) ->
            val fileTokens = tokenize(entry.filename)
            deviceTokens.count { it in fileTokens }
        }
    }

    private fun tokenizeDevice(device: CanonicalDevice): Set<String> =
        tokenize("${device.model} ${device.gpu}")

    /** Lower-case alphanumeric tokens (length ≥ 2) — filenames encode "-Xiaomi-2312DRA50G-Adreno__TM__710-". */
    private fun tokenize(raw: String): Set<String> =
        raw.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
}
