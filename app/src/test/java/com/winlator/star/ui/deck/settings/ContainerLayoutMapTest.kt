package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id -> tab rules of the container / game pages (ContainerLayout.kt): which of the six tabs (plus Game and Linux runtime) a
 * registry row lands on, what is hidden, and the order of the old categories inside a tab. Pure strings: no Compose, no icons.
 */
class ContainerLayoutMapTest {

    /** The XMB layout (seven tabs, Win components). */
    private fun tab(id: String, category: String = "") = ContainerLayoutMap.tabFor(id, category, true)

    /** The plain Deck layout (the six tabs it always had). */
    private fun deckTab(id: String, category: String = "") = ContainerLayoutMap.tabFor(id, category, false)

    // ---- General ----

    @Test
    fun generalHoldsTheNameTheWineVersionDisplayAndAudio() {
        assertEquals(EditorTabs.GENERAL, tab("windows.containerName", "windows"))
        assertEquals(EditorTabs.GENERAL, tab("windows.wineVersion", "windows"))
        for (id in listOf("display.resolution", "display.fullscreenMode", "display.alignment", "display.backend", "display.refreshInGame", "display.refreshAuto", "display.refreshManual", "display.tv.launch", "display.tv.mode", "display.tv.matchRes")) {
            assertEquals(id, EditorTabs.GENERAL, tab(id, "display"))
        }
        for (id in listOf("audio.driver", "audio.midiSoundfont", "audio.latencyPreset", "audio.microphone", "audio.env")) {
            assertEquals(id, EditorTabs.GENERAL, tab(id, "audio"))
        }
    }

    // ---- Graphics ----

    @Test
    fun graphicsHoldsTheGraphicsRowsSupersamplingHdrAndTheVisualEffects() {
        for (id in listOf("graphics.dxWrapper", "graphics.dx.dxvkVersion", "graphics.wined3d.csmt", "graphics.unrealHdr", "graphics.driver", "graphics.compositorDriver", "graphics.wl.gameDriver", "graphics.gdc.presentMode", "graphics.wrapperDetected.x", "graphics.presentMode")) {
            assertEquals(id, EditorTabs.GRAPHICS, tab(id, "graphics"))
        }
        // The two display rows that belong with the graphics rows.
        assertEquals(EditorTabs.GRAPHICS, tab("display.renderScale", "display"))
        assertEquals(EditorTabs.GRAPHICS, tab("display.hdrOutput", "display"))
        for (id in listOf("visuals.reshade.mode", "visuals.reshade.loadout", "visuals.vkbasalt", "visuals.vkbasaltLevel", "visuals.vkbasaltDenoise")) {
            assertEquals(id, EditorTabs.GRAPHICS, tab(id, "visuals"))
        }
    }

    // ---- Performance ----

    @Test
    fun performanceHoldsTheTranslatorsAndTheOverlaySwitch() {
        for (id in listOf("performance.fpsLimiter", "performance.frameGen", "performance.syncMode", "performance.box64Version", "performance.fexPreset", "performance.cpuAffinity", "performance.power.sustained", "performance.root.x", "performance.service.x")) {
            assertEquals(id, EditorTabs.PERFORMANCE, tab(id, "performance"))
        }
        assertEquals(EditorTabs.PERFORMANCE, tab("hud.show", "hud"))
        assertEquals(EditorTabs.PERFORMANCE, tab("hud.style", "hud"))
        assertEquals(EditorTabs.PERFORMANCE, tab("hud.metric.cpu", "hud"))
    }

    @Test
    fun theSecondOverlaySwitchIsHidden() {
        assertNull(tab("hud.enabled", "hud"))
        assertTrue("hud.enabled" in ContainerLayoutMap.HIDDEN)
    }

    // ---- Controls ----

    @Test
    fun controlsHoldsEveryInputRow() {
        for (id in listOf("input.controlsProfile", "input.touchMode", "input.relativeMouse", "input.mouseWarp", "input.gyro.enabled", "input.xr.primary", "input.slotOverrides")) {
            assertEquals(id, EditorTabs.CONTROLS, tab(id, "input"))
        }
    }

    // ---- Windows ----

    @Test
    fun windowsHoldsTheSessionAndDesktopRowsButNotTheComponents() {
        for (id in listOf("windows.locale", "windows.closeOnExit", "windows.runAsAdmin", "windows.theme", "windows.desktopBackground", "windows.desktopColor", "windows.wallpaperScope")) {
            assertEquals(id, EditorTabs.WINDOWS, tab(id, "windows"))
        }
    }

    // ---- Win components ----

