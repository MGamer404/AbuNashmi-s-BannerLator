package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.T

/*
 * The XMB / Deck editor's tabs for the container pages: New container, Defaults for new containers (DEFAULTS), Edit
 * container (CONTAINER) and a game (GAME). One function, [ContainerLayout.forScope], builds the [EditorLayout] for all
 * four, so they share the same seven tabs (General, Graphics, Performance, Controls, Windows, Win components, Advanced); a
 * game adds "Game" first and, for the Linux Steam client entry only, "Linux runtime" before Advanced.
 *
 * XMB ONLY: every function takes `xmb`. In the plain Deck (xmb = false) there is no Win components tab: the components.* and
 * codec rows are on Windows (after its other rows, rank 1) and their install lists follow the Windows extras, as before.
 * The table below is the XMB one.
 *
 * The registries keep their own categories (the Classic screens and the Lab style read them), so this file is a pure
 * mapping from a row's id (and old category) to its tab. THE TABLE (old category / ids -> tab, rank inside the tab):
 *
 *   old category / ids                                                   tab               rank
 *   -------------------------------------------------------------------  ----------------  ----
 *   windows.containerName, windows.wineVersion (level forced SIMPLE)     tab.general        0
 *   display.* (resolution, fullscreenMode, alignment, backend, refresh*, tv.*)  tab.general  1
 *   audio.* (driver, midiSoundfont, latencyPreset, microphone, env)      tab.general        2
 *   display.renderScale, display.hdrOutput (group "Resolution and HDR")  tab.graphics       1
 *   graphics.* (dxWrapper, dx.*, wined3d.*, unrealHdr, driver, compositorDriver, wl.*, gdc.* incl. tu.*,
 *               wrapperDetected.*, fastOpenGL, glFpsHud, glVsyncOff, renderer, sfColours, nativeRenderer,
 *               nativeBackend, presentMode, swapColours, rendererDriver)  tab.graphics       0
 *   visuals.* (reshade.mode / loadout, vkbasalt, vkbasaltLevel, vkbasaltDenoise)  tab.graphics  2
 *   performance.* (fpsLimiter, frameGen*, fgCapture, lsfgVk11, syncMode, translator*, fex*, box64*, forceSsbs,
 *               startupServices, service.*, cpuAffinity*, power.*, root.*, *PresetVars)  tab.performance  0
 *   hud.* (show, style, size, locked, scale, ..., metric.*, temp.*)      tab.performance    1
 *   hud.enabled                                                          hidden (merged into hud.show)
 *   input.* (controlsProfile ... gyro.*, xr.*, slotOverrides)            tab.controls       0
 *   windows.locale, closeOnExit, runAsAdmin, theme, desktopBackground,
 *               desktopColor, wallpaperScope                             tab.windows        0
 *   components.* (direct3d ... vcrun2010), windows.codecBackend,
 *               windows.softwareDecoding                                 tab.wincomp        0
 *       (these rows map to Win components but [ContainerLayout.forScope] hides them from the row list: WinComponentsPage.kt
 *        draws them itself, with a search bar, the first five and "Show more")
 *   expert.env (the environment variables' hidden row)                   tab.advanced       0
 *   game.name, game.arguments, launch.* (10 rows)                        tab.game           0
 *   linux.*                                                              tab.linux          0
 *   anything else: by its old category, with the same rules; an unknown category is hidden (see [HIDDEN]).
 *
 * Page extras (the non-row items), by the OLD category the section returns them under ([extraTargets]):
 *
 *   game (Icon section)                    tab.game
 *   graphics (probes, wrapper detected, tools), visuals (ReShade)   tab.graphics (graphics first)
 *   performance (translator presets, power), hud (Export HUD diagnostics)   tab.performance
 *   audio (Fine-tune the sound buffer)     tab.general
 *   input (player slot pins)               tab.controls
 *   windows: keys "windows-reset*" (Reset to app defaults) -> tab.advanced, every other key -> tab.windows
 *   components (install lists)             tab.wincomp (after the page of "wincomp", the Win components page itself)
 *   wincomp (WinComponentsSection)         tab.wincomp (first)
 *   expert (environment variables)         tab.advanced (before the Reset action)
 *   linux (Games folders, nightlies...)    tab.linux
 *
 * Hand-offs: the game page's "Game actions" (registry category "game") stay on tab.game; the launch info line and the
 * "Windows components" link (same tab now) are dropped.
 */

