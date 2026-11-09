package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Walks every row the two registries declare and checks the XMB / Deck layouts place it: no row falls through a mapping rule
 * to "no tab" except the documented hidden ones. Building the registries runs plain constructors and string lookups, but it
 * touches app classes whose static state may need Android; where the JVM cannot build one, its test is skipped (not failed).
 */
class RegistryLayoutWalkTest {

    private fun containerDefs(): List<SettingDef> {
        val built = runCatching { SettingsRegistry.defs }
        assumeTrue("SettingsRegistry cannot be built on the JVM: ${built.exceptionOrNull()}", built.isSuccess)
        return built.getOrThrow()
    }

    private fun appDefs(): List<SettingDef> {
        val built = runCatching { AppSettingsRegistry.defs }
        assumeTrue("AppSettingsRegistry cannot be built on the JVM: ${built.exceptionOrNull()}", built.isSuccess)
        return built.getOrThrow()
    }

    @Test
    fun everyContainerRowHasATabExceptTheHiddenOnes() {
        val defs = containerDefs()
        assertTrue("the registry should declare rows", defs.size > 50)
        val declared = EditorTabs.CONTAINER_IDS + EditorTabs.GAME + EditorTabs.LINUX
        val orphans = defs.filter { d -> ContainerLayoutMap.tabFor(d.id, d.category) == null && d.id !in ContainerLayoutMap.HIDDEN }.map { it.id }
        assertEquals("rows with no tab and not documented as hidden: $orphans", emptyList<String>(), orphans)
        val strays = defs.filter { d -> ContainerLayoutMap.tabFor(d.id, d.category).let { it != null && it !in declared } }.map { it.id }
        assertEquals("rows on a tab that no container / game page lists: $strays", emptyList<String>(), strays)
    }

    @Test
    fun everyAppRowHasATabExceptTheHiddenOnes() {
        val defs = appDefs()
        val documentedHidden = setOf(
            "app.library.midiDefault", "app.performance.box64Preset", "app.performance.fexPreset",
            "app.controllers.steamController", "app.controllers.trackpadMouse", "app.controllers.onScreenPriority", "app.controllers.autoHide",
        )
        val orphans = defs.filter { AppSettingsLayout.tabFor(it.id) == null && it.id !in documentedHidden }.map { it.id }
        assertEquals("rows with no tab and not documented as hidden: $orphans", emptyList<String>(), orphans)
        val strays = defs.filter { d -> AppSettingsLayout.tabFor(d.id).let { it != null && it !in EditorTabs.APP_IDS } }.map { it.id }
        assertEquals("rows on a tab the Settings page does not list: $strays", emptyList<String>(), strays)
    }

    @Test
    fun theXmbOnlyAppRowsSitOutsideTheCategoriesLabWalks() {
        // Lab walks AppSettingsRegistry.categories: the XMB-only rows (AppSettingsRows) must not be in any of them.
        val labCategories = runCatching { AppSettingsRegistry.categories.map { it.id }.toSet() }
        val xmbOnly = runCatching { AppSettingsRows.defs() }
        assumeTrue("the app registry cannot be built on the JVM", labCategories.isSuccess && xmbOnly.isSuccess)
        val inLab = xmbOnly.getOrThrow().filter { it.category in labCategories.getOrThrow() }.map { it.id }
        assertEquals("XMB-only rows Lab would list: $inLab", emptyList<String>(), inLab)
    }

    @Test
    fun everyRowDeclaresOneOfTheTwoLevels() {
        // EXPERT is only an alias: a row declared with it reads back as ADVANCED.
        for (d in containerDefs() + appDefs()) {
            assertTrue("${d.id} is ${d.level}", d.level == SettingLevel.SIMPLE || d.level == SettingLevel.ADVANCED)
        }
    }
}
