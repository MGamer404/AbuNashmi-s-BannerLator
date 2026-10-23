package com.winlator.star.communityconfigs

import android.util.Log
import com.winlator.star.core.HttpUtils
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch

/**
 * One config entry as returned by the BannerHub configs worker's `/list` endpoint. Unlike the raw
 * GitHub contents listing [CommunityConfigFetcher] walks, the worker attaches the social fields we
 * need — [sha] (the key for votes / desc), [votes], [downloads] and [appSource].
 */
data class WorkerConfigEntry(
    val filename: String,
    val sha: String,
    val device: String,
    val soc: String,
    val date: String,
    val votes: Int,
    val downloads: Int,
    val appSource: String,
)

/** One comment on a config, as returned by the worker's `/comments` endpoint. */
data class WorkerComment(
    val text: String,
    val device: String,
    val date: String,
)

/** Result of a successful `/upload` — the committed [sha] (the delete/describe key) and repo [path]. */
data class UploadResult(
    val sha: String,
    val path: String,
)

/**
 * Thin client for the first-party BannerHub configs worker (the SAME worker the app already calls for
 * Steam search). Adds the live social layer on top of the read-only GitHub mirror: per-config votes /
 * downloads / description and the comments thread.
 *
 * Every call is BLOCKING (bridged off [HttpUtils]' async executor via a latch, exactly like
 * [CommunityConfigFetcher]) — invoke from a background thread. Every failure degrades to null / empty,
 * never throws, so missing social data simply doesn't render.
 */
object CommunityConfigWorker {

    private const val TAG = "CommunityConfigs"
    private const val BASE = "https://bannerhub-configs-worker.the412banner.workers.dev"