/** The pure id -> tab rules of [ContainerLayout], with no Compose in them, so they can be unit-tested. */
internal object ContainerLayoutMap {
    const val ID_WINE_VERSION = "windows.wineVersion"
    const val ID_CONTAINER_NAME = "windows.containerName"

    /** Ids the layout drops on purpose: they stay in the registry (Lab / Classic read them) but have no tab here. */
    val HIDDEN: Set<String> = setOf("hud.enabled")

    /** The tab of the registry row [id] (old category [category]), or null when the layout hides it. */
    fun tabFor(id: String, category: String, xmb: Boolean): String? {
        if (id in HIDDEN) return null
        return when {
            id == ID_CONTAINER_NAME || id == ID_WINE_VERSION -> EditorTabs.GENERAL
            // The codec rows are declared with the Windows components category and a "windows." id.
            id == "windows.codecBackend" || id == "windows.softwareDecoding" -> if (xmb) EditorTabs.WINCOMP else EditorTabs.WINDOWS
            id == "display.renderScale" || id == "display.hdrOutput" -> EditorTabs.GRAPHICS
            id.startsWith("game.") || id.startsWith("launch.") -> EditorTabs.GAME
            id.startsWith("display.") || id.startsWith("audio.") -> EditorTabs.GENERAL
            id.startsWith("graphics.") || id.startsWith("visuals.") -> EditorTabs.GRAPHICS
            id.startsWith("performance.") || id.startsWith("hud.") -> EditorTabs.PERFORMANCE
            id.startsWith("input.") -> EditorTabs.CONTROLS
            id.startsWith("components.") -> if (xmb) EditorTabs.WINCOMP else EditorTabs.WINDOWS
            id.startsWith("windows.") -> EditorTabs.WINDOWS
            id.startsWith("linux.") -> EditorTabs.LINUX
            id.startsWith("expert.") -> EditorTabs.ADVANCED
            else -> tabForCategory(category, xmb)
        }
    }

    /** The fallback for an id none of the prefix rules knows: its old category. */
    private fun tabForCategory(category: String, xmb: Boolean): String? = when (category) {
        "game", "launch" -> EditorTabs.GAME
        "display", "audio" -> EditorTabs.GENERAL
        "graphics", "visuals" -> EditorTabs.GRAPHICS
        "performance", "hud" -> EditorTabs.PERFORMANCE
        "input" -> EditorTabs.CONTROLS
        "windows" -> EditorTabs.WINDOWS
        SettingsRegistry.CAT_COMPONENTS -> if (xmb) EditorTabs.WINCOMP else EditorTabs.WINDOWS
        "linux" -> EditorTabs.LINUX
        "expert" -> EditorTabs.ADVANCED
        else -> null
    }

    /** The order of an old category inside its tab (the stable sort keeps the registry order within one rank). */
    fun rankOf(id: String, xmb: Boolean): Int = when {
        // General: name and Wine version first (needed to create a container), then display, then audio.
        id == ID_CONTAINER_NAME || id == ID_WINE_VERSION -> 0
        id.startsWith("audio.") -> 2
        // Graphics: the graphics rows first, then supersampling / HDR output, then the visual effects.
        id == "display.renderScale" || id == "display.hdrOutput" -> 1
        id.startsWith("display.") -> 1
        id.startsWith("visuals.") -> 2
        // Performance: the performance rows, then the overlay.
        id.startsWith("hud.") -> 1
        // Plain Deck, Windows: the session / desktop rows, then the Windows components (DLL rows and codecs).
        !xmb && (id.startsWith("components.") || id == "windows.codecBackend" || id == "windows.softwareDecoding") -> 1
        // XMB, Win components: the DLL rows, then the two codec rows (the registry order; the page draws them itself).
        else -> 0
    }

    /** The tabs the extras of the old category [old] may feed. */
    fun extraTargets(old: String, xmb: Boolean): Set<String> = when (old) {
        "game" -> setOf(EditorTabs.GAME)
        "graphics", "visuals" -> setOf(EditorTabs.GRAPHICS)
        "performance", "hud" -> setOf(EditorTabs.PERFORMANCE)
        "audio" -> setOf(EditorTabs.GENERAL)
        "input" -> setOf(EditorTabs.CONTROLS)
        "windows" -> setOf(EditorTabs.WINDOWS, EditorTabs.ADVANCED)
        // The install lists of DeckComponentsState (XMB: Win components; plain Deck: Windows), and the Win components page of
        // WinComponentsSection (XMB only: the plain Deck has no such source).
        SettingsRegistry.CAT_COMPONENTS -> setOf(if (xmb) EditorTabs.WINCOMP else EditorTabs.WINDOWS)
        WinComponentsLogic.EXTRAS_KEY -> if (xmb) setOf(EditorTabs.WINCOMP) else emptySet()
        "linux" -> setOf(EditorTabs.LINUX)
        "expert" -> setOf(EditorTabs.ADVANCED)
        else -> emptySet()
    }

