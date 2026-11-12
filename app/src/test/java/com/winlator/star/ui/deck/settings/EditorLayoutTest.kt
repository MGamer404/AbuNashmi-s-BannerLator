package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the XMB / Deck editor's tab layer (EditorLayout.kt) and the level alias (SettingsModel.kt): no Compose
 * runtime and no Android classes involved, so no registry row is built (those need strings and storage).
 */
class EditorLayoutTest {

    private fun row(id: String, category: String, group: String = "Group", level: SettingLevel = SettingLevel.SIMPLE) = SettingDef(
        id = id, category = category, group = group, label = id, level = level,
        control = { _, _ -> Control.Toggle },
    )

    private fun layout(
        vararg tabIds: String,
        tabOf: (SettingDef) -> String?,
        rank: (SettingDef) -> Int = { 0 },
        groupOf: (SettingDef) -> String = { it.group },
        levelOf: (SettingDef) -> SettingLevel = { it.level },
    ) = EditorLayout(
        tabs = tabIds.map { EditorTabs.tab(it) },
        tabOf = tabOf,
        rank = rank,
        groupOf = groupOf,
        levelOf = levelOf,
    )

    // ---- Levels ----

    @Test
    fun expertIsAnAliasOfAdvanced() {
        assertEquals(SettingLevel.ADVANCED, SettingLevel.EXPERT.effective)
        assertEquals(SettingLevel.ADVANCED, SettingLevel.ADVANCED.effective)
        assertEquals(SettingLevel.SIMPLE, SettingLevel.SIMPLE.effective)
        assertEquals("Advanced", SettingLevel.EXPERT.label)
    }

    @Test
    fun aStoredLevelReadsBackAsOneOfTwo() {
        assertEquals(SettingLevel.SIMPLE, SettingLevel.parse(null))
        assertEquals(SettingLevel.SIMPLE, SettingLevel.parse(""))
        assertEquals(SettingLevel.SIMPLE, SettingLevel.parse("SIMPLE"))
        assertEquals(SettingLevel.SIMPLE, SettingLevel.parse("nonsense"))
        assertEquals(SettingLevel.ADVANCED, SettingLevel.parse("ADVANCED"))
        assertEquals(SettingLevel.ADVANCED, SettingLevel.parse("EXPERT"))
    }

    @Test
    fun aRowDeclaredExpertIsAdvanced() {
        assertEquals(SettingLevel.ADVANCED, row("a", "x", level = SettingLevel.EXPERT).level)
        assertEquals(SettingLevel.SIMPLE, row("a", "x", level = SettingLevel.SIMPLE).level)
        // So "shown at the chosen level" never needs a third step: nothing sits above ADVANCED.
        assertTrue(row("a", "x", level = SettingLevel.EXPERT).level <= SettingLevel.ADVANCED)
    }

    // ---- SettingDef.copyWith ----

    @Test
    fun copyWithMovesTheRowAndKeepsEverythingElse() {
        val visible: (SettingsEnv, (String) -> String) -> Boolean = { _, _ -> false }
        val original = SettingDef(
            id = "graphics.x", category = "graphics", group = "Old", label = "Label", hint = "Hint", help = "Help", tech = "Tech",
            level = SettingLevel.EXPERT, control = { _, _ -> Control.Toggle }, onDefaults = false, showInGame = false,
            visible = visible, worlds = LINUX_ONLY, getMore = "dxvk",
        )
        val moved = original.copyWith(category = "tab.graphics", group = "New", level = SettingLevel.SIMPLE)
        assertEquals("tab.graphics", moved.category)
        assertEquals("New", moved.group)
        assertEquals(SettingLevel.SIMPLE, moved.level)
        assertEquals("graphics.x", moved.id)
        assertEquals("Label", moved.label)
        assertEquals("Hint", moved.hint)
        assertEquals("Help", moved.help)
        assertEquals("Tech", moved.tech)
        assertFalse(moved.onDefaults)
        assertFalse(moved.showInGame)
        assertSame(visible, moved.visible)
        assertEquals(LINUX_ONLY, moved.worlds)
        assertEquals("dxvk", moved.getMore)
        // The original is untouched; an argument left out keeps its value.
        assertEquals("graphics", original.category)
        assertEquals("Old", original.copyWith(category = "t").group)
        assertEquals(SettingLevel.ADVANCED, original.copyWith(group = "g").level)
    }

