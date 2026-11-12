package com.winlator.star.ui.screens

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The geometry and lists of the XMB Home's Steam Deck library look (XmbSteamHome.kt), kept free of Compose and Android so it can be
// unit-tested.

/** The first game's card has the shape of Steam's wide capsule (460x215), at the same height as the other cards. */
internal const val XMB_STEAM_WIDE_RATIO = 460f / 215f

/** The card under the cursor is drawn this many times its size: a subtle lift, not a zoom. */
internal const val XMB_STEAM_FOCUS_SCALE = 1.06f

/** The text tabs are this tall (dp): the touch target. */
internal const val XMB_STEAM_TABS_H = 48f

/** How far (dp, on a 412 dp phone) the covers lift while a game's options are open, at most. */
private const val XMB_STEAM_LIFT = 120f

/** The text tabs under the focused game's title. */
internal enum class XmbSteamTab { STORES, DOWNLOADS, FRIENDS, COMMUNITY }

/** The tabs in the order they are shown: Friends only while signed in to Steam. */
internal fun xmbSteamTabs(steamSignedIn: Boolean): List<XmbSteamTab> =
    XmbSteamTab.entries.filter { it != XmbSteamTab.FRIENDS || steamSignedIn }

/** The pictures the first game's wide card tries: its landscape art, then its background art, each once; the caller falls back to the cover. */
internal fun <T> xmbSteamWideOrder(wide: List<T>, background: List<T>): List<T> = (wide + background).distinct()

/** The share of its size the card [index] is drawn at with the cursor at [pos]: [focus] on the cursor, 1 away from it, gliding in between. */
internal fun xmbSteamScale(index: Int, pos: Float, focus: Float = XMB_STEAM_FOCUS_SCALE): Float =
    1f + (focus - 1f) * (1f - abs(index - pos)).coerceAtLeast(0f)

/**
 * The slots to compose with the cursor at [pos]: [count] cards in all, on a screen [viewW] wide with cards at least [pitch] apart. A
 * window around the cursor wide enough for the row to run from edge to edge on either side of it (the row is centred on the cursor
 * or pinned to an end), so a library of hundreds only ever composes a couple of dozen covers.
 */
internal fun xmbSteamWindow(pos: Float, count: Int, viewW: Float, pitch: Float): IntRange {
    val reach = ceil(viewW / max(1f, pitch)).toInt() + 2
    val base = floor(pos).toInt()
    return (base - reach).coerceAtLeast(0)..(base + reach).coerceAtMost(count - 1)
}

/**
 * The row of cards: [count] of them, the first [firstW] wide and the others [otherW], [gap] apart, on a screen [viewW] wide with an
 * [edge] margin. Widths are those of an unfocused card; the one under the cursor is drawn [focus] times larger, from its left edge.
 */
internal class XmbSteamRow(
    val count: Int,
    val firstW: Float,
    val otherW: Float,
    val gap: Float,
    val viewW: Float,
    val edge: Float,
    val focus: Float = XMB_STEAM_FOCUS_SCALE,
) {
    private fun fullW(i: Int): Float = if (i == 0) firstW else otherW

    /** Card [i]'s left edge along the strip (0 = the first card's) with the cursor at [pos]; every card takes its drawn width. */
    fun left(i: Int, pos: Float): Float {
        if (i <= 0) return 0f
        // Only the (at most two) cards the cursor is on or between are drawn larger than their base size.
        var x = firstW + gap + (i - 1) * (otherW + gap)
        val lo = floor(pos).toInt()
        for (j in lo..lo + 1) if (j in 0 until i) x += fullW(j) * (xmbSteamScale(j, pos, focus) - 1f)
        return x
    }

    /** The strip's whole width with the cursor at [pos]. */
    fun total(pos: Float): Float = if (count <= 0) 0f else left(count, pos) - gap

    private fun centre(i: Int, pos: Float): Float = left(i, pos) + fullW(i) * xmbSteamScale(i, pos, focus) / 2f

    /**
     * Card [index]'s left edge on the screen with the cursor at [pos] (fractional while it glides): the cursor's card is centred, except
     * where that would show empty space: the first cards stay pinned to the left [edge] margin and the last ones to the right margin, and
     * a row that fits on the screen does not scroll at all (left aligned at the margin). That is clamp(centred, pinned right, pinned left).
     */
    fun x(index: Int, pos: Float): Float {
        if (count <= 0) return edge
        val p = pos.coerceIn(0f, (count - 1).toFloat())
        val lo = floor(p).toInt()
        val hi = min(lo + 1, count - 1)
        val focusCentre = centre(lo, pos) + (centre(hi, pos) - centre(lo, pos)) * (p - lo)
        val total = total(pos)
        val pinnedLeft = edge
        val pinnedRight = viewW - edge - total
        val shift = if (pinnedRight >= pinnedLeft) pinnedLeft else (viewW / 2f - focusCentre).coerceIn(pinnedRight, pinnedLeft)
        return left(index, pos) + shift
    }
}

