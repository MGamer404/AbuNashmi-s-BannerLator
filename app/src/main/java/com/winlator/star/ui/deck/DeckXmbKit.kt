package com.winlator.star.ui.deck

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.theme.AppThemeState
import kotlin.math.min

// Shared pieces of the Deck interface's XMB-home mode, for the Deck shell and for the store and download screens,
// which are activities of their own.

/**
 * True while the Deck interface runs with the XMB home. Read from the theme prefs rather than AppThemeState, because a
 * store screen can be the first one in its process and AppThemeState is set up only by the main screens.
 */
internal fun isXmbHome(context: Context): Boolean {
    val prefs = context.getSharedPreferences("winlator_theme", Context.MODE_PRIVATE)
    return prefs.getString("ui_style", AppThemeState.UI_STYLE_CLASSIC) == AppThemeState.UI_STYLE_DECK &&
        prefs.getBoolean("deck_xmb_home", false)
}

/** [isXmbHome] for a screen, read once when it opens (the setting can't change while a store screen is up). */
@Composable
internal fun rememberXmbHome(): Boolean {
    val context = LocalContext.current
    return remember(context) { isXmbHome(context) }
}

/**
 * Sizes as a share of the screen, kept within limits, so the XMB-home layouts keep their proportions from a small phone
 * to a tablet without text or touch targets ending up too small or too large.
 */
@Immutable
internal class ScreenScale(val widthDp: Float, val heightDp: Float) {
    /** [fraction] of the screen width, between [min] and [max]. */
    fun w(fraction: Float, min: Dp = 0.dp, max: Dp = Dp.Infinity): Dp = (widthDp * fraction).dp.coerceIn(min, max)

    /** [fraction] of the screen height, between [min] and [max]. */
    fun h(fraction: Float, min: Dp = 0.dp, max: Dp = Dp.Infinity): Dp = (heightDp * fraction).dp.coerceIn(min, max)

    /** The screen's short side as a factor of a 412 dp phone, held between 0.85 and 1.6: text and icons follow it. */
    val factor: Float = (min(widthDp, heightDp) / 412f).coerceIn(0.85f, 1.6f)

    /** [base] dp as drawn on a 412 dp phone, scaled to this screen. */
    fun dp(base: Float): Dp = (base * factor).dp

    /** [base] sp as drawn on a 412 dp phone, scaled to this screen. */
    fun sp(base: Float): TextUnit = (base * factor).sp
}

@Composable
internal fun rememberScreenScale(): ScreenScale {
    val cfg = LocalConfiguration.current
    return remember(cfg.screenWidthDp, cfg.screenHeightDp) { ScreenScale(cfg.screenWidthDp.toFloat(), cfg.screenHeightDp.toFloat()) }
}

/**
 * XMB-home window for a store or download screen, the way the Deck shell sets its own: laid out edge to edge and under
 * the camera hole, with the status and navigation bars hidden (a swipe in from the edge shows them for a moment),
 * re-hidden whenever the screen resumes and put back when it closes. Pair it with [xmbFrame] for the content.
 */
@Composable
internal fun XmbScreenWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(enabled, activity, lifecycleOwner) {
        val window = activity?.window
        if (!enabled || window == null) return@DisposableEffect onDispose {}
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val cutoutBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val hide = {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        hide()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hide() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = cutoutBefore }
            }
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }
}

/**
 * The XMB-home frame: as wide as the camera strip, kept on both sides so a page sits evenly between the edges. Content
 * pads by it horizontally; backgrounds and bars run to the edges.
 */
@Composable
internal fun xmbFrame(): Dp {
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val dir = LocalLayoutDirection.current
    return maxOf(cutout.calculateStartPadding(dir), cutout.calculateEndPadding(dir))
}
