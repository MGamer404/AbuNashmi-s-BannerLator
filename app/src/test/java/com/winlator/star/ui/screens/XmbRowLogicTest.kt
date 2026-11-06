package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB games row's slot maths (the All Games card after the last game), its row limit, and the All Games page's grid. */
class XmbRowLogicTest {

    private val paths = listOf("a.desktop", "b.desktop", "c.desktop")

    @Test fun slotOf_withAllCard_leavesTheGamesWhereTheyAre() {
        assertEquals(0, xmbSlotOf(paths, "a.desktop", hasAll = true))
        assertEquals(2, xmbSlotOf(paths, "c.desktop", hasAll = true))
    }

    @Test fun slotOf_withoutAllCard_isThePlainIndex() {
        assertEquals(0, xmbSlotOf(paths, "a.desktop", hasAll = false))
        assertEquals(2, xmbSlotOf(paths, "c.desktop", hasAll = false))
    }

    @Test fun slotOf_allCardPath_isTheSlotAfterTheLastGameOnlyWhenThereIsACard() {
        assertEquals(3, xmbSlotOf(paths, XMB_ALL_PATH, hasAll = true))
        assertEquals(-1, xmbSlotOf(paths, XMB_ALL_PATH, hasAll = false))
    }

    @Test fun slotOf_noGames_theCardIsSlotZero() {
        assertEquals(0, xmbSlotOf(emptyList(), XMB_ALL_PATH, hasAll = true))
    }

    @Test fun gridColumns_wideScreen_isSix() {
        assertEquals(6, xmbGridColumns(avail = 740f, gap = 12f, minCell = 80f))
        assertEquals(6, xmbGridColumns(avail = 1400f, gap = 12f, minCell = 80f))
    }

    @Test fun gridColumns_narrowScreen_dropsColumnsButNotBelowThree() {
        assertEquals(4, xmbGridColumns(avail = 380f, gap = 12f, minCell = 80f))
        assertEquals(3, xmbGridColumns(avail = 280f, gap = 12f, minCell = 80f))
        assertEquals(3, xmbGridColumns(avail = 100f, gap = 12f, minCell = 80f))
    }

    @Test fun slotOf_unknownOrMissingPath_isNone() {
        assertEquals(-1, xmbSlotOf(paths, "gone.desktop", hasAll = true))
        assertEquals(-1, xmbSlotOf(paths, null, hasAll = true))
        assertEquals(-1, xmbSlotOf(emptyList(), "a.desktop", hasAll = true))
    }

    @Test fun rowOf_keepsTheFirstSix_unlessTurnedOff() {
        val ten = (1..10).toList()
        assertEquals((1..6).toList(), xmbRowOf(ten))
        assertEquals(ten, xmbRowOf(ten, enabled = false))
        assertEquals(listOf(1, 2, 3), xmbRowOf(listOf(1, 2, 3)))
        assertEquals(XMB_ROW_LIMIT, xmbRowOf(ten).size)
    }