    // ---- EditorLayout.apply ----

    @Test
    fun applyFilesRowsIntoTabsAndDropsHiddenOnes() {
        val l = layout(EditorTabs.GENERAL, EditorTabs.GRAPHICS, tabOf = { d ->
            when {
                d.id == "hud.enabled" -> null
                d.category == "display" -> EditorTabs.GENERAL
                else -> EditorTabs.GRAPHICS
            }
        })
        val out = l.apply(listOf(row("display.a", "display"), row("hud.enabled", "hud"), row("graphics.a", "graphics")))
        assertEquals(listOf("display.a", "graphics.a"), out.map { it.id })
        assertEquals(listOf(EditorTabs.GENERAL, EditorTabs.GRAPHICS), out.map { it.category })
    }

    @Test
    fun applyOrdersByRankKeepingRegistryOrderWithinARank() {
        val l = layout(EditorTabs.GRAPHICS, tabOf = { EditorTabs.GRAPHICS }, rank = { d -> if (d.category == "visuals") 1 else 0 })
        val out = l.apply(listOf(row("v1", "visuals"), row("g1", "graphics"), row("v2", "visuals"), row("g2", "graphics")))
        assertEquals(listOf("g1", "g2", "v1", "v2"), out.map { it.id })
    }

    @Test
    fun applyRenamesGroupsAndForcesLevels() {
        val l = layout(
            EditorTabs.PERFORMANCE,
            tabOf = { EditorTabs.PERFORMANCE },
            groupOf = { d -> if (d.group == "Overlay") "Performance overlay" else d.group },
            levelOf = { d -> if (d.id == "windows.wineVersion") SettingLevel.SIMPLE else d.level },
        )
        val out = l.apply(listOf(row("hud.show", "hud", group = "Overlay"), row("windows.wineVersion", "windows", level = SettingLevel.EXPERT)))
        assertEquals("Performance overlay", out[0].group)
        assertEquals(SettingLevel.SIMPLE, out[1].level)
    }

    @Test
    fun applyNormalisesAnExpertLevelAFunctionReturns() {
        val l = layout(EditorTabs.GENERAL, tabOf = { EditorTabs.GENERAL }, levelOf = { SettingLevel.EXPERT })
        assertEquals(SettingLevel.ADVANCED, l.apply(listOf(row("a", "x")))[0].level)
    }

    @Test
    fun applyDropsARowMappedToATabTheLayoutDoesNotHave() {
        val l = layout(EditorTabs.GENERAL, tabOf = { EditorTabs.LINUX })
        assertTrue(l.apply(listOf(row("a", "x"))).isEmpty())
    }

    @Test
    fun applyKeepsTheSameRowWhenNothingChanges() {
        val r = row("a", EditorTabs.GENERAL)
        val l = layout(EditorTabs.GENERAL, tabOf = { EditorTabs.GENERAL })
        assertSame(r, l.apply(listOf(r))[0])
    }

    // ---- Hand-offs and extras ----

    @Test
    fun handoffsFollowTheirTabAndDropWhenNoneIsGiven() {
        val details = Handoff("Details", "hint", SettingsRegistry.TARGET_DETAILS)
        val change = Handoff("Change", "hint", SettingsRegistry.TARGET_CHANGE_EXE)
        val launchInfo = Handoff("Launch info", "hint", SettingsRegistry.TARGET_NONE)
        val l = EditorLayout(
            tabs = listOf(EditorTabs.tab(EditorTabs.GAME), EditorTabs.tab(EditorTabs.GENERAL)),
            tabOf = { null },
            handoffTab = { old, h -> if (old == "game") EditorTabs.GAME else if (h === change) EditorTabs.GENERAL else null },
        )
        val out = l.rekeyHandoffs(mapOf("game" to listOf(details, change), "launch" to listOf(launchInfo), "windows" to listOf(change)))
        assertEquals(listOf(details, change), out[EditorTabs.GAME])
        assertEquals(listOf(change), out[EditorTabs.GENERAL])
        assertEquals(setOf(EditorTabs.GAME, EditorTabs.GENERAL), out.keys)
    }

