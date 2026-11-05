package com.winlator.star.ui.deck

import androidx.compose.ui.graphics.Color
import com.winlator.star.ui.theme.themePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The XMB wash must never cost the text its contrast. These check the maths behind [xmbWashFor] against every theme
 * preset and a spread of custom accents, the same way the screen composites it (accent over background, then the veil).
 */
class XmbBackdropTest {

    private fun contrastOn(text: Color, ground: Color): Double =
        xmbContrast(xmbLuminance(text.red, text.green, text.blue), xmbLuminance(ground.red, ground.green, ground.blue))

    @Test
    fun luminanceAndContrastMatchWcag() {
        assertEquals(1.0, xmbLuminance(1f, 1f, 1f), 1e-9)
        assertEquals(0.0, xmbLuminance(0f, 0f, 0f), 1e-9)
        assertEquals(21.0, xmbContrast(1.0, 0.0), 1e-9)
        assertEquals(21.0, xmbContrast(0.0, 1.0), 1e-9)
        assertEquals(1.0, xmbContrast(0.5, 0.5), 1e-9)
    }

    @Test
    fun washedRgbEndsAreTheBaseAndTheAccent() {
        val base = Color(0xFF204060)
        val accent = Color(0xFFE0A82E)
        val none = xmbWashedRgb(base, accent, 0f, 0f)
        assertEquals(base.red, none[0], 1e-6f)
        assertEquals(base.green, none[1], 1e-6f)
        assertEquals(base.blue, none[2], 1e-6f)
        val full = xmbWashedRgb(base, accent, 1f, 0f)
        assertEquals(accent.red, full[0], 1e-6f)
        assertEquals(accent.green, full[1], 1e-6f)
        assertEquals(accent.blue, full[2], 1e-6f)
    }

    @Test
    fun everyPresetKeepsItsTextReadableAtFullStrength() {
        for (p in themePresets) {
            val texts = listOf(p.onBackground, p.onSurface, p.onSurfaceVariant)
            val wash = xmbWashFor(p.background, p.primary, texts)
            // All presets get the same strength: none is held back, so the look is the same in every theme.
            assertEquals("${p.name} is held back", XMB_WASH_MAX_ALPHA, wash.accentAlpha, 1e-6f)
            for (t in texts) {
                val ratio = contrastOn(t, wash.flat)
                assertTrue("${p.name}: $ratio on the wash", ratio >= XMB_WASH_MIN_CONTRAST)
            }
        }
    }

    @Test
    fun anyCustomAccentOnAnyPresetKeepsTheTextReadable() {
        val accents = listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFFFFF00), Color(0xFF00FFFF),
            Color(0xFFFF00FF), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFF88C0D0),
        )
        for (p in themePresets) {
            val texts = listOf(p.onBackground, p.onSurface, p.onSurfaceVariant)
            for (accent in accents) {
                val wash = xmbWashFor(p.background, accent, texts)
                assertTrue(wash.accentAlpha in 0f..XMB_WASH_MAX_ALPHA)
                for (t in texts) {
                    val ratio = contrastOn(t, wash.flat)
                    assertTrue("${p.name} + $accent: $ratio", ratio >= XMB_WASH_MIN_CONTRAST)
                }
            }
        }
    }

    @Test
    fun lightSchemeIsHeldBackInsteadOfLosingContrast() {
        val background = Color(0xFFF5F5F5)
        val texts = listOf(Color(0xFF1A1A1A), Color(0xFF1A1A1A), Color(0xFF555555))
        for (accent in listOf(Color(0xFF0055FF), Color(0xFF000000), Color(0xFF800000), Color(0xFFFFFFFF))) {
            val wash = xmbWashFor(background, accent, texts)
            // No white lift on a light background; it would only turn it grey.
            assertEquals(0f, wash.veilAlpha, 0f)
            assertTrue(wash.accentAlpha in 0f..XMB_WASH_MAX_ALPHA)
            for (t in texts) {
                assertTrue(contrastOn(t, wash.flat) >= XMB_WASH_MIN_CONTRAST)
            }
        }
    }

    @Test
    fun washBacksOffToNothingWhenTheTextCannotKeepUp() {
        // Mid-grey text on a mid-grey ground is below the minimum already, so no amount of wash can fix it.
        val alpha = xmbWashAlpha(Color(0xFF808080), Color(0xFFFFFFFF), listOf(Color(0xFF909090)))
        assertEquals(0f, alpha, 0f)
    }
}