    @Test fun gridMove_leftRight_walkTheReadingOrder() {
        assertEquals(4, xmbGridMove(5, XmbMove.LEFT, cols = 3, count = 10, page = 9))
        assertEquals(3, xmbGridMove(2, XmbMove.RIGHT, cols = 3, count = 10, page = 9))
        assertEquals(0, xmbGridMove(0, XmbMove.LEFT, cols = 3, count = 10, page = 9))
        assertEquals(9, xmbGridMove(9, XmbMove.RIGHT, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_upDown_stepARow() {
        assertEquals(1, xmbGridMove(4, XmbMove.UP, cols = 3, count = 10, page = 9))
        assertEquals(7, xmbGridMove(4, XmbMove.DOWN, cols = 3, count = 10, page = 9))
        assertEquals(1, xmbGridMove(1, XmbMove.UP, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_down_fromAboveAShortLastRow_landsOnTheLastCell() {
        // 10 cells in 3 columns: the last row holds only cell 9.
        assertEquals(9, xmbGridMove(7, XmbMove.DOWN, cols = 3, count = 10, page = 9))
        assertEquals(9, xmbGridMove(8, XmbMove.DOWN, cols = 3, count = 10, page = 9))
        // Already on the last row: nowhere to go.
        assertEquals(9, xmbGridMove(9, XmbMove.DOWN, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_list_isOneColumn() {
        assertEquals(3, xmbGridMove(2, XmbMove.DOWN, cols = 1, count = 5, page = 3))
        assertEquals(1, xmbGridMove(2, XmbMove.UP, cols = 1, count = 5, page = 3))
    }

    @Test fun gridMove_pageJumps_clampToTheEnds() {
        assertEquals(9, xmbGridMove(5, XmbMove.PAGE_DOWN, cols = 3, count = 10, page = 9))
        assertEquals(0, xmbGridMove(5, XmbMove.PAGE_UP, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_emptyGrid_staysAtZero() {
        assertEquals(0, xmbGridMove(0, XmbMove.DOWN, cols = 3, count = 0, page = 9))
    }

    // The games here are plain strings; one with an "x" in front has no art.
    private val hasArt: (String) -> Boolean = { !it.startsWith("x") }

    @Test fun recentOrder_rowFirst_thenTheRest_withoutRepeats() {
        assertEquals(listOf("c", "a", "b", "d"), xmbRecentOrder(listOf("c", "a"), listOf("a", "b", "c", "d")) { it })
        assertEquals(listOf("a", "b"), xmbRecentOrder(emptyList(), listOf("a", "b")) { it })
    }

    @Test fun mosaic_moreThanFour_threeCoversAndTheRestCounted() {
        val m = xmbMosaicPick((1..8).map { "g$it" }, hasArt)
        assertEquals(listOf("g1", "g2", "g3"), m.covers)
        assertEquals(5, m.more)
    }

    @Test fun mosaic_skipsGamesWithoutArtWhenItCan() {
        val m = xmbMosaicPick(listOf("xa", "b", "xc", "d", "e", "f"), hasArt)
        assertEquals(listOf("b", "d", "e"), m.covers)
        assertEquals(3, m.more)
    }

    @Test fun mosaic_fewGamesWithArt_areFilledUpWithTheOthersInOrder() {
        val m = xmbMosaicPick(listOf("xa", "b", "xc", "xd", "xe"), hasArt)
        assertEquals(listOf("b", "xa", "xc"), m.covers)
        assertEquals(2, m.more)
    }

    @Test fun mosaic_fourOrFewerGames_showEveryCoverAndNoMoreTile() {
        val four = xmbMosaicPick(listOf("a", "b", "c", "d"), hasArt)
        assertEquals(listOf("a", "b", "c", "d"), four.covers)
        assertEquals(0, four.more)
        val three = xmbMosaicPick(listOf("a", "b", "c"), hasArt)
        assertEquals(listOf("a", "b", "c"), three.covers)
        assertEquals(0, three.more)
        val two = xmbMosaicPick(listOf("xa", "b"), hasArt)
        assertEquals(listOf("b", "xa"), two.covers)
        assertEquals(0, two.more)
    }

    @Test fun mosaic_noGames_isEmpty() {
        val m = xmbMosaicPick(emptyList<String>(), hasArt)
        assertTrue(m.covers.isEmpty())
        assertEquals(0, m.more)
    }

    @Test fun mosaic_fiveGames_threeCoversAndTwoMore() {
        val m = xmbMosaicPick((1..5).map { "g$it" }, hasArt)
        assertEquals(3, m.covers.size)
        assertEquals(2, m.more)
    }

    @Test fun collagePick_onlyGamesWithArt_atMostNine() {
        val games = listOf("xa") + (1..12).map { "g$it" }
        assertEquals((1..9).map { "g$it" }, xmbCollagePick(games, hasArt))
        assertEquals(listOf("b"), xmbCollagePick(listOf("xa", "b", "xc"), hasArt))
        assertTrue(xmbCollagePick(listOf("xa"), hasArt).isEmpty())
    }

    @Test fun collageSpec_noCovers_isNone() {
        assertNull(xmbCollageSpec(2.2f, 0))
    }

    @Test fun collageSpec_wideScreen_isAWideGrid() {
        val s = xmbCollageSpec(2.2f, 9)!!
        assertEquals(4, s.cols)
        assertEquals(2, s.rows)
        assertEquals(XMB_COLLAGE_HEIGHT, s.height)
        assertEquals(792, s.width)
    }

    @Test fun collageSpec_portraitScreen_isATallGrid() {
        val s = xmbCollageSpec(0.46f, 9)!!
        assertEquals(2, s.cols)
        assertEquals(3, s.rows)
    }

    @Test fun collageSpec_neverNeedsMoreCoversThanThereAre() {
        for (aspect in listOf(0.46f, 1f, 1.78f, 2.2f)) {
            for (n in 1..14) {
                val s = xmbCollageSpec(aspect, n)!!
                assertTrue(s.cols * s.rows in 1..minOf(n, XMB_COLLAGE_MAX))
            }
        }
        val one = xmbCollageSpec(2.2f, 1)!!
        assertEquals(1, one.cols * one.rows)
    }

    @Test fun collageSpec_nonsenseAspect_stillGivesAGrid() {
        val s = xmbCollageSpec(0f, 4)!!
        assertEquals(XMB_COLLAGE_HEIGHT, s.height)
        assertTrue(s.cols * s.rows in 1..4)
    }

    @Test fun cellRect_cellsTileThePictureExactly() {
        assertEquals(XmbRect(0, 0, 33, 25), xmbCellRect(0, 3, 2, 100, 50))
        assertEquals(XmbRect(66, 0, 100, 25), xmbCellRect(2, 3, 2, 100, 50))
        assertEquals(XmbRect(0, 25, 33, 50), xmbCellRect(3, 3, 2, 100, 50))
        assertEquals(XmbRect(66, 25, 100, 50), xmbCellRect(5, 3, 2, 100, 50))
    }

    @Test fun centerCrop_widePicture_losesItsSides() {
        assertEquals(XmbRect(50, 0, 150, 100), xmbCenterCrop(200, 100, 100, 100))
    }

    @Test fun centerCrop_tallPicture_losesTopAndBottom() {
        assertEquals(XmbRect(0, 50, 100, 150), xmbCenterCrop(100, 200, 100, 100))
    }

    @Test fun centerCrop_sameShape_keepsEverything() {
        assertEquals(XmbRect(0, 0, 600, 900), xmbCenterCrop(600, 900, 200, 300))
    }

    @Test fun centerCrop_emptyBox_keepsEverything() {
        assertEquals(XmbRect(0, 0, 10, 20), xmbCenterCrop(10, 20, 0, 5))
    }
}