    @Test
    fun anExtraGoesToTheSingleTargetTabOfItsCategory() {
        val l = EditorLayout(
            tabs = listOf(EditorTabs.tab(EditorTabs.WINDOWS), EditorTabs.tab(EditorTabs.ADVANCED)),
            tabOf = { null },
            extraTargets = { old -> if (old == "windows") setOf(EditorTabs.WINDOWS, EditorTabs.ADVANCED) else setOf(EditorTabs.WINDOWS) },
            extraTab = { old, key -> if (old == "windows" && key.startsWith("windows-reset")) EditorTabs.ADVANCED else if (old == "windows") EditorTabs.WINDOWS else null },
        )
        assertEquals(EditorTabs.WINDOWS, l.extraTabOf("components", "components-list"))
        assertEquals(EditorTabs.WINDOWS, l.extraTabOf("windows", "drives"))
        assertEquals(EditorTabs.ADVANCED, l.extraTabOf("windows", "windows-reset:all"))
    }

    @Test
    fun aCategoryWithSeveralTargetsAndNoRuleForAnExtraDropsIt() {
        val l = EditorLayout(
            tabs = listOf(EditorTabs.tab(EditorTabs.WINDOWS), EditorTabs.tab(EditorTabs.ADVANCED)),
            tabOf = { null },
            extraTargets = { setOf(EditorTabs.WINDOWS, EditorTabs.ADVANCED) },
        )
        assertNull(l.extraTabOf("windows", "drives"))
    }

    @Test
    fun extrasOfSeveralCategoriesMergeIntoOneTab() {
        val l = EditorLayout(
            tabs = listOf(EditorTabs.tab(EditorTabs.WINDOWS), EditorTabs.tab(EditorTabs.ADVANCED)),
            tabOf = { null },
            extraTargets = { old -> if (old == "expert") setOf(EditorTabs.ADVANCED) else setOf(EditorTabs.WINDOWS) },
        )
        var focused = false
        val out = l.rekeyExtras(
            linkedMapOf(
                "windows" to CategoryExtra(),
                "components" to CategoryExtra(searchKey = "search", focusSearch = { focused = true }),
                "expert" to CategoryExtra(level = SettingLevel.EXPERT),
            ),
        )
        assertEquals(setOf(EditorTabs.WINDOWS, EditorTabs.ADVANCED), out.keys)
        assertEquals("search", out[EditorTabs.WINDOWS]?.searchKey)
        out[EditorTabs.WINDOWS]?.focusSearch?.invoke()
        assertTrue(focused)
        assertNull(out[EditorTabs.ADVANCED]?.searchKey)
        assertEquals(SettingLevel.SIMPLE, out[EditorTabs.WINDOWS]?.level)
        // An EXPERT extra is ADVANCED.
        assertEquals(SettingLevel.ADVANCED, out[EditorTabs.ADVANCED]?.level)
    }

    @Test
    fun anExtraLevelOverrideLiftsATabThatOnlyHoldsAdvancedItems() {
        val l = EditorLayout(
            tabs = listOf(EditorTabs.tab(EditorTabs.ADVANCED)),
            tabOf = { null },
            extraTargets = { setOf(EditorTabs.ADVANCED) },
            extraLevel = { old, _ -> if (old == "windows") SettingLevel.ADVANCED else null },
        )
        val out = l.rekeyExtras(linkedMapOf("windows" to CategoryExtra(), "expert" to CategoryExtra(level = SettingLevel.ADVANCED)))
        assertEquals(SettingLevel.ADVANCED, out[EditorTabs.ADVANCED]?.level)
    }

    @Test
    fun extrasOfCategoriesThatFeedNoTabOfTheLayoutAreDropped() {
        val l = EditorLayout(tabs = listOf(EditorTabs.tab(EditorTabs.GENERAL)), tabOf = { null }, extraTargets = { setOf(EditorTabs.LINUX) })
        assertTrue(l.rekeyExtras(mapOf("linux" to CategoryExtra())).isEmpty())
    }

    // ---- Tab lists per scope ----

    @Test
    fun containerPagesAreTheSameSevenTabsOnEveryScope() {
        val seven = listOf(
            EditorTabs.GENERAL, EditorTabs.GRAPHICS, EditorTabs.PERFORMANCE, EditorTabs.CONTROLS,
            EditorTabs.WINDOWS, EditorTabs.WINCOMP, EditorTabs.ADVANCED,
        )
        assertEquals(seven, EditorTabs.idsFor(EditorScope.CONTAINER, xmb = true))
        assertEquals(seven, EditorTabs.idsFor(EditorScope.DEFAULTS, xmb = true))
        assertEquals(7, EditorTabs.CONTAINER_IDS.size)
    }

