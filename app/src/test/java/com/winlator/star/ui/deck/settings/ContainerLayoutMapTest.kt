package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id -> tab rules of the container / game pages (ContainerLayout.kt): which of the six tabs (plus Game and Linux runtime) a
 * registry row lands on, what is hidden, and the order of the old categories inside a tab. Pure strings: no Compose, no icons.
 */
class ContainerLayoutMapTest {

    private fun tab(id: String, category: String = "") = ContainerLayoutMap.tabFor(id, category)

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
    fun windowsHoldsTheSessionDesktopAndTheComponentRows() {
        for (id in listOf("windows.locale", "windows.closeOnExit", "windows.runAsAdmin", "windows.theme", "windows.desktopBackground", "windows.desktopColor", "windows.wallpaperScope")) {
            assertEquals(id, EditorTabs.WINDOWS, tab(id, "windows"))
        }
        for (id in listOf("components.direct3d", "components.directsound", "components.vcrun2010")) {
            assertEquals(id, EditorTabs.WINDOWS, tab(id, SettingsRegistry.CAT_COMPONENTS))
        }
        // Declared in the components category with a "windows." id: still Windows, never General.
        assertEquals(EditorTabs.WINDOWS, tab("windows.codecBackend", SettingsRegistry.CAT_COMPONENTS))
        assertEquals(EditorTabs.WINDOWS, tab("windows.softwareDecoding", SettingsRegistry.CAT_COMPONENTS))
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
        assertEquals(EditorTabs.WINDOWS, tab("something.new", SettingsRegistry.CAT_COMPONENTS))
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
        val name = ContainerLayoutMap.rankOf("windows.containerName")
        val wine = ContainerLayoutMap.rankOf("windows.wineVersion")
        val display = ContainerLayoutMap.rankOf("display.resolution")
        val audio = ContainerLayoutMap.rankOf("audio.driver")
        assertEquals(name, wine)
        assertTrue(name < display)
        assertTrue(display < audio)
    }

    @Test
    fun graphicsOrdersTheGraphicsRowsThenSupersamplingAndHdrThenTheEffects() {
        val graphics = ContainerLayoutMap.rankOf("graphics.dxWrapper")
        val renderScale = ContainerLayoutMap.rankOf("display.renderScale")
        val hdr = ContainerLayoutMap.rankOf("display.hdrOutput")
        val effects = ContainerLayoutMap.rankOf("visuals.vkbasalt")
        assertEquals(renderScale, hdr)
        assertTrue(graphics < renderScale)
        assertTrue(renderScale < effects)
    }

    @Test
    fun performanceOrdersTheTranslatorRowsBeforeTheOverlay() {
        assertTrue(ContainerLayoutMap.rankOf("performance.fpsLimiter") < ContainerLayoutMap.rankOf("hud.show"))
        assertEquals(ContainerLayoutMap.rankOf("hud.show"), ContainerLayoutMap.rankOf("hud.metric.cpu"))
    }

    @Test
    fun windowsOrdersTheSessionRowsBeforeTheComponentsAndCodecs() {
        val session = ContainerLayoutMap.rankOf("windows.locale")
        assertTrue(session < ContainerLayoutMap.rankOf("components.direct3d"))
        assertEquals(ContainerLayoutMap.rankOf("components.direct3d"), ContainerLayoutMap.rankOf("windows.codecBackend"))
        assertEquals(ContainerLayoutMap.rankOf("components.direct3d"), ContainerLayoutMap.rankOf("windows.softwareDecoding"))
    }

    // ---- Page extras ----

    @Test
    fun extrasOfEveryOldCategoryFeedTheirTab() {
        assertEquals(setOf(EditorTabs.GAME), ContainerLayoutMap.extraTargets("game"))
        assertEquals(setOf(EditorTabs.GRAPHICS), ContainerLayoutMap.extraTargets("graphics"))
        assertEquals(setOf(EditorTabs.GRAPHICS), ContainerLayoutMap.extraTargets("visuals"))
        assertEquals(setOf(EditorTabs.PERFORMANCE), ContainerLayoutMap.extraTargets("performance"))
        assertEquals(setOf(EditorTabs.PERFORMANCE), ContainerLayoutMap.extraTargets("hud"))
        assertEquals(setOf(EditorTabs.GENERAL), ContainerLayoutMap.extraTargets("audio"))
        assertEquals(setOf(EditorTabs.CONTROLS), ContainerLayoutMap.extraTargets("input"))
        assertEquals(setOf(EditorTabs.WINDOWS), ContainerLayoutMap.extraTargets(SettingsRegistry.CAT_COMPONENTS))
        assertEquals(setOf(EditorTabs.LINUX), ContainerLayoutMap.extraTargets("linux"))
        assertEquals(setOf(EditorTabs.ADVANCED), ContainerLayoutMap.extraTargets("expert"))
        assertTrue(ContainerLayoutMap.extraTargets("nonsense").isEmpty())
    }

    @Test
    fun theWindowsExtrasSplitBetweenWindowsAndAdvanced() {
        assertEquals(setOf(EditorTabs.WINDOWS, EditorTabs.ADVANCED), ContainerLayoutMap.extraTargets("windows"))
        assertEquals(EditorTabs.ADVANCED, ContainerLayoutMap.extraTab("windows", "windows-reset:all"))
        assertEquals(EditorTabs.ADVANCED, ContainerLayoutMap.extraTab("windows", "windows-reset-label"))
        assertEquals(EditorTabs.WINDOWS, ContainerLayoutMap.extraTab("windows", "drives"))
        assertEquals(EditorTabs.WINDOWS, ContainerLayoutMap.extraTab("windows", "windows-wine:wine"))
        // Every other category has one target: no per-item rule.
        assertNull(ContainerLayoutMap.extraTab("graphics", "graphics-tools-label"))
    }

    @Test
    fun theEnvironmentVariablesComeBeforeTheResetAction() {
        assertTrue(ContainerLayoutMap.extraRank("expert") < ContainerLayoutMap.extraRank("windows"))
    }
}
