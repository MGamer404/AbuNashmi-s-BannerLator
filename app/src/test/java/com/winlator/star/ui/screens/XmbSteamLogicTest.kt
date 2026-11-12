package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Steam look of the XMB Home: its tab list, the art order of the wide card, the cards' places in the row and the layout. */
class XmbSteamLogicTest {

    // A wide first card (200) and portrait cards (100), 10 apart, as the row has them.
    private val fullW = { slot: Int -> if (slot == 0) 200f else 100f }

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

    @Test fun scale_isFullOnTheCursorAndRestAwayFromIt() {
        assertEquals(1f, xmbSteamScale(2, 2f), 0.0001f)
        assertEquals(XMB_STEAM_REST_SCALE, xmbSteamScale(0, 2f), 0.0001f)
        assertEquals(XMB_STEAM_REST_SCALE, xmbSteamScale(5, 2f), 0.0001f)
    }

    @Test fun scale_glidesBetweenTwoCards() {
        val mid = (1f + XMB_STEAM_REST_SCALE) / 2f
        assertEquals(mid, xmbSteamScale(1, 1.5f), 0.0001f)
        assertEquals(mid, xmbSteamScale(2, 1.5f), 0.0001f)
    }

    @Test fun offset_focusedCardSitsOnTheAnchor() {
        assertEquals(0f, xmbSteamOffset(0, 0f, fullW, 10f), 0.0001f)
        assertEquals(0f, xmbSteamOffset(3, 3f, fullW, 10f), 0.0001f)
    }

    @Test fun offset_nextCardFollowsTheFocusedOneAfterTheGap() {
        assertEquals(210f, xmbSteamOffset(1, 0f, fullW, 10f), 0.0001f)
        // Slot 2 is one rest-sized portrait card (80) and a gap further.
        assertEquals(210f + 100f * XMB_STEAM_REST_SCALE + 10f, xmbSteamOffset(2, 0f, fullW, 10f), 0.0001f)
    }

    @Test fun offset_earlierCardsSitLeftOfTheAnchorAtRestSize() {
        assertEquals(-(200f * XMB_STEAM_REST_SCALE + 10f), xmbSteamOffset(0, 1f, fullW, 10f), 0.0001f)
    }

    @Test fun offset_midSwipeMovesHalfwayAndStaysEven() {
        // Both cards the cursor is between are drawn at the same size, so the focused edge is half way to the next card's.
        val half = (200f * (XMB_STEAM_REST_SCALE + 1f) / 2f + 10f) / 2f
        assertEquals(half, xmbSteamOffset(1, 0.5f, fullW, 10f), 0.0001f)
        assertEquals(-half, xmbSteamOffset(0, 0.5f, fullW, 10f), 0.0001f)
    }

    @Test fun offset_overshootPastTheEndsKeepsTheRowOnScreenMaths() {
        assertEquals(0f, xmbSteamOffset(0, -0.4f, fullW, 10f), 0.0001f)
    }

    @Test fun ahead_coversTheWidthPlusTwoSpare() {
        assertEquals(10 + 2, xmbSteamAhead(800f, 70f, 10f))
        assertEquals(1 + 2, xmbSteamAhead(50f, 90f, 10f))
    }

    @Test fun layout_rowSitsAboveTheTitleAndTheTabsAboveTheHints() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(393f - 46f, l.tabsTop + XMB_STEAM_TABS_H, 0.0001f)
        assertTrue(l.cardBottom < l.titleY)
        assertTrue(l.titleY < l.subtitleY)
        assertTrue(l.subtitleY + 16f <= l.tabsTop + 0.0001f)
        // The covers keep clear of the floating tab bar, even lifted.
        assertTrue(l.cardTop >= 50f)
        assertTrue(l.cardTop - l.lift >= 50f)
    }

    @Test fun layout_optionsColumnOpensUnderTheLiftedCard() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(l.cardBottom - l.lift + 12f, l.colTop, 0.0001f)
        assertTrue(l.nVis in 2..8)
    }

    @Test fun layout_onlyTheFirstGamesCardIsWide() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(l.wideW, l.cardW(0, 3), 0.0001f)
        assertEquals(l.fW, l.cardW(1, 3), 0.0001f)
        // With no games the first slot is the All Games card, a portrait one.
        assertEquals(l.fW, l.cardW(0, 0), 0.0001f)
    }

    @Test fun layout_portraitScreenKeepsTheWideCardOnScreen() {
        val l = XmbSteamLayout(w = 393f, h = 851f, clipTop = 50f, bottomClear = 46f, k = 0.95f, rowH = 46f, extra = 6f)
        assertTrue(l.port)
        assertTrue(l.anchorX + l.wideW <= 393f)
    }
}