    @Test
    fun winComponentsHoldsTheDllRowsAndTheCodecRows() {
        for (id in listOf("components.direct3d", "components.directsound", "components.directmusic", "components.directshow", "components.directplay", "components.xaudio", "components.vcrun2010")) {
            assertEquals(id, EditorTabs.WINCOMP, tab(id, SettingsRegistry.CAT_COMPONENTS))
        }
        // Declared in the components category with a "windows." id: Win components, never Windows or General.
        assertEquals(EditorTabs.WINCOMP, tab("windows.codecBackend", SettingsRegistry.CAT_COMPONENTS))
        assertEquals(EditorTabs.WINCOMP, tab("windows.softwareDecoding", SettingsRegistry.CAT_COMPONENTS))
    }

    @Test
    fun everyComponentRowIsOneTheWinComponentsPageDraws() {
        // The layout hides these rows from the list (tabOf = null) because the page draws them: the two rules must agree.
        for (id in listOf("components.direct3d", "components.vcrun2010", "windows.codecBackend", "windows.softwareDecoding")) {
            assertTrue(id, WinComponentsLogic.isComponentRow(id))
            assertEquals(id, EditorTabs.WINCOMP, tab(id, SettingsRegistry.CAT_COMPONENTS))
        }
        for (id in listOf("windows.locale", "windows.wineVersion", "windows.desktopBackground", "graphics.dxWrapper")) {
            assertFalse(id, WinComponentsLogic.isComponentRow(id))
        }
    }

    // ---- Advanced ----

    @Test
    fun advancedHoldsTheEnvironmentVariablesRow() {
        assertEquals(EditorTabs.ADVANCED, tab("expert.env", "expert"))
    }

    // ---- Game and Linux ----

    @Test
    fun theGameTabHoldsTheNameArgumentsAndTheLaunchRows() {
        assertEquals(EditorTabs.GAME, tab("game.name", "game"))
        assertEquals(EditorTabs.GAME, tab("game.arguments", "game"))
        for (id in listOf("launch.method", "launch.rememberChoice", "launch.controllerPassthrough", "launch.steamInput", "launch.vacSecure", "launch.epicEos", "launch.xboxNexusRuntime")) {
            assertEquals(id, EditorTabs.GAME, tab(id, "launch"))
        }
    }

    @Test
    fun theLinuxTabHoldsEveryLinuxRow() {
        for (id in listOf("linux.drawDriver", "linux.clientCores", "linux.gameCores", "linux.gamesFolders")) {
            assertEquals(id, EditorTabs.LINUX, tab(id, "linux"))
        }
    }

    // ---- Unknown ids ----

    @Test
    fun anUnknownIdFollowsItsOldCategory() {
        assertEquals(EditorTabs.GRAPHICS, tab("something.new", "graphics"))
        assertEquals(EditorTabs.PERFORMANCE, tab("something.new", "hud"))
        assertEquals(EditorTabs.WINCOMP, tab("something.new", SettingsRegistry.CAT_COMPONENTS))
    }

    @Test
    fun anUnknownIdInAnUnknownCategoryIsHidden() {
        assertNull(tab("something.new", "nonsense"))
        assertNull(tab("something.new", ""))
        // The app settings' categories are not container categories.
        assertNull(tab("x.y", "app.general"))
    }

    @Test
    fun everyTabAMappedRowCanLandOnIsOneOfTheDeclaredContainerOrGameTabs() {
        val declared = EditorTabs.CONTAINER_IDS + EditorTabs.GAME + EditorTabs.LINUX
        val ids = listOf(
            "windows.containerName" to "windows", "display.resolution" to "display", "audio.driver" to "audio", "graphics.dxWrapper" to "graphics",
            "visuals.vkbasalt" to "visuals", "performance.fpsLimiter" to "performance", "hud.show" to "hud", "input.touchMode" to "input",
            "windows.locale" to "windows", "components.direct3d" to SettingsRegistry.CAT_COMPONENTS, "expert.env" to "expert",
            "game.name" to "game", "launch.method" to "launch", "linux.clientCores" to "linux",
        )
        for ((id, cat) in ids) assertTrue(id, tab(id, cat) in declared)
    }

    // ---- Order inside a tab ----

    @Test
    fun generalOrdersNameAndWineVersionThenDisplayThenAudio() {
        val name = ContainerLayoutMap.rankOf("windows.containerName", true)
        val wine = ContainerLayoutMap.rankOf("windows.wineVersion", true)
        val display = ContainerLayoutMap.rankOf("display.resolution", true)
        val audio = ContainerLayoutMap.rankOf("audio.driver", true)
        assertEquals(name, wine)
        assertTrue(name < display)
        assertTrue(display < audio)
    }

