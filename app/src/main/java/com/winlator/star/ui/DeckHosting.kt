package com.winlator.star.ui

import android.app.Activity
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

// What a Classic screen needs to know when the Deck shell hosts it. Kept out of ui/deck so the
// Classic screens read two small locals and never depend on the Deck package.

/**
 * True while the Deck shell's landscape frame hosts this screen. The frame already shows the page
 * title and the Steam connection pill, so a screen that draws its own title row skips it.
 * Classic, portrait Deck and the screens' own activities leave it false.
 */
val LocalHostedInDeck = staticCompositionLocalOf { false }

/** Counts the side lists (a [com.winlator.star.ui.components.CollapsibleRail]) a hosted screen shows. */
class DeckSideLists {
    val count: MutableIntState = mutableIntStateOf(0)
}

/** Set by the Deck shell; null everywhere else. */
val LocalDeckSideLists = staticCompositionLocalOf<DeckSideLists?> { null }

/**
 * Called by a screen's own side list. Inside the Deck shell the page-tab rail then shrinks to icons,
 * so two full-width rails never sit side by side. Does nothing outside the Deck shell.
 */
@Composable
fun ReportSideListToDeck() {
    val lists = LocalDeckSideLists.current ?: return
    DisposableEffect(lists) {
        lists.count.intValue++
        onDispose { lists.count.intValue-- }
    }
}

/**
 * Whether the Deck shell has hidden Android's navigation bar (landscape Deck). A screen that hides
 * and restores the system bars itself (Big Picture) restores only the status bar while this is set.
 * Compose state, so a dialog open while it changes ([DeckDialogBars]) follows along.
 */
object DeckWindow {
    private var hiddenIn: WeakReference<Activity>? by mutableStateOf(null)

    /** True while some activity's Deck shell keeps the navigation bar hidden. */
    val navBarHidden: Boolean get() = hiddenIn?.get() != null

    /** Set by the Deck shell for its own activity, cleared (null) when it shows the bar again. */
    fun setNavBarHidden(activity: Activity?) {
        hiddenIn = activity?.let { WeakReference(it) }
    }

    /** Whether [activity] is the one whose navigation bar the Deck shell hides (its dialogs follow it). */
    fun hidesNavBarIn(activity: Activity?): Boolean = activity != null && hiddenIn?.get() === activity
}

/**
 * Called inside a dialog's or bottom sheet's content. Each Dialog/AlertDialog/ModalBottomSheet is its
 * own window and brings Android's navigation bar back over the landscape Deck shell; this hides it on
 * that window too, with the same swipe-to-show behaviour. Does nothing in Classic or portrait Deck.
 */
@Composable
fun DeckDialogBars() {
    // Only dialogs of the Deck shell's own activity: a store or game activity keeps its own bars.
    val view = LocalView.current
    val hidden = DeckWindow.hidesNavBarIn(view.context.findActivity())
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(hidden, dialogWindow, view) {
        if (hidden) {
            if (dialogWindow != null) {
                val controller = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // A bottom sheet's window has no Window object; its view's own controller reaches it.
                view.windowInsetsController?.let {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.navigationBars())
                }
            }
        }
        onDispose {}
    }
}

/**
 * The Deck style's density (smaller than the rest of the app), set by DeckTheme. Composition locals reach a
 * dialog's content but LocalDensity does not: every dialog and sheet window starts again from the raw density.
 */
val LocalDeckDensity = staticCompositionLocalOf<Density?> { null }

/** Wraps a dialog's or sheet's content so it is drawn at the Deck style's size; does nothing in Classic. */
@Composable
fun DeckDialogScaled(content: @Composable () -> Unit) {
    val deck = LocalDeckDensity.current
    if (deck == null) content() else CompositionLocalProvider(LocalDensity provides deck, content = content)
}