    /**
     * GET `/list?game=` — every config for [game] with votes / downloads / app_source attached. When
     * [ns] is non-blank it is appended as `&ns=` to read a NAMESPACED repo (e.g. our own
     * `bannerlator-game-configs` via `ns=bannerlator`); a blank [ns] keeps the default BannerHub read
     * byte-for-byte unchanged.
     */
    fun list(game: String, ns: String = ""): List<WorkerConfigEntry> {
        val nsQ = if (ns.isBlank()) "" else "&ns=${enc(ns)}"
        val body = get("$BASE/list?game=${enc(game)}$nsQ") ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<WorkerConfigEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val filename = o.optString("filename", "").trim()
                if (filename.isEmpty()) continue
                out.add(
                    WorkerConfigEntry(
                        filename = filename,
                        sha = o.optString("sha", "").trim(),
                        device = o.optString("device", "").trim(),
                        soc = o.optString("soc", "").trim(),
                        date = o.optString("date", "").trim(),
                        votes = o.optInt("votes", 0),
                        downloads = o.optInt("downloads", 0),
                        appSource = o.optString("app_source", "").trim(),
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Worker /list parse failed for $game", e)
            emptyList()
        }
    }

    /**
     * GET `/download?game=&file=&sha=` — the raw config JSON (also bumps the sampled download count).
     * Optional: the detail page fetches raw via [CommunityConfigFetcher]; kept for completeness / reuse.
     * When [ns] is non-blank it is appended as `&ns=` to read the NAMESPACED repo (e.g. `bannerlator`);
     * a blank [ns] keeps the default BannerHub download unchanged.
     */
    fun download(game: String, file: String, sha: String? = null, ns: String = ""): String? {
        val shaQ = if (sha.isNullOrBlank()) "" else "&sha=${enc(sha)}"
        val nsQ = if (ns.isBlank()) "" else "&ns=${enc(ns)}"
        return get("$BASE/download?game=${enc(game)}&file=${enc(file)}$shaQ$nsQ")
    }

    /** GET `/comments?game=&file=` — the comment thread for a config (may be empty). */
    fun comments(game: String, file: String): List<WorkerComment> {
        val body = get("$BASE/comments?game=${enc(game)}&file=${enc(file)}") ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<WorkerComment>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text", "").trim()
                if (text.isEmpty()) continue
                out.add(
                    WorkerComment(
                        text = text,
                        device = o.optString("device", "").trim(),
                        date = o.optString("date", "").trim(),
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Worker /comments parse failed for $game/$file", e)
            emptyList()
        }
    }

    /** GET `/desc?sha=` — the uploader's description for a config (empty string when none). */
    fun desc(sha: String): String {
        if (sha.isBlank()) return ""
        val body = get("$BASE/desc?sha=${enc(sha)}") ?: return ""
        return try {
            JSONObject(body).optString("text", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** POST `/vote` with `{sha, game, filename}` — returns the new vote count, or null on failure. */
    fun vote(sha: String, game: String, filename: String): Int? {
        val body = JSONObject()
            .put("sha", sha)
            .put("game", game)
            .put("filename", filename)
            .toString()
        val resp = post("$BASE/vote", body) ?: return null
        return try {
            val o = JSONObject(resp)
            if (o.optBoolean("success", true)) o.optInt("votes", -1).takeIf { it >= 0 } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * POST `/comment` with `{game, filename, text, device}` — text is clamped to the worker's 500-char
     * limit here so a too-long body isn't silently rejected. Returns true on success.
     */
    fun postComment(game: String, filename: String, text: String, device: String): Boolean {
        val body = JSONObject()
            .put("game", game)
            .put("filename", filename)
            .put("text", text.take(500))
            .put("device", device.take(60))
            .toString()
        val resp = post("$BASE/comment", body) ?: return false
        return try {
            JSONObject(resp).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * POST `/upload?ns=` with `{game, filename, content, upload_token}` — [contentBase64] is the config
     * JSON base64-encoded. [ns] namespaces the upload into OUR repo (default "bannerlator") so BannerHub
     * users never see it. When [session] is non-blank (the user is signed in, Phase 2) it is added so the
     * worker registers the upload under that account; a blank/null [session] keeps the anonymous shape.
     * Returns the committed [UploadResult] on success, or null on any failure.
     */
    fun upload(
        game: String,
        filename: String,
        contentBase64: String,
        uploadToken: String,
        ns: String = "bannerlator",
        session: String? = null,
    ): UploadResult? {
        val body = JSONObject()
            .put("game", game)
            .put("filename", filename)
            .put("content", contentBase64)
            .put("upload_token", uploadToken)
            .also { if (!session.isNullOrBlank()) it.put("session", session) }
            .toString()
        val resp = post("$BASE/upload?ns=${enc(ns)}", body) ?: return null
        return try {
            val o = JSONObject(resp)
            if (!o.optBoolean("success", false)) return null
            val sha = o.optString("sha", "").trim()
            if (sha.isEmpty()) return null
            UploadResult(sha = sha, path = o.optString("path", "").trim())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * POST `/delete?ns=` with `{sha, game, filename, upload_token}` — retires the user's own upload
     * (authorized by the [uploadToken] that minted it). Returns true on success.
     */
    fun deleteUpload(
        sha: String,
        game: String,
        filename: String,
        uploadToken: String,
        ns: String = "bannerlator",
    ): Boolean {
        val body = JSONObject()
            .put("sha", sha)
            .put("game", game)
            .put("filename", filename)
            .put("upload_token", uploadToken)
            .toString()
        val resp = post("$BASE/delete?ns=${enc(ns)}", body) ?: return false
        return try {
            JSONObject(resp).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * POST `/describe?ns=` with `{sha, token, text}` — set/replace the uploader's description for their
     * own config (authorized by the upload [token]). Not yet wired to UI — reserved for step 3's
     * My-uploads edit-description. Returns true on success.
     */
    fun describe(sha: String, token: String, text: String, ns: String = "bannerlator"): Boolean {
        val body = JSONObject()
            .put("sha", sha)
            .put("token", token)
            .put("text", text)
            .toString()
        val resp = post("$BASE/describe?ns=${enc(ns)}", body) ?: return false
        return try {
            JSONObject(resp).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun enc(raw: String): String = URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /** Blocking GET bridged off [HttpUtils]' async executor (mirrors [CommunityConfigFetcher]). */
    private fun get(url: String): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        HttpUtils.download(url) { body ->
            holder[0] = body
            latch.countDown()
        }
        return try {
            latch.await()
            holder[0]
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** Blocking POST (JSON body) bridged off [HttpUtils]' async executor. */
    private fun post(url: String, jsonBody: String): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        HttpUtils.post(url, jsonBody) { body ->
            holder[0] = body
            latch.countDown()
        }
        return try {
            latch.await()
            holder[0]
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
