package com.winlator.star.ui.screens

// The index maths of the XMB games row and of the All Games page, kept free of Compose and Android so it can be unit-tested.

/** The games row on Home shows this many of the most recent games; the rest are on the All Games page. */
internal const val XMB_ROW_LIMIT = 6

/** What the row remembers as the focused "path" while the All Games card is the one under the cursor (no real path looks like it). */
internal const val XMB_ALL_PATH = "xmb:all-games"

/** The first [limit] of an already ordered [list] when [enabled]; a search, a filter or a selection shows its whole list. */
internal fun <T> xmbRowOf(list: List<T>, limit: Int = XMB_ROW_LIMIT, enabled: Boolean = true): List<T> =
    if (enabled) list.take(limit) else list

/**
 * Where a remembered [path] sits in the row: [paths] are the games, and with [hasAll] the All Games card takes slot 0 and
 * pushes every game one slot right. -1 = the path isn't in the row.
 */
internal fun xmbSlotOf(paths: List<String>, path: String?, hasAll: Boolean): Int {
    if (path == null) return -1
    if (path == XMB_ALL_PATH) return if (hasAll) 0 else -1
    val i = paths.indexOf(path)
    return if (i < 0) -1 else i + (if (hasAll) 1 else 0)
}

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