    @Test
    fun winComponentsIsItsOwnTabRightAfterWindows() {
        val ids = EditorTabs.CONTAINER_IDS
        assertEquals(ids.indexOf(EditorTabs.WINDOWS) + 1, ids.indexOf(EditorTabs.WINCOMP))
        assertEquals("tab.wincomp", EditorTabs.WINCOMP)
        val tab = EditorTabs.tab(EditorTabs.WINCOMP)
        assertEquals(EditorTabs.WINCOMP, tab.id)
        assertEquals("Win components", tab.label)
        // Windows no longer claims the components.
        assertFalse(EditorTabs.tab(EditorTabs.WINDOWS).desc.contains("components"))
    }

    @Test
    fun thePlainDeckKeepsTheSixContainerTabsOfBefore() {
        val six = listOf(EditorTabs.GENERAL, EditorTabs.GRAPHICS, EditorTabs.PERFORMANCE, EditorTabs.CONTROLS, EditorTabs.WINDOWS, EditorTabs.ADVANCED)
        assertEquals(six, EditorTabs.DECK_CONTAINER_IDS)
        assertEquals(six, EditorTabs.idsFor(EditorScope.CONTAINER, xmb = false))
        assertEquals(six, EditorTabs.idsFor(EditorScope.DEFAULTS, xmb = false))
        assertEquals(listOf(EditorTabs.GAME) + six, EditorTabs.idsFor(EditorScope.GAME, World.WINE, xmb = false))
        val linux = EditorTabs.idsFor(EditorScope.GAME, World.LINUX, xmb = false)
        assertEquals(8, linux.size)
        assertFalse(EditorTabs.WINCOMP in linux)
        assertEquals(EditorTabs.APP_IDS, EditorTabs.idsFor(EditorScope.APP, xmb = false))
        // The hints of Windows and Storage & data are the earlier ones there.
        assertTrue(EditorTabs.tab(EditorTabs.WINDOWS, xmb = false).desc.contains("components"))
        assertTrue(EditorTabs.tab(EditorTabs.APP_STORAGE, xmb = false).desc.contains("logs"))
        assertFalse(EditorTabs.tab(EditorTabs.APP_STORAGE, xmb = true).desc.contains("logs"))
        assertEquals(six, EditorTabs.tabsFor(EditorScope.CONTAINER, xmb = false).map { it.id })
    }

    @Test
    fun aGameHasGameFirstThenTheSameSeven() {
        val ids = EditorTabs.idsFor(EditorScope.GAME, World.WINE, xmb = true)
        assertEquals(8, ids.size)
        assertEquals(EditorTabs.GAME, ids.first())
        assertEquals(EditorTabs.CONTAINER_IDS, ids.drop(1))
        assertFalse(EditorTabs.LINUX in ids)
    }

    @Test
    fun onlyTheLinuxEntryHasTheLinuxRuntimeTab() {
        val ids = EditorTabs.idsFor(EditorScope.GAME, World.LINUX, xmb = true)
        assertEquals(9, ids.size)
        assertEquals(EditorTabs.GAME, ids.first())
        assertEquals(EditorTabs.ADVANCED, ids.last())
        assertTrue(ids.indexOf(EditorTabs.LINUX) in 1 until ids.lastIndex)
        assertEquals(listOf(EditorTabs.GAME), EditorTabs.idsFor(EditorScope.GAME, World.ANDROID, xmb = true))
    }

    @Test
    fun appSettingsHaveSixTabsOfTheirOwn() {
        val ids = EditorTabs.idsFor(EditorScope.APP, xmb = true)
        assertEquals(6, ids.size)
        assertTrue(ids.all { it.startsWith("app.t.") })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyListedTabIsDeclaredWithItsOwnId() {
        val all = EditorScope.values().flatMap { s -> World.values().flatMap { w -> EditorTabs.idsFor(s, w, xmb = true) + EditorTabs.idsFor(s, w, xmb = false) } }.toSet()
        all.forEach { id -> assertEquals(id, EditorTabs.tab(id).id) }
        assertEquals("Game actions", EditorTabs.tab(EditorTabs.GAME).actionsLabel)
        assertNull(EditorTabs.tab(EditorTabs.GENERAL).actionsLabel)
    }
}
