package com.winlator.star.ui.screens

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

// The index maths of the XMB games row and of the All Games page, kept free of Compose and Android so it can be unit-tested.

/** The games row on Home shows this many of the most recent games; the rest are on the All Games page. */
internal const val XMB_ROW_LIMIT = 6

/** The All Games page's grid view shows this many games per row (fewer only when the screen is too narrow, see [xmbGridColumns]). */
internal const val XMB_GRID_COLS = 6

/** What the row remembers as the focused "path" while the All Games card is the one under the cursor (no real path looks like it). */
internal const val XMB_ALL_PATH = "xmb:all-games"

/** The first [limit] of an already ordered [list] when [enabled]; a search, a filter or a selection shows its whole list. */
internal fun <T> xmbRowOf(list: List<T>, limit: Int = XMB_ROW_LIMIT, enabled: Boolean = true): List<T> =
    if (enabled) list.take(limit) else list

/**
 * Where a remembered [path] sits in the row: [paths] are the games, which keep slots 0 until the last, and with [hasAll] the
 * All Games card takes the slot after the last game (so slot 0 is always the first game, or the card alone when there are no
 * games). -1 = the path isn't in the row.
 */
internal fun xmbSlotOf(paths: List<String>, path: String?, hasAll: Boolean): Int {
    if (path == null) return -1
    if (path == XMB_ALL_PATH) return if (hasAll) paths.size else -1
    return paths.indexOf(path)
}

/**
 * Columns of the All Games page's grid view: [wanted] when a cover can stay at least [minCell] wide, fewer on a narrow
 * (portrait) screen, never under [minCols]. [avail] and [gap] are the grid's width and the space between covers, in the same unit.
 */
internal fun xmbGridColumns(avail: Float, gap: Float, minCell: Float, wanted: Int = XMB_GRID_COLS, minCols: Int = 3): Int =
    ((avail + gap) / (minCell + gap)).toInt().coerceIn(minCols, wanted)

internal enum class XmbMove { LEFT, RIGHT, UP, DOWN, PAGE_UP, PAGE_DOWN }

/**
 * The cell the cursor lands on after [move] in a grid of [cols] columns holding [count] cells, row by row: Left/Right step
 * through the reading order, Up/Down a whole row (Down from a row above a shorter last row lands on its last cell).
 * [page] is how far a page jump goes.
 */
internal fun xmbGridMove(sel: Int, move: XmbMove, cols: Int, count: Int, page: Int): Int {
    if (count <= 0) return 0
    val last = count - 1
    val c = cols.coerceAtLeast(1)
    val s = sel.coerceIn(0, last)
    return when (move) {
        XmbMove.LEFT -> (s - 1).coerceAtLeast(0)
        XmbMove.RIGHT -> (s + 1).coerceAtMost(last)
        XmbMove.UP -> if (s - c >= 0) s - c else s
        XmbMove.DOWN -> when {
            s + c <= last -> s + c
            s / c < last / c -> last
            else -> s
        }
        XmbMove.PAGE_UP -> (s - page).coerceAtLeast(0)
        XmbMove.PAGE_DOWN -> (s + page).coerceAtMost(last)
    }
}

// The All Games card's mosaic (a folder-like 2 x 2 preview of the library) and the collage behind it when it is hovered.

/** The All Games card's mosaic shows this many covers, and a "+N more" tile as the fourth cell when the library has more. */
internal const val XMB_MOSAIC_COVERS = 3

/** The mosaic is a 2 x 2 grid: a library of up to this many games is shown cover by cover, without a "+N more" tile. */
internal const val XMB_MOSAIC_CELLS = 4

/** The collage behind the hovered All Games card is built from at most this many covers. */
internal const val XMB_COLLAGE_MAX = 9

/** The covers a mosaic shows ([covers], in cell order) and the games left out of it ([more], 0 = no "+N more" tile). */
internal data class XmbMosaic<T>(val covers: List<T>, val more: Int)

/**
 * The games the mosaic and the collage draw from, most recent first: the games [row] shows (the host's most recent ones), then
 * the rest of [all] in its own order. [key] tells two entries of the same game apart.
 */
