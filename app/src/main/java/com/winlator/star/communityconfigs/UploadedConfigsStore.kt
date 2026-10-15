package com.winlator.star.communityconfigs

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PHASE 3 (online sharing) — the local registry of the user's OWN community-config uploads, so the app
 * can offer "replace your existing upload" and (later) a My-uploads list even after a reinstall.
 *
 * Backed by BOTH:
 *  - SharedPreferences [PREFS] — the fast local cache read on every hot path.
 *  - a manifest file [MANIFEST] under {@code Download/bannerlator/game-configs/} — the durable copy the
 *    upload_token backup lives in, surviving app uninstall so a reinstalled app can recover the tokens
 *    needed to delete/replace/describe past uploads.
 *
 * Every read hydrates the SP cache from the manifest when the cache is empty but the manifest exists
 * (the reinstall case). All file IO is best-effort and wrapped in try/catch — nothing here ever throws;
 * a failed manifest write simply leaves the SP cache authoritative for this run. Call off the main
 * thread (the upload path already runs on Dispatchers.IO).
 */
object UploadedConfigsStore {

    private const val TAG = "CommunityConfigs"
    private const val PREFS = "banner_config_uploads"
    private const val KEY = "records"
    private const val MANIFEST = "my-uploaded-configs.json"

    /**
     * One recorded upload. [token] is the {@code upload_token} that minted it (needed to delete/replace/
     * describe), [sha] the worker's key for it, [game]/[filename] the identity, and [soc]/[device]/[date]
     * the provenance shown in a My-uploads list. Only one record is kept per [game].
     */
    data class UploadedConfig(
        val game: String,
        val filename: String,
        val sha: String,
        val token: String,
        val soc: String,
        val device: String,
        val date: Long,
    )

    /**
     * Add [record], write-through to BOTH the SP cache and the manifest. A record with the same [game]
     * as an existing one REPLACES it (one upload per game — the user's latest wins).
     */
    fun add(context: Context, record: UploadedConfig) {
        val current = all(context).filterNot { it.game == record.game }.toMutableList()
        current.add(record)
        persist(context, current)
    }

    /**
     * PHASE 4 (optional accounts) — CROSS-DEVICE RECOVERY. Fold [records] (rebuilt from an account's
     * server-side upload registry at login) into the local store, deduped by [UploadedConfig.sha]. Any
     * record whose sha is already present is skipped — the existing LOCAL record is kept as-is (it may
     * carry richer provenance than the login-derived one). New shas are appended. Write-through to BOTH
     * the SP cache and the durable manifest. Best-effort and never throws; call off the main thread.
     */
    fun merge(context: Context, records: List<UploadedConfig>) {
        if (records.isEmpty()) return
        val current = all(context).toMutableList()
        val known = current.mapTo(HashSet()) { it.sha }
        var changed = false
        for (r in records) {
            if (r.sha.isBlank() || r.sha in known) continue
            current.add(r)
            known.add(r.sha)
            changed = true
        }
        if (changed) persist(context, current)
    }

    /** Remove the record with [sha] from both the SP cache and the manifest. */
    fun remove(context: Context, sha: String) {
        val current = all(context).filterNot { it.sha == sha }
        persist(context, current)
    }

    /** Every recorded upload. Hydrates the SP cache from the manifest on a cold (reinstall) read. */
    fun all(context: Context): List<UploadedConfig> {
        val fromPrefs = readPrefs(context)
        if (fromPrefs.isNotEmpty()) return fromPrefs
        // SP empty — try the durable manifest (reinstall recovery) and re-seed the cache from it.
        val fromManifest = readManifest()
        if (fromManifest.isNotEmpty()) writePrefs(context, fromManifest)
        return fromManifest
    }

    /** The user's existing upload for [game], or null when they haven't shared one. */
    fun forGame(context: Context, game: String): UploadedConfig? =
        all(context).firstOrNull { it.game == game }

    // --- persistence -----------------------------------------------------------------------------

    private fun persist(context: Context, records: List<UploadedConfig>) {
        writePrefs(context, records)
        writeManifest(records)
    }

    private fun readPrefs(context: Context): List<UploadedConfig> =
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            if (raw.isNullOrBlank()) emptyList() else parse(JSONArray(raw))
        } catch (e: Exception) {
            Log.w(TAG, "Uploaded-configs prefs read failed", e)
            emptyList()
        }

    private fun writePrefs(context: Context, records: List<UploadedConfig>) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, encode(records).toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Uploaded-configs prefs write failed", e)
        }
    }

    private fun readManifest(): List<UploadedConfig> =
        try {
            val file = manifestFile()
            if (file == null || !file.exists()) emptyList() else parse(JSONArray(file.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Uploaded-configs manifest read failed", e)
            emptyList()
        }

    private fun writeManifest(records: List<UploadedConfig>) {
        try {
            val file = manifestFile() ?: return
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            file.writeText(encode(records).toString(2))
            file.setReadable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Uploaded-configs manifest write failed", e)
        }
    }

    private fun manifestFile(): File? =
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(File(downloads, "bannerlator/game-configs"), MANIFEST)
        } catch (e: Exception) {
            null
        }

    private fun encode(records: List<UploadedConfig>): JSONArray {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject()
                    .put("game", r.game)
                    .put("filename", r.filename)
                    .put("sha", r.sha)
                    .put("token", r.token)
                    .put("soc", r.soc)
                    .put("device", r.device)
                    .put("date", r.date)
            )
        }
        return arr
    }

    private fun parse(arr: JSONArray): List<UploadedConfig> {
        val out = ArrayList<UploadedConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val game = o.optString("game", "").trim()
            val sha = o.optString("sha", "").trim()
            if (game.isEmpty() || sha.isEmpty()) continue
            out.add(
                UploadedConfig(
                    game = game,
                    filename = o.optString("filename", "").trim(),
                    sha = sha,
                    token = o.optString("token", "").trim(),
                    soc = o.optString("soc", "").trim(),
                    device = o.optString("device", "").trim(),
                    date = o.optLong("date", 0L),
                )
            )
        }
        return out
    }
}
