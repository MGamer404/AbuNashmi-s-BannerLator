package com.winlator.star.ui.screens

import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.communityconfigs.GameMatcher
import com.winlator.star.communityconfigs.WorkerConfigEntry
import kotlin.math.floor

// The pure rules of the XMB Home's Community configs page (ui/deck/XmbCommunityPage.kt), kept free of Compose and Android so they can be
// unit-tested: the card grid's columns and the D-pad's moves in it, the filter strip, which games the library already has, which fit this
// device, and the order of a game's configs. The list rules the page shares with the old dialog are in CommunityBrowserLogic.kt.

/** [xmbCcMove]'s answer for Up from the grid's first row: the cursor goes to the filter strip. */
internal const val XMB_CC_TO_STRIP = -1

/** The controls of the filter strip, left to right: search, fits this device, in the library, sort, refresh. */
internal const val XMB_CC_STRIP_SEARCH = 0
internal const val XMB_CC_STRIP_FITS = 1
internal const val XMB_CC_STRIP_LIBRARY = 2
internal const val XMB_CC_STRIP_SORT = 3
internal const val XMB_CC_STRIP_REFRESH = 4
internal const val XMB_CC_STRIP_COUNT = 5

/** A game card is never narrower than this (dp); the grid has as many columns as fit. */
internal const val XMB_CC_MIN_CARD_DP = 250f

/** The gap (dp) between the cards. */
internal const val XMB_CC_GAP_DP = 10f

/** The most columns the grid ever has. */
internal const val XMB_CC_MAX_COLUMNS = 4

/** How many cards of at least [minCard] dp fit [widthDp] with [gap] between them: 1 to [XMB_CC_MAX_COLUMNS]. */
internal fun xmbCcColumns(widthDp: Float, minCard: Float = XMB_CC_MIN_CARD_DP, gap: Float = XMB_CC_GAP_DP): Int {
    if (widthDp <= 0f || minCard <= 0f) return 1
    return floor((widthDp + gap) / (minCard + gap)).toInt().coerceIn(1, XMB_CC_MAX_COLUMNS)
}

/**
 * The card the D-pad reaches from [index] with [dx] / [dy] (each -1, 0 or 1) in [count] cards laid [columns] to a row: Left / Right stop
 * at the row's ends, Down from a full row to the card under it (the last card when the row below is shorter), Down from the last row
 * stays, and Up from the first row is [XMB_CC_TO_STRIP].
 */
internal fun xmbCcMove(index: Int, dx: Int, dy: Int, columns: Int, count: Int): Int {
    if (count <= 0) return if (dy < 0) XMB_CC_TO_STRIP else 0
    val cols = columns.coerceAtLeast(1)
    val i = index.coerceIn(0, count - 1)
    if (dx != 0) {
        val to = i + dx
        return if (to < 0 || to >= count || to / cols != i / cols) i else to
    }
    if (dy == 0) return i
    if (dy < 0) return if (i < cols) XMB_CC_TO_STRIP else i - cols
    val down = i + cols
    if (down < count) return down
    return if (i / cols == (count - 1) / cols) i else count - 1
}

/** The strip's control [delta] places from [index], stopping at the ends. */
internal fun xmbCcStripStep(index: Int, delta: Int): Int = (index + delta).coerceIn(0, XMB_CC_STRIP_COUNT - 1)

/** The order after [sort] on the sort control: most configs first, then A to Z (the dialog's "Devices" order is not offered). */
internal fun xmbCcNextSort(sort: CatalogSort): CatalogSort = if (sort == CatalogSort.CONFIGS) CatalogSort.NAME else CatalogSort.CONFIGS

/**
 * The games the page lists. [fits] (the games with a config for this device's hardware) narrows them when [onlyFits], and [mine] (the games
 * the library already has) when [onlyMine]; a search and the sort are as the dialog's (see [communityVisibleGames]).
 */
internal fun xmbCcVisible(
    games: List<CanonicalGame>,
    query: String,
    sort: CatalogSort,
    fits: Set<String>,
    onlyFits: Boolean,
    mine: Set<String>,
    onlyMine: Boolean,
): List<CanonicalGame> = communityVisibleGames(games, query, sort) { g ->
    (!onlyFits || g.identity in fits) && (!onlyMine || g.identity in mine)
}

/** The identities of the [games] that have a config for the hardware [userSoc] / [userGpu] (the dialog's "Matches my device"). */
internal fun xmbCcFittingGames(games: List<CanonicalGame>, userSoc: String?, userGpu: String?): Set<String> {
    if (userSoc == null && userGpu == null) return emptySet()
    val out = HashSet<String>()
    for (g in games) if (g.devices.any { GameMatcher.deviceMatchesUser(it, userSoc, userGpu) }) out.add(g.identity)
    return out
}

/** A game name as a key that ignores case, punctuation, edition words, word order and repeats ("The Witcher 3: GOTY" = "witcher 3"). */
private fun xmbCcNameKey(name: String): String = GameMatcher.tokenize(name).distinct().sorted().joinToString(" ")

/**
 * The identities of the [games] the library already has: a game whose Steam appid is one of [steamAppIds], or whose name is one of
 * [names] (the library's game names) once both are normalised. Exact only: a loose match would claim games the library does not have.
 */
internal fun xmbCcLibraryGames(games: List<CanonicalGame>, names: List<String>, steamAppIds: Collection<String>): Set<String> {
    val ids = steamAppIds.filter { it.isNotBlank() }.toSet()
    val keys = names.map { xmbCcNameKey(it) }.filter { it.isNotEmpty() }.toSet()
    if (ids.isEmpty() && keys.isEmpty()) return emptySet()
    val out = HashSet<String>()
    for (g in games) {
        val appId = g.steamAppId
        if (appId != null && appId in ids) {
            out.add(g.identity)
            continue
        }
        val key = xmbCcNameKey(g.name)
        if (key.isNotEmpty() && key in keys) out.add(g.identity)
    }
    return out
}

/**
 * A game's uploaded configs ([entries], each with the worker folder it came from) with those [fits] this device first, each group keeping
 * its order (the worker's: most votes first, then newest).
 */
internal fun xmbCcRankConfigs(
    entries: List<Pair<String, WorkerConfigEntry>>,
    fits: (WorkerConfigEntry) -> Boolean,
): List<Pair<String, WorkerConfigEntry>> {
    val (match, rest) = entries.partition { fits(it.second) }
    return match + rest
}
