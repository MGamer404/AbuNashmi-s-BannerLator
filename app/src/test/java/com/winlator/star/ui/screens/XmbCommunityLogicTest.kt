package com.winlator.star.ui.screens

import com.winlator.star.communityconfigs.CanonicalDevice
import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.communityconfigs.WorkerConfigEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB Home's Community configs page: the grid and the D-pad's moves in it, the filters, library and device matching, config order. */
class XmbCommunityLogicTest {

    private fun game(identity: String, name: String, configs: Int, devices: List<CanonicalDevice> = emptyList()) =
        CanonicalGame(identity, name, listOf(name), devices, configs)

    private fun entry(device: String, votes: Int, soc: String = "") =
        WorkerConfigEntry("$device.json", "sha-$device", device, soc, "2026-01-01", votes, 0, "")

    private val games = listOf(
        game("1245620", "Elden Ring", 30, listOf(CanonicalDevice("Pixel 8", "Adreno 740", "Snapdragon 8 Gen 2"))),
        game("name:pes-2013", "PES 2013", 5, listOf(CanonicalDevice("Poco F5", "Adreno 725", "Snapdragon 7+ Gen 2"))),
        game("1091500", "Cyberpunk 2077", 12),
        game("292030", "The Witcher 3: Wild Hunt", 20),
    )

    // --- the grid ---

    @Test fun columns_asManyCardsAsFit_oneToFour() {
        assertEquals(1, xmbCcColumns(328f))
        assertEquals(3, xmbCcColumns(800f))
        assertEquals(4, xmbCcColumns(2000f))
        assertEquals(1, xmbCcColumns(0f))
    }

    @Test fun move_leftRightStopAtTheRowEnds() {
        // 7 cards, 3 to a row: [0 1 2] [3 4 5] [6]
        assertEquals(1, xmbCcMove(0, 1, 0, 3, 7))
        assertEquals(2, xmbCcMove(2, 1, 0, 3, 7))
        assertEquals(3, xmbCcMove(3, -1, 0, 3, 7))
        assertEquals(6, xmbCcMove(6, 1, 0, 3, 7))
        assertEquals(0, xmbCcMove(0, -1, 0, 3, 7))
    }

    @Test fun move_downLandsOnTheCardBelow_orTheLastOfAShorterRow() {
        assertEquals(4, xmbCcMove(1, 0, 1, 3, 7))
        assertEquals(6, xmbCcMove(4, 0, 1, 3, 7))
        assertEquals(6, xmbCcMove(5, 0, 1, 3, 7))
        assertEquals(6, xmbCcMove(6, 0, 1, 3, 7))
        assertEquals(5, xmbCcMove(5, 0, 1, 3, 6))
    }

    @Test fun move_upFromTheFirstRowGoesToTheStrip() {
        assertEquals(XMB_CC_TO_STRIP, xmbCcMove(2, 0, -1, 3, 7))
        assertEquals(0, xmbCcMove(3, 0, -1, 3, 7))
        assertEquals(XMB_CC_TO_STRIP, xmbCcMove(0, 0, -1, 1, 1))
    }

    @Test fun move_noCardsOrAStrayIndexIsSafe() {
        assertEquals(XMB_CC_TO_STRIP, xmbCcMove(0, 0, -1, 3, 0))
        assertEquals(0, xmbCcMove(0, 1, 0, 3, 0))
        assertEquals(6, xmbCcMove(40, 1, 0, 3, 7))
    }

    // --- the strip ---

    @Test fun strip_stopsAtTheEnds() {
        assertEquals(0, xmbCcStripStep(0, -1))
        assertEquals(XMB_CC_STRIP_COUNT - 1, xmbCcStripStep(XMB_CC_STRIP_COUNT - 1, 1))
        assertEquals(XMB_CC_STRIP_FITS, xmbCcStripStep(XMB_CC_STRIP_SEARCH, 1))
    }

    @Test fun sort_cyclesBetweenPopularAndName() {
        assertEquals(CatalogSort.NAME, xmbCcNextSort(CatalogSort.CONFIGS))
        assertEquals(CatalogSort.CONFIGS, xmbCcNextSort(CatalogSort.NAME))
        assertEquals(CatalogSort.CONFIGS, xmbCcNextSort(CatalogSort.DEVICES))
    }

