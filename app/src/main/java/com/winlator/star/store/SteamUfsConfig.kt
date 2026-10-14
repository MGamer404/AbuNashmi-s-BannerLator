package com.winlator.star.store

import android.content.Context
import android.util.Log
import `in`.dragonbra.javasteam.types.KeyValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * A game's declared Steam Cloud (UFS) configuration from PICS product info (`appinfo → ufs`):
 *
 *  - `quota` / `maxnumfiles` — set for EVERY cloud-enabled game. A game that saves through the Steam
 *    API (ISteamRemoteStorage — Left 4 Dead 2, Counter-Strike: Source, …) has these but no
 *    `savefiles` rules at all; its cloud files carry no `%Root%` token.
 *  - `savefiles` — Steam Auto-Cloud rules (`root` + `path` + `pattern` + `recursive` + `platforms`):
 *    which folders/files the client itself picks up from disk (Monster Train 2:
 *    `WinAppDataLocalLow` / `Shiny Shoe/MonsterTrain2` / `*`, recursive).
 *
 * The config is cached in [SteamPrefs] so Collect (which runs on game exit, possibly offline) can scope
 * itself to the rules without a network round-trip. Only a populated product-info answer is cached.
 */
object SteamUfsConfig {

    private const val TAG = "BH_STEAM_CLOUD"

    /** One Auto-Cloud rule. [path] has its `{64BitSteamID}` / `{Steam3AccountID}` tokens filled in. */
    data class Rule(val root: String, val path: String, val pattern: String, val recursive: Boolean)

    data class Config(val quota: Long, val maxNumFiles: Int, val rules: List<Rule>) {
        /** True when the game has a Steam Cloud store at all (rules, or just an API quota). */
        val declaresCloud: Boolean get() = rules.isNotEmpty() || quota > 0 || maxNumFiles > 0
    }

    /** The cached config, or null if it was never fetched. Never touches the network. */
    fun cached(ctx: Context, appId: Int): Config? {
        val raw = try { SteamPrefs.getUfsConfigJson(ctx, appId) } catch (_: Throwable) { null } ?: return null
        return try { decode(JSONObject(raw), ctx) } catch (e: Exception) { null }
    }

    /**
     * The config: cached if known, else (when [allowNetwork]) one PICS read, cached on success. Null =
     * unknown (not signed in / PICS failed / metadata-only answer). Blocking — call off the main thread.
     */
    fun get(ctx: Context, appId: Int, allowNetwork: Boolean, timeoutMs: Long = 30_000L): Config? {
        cached(ctx, appId)?.let { return it }
        if (!allowNetwork) return null
        return try {
            val kv = SteamRepository.getInstance().fetchAppKeyValues(appId, timeoutMs) ?: return null
            if (kv.children.isEmpty()) return null
            val json = encode(kv)
            SteamPrefs.setUfsConfigJson(ctx, appId, json.toString())
            decode(json, ctx)
        } catch (e: Exception) {
            Log.w(TAG, "UFS config fetch failed for appId=$appId", e)
            null
        }
    }

    /** Store from a product-info answer someone already fetched (keeps the cache warm for free). */
    fun remember(ctx: Context, appId: Int, appKeyValues: KeyValue): Config? {
        if (appKeyValues.children.isEmpty()) return null
        return try {
            val json = encode(appKeyValues)
            SteamPrefs.setUfsConfigJson(ctx, appId, json.toString())
            decode(json, ctx)
        } catch (e: Exception) { null }
    }

    // ── KeyValues → JSON (raw, tokens unfilled) → Config ────────────────────────────────────

    private fun encode(app: KeyValue): JSONObject {
        val ufs = app.get("ufs")
        val out = JSONObject()
        out.put("quota", ufs.get("quota").value?.trim()?.toLongOrNull() ?: 0L)
        out.put("maxnumfiles", ufs.get("maxnumfiles").value?.trim()?.toIntOrNull() ?: 0)
        val rules = JSONArray()
        for (entry in ufs.get("savefiles").children) {
            val root = entry.get("root").value?.trim().orEmpty()
            if (root.isEmpty()) continue
            if (!appliesToWindows(entry.get("platforms"))) continue
            rules.put(JSONObject()
                .put("root", root)
                .put("path", entry.get("path").value?.trim().orEmpty())
                .put("pattern", entry.get("pattern").value?.trim().orEmpty().ifEmpty { "*" })
                .put("recursive", entry.get("recursive").value?.trim() == "1"))
        }
        out.put("rules", rules)
        return out
    }

    /** A rule with no platform list applies everywhere; otherwise it must name Windows (or "all"). */
    private fun appliesToWindows(platforms: KeyValue): Boolean {
        val values = ArrayList<String>()
        platforms.value?.let { if (it.isNotBlank()) values.add(it) }
        for (c in platforms.children) c.value?.let { values.add(it) }
        if (values.isEmpty()) return true
        return values.any { it.equals("windows", true) || it.equals("all", true) }
    }

    private fun decode(o: JSONObject, ctx: Context): Config {
        val arr = o.optJSONArray("rules") ?: JSONArray()
        val steam64 = try { SteamPrefs.init(ctx); SteamPrefs.steamId64 } catch (_: Throwable) { 0L }
        val account = try { SteamPrefs.accountIdOrDerived(ctx) } catch (_: Throwable) { 0 }
        val rules = ArrayList<Rule>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            var path = r.optString("path", "").replace('\\', '/').trim('/')
            if (path.contains('{')) {
                // A rule keyed by the account can't be resolved without one — skip it rather than guess.
                if ((path.contains("{64BitSteamID}", true) && steam64 == 0L) ||
                    (path.contains("{Steam3AccountID}", true) && account == 0)) continue
                path = path.replace("{64BitSteamID}", steam64.toString(), true)
                    .replace("{Steam3AccountID}", account.toString(), true)
            }
            rules.add(Rule(r.optString("root"), path, r.optString("pattern", "*").ifEmpty { "*" }, r.optBoolean("recursive", false)))
        }
        return Config(o.optLong("quota", 0L), o.optInt("maxnumfiles", 0), rules)
    }

    // ── Rule matching ──────────────────────────────────────────────────────────────────────

    /** Steam glob (`*`, `?`) → case-insensitive regex over one file name. */
    fun patternRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