internal fun <T> xmbRecentOrder(row: List<T>, all: List<T>, key: (T) -> String): List<T> = (row + all).distinctBy(key)

/**
 * What the mosaic shows for [ordered] games (most recent first): up to four games are shown cover by cover, more than that
 * [XMB_MOSAIC_COVERS] covers and a "+N more" tile counting the rest. Games with art come first (the rest only fill up when
 * fewer than that have any), each group keeping its order, so the pick never changes between recompositions.
 */
internal fun <T> xmbMosaicPick(ordered: List<T>, hasArt: (T) -> Boolean): XmbMosaic<T> {
    val total = ordered.size
    val (withArt, without) = ordered.partition(hasArt)
    val covers = (withArt + without).take(if (total <= XMB_MOSAIC_CELLS) total else XMB_MOSAIC_COVERS)
    return XmbMosaic(covers, if (total <= XMB_MOSAIC_CELLS) 0 else total - covers.size)
}

/** The covers the collage is built from: the most recent [XMB_COLLAGE_MAX] games that have art. */
internal fun <T> xmbCollagePick(ordered: List<T>, hasArt: (T) -> Boolean): List<T> = ordered.filter(hasArt).take(XMB_COLLAGE_MAX)

/** The collage bitmap's grid ([cols] x [rows] covers) and size in pixels. */
internal data class XmbCollageSpec(val cols: Int, val rows: Int, val width: Int, val height: Int)

/** The collage bitmap is this tall; its width follows the screen's shape. It stays small because it is blurred and scaled up. */
internal const val XMB_COLLAGE_HEIGHT = 360

/**
 * The collage grid for a screen [aspect] (width / height) with [available] covers: the most covers (up to [XMB_COLLAGE_MAX], never
 * more than there are) in cells that stay close to a cover's 2:3 shape, so a wide screen gets a wide grid and a portrait one a
 * tall one. Null when there are no covers.
 */
internal fun xmbCollageSpec(aspect: Float, available: Int): XmbCollageSpec? {
    val n = minOf(available, XMB_COLLAGE_MAX)
    if (n <= 0) return null
    val a = if (aspect.isFinite() && aspect > 0.1f) aspect else 1f
    var best = 1 to 1
    var bestScore = Float.MAX_VALUE
    for (rows in 1..3) for (cols in 1..5) {
        val tiles = cols * rows
        if (tiles > n) continue
        // How far a cell's shape is from 2:3, in log terms (so too wide and too tall weigh the same), less a bonus per cover.
        val cell = a * rows / cols
        val score = abs(ln(cell / (2f / 3f))) - 0.1f * tiles
        if (score < bestScore - 1e-6f) { bestScore = score; best = cols to rows }
    }
    return XmbCollageSpec(best.first, best.second, (XMB_COLLAGE_HEIGHT * a).roundToInt().coerceIn(1, 1280), XMB_COLLAGE_HEIGHT)
}

/** A rectangle in pixels (right and bottom exclusive). */
internal data class XmbRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Cell [index] (row by row) of a [cols] x [rows] grid laid over a [width] x [height] picture; the cells tile it exactly. */
internal fun xmbCellRect(index: Int, cols: Int, rows: Int, width: Int, height: Int): XmbRect {
    val c = index % cols
    val r = index / cols
    return XmbRect(c * width / cols, r * height / rows, (c + 1) * width / cols, (r + 1) * height / rows)
}

/** The middle part of a [srcW] x [srcH] picture that has the shape of a [dstW] x [dstH] box (what centre-crop shows). */
internal fun xmbCenterCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): XmbRect {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return XmbRect(0, 0, maxOf(srcW, 0), maxOf(srcH, 0))
    return if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
        // Wider than the box: trim the sides.
        val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
        val l = (srcW - w) / 2
        XmbRect(l, 0, l + w, srcH)
    } else {
        // Taller than the box (or the same shape): trim top and bottom.
        val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
        val t = (srcH - h) / 2
        XmbRect(0, t, srcW, t + h)
    }
}