    // --- filtering ---

    @Test fun visible_mostConfigsFirst_orAToZ() {
        val none = emptySet<String>()
        assertEquals(
            listOf("Elden Ring", "The Witcher 3: Wild Hunt", "Cyberpunk 2077", "PES 2013"),
            xmbCcVisible(games, "", CatalogSort.CONFIGS, none, false, none, false).map { it.name },
        )
        assertEquals(
            listOf("Cyberpunk 2077", "Elden Ring", "PES 2013", "The Witcher 3: Wild Hunt"),
            xmbCcVisible(games, "", CatalogSort.NAME, none, false, none, false).map { it.name },
        )
    }

    @Test fun visible_onlyFitsAndOnlyLibraryNarrowTheList() {
        val fits = setOf("1245620", "name:pes-2013")
        val mine = setOf("1245620", "292030")
        assertEquals(listOf("Elden Ring", "PES 2013"), xmbCcVisible(games, "", CatalogSort.CONFIGS, fits, true, mine, false).map { it.name })
        assertEquals(listOf("Elden Ring", "The Witcher 3: Wild Hunt"), xmbCcVisible(games, "", CatalogSort.CONFIGS, fits, false, mine, true).map { it.name })
        assertEquals(listOf("Elden Ring"), xmbCcVisible(games, "", CatalogSort.CONFIGS, fits, true, mine, true).map { it.name })
    }

    @Test fun visible_aSearchRanksByRelevance_aShortOneIsNoSearch() {
        val none = emptySet<String>()
        assertEquals(listOf("Elden Ring"), xmbCcVisible(games, "elden", CatalogSort.NAME, none, false, none, false).map { it.name })
        assertEquals(4, xmbCcVisible(games, "e", CatalogSort.NAME, none, false, none, false).size)
        assertTrue(xmbCcVisible(games, "zzzz", CatalogSort.NAME, none, false, none, false).isEmpty())
    }

    // --- matching ---

    @Test fun fitting_gamesWithAConfigForThisHardware() {
        assertEquals(setOf("1245620"), xmbCcFittingGames(games, "Snapdragon 8 Gen 2", null))
        assertEquals(setOf("name:pes-2013"), xmbCcFittingGames(games, null, "Adreno (TM) 725"))
        assertTrue(xmbCcFittingGames(games, null, null).isEmpty())
    }

    @Test fun library_matchesByAppIdOrByTheNormalisedName() {
        assertEquals(setOf("1245620"), xmbCcLibraryGames(games, listOf("Whatever"), listOf("1245620")))
        // "GOTY" and "The" are edition noise, punctuation and case do not matter.
        assertEquals(
            setOf("292030"),
            xmbCcLibraryGames(games, listOf("witcher 3 wild hunt GOTY"), emptyList()),
        )
        assertEquals(setOf("name:pes-2013"), xmbCcLibraryGames(games, listOf("PES_2013"), emptyList()))
    }

    @Test fun library_aLooseNameDoesNotMatch_andAnEmptyLibraryMatchesNothing() {
        assertTrue(xmbCcLibraryGames(games, listOf("Elden"), emptyList()).isEmpty())
        assertTrue(xmbCcLibraryGames(games, emptyList(), listOf("", " ")).isEmpty())
    }

    // --- a game's configs ---

    @Test fun rank_configsThatFitComeFirst_eachGroupKeepsItsOrder() {
        val list = listOf(
            "f" to entry("Pixel 6", 50),
            "f" to entry("Pixel 8", 40),
            "f" to entry("Pixel 9", 30),
            "f" to entry("Pixel 7", 10),
        )
        val ranked = xmbCcRankConfigs(list) { it.device == "Pixel 8" || it.device == "Pixel 7" }
        assertEquals(listOf("Pixel 8", "Pixel 7", "Pixel 6", "Pixel 9"), ranked.map { it.second.device })
    }

    @Test fun rank_noneFitting_keepsTheWorkersOrder() {
        val list = listOf("f" to entry("A", 3), "f" to entry("B", 2))
        assertEquals(list, xmbCcRankConfigs(list) { false })
    }
}
