package com.winlator.star.ui.deck

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

// The XMB interface's one background: the theme colour as an even, soft wash over the preset's own background. There is no
// gradient and nothing depends on the position, so the screen is the same tone at the left edge as at the right (the old
// glows and the black scrim over the Home art read as the colour "dimming" towards the left). Used behind XMB Home, the XMB
// tabs and the XMB store screens; Classic and the other Deck styles don't use it.
//
// "Frosted" here is a tint plus a faint milky veil, composited ahead of time into one opaque colour ([XmbWash.flat]), so it
// looks the same on every Android version (no RenderEffect) and a page painting it twice doesn't get a second layer of tint.

/**
 * Most of the accent that is mixed into the background; the wash backs off from this when text would lose contrast. At 0.20
 * every built-in preset keeps its secondary text above 4.5:1 with some room (0.22 is the first to tip Phosphor and Frost), so
 * they all get the same strength and only an unusual custom accent is held back.
 */
internal const val XMB_WASH_MAX_ALPHA = 0.20f

/** The faint white lift that makes the tint read as frosted glass rather than a coloured fill (dark themes only). */
internal const val XMB_WASH_VEIL_ALPHA = 0.04f

/** WCAG AA for body text; the secondary text colour is held to it too. */
internal const val XMB_WASH_MIN_CONTRAST = 4.5

private fun channelToLinear(c: Float): Double {
    val v = c.toDouble()
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance of an sRGB colour given as 0..1 channels. */
internal fun xmbLuminance(r: Float, g: Float, b: Float): Double =
    0.2126 * channelToLinear(r) + 0.7152 * channelToLinear(g) + 0.0722 * channelToLinear(b)

/** WCAG contrast ratio (1..21) between two relative luminances, in either order. */
internal fun xmbContrast(l1: Double, l2: Double): Double {
    val hi = maxOf(l1, l2)
    val lo = minOf(l1, l2)
    return (hi + 0.05) / (lo + 0.05)
}

// A Color keeps 8 bits per channel, so the contrast is judged on the colour that is really drawn: a value that passes by a
// hair before the rounding could land just under the minimum after it.
private fun to8Bit(c: Float): Float = (c.coerceIn(0f, 1f) * 255f).roundToInt() / 255f

/**
 * The opaque colour (r, g, b as 0..1, rounded to the 8 bits a Color keeps) that [accent] at [alpha] makes over [base], then
 * the white veil of [veil] over that.
 */
internal fun xmbWashedRgb(base: Color, accent: Color, alpha: Float, veil: Float): FloatArray {
    val r = base.red * (1f - alpha) + accent.red * alpha
    val g = base.green * (1f - alpha) + accent.green * alpha
    val b = base.blue * (1f - alpha) + accent.blue * alpha
    return floatArrayOf(to8Bit(r * (1f - veil) + veil), to8Bit(g * (1f - veil) + veil), to8Bit(b * (1f - veil) + veil))
}

private fun isDarkBase(background: Color): Boolean = xmbLuminance(background.red, background.green, background.blue) < 0.18

/** The veil strength for a background: only dark themes get the white lift, a light one would just go grey. */
internal fun xmbWashVeil(background: Color): Float = if (isDarkBase(background)) XMB_WASH_VEIL_ALPHA else 0f

/**
 * How much of [accent] to mix into [background]: [XMB_WASH_MAX_ALPHA] unless that would drop the contrast of any of [texts]
 * on the washed colour below [XMB_WASH_MIN_CONTRAST], then the largest value that keeps it (down to no tint at all). The
 * presets all pass at the maximum; this keeps a very light custom accent, or a light scheme, readable too.
 */
internal fun xmbWashAlpha(background: Color, accent: Color, texts: List<Color>): Float {
    val veil = xmbWashVeil(background)
    var steps = (XMB_WASH_MAX_ALPHA * 100f).roundToInt()
    while (steps > 0) {
        val a = steps / 100f
        val rgb = xmbWashedRgb(background, accent, a, veil)
        val lum = xmbLuminance(rgb[0], rgb[1], rgb[2])
        if (texts.all { xmbContrast(xmbLuminance(it.red, it.green, it.blue), lum) >= XMB_WASH_MIN_CONTRAST }) return a
        steps--
    }
    return 0f
}

/** The wash for one colour scheme: how much accent and white veil went in, and the opaque colour they make over the plain background. */
internal class XmbWash(val accentAlpha: Float, val veilAlpha: Float, val flat: Color)

internal fun xmbWashFor(background: Color, accent: Color, texts: List<Color>): XmbWash {
    val alpha = xmbWashAlpha(background, accent, texts)
    val veilAlpha = xmbWashVeil(background)
    val rgb = xmbWashedRgb(background, accent, alpha, veilAlpha)
    return XmbWash(alpha, veilAlpha, Color(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f)))
}

/** The wash [XmbWashTheme] settled on for this subtree, so a nested reader doesn't wash the already washed background again. */
private val LocalXmbWash = staticCompositionLocalOf<XmbWash?> { null }

/** The wash for the current theme; recomputed only when the colour scheme changes. */
@Composable
internal fun rememberXmbWash(): XmbWash {
    val provided = LocalXmbWash.current
    val cs = MaterialTheme.colorScheme
    return provided ?: remember(cs.background, cs.primary, cs.onBackground, cs.onSurface, cs.onSurfaceVariant) {
        xmbWashFor(cs.background, cs.primary, listOf(cs.onBackground, cs.onSurface, cs.onSurfaceVariant))
    }
}

/**
 * In XMB mode, a theme whose `background` is the washed colour. The pages inside (the tabs, the store lists and rails, the
 * download manager) paint `colorScheme.background` as their own fill, and left alone each would sit on the plain preset colour
 * as a flat patch over the wash. Everything else in the scheme, the surfaces and the text, is untouched. Off: [content] as is.
 */
@Composable
internal fun XmbWashTheme(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val wash = rememberXmbWash()
    val base = MaterialTheme.colorScheme
    val washed = remember(base, wash) { base.copy(background = wash.flat) }
    CompositionLocalProvider(LocalXmbWash provides wash) {
        MaterialTheme(
            colorScheme = washed,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/**
 * The wash already settled on the theme background, as one opaque fill: for a screen that is the bottom layer (a store
 * screen, the Deck shell, XMB Home). It is one colour, so a fill under [XmbWashTheme] and a fill painted again on top of it
 * look the same, and a host without the shell (the classic Games screen) still gets the wash. In XMB mode only; elsewhere
 * keep the plain `background(colorScheme.background)`.
 */
@Composable
internal fun Modifier.xmbWashBackground(): Modifier = this.background(rememberXmbWash().flat)
