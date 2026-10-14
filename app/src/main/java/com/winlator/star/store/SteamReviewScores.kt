package com.winlator.star.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Steam USER-review scores (the percent-positive the store shows under "All Reviews") for the
 * Library tab's rating sort and card line.
 *
 * ## Why the public store API and not PICS
 * PICS' `common` block carries a review percentage for many apps, but never the review COUNT (needed
 * as the tie-break), and the Rust engine returns app info through a fixed field set that cannot be
 * extended without rebuilding native code. `store.steampowered.com/appreviews/<appid>` answers the
 * same way whichever engine is running and needs no login, so both engines share one source.
 *
 * ## Behaviour
 *  - [backfill] walks the games that have no fresh score, strictly one request at a time, with a
 *    [REQUEST_GAP_MS] pause after each, and caches every answer in `steam_games` (review_* columns)
 *    so it survives restarts and works offline afterwards. Entries older than [STALE_SEC] are
 *    refreshed.
 *  - HTTP 429 / 5xx / transport errors back off exponentially and retry the same game; after
 *    [MAX_CONSECUTIVE_FAILURES] in a row the run stops and stays quiet for [COOLDOWN_MS].
 *  - A game Steam answers for but has fewer than [MIN_REVIEWS] reviews (Steam itself shows no
 *    rating below ten) is stored as unrated (-1) with a fetch stamp, so it is not re-requested on
 *    every launch.
 *  - Never throws to the caller (other than cancellation) and never touches the main thread: the
 *    network and DB work run on [Dispatchers.IO]; only [backfill]'s `onProgress` runs in the caller's
 *    context.
 */
internal object SteamReviewScores {

    private const val TAG = StorefrontLog.LIBRARY

    /** A stored score older than this is fetched again. */
    const val STALE_SEC = 7L * 24 * 60 * 60

    /** Below this many reviews Steam shows no rating, so neither do we. */
    const val MIN_REVIEWS = 10

    /**
     * The Library's rating sort only RANKS a game with at least this many reviews; everything below
     * (and every unrated game) sits in the last "not enough reviews" group however high its percent.
     * The real percent / label / total are still stored and shown on the card.
     */
    const val MIN_RANKED_REVIEWS = 500

    /** Pause after every completed request: polite to the store, and keeps the UI quiet. */
    private const val REQUEST_GAP_MS = 200L

    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_CONSECUTIVE_FAILURES = 4

    /** After giving up, [backfill] is a no-op for this long (the next library visit retries). */
    private const val COOLDOWN_MS = 2L * 60 * 1000

    /** [backfill] reports progress at most this often so a list of hundreds doesn't churn the UI. */
    private const val PROGRESS_EVERY_MS = 1_500L

    /** One Steam review summary. [percent] -1 = no rating; [desc] is Steam's label or "". */
    internal data class ReviewSummary(val percent: Int, val total: Int, val desc: String)

    private sealed interface FetchResult {
        data class Ok(val summary: ReviewSummary) : FetchResult

        /** This game will not get a score from a retry (bad JSON, 404, ...). Skip it. */
        data class Permanent(val reason: String) : FetchResult

        /** Rate limit / server error / network trouble: back off and try the same game again. */
        data class Retry(val reason: String) : FetchResult
    }

    /** One run at a time, ever — also across a cancelled-and-restarted caller. */
    private val gate = Mutex()

    /** appIds already settled this process (scored, or skipped as unscoreable). */
    private val settled: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    @Volatile
    private var cooldownUntilMs = 0L

    /** True when [game] has no score yet, or one older than [STALE_SEC]. Demos are never queried:
     *  Steam files reviews under the full game, so a demo's own page has none. */
    fun needsFetch(game: SteamGame, nowSec: Long): Boolean =
        game.appId > 0 &&
            game.type != LibraryTypeFilter.TYPE_DEMO &&
            (game.reviewUpdated <= 0L || abs(nowSec - game.reviewUpdated) > STALE_SEC)

    /** The subset of [games] [backfill] would still request, in the same order. */
    fun pending(games: List<SteamGame>): List<SteamGame> {
        val now = System.currentTimeMillis() / 1000L
        return games.filter { needsFetch(it, now) && it.appId !in settled }
    }

    /**
     * Fetch and cache a score for every game in [games] that needs one, in list order (so callers
     * put what is on screen first). Suspends until done, cancelled, or it gives up; returns how many
     * scores were stored.
     *
     * [onProgress] runs in the CALLER's coroutine context, debounced to [PROGRESS_EVERY_MS], plus
     * once at the end if anything was stored since the last call — the Library tab passes its
     * `reload` so rows pick up the new values. It is not called when the run is cancelled.
     */
    suspend fun backfill(games: List<SteamGame>, onProgress: suspend () -> Unit): Int {
        if (System.currentTimeMillis() < cooldownUntilMs) return 0
        if (pending(games).isEmpty()) return 0
        // withLock queues a second caller behind the first rather than dropping it, and is itself
        // cancellable; the first run notices ITS cancellation within one request.
        return gate.withLock { runLocked(games, onProgress) }
    }

