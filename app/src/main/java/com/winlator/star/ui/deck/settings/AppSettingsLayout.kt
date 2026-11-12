package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.T

// The XMB / Deck Settings tab: six real tabs (EditorTabs.APP_*) over the registry's rows. The legacy rows keep their categories
// ("app.general", "app.saves"...) for Lab and the Classic shell; this layout files them, and the XMB-only rows of
// AppSettingsRows, into the tabs by id. Every legacy hand-off is dropped (each is a real row or action here, or removed), and
// the page items of AppSettingsExtras are keyed by tab id.
//
//   app.t.general      General           landing, updateNotify, updatePrerelease + (extras) Updates block, About / Help / Account / Run setup again
//   app.t.appearance   Appearance        style, labLook, preset (plain Deck; XMB: an extras line + popup, first), orientation, uiScale, fontScale, showStores / Internal / Sd + (extras) accent colour picker
//   app.t.library      Games & library   steamGridKey, steam.* (4), contents.* (2), steam.region + (extras) region / network tests, Steam library locations (XMB: those come first)
//   app.t.performance  Performance       sustained, threadBoost, bigCores, gpuClockLock, saveSessionFps + (extras) Root controls dialog, Lossless.dll
//   app.t.storage      Storage & data    downloads.* (5), saves.* (8), logs.* (14; plain Deck only) + (extras) space used, folders, sound banks, backup / restore, Wine channels (plain Deck only)
//   app.t.advanced     Advanced          advanced.* (6), controllers.cursorSpeed / trueMouse / disableXinput + (extras) frame-gen training capture
//
// Hidden here (still in the registry for Lab / Classic): library.midiDefault, performance.box64Preset / fexPreset (the Defaults for
// new containers editor owns both), controllers.steamController / trackpadMouse / onScreenPriority / autoHide (Controls > Device).
// XMB ONLY (the `xmb` flag of every function here): logs are not in Settings at all (every app.logs.* id is hidden: what is recorded,
// where it goes and how long it is kept live in Tools > Logs), and the theme preset is not a row but a page item (ThemePresetPopup)
// that opens a grid of themes. The plain Deck (xmb = false) keeps the earlier arrangement: the preset row, the logs rows in Storage.

internal object AppSettingsLayout {

    private val tabIds: Set<String> = EditorTabs.APP_IDS.toSet()

    /**
     * The tab id of an app row, by its id, or null to hide it in this layout. A pure function of the id (unit-testable). [xmb] is true
     * in the XMB interface (the logs rows and the Theme preset row are hidden there) and false in the plain Deck (both are shown).
     */
    fun tabFor(id: String, xmb: Boolean): String? = when {
        id == "app.library.midiDefault" -> null
        xmb && id.startsWith("app.logs.") -> null
        // XMB: the Theme preset is the page item of AppSettingsExtras (a popup with a swatch grid), not a row.
        xmb && id == "app.appearance.preset" -> null
        id == "app.performance.box64Preset" || id == "app.performance.fexPreset" -> null
        id == "app.controllers.cursorSpeed" || id == "app.controllers.trueMouse" || id == "app.controllers.disableXinput" -> EditorTabs.APP_ADVANCED
        id.startsWith("app.controllers.") -> null
        id.startsWith("app.general.") -> EditorTabs.APP_GENERAL
        id.startsWith("app.appearance.") -> EditorTabs.APP_APPEARANCE
        id.startsWith("app.library.") -> EditorTabs.APP_LIBRARY
        id.startsWith("app.steam.") -> EditorTabs.APP_LIBRARY
        id.startsWith("app.contents.") -> EditorTabs.APP_LIBRARY
        id.startsWith("app.performance.") -> EditorTabs.APP_PERFORMANCE
        id.startsWith("app.downloads.") -> EditorTabs.APP_STORAGE
        id.startsWith("app.saves.") -> EditorTabs.APP_STORAGE
        id.startsWith("app.logs.") -> EditorTabs.APP_STORAGE
        id.startsWith("app.advanced.") -> EditorTabs.APP_ADVANCED
        else -> null
    }

    /** The order of rows inside their tab (stable: rows of one rank keep the registry's order). */
    fun rankOf(id: String, xmb: Boolean): Int = when {
        // Library: cover art, friends, component downloads, then the Steam region (its test actions come right after it).
        id == "app.steam.region" -> 3
        id.startsWith("app.contents.") -> 2
        id.startsWith("app.steam.") -> 1
        // Storage: download engines, then saves and cloud, then (plain Deck only) the logs (the Wine channel chooser follows them).
        id.startsWith("app.saves.") -> 1
        !xmb && id.startsWith("app.logs.") -> 2
        // Advanced: the experimental / X11 switches, then mouse and keyboard.
        id.startsWith("app.controllers.") -> 1
        else -> 0
    }

    private fun groupOf(def: SettingDef): String =
        if (def.id == "app.contents.keepRaw") T("Component downloads") else def.group

    private fun build(xmb: Boolean): EditorLayout = EditorLayout(
        tabs = EditorTabs.tabsFor(EditorScope.APP, xmb = xmb),
        tabOf = { def -> tabFor(def.id, xmb) },
        rank = { def -> rankOf(def.id, xmb) },
        groupOf = { def -> groupOf(def) },
        // AppSettingsExtras keys its page items by tab id, so a source feeds exactly the tab of its own key.
        extraTargets = { old -> if (old in tabIds) setOf(old) else emptySet() },
    )

    private val xmbLayout: EditorLayout by lazy { build(true) }
    private val deckLayout: EditorLayout by lazy { build(false) }

    /** The layout DeckAppSettings passes to the editor (one instance per flavour: the editor needs it remembered). */
    fun layoutFor(xmb: Boolean): EditorLayout = if (xmb) xmbLayout else deckLayout
}
