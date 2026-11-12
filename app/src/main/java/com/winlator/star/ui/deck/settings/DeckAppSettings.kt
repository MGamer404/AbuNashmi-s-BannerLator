package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.simple.SimpleRoutes
import com.winlator.star.ui.theme.AppThemeState

// ── App settings ────────────────────────────────────────────────────────────────────────────────

/**
 * The Settings tab. In the XMB / Deck interface it is the six-tab editor of [AppSettingsLayout] (XMB: theme popup, Steam library locations first,
 * no logs; plain Deck: theme row, locations last, logs in Storage): the registry's rows plus the XMB-only
 * rows (AppSettingsRows), the page items of [AppSettingsExtras] (updates, folders, backup, sound banks, Lossless.dll, frame-gen
 * capture...) and no hand-off row at all. Lab (the Simple look) still gets the registry lists unchanged ([layout] null, no extras), so
 * its own Settings menu keeps working, hand-offs included. [onMyAccount] null hides the My account line of General > About and help.
 */
@Composable
internal fun DeckAppSettings(navController: NavHostController, onAbout: () -> Unit, onMyAccount: (() -> Unit)? = null) {
    val context = LocalContext.current
    val env = remember { SettingsEnv(context, EditorScope.APP, null, null) }
    val source = remember { AppSource(env) }
    val simple = AppThemeState.simpleLook
    // XMB only: the new arrangement (theme popup, Steam library locations first, logs only in Tools > Logs). The plain Deck keeps its old one.
    val xmb = rememberXmbHome()
    val about by rememberUpdatedState(onAbout)
    val account by rememberUpdatedState(onMyAccount)
    val openAbout: () -> Unit = { about() }
    val openAccount: () -> Unit = { account?.invoke() }
    val tools = remember(xmb) { AppSettingsExtras(context, openAbout, if (onMyAccount != null) openAccount else null, xmb) }
    val extras = remember(tools, simple) { if (simple) emptyMap<String, CategoryExtra>() else tools.extras() }
    DeckSettingsEditor(
        header = EditorHeader(
            overline = "Bannerlator",
            title = L("Settings"),
            icon = Icons.Filled.Settings,
            subtitle = L("Every app setting applies straight away."),
        ),
        source = source,
        categories = AppSettingsRegistry.categories,
        defs = AppSettingsRegistry.defs,
        handoffs = AppSettingsRegistry.handoffs,
        onExit = null,
        // Only Lab reaches a hand-off (its Settings menu lists them); the XMB / Deck layout drops every one.
        onHandoff = { h ->
            when (h.target) {
                AppSettingsRegistry.TARGET_APPEARANCE -> navController.navigate(
                    if (AppThemeState.simpleLook) SimpleRoutes.APPEARANCE else Screen.Appearance.route,
                )
                AppSettingsRegistry.TARGET_LOGS -> navController.navigate(SimpleRoutes.LOGS)
                AppSettingsRegistry.TARGET_ABOUT -> onAbout()
                AppSettingsRegistry.TARGET_INPUT_CONTROLS -> navController.navigate(Screen.InputControls.route)
                AppSettingsRegistry.TARGET_CONTENTS -> navController.navigate(
                    if (AppThemeState.simpleLook) SimpleRoutes.DRIVERS else Screen.Contents.route,
                )
                AppSettingsRegistry.TARGET_DEFAULTS -> navController.navigate(DeckSettingsRoutes.DEFAULTS)
                else -> navController.navigate(Screen.Settings.route)
            }
        },
        extras = extras,
        layout = if (simple) null else AppSettingsLayout.layoutFor(xmb),
    )
    if (!simple) tools.Dialogs()
}