    /** The tab of one extra item. Only the Windows page is split: Reset to app defaults goes to Advanced. */
    fun extraTab(old: String, key: String): String? = when {
        old == "windows" && key.startsWith("windows-reset") -> EditorTabs.ADVANCED
        old == "windows" -> EditorTabs.WINDOWS
        else -> null
    }

    /** The order of the merged extra sources inside a tab (one rank per old category). */
    fun extraRank(old: String, xmb: Boolean): Int = when (old) {
        "expert" -> 0
        "visuals", "hud", "windows" -> 1
        // XMB, Win components: its own page (search, first five, Show more) above the install lists. Plain Deck: the install
        // lists come after the Windows extras.
        SettingsRegistry.CAT_COMPONENTS -> if (xmb) 1 else 2
        else -> 0
    }
}

/** The tab layout of the container pages and a game's pages (see the table at the top of this file). */
internal object ContainerLayout {
    /**
     * The layout for an editor of [scope]: DEFAULTS, CONTAINER (also a new container) and GAME. [world] only matters for a
     * game (its tab list: Android game = Game only, the Linux Steam client entry adds Linux runtime). [xmb] is true in the XMB
     * interface (the Win components tab) and false in the plain Deck (the six tabs it always had). Remember the result.
     */
    fun forScope(scope: EditorScope, world: World = World.WINE, xmb: Boolean): EditorLayout = EditorLayout(
        tabs = EditorTabs.tabsFor(scope, world, xmb),
        // XMB: the component rows are on Win components in the map, but that tab draws them itself (WinComponentsPage.kt): not as
        // rows. Plain Deck: they are ordinary rows of the Windows tab.
        tabOf = { d -> if (xmb && WinComponentsLogic.isComponentRow(d.id)) null else ContainerLayoutMap.tabFor(d.id, d.category, xmb) },
        rank = { d -> ContainerLayoutMap.rankOf(d.id, xmb) },
        groupOf = { d -> groupOf(d) },
        // The Wine version is needed to create a container, so it shows at Simple (it was Advanced).
        levelOf = { d -> if (d.id == ContainerLayoutMap.ID_WINE_VERSION) SettingLevel.SIMPLE else d.level },
        handoffTab = { old, _ -> if (old == "game") EditorTabs.GAME else null },
        extraTargets = { old -> ContainerLayoutMap.extraTargets(old, xmb) },
        extraTab = { old, key -> ContainerLayoutMap.extraTab(old, key) },
        extraRank = { old -> ContainerLayoutMap.extraRank(old, xmb) },
        extraLevel = { old, tab -> extraLevelOf(scope, old, tab) },
    )

    /** The group header a row sits under in its tab. */
    private fun groupOf(d: SettingDef): String = when {
        d.id == "display.renderScale" || d.id == "display.hdrOutput" || d.id == "graphics.unrealHdr" -> T("Resolution and HDR")
        // hud.show declares the group with T(), the other overlay rows with L(): both read "Overlay" in English.
        d.category == "hud" && (d.group == T("Overlay") || d.group == L("Overlay")) -> T("Performance overlay")
        else -> d.group
    }

    /**
     * The level at which an old category's extras make a tab appear. The Windows section's extras are SIMPLE, but on
     * Defaults for new containers they are only the wallpaper (when an image is chosen) and Reset to app defaults, so
     * there the Windows tab starts at Advanced; Reset sits in Advanced, which only shows at Advanced anyway.
     */
    private fun extraLevelOf(scope: EditorScope, old: String, tab: String): SettingLevel? = when {
        old == "windows" && tab == EditorTabs.ADVANCED -> SettingLevel.ADVANCED
        old == "windows" && tab == EditorTabs.WINDOWS && scope == EditorScope.DEFAULTS -> SettingLevel.ADVANCED
        else -> null
    }
}
