package com.winlator.star.ui.screens

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The geometry and lists of the XMB Home's Steam Deck library look (XmbSteamHome.kt), kept free of Compose and Android so it can be
// unit-tested.

/** The first game's card is drawn this many times wider than tall (Steam's own 460x215 header is 2.1). */
internal const val XMB_STEAM_WIDE_RATIO = 1.9f

/** A card that is not under the cursor is drawn at this share of the focused card's size. */
internal const val XMB_STEAM_REST_SCALE = 0.8f

/** The text tabs are this tall (dp): the touch target. */
internal const val XMB_STEAM_TABS_H = 48f

/** How far (dp, on a 412 dp phone) the covers lift while a game's options are open, at most. */
private const val XMB_STEAM_LIFT = 80f

/** The text tabs under the focused game's title. */
internal enum class XmbSteamTab { STORES, DOWNLOADS, FRIENDS, COMMUNITY }

/** The tabs in the order they are shown: Friends only while signed in to Steam. */
internal fun xmbSteamTabs(steamSignedIn: Boolean): List<XmbSteamTab> =
    XmbSteamTab.entries.filter { it != XmbSteamTab.FRIENDS || steamSignedIn }

/** The pictures the first game's wide card tries: its landscape art, then its background art, each once; the caller falls back to the cover. */
internal fun <T> xmbSteamWideOrder(wide: List<T>, background: List<T>): List<T> = (wide + background).distinct()

/**
 * Where everything of the Steam look sits (dp, from the content box's top left): one row of covers resting low, the focused game's
 * title and subtitle under it and the text tabs under those. [w] x [h] is the content box, [clipTop] the room the host's floating
 * tab bar takes at the top, [bottomClear] the room its hints take at the bottom, [k] the screen's scale ([ScreenScale.factor]) and
 * [rowH] / [extra] the options column's row height and the gap after its first row.
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
    val tabsTop = h - bottomClear - XMB_STEAM_TABS_H

    /** The covers' common bottom edge: the title, the subtitle and the tabs are under it. */
    val cardBottom = tabsTop - (8f + 24f + 16f + 4f) * k

    /** The focused portrait cover's height (the one under the cursor is drawn at full size, the others at [XMB_STEAM_REST_SCALE]). */
    val fH = min(min(h * 0.34f, w * 0.45f), cardBottom - clipTop - 20f * k).coerceAtLeast(72f)
    val fW = fH * 2f / 3f
    val wideW = fH * XMB_STEAM_WIDE_RATIO
    val gap = (fH * 0.07f).roundToInt().toFloat()
    val cardTop = cardBottom - fH

    /** The focused card's left edge: the earlier cards sit to its left and are cut off by the screen's edge. */
    val anchorX = (w * 0.10f).coerceIn(20f * k, 140f * k)
    val titleY = cardBottom + 8f * k
    val subtitleY = titleY + 24f * k

    /** How far the covers lift while the options are open, so the column fits under the focused card without it going under the tab bar. */
    val lift = min(XMB_STEAM_LIFT * k, cardTop - clipTop - 4f * k).coerceAtLeast(0f)

    /** The options column starts right under the lifted focused card. */
    val colTop = cardBottom - lift + 12f
    val nVis = ((h - bottomClear - colTop - extra) / rowH).toInt().coerceIn(2, 8)

    /** The full-size width of the card in [slot]: only the first game's is wide (with no games the slot is the All Games card). */
    fun cardW(slot: Int, games: Int): Float = if (slot == 0 && games > 0) wideW else fW
}

/** The share of its full size the card [index] is drawn at with the cursor at [pos]: 1 on the cursor, [rest] away from it, gliding in between. */
internal fun xmbSteamScale(index: Int, pos: Float, rest: Float = XMB_STEAM_REST_SCALE): Float =
    rest + (1f - rest) * (1f - abs(index - pos)).coerceAtLeast(0f)

/**
 * The left edge of card [index] relative to the focused card's left edge, with the cursor at [pos] (fractional mid-swipe: the focused
 * edge is the one between the two cards it is moving between). [fullW] is a slot's full-size width and [gap] the space between cards;
 * every card takes its drawn width ([xmbSteamScale]), so the spacing stays even while the cursor moves.
 */
internal fun xmbSteamOffset(index: Int, pos: Float, fullW: (Int) -> Float, gap: Float, rest: Float = XMB_STEAM_REST_SCALE): Float {
    fun left(i: Int): Float {
        var x = 0f
        for (j in 0 until i) x += fullW(j) * xmbSteamScale(j, pos, rest) + gap
        return x
    }
    val lo = floor(pos).toInt()
    val focusLeft = left(lo) + (left(lo + 1) - left(lo)) * (pos - lo)
    return left(index) - focusLeft
}

/** How many cards from the cursor to the right the row draws before the screen's edge cuts it ([w] wide, cards at least [minCard] wide, [gap] apart). */
internal fun xmbSteamAhead(w: Float, minCard: Float, gap: Float): Int = ceil(w / max(1f, minCard + gap)).toInt() + 2