/**
 * Where everything of the Steam look sits (dp, from the content box's top left): one row of covers resting low (tops at half the
 * screen, 27% of its height tall), the focused game's title and subtitle right under it and the centred text tabs near the bottom.
 * [w] x [h] is the content box, [clipTop] the room the host's floating tab bar takes at the top, [bottomClear] the room its hints take
 * at the bottom, [k] the screen's scale ([ScreenScale.factor]) and [rowH] / [extra] the options column's row height and the gap
 * after its first row.
 */
internal class XmbSteamLayout(
    val w: Float,
    val h: Float,
    clipTop: Float,
    bottomClear: Float,
    k: Float,
    rowH: Float,
    extra: Float,
) {
    val port = h > w * 1.05f

    /** The margin the row is pinned to at its ends. */
    val edge = (w * 0.035f).coerceIn(16f * k, 48f * k)

    /** An unfocused portrait cover's height; the wide first card is as tall. */
    val fH = min(h * 0.27f, w * 0.30f).coerceAtLeast(72f)
    val fW = fH * 2f / 3f
    val wideW = fH * XMB_STEAM_WIDE_RATIO
    val gap = 6f
    val cardTop = max(h * 0.50f, clipTop + 8f * k)
    val cardBottom = cardTop + fH
    val titleY = cardBottom + 4f * k

    /** The title is as wide as the wide card (at most half the screen); it is kept on the screen when the row is pinned right. */
    val titleW = min(wideW, w * 0.5f)

    /** The tabs are centred in a band [XMB_STEAM_TABS_H] tall; a narrow screen, where they would meet the hints, lifts them above those. */
    val tabsTop = (if (w >= 640f) h * 0.91f else min(h * 0.91f, h - bottomClear - XMB_STEAM_TABS_H / 2f)) - XMB_STEAM_TABS_H / 2f

    /** How far the covers lift while the options are open, so the column fits under the focused card without it going under the tab bar. */
    val lift = min(XMB_STEAM_LIFT * k, cardTop - clipTop - 4f * k).coerceAtLeast(0f)

    /** The options column starts right under the lifted focused card. */
    val colTop = cardBottom - lift + 12f
    val nVis = ((h - bottomClear - colTop - extra) / rowH).toInt().coerceIn(2, 8)

    /** The row for a library of [games] (all of them: there is no All Games card here). */
    fun row(games: Int): XmbSteamRow = XmbSteamRow(games, if (games > 0) wideW else fW, fW, gap, w, edge)

    /** The base width of the card in [slot]: only the first game's is wide. */
    fun cardW(slot: Int, games: Int): Float = if (slot == 0 && games > 0) wideW else fW

    /** The title's left edge: the focused card's, but never so far right that the title leaves the screen. */
    fun titleX(row: XmbSteamRow, target: Int, pos: Float): Float = min(row.x(target, pos), w - edge - titleW).coerceAtLeast(0f)
}