    @Test
    fun graphicsOrdersTheGraphicsRowsThenSupersamplingAndHdrThenTheEffects() {
        val graphics = ContainerLayoutMap.rankOf("graphics.dxWrapper", true)
        val renderScale = ContainerLayoutMap.rankOf("display.renderScale", true)
        val hdr = ContainerLayoutMap.rankOf("display.hdrOutput", true)
        val effects = ContainerLayoutMap.rankOf("visuals.vkbasalt", true)
        assertEquals(renderScale, hdr)
        assertTrue(graphics < renderScale)
        assertTrue(renderScale < effects)
    }

    @Test
    fun performanceOrdersTheTranslatorRowsBeforeTheOverlay() {
        assertTrue(ContainerLayoutMap.rankOf("performance.fpsLimiter", true) < ContainerLayoutMap.rankOf("hud.show", true))
        assertEquals(ContainerLayoutMap.rankOf("hud.show", true), ContainerLayoutMap.rankOf("hud.metric.cpu", true))
    }

    @Test
    fun winComponentsKeepsTheRegistryOrderOfTheDllAndCodecRows() {
        val dll = ContainerLayoutMap.rankOf("components.direct3d", true)
        assertEquals(dll, ContainerLayoutMap.rankOf("components.vcrun2010", true))
        assertEquals(dll, ContainerLayoutMap.rankOf("windows.codecBackend", true))
        assertEquals(dll, ContainerLayoutMap.rankOf("windows.softwareDecoding", true))
    }

    // ---- Page extras ----

    @Test
    fun extrasOfEveryOldCategoryFeedTheirTab() {
        assertEquals(setOf(EditorTabs.GAME), ContainerLayoutMap.extraTargets("game", true))
        assertEquals(setOf(EditorTabs.GRAPHICS), ContainerLayoutMap.extraTargets("graphics", true))
        assertEquals(setOf(EditorTabs.GRAPHICS), ContainerLayoutMap.extraTargets("visuals", true))
        assertEquals(setOf(EditorTabs.PERFORMANCE), ContainerLayoutMap.extraTargets("performance", true))
        assertEquals(setOf(EditorTabs.PERFORMANCE), ContainerLayoutMap.extraTargets("hud", true))
        assertEquals(setOf(EditorTabs.GENERAL), ContainerLayoutMap.extraTargets("audio", true))
        assertEquals(setOf(EditorTabs.CONTROLS), ContainerLayoutMap.extraTargets("input", true))
        // The install lists (the old components category) and the Win components page feed the new tab, not Windows.
        assertEquals(setOf(EditorTabs.WINCOMP), ContainerLayoutMap.extraTargets(SettingsRegistry.CAT_COMPONENTS, true))
        assertEquals(setOf(EditorTabs.WINCOMP), ContainerLayoutMap.extraTargets(WinComponentsLogic.EXTRAS_KEY, true))
        assertEquals(setOf(EditorTabs.LINUX), ContainerLayoutMap.extraTargets("linux", true))
        assertEquals(setOf(EditorTabs.ADVANCED), ContainerLayoutMap.extraTargets("expert", true))
        assertTrue(ContainerLayoutMap.extraTargets("nonsense", true).isEmpty())
    }

    @Test
    fun theWindowsExtrasSplitBetweenWindowsAndAdvanced() {
        assertEquals(setOf(EditorTabs.WINDOWS, EditorTabs.ADVANCED), ContainerLayoutMap.extraTargets("windows", true))
        assertEquals(EditorTabs.ADVANCED, ContainerLayoutMap.extraTab("windows", "windows-reset:all"))
        assertEquals(EditorTabs.ADVANCED, ContainerLayoutMap.extraTab("windows", "windows-reset-label"))
        assertEquals(EditorTabs.WINDOWS, ContainerLayoutMap.extraTab("windows", "drives"))
        assertEquals(EditorTabs.WINDOWS, ContainerLayoutMap.extraTab("windows", "windows-wine:wine"))
        // Every other category has one target: no per-item rule.
        assertNull(ContainerLayoutMap.extraTab("graphics", "graphics-tools-label"))
    }

    @Test
    fun theWinComponentsPageSortsAboveTheInstallLists() {
        assertTrue(ContainerLayoutMap.extraRank(WinComponentsLogic.EXTRAS_KEY, true) < ContainerLayoutMap.extraRank(SettingsRegistry.CAT_COMPONENTS, true))
    }

