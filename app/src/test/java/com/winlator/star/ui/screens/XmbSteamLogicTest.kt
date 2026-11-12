package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Steam look of the XMB Home: its tab list, the art order of the wide card, the row's scrolling and windowing, and the layout. */
class XmbSteamLogicTest {

    private val delta = 0.001f

    // A screen 800 wide with a 20 margin; a wide first card (200) and portrait cards (100), 10 apart, no focus growth unless asked.
    private fun row(count: Int, focus: Float = 1f, viewW: Float = 800f) =
        XmbSteamRow(count, firstW = 200f, otherW = 100f, gap = 10f, viewW = viewW, edge = 20f, focus = focus)

    @Test fun tabs_friendsOnlyWhileSignedInToSteam() {
        assertEquals(listOf(XmbSteamTab.STORES, XmbSteamTab.DOWNLOADS, XmbSteamTab.COMMUNITY), xmbSteamTabs(false))
        assertEquals(
            listOf(XmbSteamTab.STORES, XmbSteamTab.DOWNLOADS, XmbSteamTab.FRIENDS, XmbSteamTab.COMMUNITY),
            xmbSteamTabs(true),
        )
    }

    @Test fun wideOrder_landscapeArtFirstThenBackground_eachOnce() {
        assertEquals(listOf("pick", "header", "hero2x", "hero"), xmbSteamWideOrder(listOf("pick", "header"), listOf("pick", "hero2x", "hero")))
        assertEquals(emptyList<String>(), xmbSteamWideOrder(emptyList<String>(), emptyList()))
    }

    @Test fun scale_isASubtleLiftOnTheCursorOnly() {
        assertEquals(XMB_STEAM_FOCUS_SCALE, xmbSteamScale(2, 2f), delta)
        assertEquals(1f, xmbSteamScale(0, 2f), delta)
        assertEquals(1f, xmbSteamScale(5, 2f), delta)
        assertTrue(XMB_STEAM_FOCUS_SCALE in 1.05f..1.08f)
    }

    @Test fun scale_glidesBetweenTwoCards() {
        val mid = (1f + XMB_STEAM_FOCUS_SCALE) / 2f
        assertEquals(mid, xmbSteamScale(1, 1.5f), delta)
        assertEquals(mid, xmbSteamScale(2, 1.5f), delta)
    }

    @Test fun row_wideCapsuleHasSteamsShape() {
        assertEquals(460f / 215f, XMB_STEAM_WIDE_RATIO, delta)
    }

    @Test fun x_longRowAtTheStart_isPinnedToTheLeftMargin() {
        val r = row(30)
        assertEquals(20f, r.x(0, 0f), delta)
        assertEquals(20f + 210f, r.x(1, 0f), delta)
        // Cards 1 and 2 are still too near the start to be centred.
        assertEquals(20f, r.x(0, 1f), delta)
        assertEquals(20f, r.x(0, 2f), delta)
    }

    @Test fun x_longRowInTheMiddle_centresTheFocusedCard() {
        val r = row(30)
        for (p in listOf(8, 12, 20)) assertEquals(400f, r.x(p, p.toFloat()) + 50f, delta)
        // The card before it sits one pitch to the left.
        assertEquals(400f - 50f - 110f, r.x(11, 12f), delta)
    }

    @Test fun x_longRowAtTheEnd_isPinnedToTheRightMargin() {
        val r = row(30)
        assertEquals(800f - 20f, r.x(29, 29f) + 100f, delta)
        assertEquals(800f - 20f, r.x(29, 28f) + 100f, delta)
        assertEquals(800f - 20f, r.x(29, 27f) + 100f, delta)
    }

    @Test fun x_rowThatFits_doesNotScroll() {
        val r = row(4)
        for (p in 0..3) {
            assertEquals(20f, r.x(0, p.toFloat()), delta)
            assertEquals(230f, r.x(1, p.toFloat()), delta)
        }
    }