    private suspend fun runLocked(games: List<SteamGame>, onProgress: suspend () -> Unit): Int {
        if (System.currentTimeMillis() < cooldownUntilMs) return 0
        // Re-filtered under the lock: a run that held it first may already have settled some of these.
        val todo = pending(games)
        if (todo.isEmpty()) return 0
        StorefrontLog.i(TAG, "review scores: ${todo.size} of ${games.size} game(s) need a Steam rating")

        var stored = 0
        var unreported = 0
        var lastReportMs = System.currentTimeMillis()
        var failures = 0
        var i = 0
        while (i < todo.size) {
            coroutineContext.ensureActive()
            val game = todo[i]
            if (game.appId in settled) { i++; continue }

            val result = withContext(Dispatchers.IO) { fetchOne(game.appId) }
            when (result) {
                is FetchResult.Ok -> {
                    failures = 0
                    val s = result.summary
                    val saved = withContext(Dispatchers.IO) { store(game.appId, s) }
                    if (saved) {
                        settled.add(game.appId)
                        stored++
                        unreported++
                    }
                    i++
                    val now = System.currentTimeMillis()
                    if (unreported > 0 && now - lastReportMs >= PROGRESS_EVERY_MS) {
                        lastReportMs = now
                        unreported = 0
                        onProgress()
                    }
                    delay(REQUEST_GAP_MS)
                }

                is FetchResult.Permanent -> {
                    StorefrontLog.w(TAG, "review scores: app ${game.appId} skipped (${result.reason})")
                    settled.add(game.appId)
                    i++
                    delay(REQUEST_GAP_MS)
                }

                is FetchResult.Retry -> {
                    failures++
                    if (failures >= MAX_CONSECUTIVE_FAILURES) {
                        cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
                        StorefrontLog.w(
                            TAG,
                            "review scores: giving up after $failures consecutive failures " +
                                "(${result.reason}) — retrying on the next library visit",
                        )
                        break
                    }
                    val wait = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (failures - 1))
                    StorefrontLog.w(TAG, "review scores: ${result.reason} — backing off ${wait}ms")
                    delay(wait)
                }
            }
        }
        if (unreported > 0) onProgress()
        StorefrontLog.i(TAG, "review scores: stored $stored rating(s)")
        return stored
    }

    // ── Network + parse ───────────────────────────────────────────────────────────────────────

    private fun fetchOne(appId: Int): FetchResult {
        val url = "https://store.steampowered.com/appreviews/$appId" +
            "?json=1&language=all&purchase_type=all&num_per_page=0"
        return when (val r = SteamStoreSearch.httpGetDetailed(url)) {
            is SteamStoreSearch.HttpOutcome.Ok -> try {
                FetchResult.Ok(parseSummary(r.body))
            } catch (e: Exception) {
                FetchResult.Permanent("unparseable response: ${e.javaClass.simpleName}")
            }
            is SteamStoreSearch.HttpOutcome.HttpError ->
                if (r.code == 429 || r.code >= 500) FetchResult.Retry("HTTP ${r.code}")
                else FetchResult.Permanent("HTTP ${r.code}")
            is SteamStoreSearch.HttpOutcome.Transport -> FetchResult.Retry("network ${r.kind}")
            is SteamStoreSearch.HttpOutcome.EmptyBody -> FetchResult.Permanent("empty response")
        }
    }

    /**
     * Parse an `appreviews` response. A `success` other than 1 or a missing `query_summary` is
     * "Steam has nothing for this app" → unrated. Throws [org.json.JSONException] on malformed JSON
     * (the caller turns that into a skip).
     */
    internal fun parseSummary(json: String): ReviewSummary {
        val root = JSONObject(json)
        if (root.optInt("success", 0) != 1) return ReviewSummary(-1, 0, "")
        val q = root.optJSONObject("query_summary") ?: return ReviewSummary(-1, 0, "")
        val positive = q.optInt("total_positive", 0).coerceAtLeast(0)
        val negative = q.optInt("total_negative", 0).coerceAtLeast(0)
        val total = positive + negative
        if (total < MIN_REVIEWS) return ReviewSummary(-1, total, "")
        val percent = (positive * 100.0 / total).roundToInt().coerceIn(0, 100)
        val raw = q.optString("review_score_desc", "").trim()
        // Below the rating threshold Steam's label is a count ("7 user reviews" / "No user reviews"),
        // not a rating word — never show that as if it were one.
        val desc = if (raw.isEmpty() || raw.first().isDigit() || raw.startsWith("No ", ignoreCase = true)) "" else raw
        return ReviewSummary(percent, total, desc)
    }

    private fun store(appId: Int, s: ReviewSummary): Boolean = try {
        val repo = SteamRepository.getInstance()
        repo.database.setReviewScore(appId, s.percent, s.total, s.desc)
        // The cached row list is what the Library tab reloads from; drop it so the next read sees this.
        repo.invalidateGameCache()
        true
    } catch (e: Exception) {
        StorefrontLog.w(TAG, "review scores: could not save app $appId", e)
        false
    }
}