    @Test
    fun theWinComponentsTabIsFedByBothSourcesAndWindowsByNeitherOfThem() {
        val l = ContainerLayout.forScope(EditorScope.CONTAINER, xmb = true)
        assertEquals(EditorTabs.WINCOMP, l.extraTabOf(SettingsRegistry.CAT_COMPONENTS, "components-search"))
        assertEquals(EditorTabs.WINCOMP, l.extraTabOf(WinComponentsLogic.EXTRAS_KEY, "wincomp-row:components.direct3d"))
        assertEquals(EditorTabs.WINDOWS, l.extraTabOf("windows", "windows-drives-add"))
        assertEquals(7, l.tabs.size)
    }

    // ---- The plain Deck (XMB only: no Win components tab) ----

    @Test
    fun thePlainDeckKeepsTheComponentsAndCodecRowsOnWindows() {
        for (id in listOf("components.direct3d", "components.directsound", "components.vcrun2010")) {
            assertEquals(id, EditorTabs.WINDOWS, deckTab(id, SettingsRegistry.CAT_COMPONENTS))
        }
        assertEquals(EditorTabs.WINDOWS, deckTab("windows.codecBackend", SettingsRegistry.CAT_COMPONENTS))
        assertEquals(EditorTabs.WINDOWS, deckTab("windows.softwareDecoding", SettingsRegistry.CAT_COMPONENTS))
        assertEquals(EditorTabs.WINDOWS, deckTab("something.new", SettingsRegistry.CAT_COMPONENTS))
        // They come after the session / desktop rows, as before.
        assertTrue(ContainerLayoutMap.rankOf("windows.locale", false) < ContainerLayoutMap.rankOf("components.direct3d", false))
        assertEquals(ContainerLayoutMap.rankOf("components.direct3d", false), ContainerLayoutMap.rankOf("windows.codecBackend", false))
    }

    @Test
    fun everythingElseIsTheSameInBothLayouts() {
        val ids = listOf(
            "windows.containerName" to "windows", "windows.wineVersion" to "windows", "display.resolution" to "display", "display.renderScale" to "display",
            "audio.driver" to "audio", "graphics.dxWrapper" to "graphics", "visuals.vkbasalt" to "visuals", "performance.fpsLimiter" to "performance",
            "hud.show" to "hud", "hud.enabled" to "hud", "input.touchMode" to "input", "windows.locale" to "windows", "expert.env" to "expert",
            "game.name" to "game", "launch.method" to "launch", "linux.clientCores" to "linux", "something.new" to "nonsense",
        )
        for ((id, cat) in ids) {
            assertEquals(id, tab(id, cat), deckTab(id, cat))
            assertEquals(id, ContainerLayoutMap.rankOf(id, true), ContainerLayoutMap.rankOf(id, false))
        }
    }

    @Test
    fun thePlainDeckFeedsTheInstallListsToWindowsAfterItsOwnExtrasAndHasNoWinComponentsPage() {
        assertEquals(setOf(EditorTabs.WINDOWS), ContainerLayoutMap.extraTargets(SettingsRegistry.CAT_COMPONENTS, false))
        assertTrue(ContainerLayoutMap.extraTargets(WinComponentsLogic.EXTRAS_KEY, false).isEmpty())
        assertTrue(ContainerLayoutMap.extraRank("windows", false) < ContainerLayoutMap.extraRank(SettingsRegistry.CAT_COMPONENTS, false))
        // The other categories are unchanged.
        for (old in listOf("game", "graphics", "visuals", "performance", "hud", "audio", "input", "windows", "linux", "expert")) {
            assertEquals(old, ContainerLayoutMap.extraTargets(old, true), ContainerLayoutMap.extraTargets(old, false))
            assertEquals(old, ContainerLayoutMap.extraRank(old, true), ContainerLayoutMap.extraRank(old, false))
        }
    }

    @Test
    fun thePlainDeckLayoutHasSixTabsAndNoWinComponents() {
        val l = ContainerLayout.forScope(EditorScope.CONTAINER, xmb = false)
        assertEquals(6, l.tabs.size)
        assertFalse(l.tabs.any { it.id == EditorTabs.WINCOMP })
        assertEquals(EditorTabs.WINDOWS, l.extraTabOf(SettingsRegistry.CAT_COMPONENTS, "components-search"))
        assertEquals(EditorTabs.WINDOWS, l.extraTabOf("windows", "windows-drives-add"))
    }

    @Test
    fun theEnvironmentVariablesComeBeforeTheResetAction() {
        assertTrue(ContainerLayoutMap.extraRank("expert", true) < ContainerLayoutMap.extraRank("windows", true))
    }
}