    @Test fun x_glidesWithoutJumps() {
        val r = row(30)
        var last = r.x(10, 0f)
        var p = 0f
        while (p <= 29f) {
            val x = r.x(10, p)
            assertTrue("jump at $p: $last -> $x", kotlin.math.abs(x - last) < 6.5f)
            last = x
            p += 0.05f
        }
    }

    @Test fun x_focusGrowthKeepsTheNeighboursApart() {
        val r = row(30, focus = 1.1f)
        // Card 12 is 10% wider while focused (110) and its left neighbour ends one gap before it.
        assertEquals(r.x(12, 12f) - 10f, r.x(11, 12f) + 100f, delta)
        assertEquals(r.x(12, 12f) + 110f + 10f, r.x(13, 12f), delta)
    }

    @Test fun x_overshootPastTheEndsStaysPinned() {
        val r = row(30)
        assertEquals(20f, r.x(0, -0.4f), delta)
    }

    @Test fun x_emptyAndSingleRows() {
        assertEquals(20f, row(0).x(0, 0f), delta)
        assertEquals(20f, row(1).x(0, 0f), delta)
    }

    @Test fun window_aroundTheCursorOnly() {
        // 800 wide, 110 apart: 8 cards across, plus 2 spare, each side.
        assertEquals(40..60, xmbSteamWindow(50f, 300, 800f, 110f))
        val w = xmbSteamWindow(150f, 300, 800f, 110f)
        assertEquals(140, w.first)
        assertEquals(160, w.last)
        assertTrue(w.count() < 40)
    }

    @Test fun window_isClampedToTheLibrary() {
        assertEquals(0..10, xmbSteamWindow(0f, 300, 800f, 110f))
        assertEquals(290..299, xmbSteamWindow(299f, 300, 800f, 110f))
        assertTrue(xmbSteamWindow(0f, 0, 800f, 110f).isEmpty())
    }

    @Test fun layout_coversSitAtHalfTheScreenAndAreAQuarterTall() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(393f * 0.5f, l.cardTop, delta)
        assertEquals(393f * 0.27f, l.fH, delta)
        assertEquals(6f, l.gap, delta)
        assertEquals(l.fH * XMB_STEAM_WIDE_RATIO, l.wideW, delta)
    }

    @Test fun layout_titleUnderTheCardsAndTabsBelowIt() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertTrue(l.cardBottom < l.titleY)
        assertTrue(l.titleY + 20f + 14f <= l.tabsTop + XMB_STEAM_TABS_H / 2f)
        assertTrue(l.tabsTop + XMB_STEAM_TABS_H <= 393f)
        // The covers keep clear of the floating tab bar, even lifted.
        assertTrue(l.cardTop - l.lift >= 50f)
    }

    @Test fun layout_optionsColumnOpensUnderTheLiftedCard() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(l.cardBottom - l.lift + 12f, l.colTop, delta)
        assertTrue(l.nVis in 2..8)
    }

    @Test fun layout_onlyTheFirstGamesCardIsWide() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(l.wideW, l.cardW(0, 3), delta)
        assertEquals(l.fW, l.cardW(1, 3), delta)
        assertEquals(l.fW, l.cardW(0, 0), delta)
        assertEquals(l.wideW, l.row(3).firstW, delta)
        assertEquals(3, l.row(3).count)
    }

    @Test fun layout_titleStaysOnTheScreenWhenTheRowIsPinnedRight() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        val row = l.row(30)
        assertTrue(l.titleX(row, 29, 29f) + l.titleW <= 851f - l.edge + delta)
        assertEquals(row.x(0, 0f), l.titleX(row, 0, 0f), delta)
    }

    @Test fun layout_portraitScreenKeepsTheWideCardOnScreen() {
        val l = XmbSteamLayout(w = 393f, h = 851f, clipTop = 50f, bottomClear = 46f, k = 0.95f, rowH = 46f, extra = 6f)
        assertTrue(l.port)
        assertTrue(l.wideW + 2 * l.edge <= 393f)
    }
}
