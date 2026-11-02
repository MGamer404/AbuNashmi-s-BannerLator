package com.winlator.star.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of [WineFontFallback]: when a `user.reg` already has a "Tahoma" font replacement. */
class WineFontFallbackTest {

    private val replacementsKey = "[Software\\\\Wine\\\\Fonts\\\\Replacements] 1700000000\n#time=1dc0000000000000\n"

    @Test
    fun tahomaReplacementIsNeededWhenTheRegistryHasNone() {
        assertTrue(WineFontFallback.tahomaReplacementNeeded(""))
        assertTrue(WineFontFallback.tahomaReplacementNeeded("[Software\\\\Wine\\\\Drivers] 1\n\"Audio\"=\"pulse\"\n"))
        // Other replacements stay out of the way: only Tahoma counts.
        assertTrue(WineFontFallback.tahomaReplacementNeeded(replacementsKey + "\"Segoe UI\"=\"Arial\"\n"))
    }

    @Test
    fun tahomaReplacementIsNotNeededOnceOneExists() {
        assertFalse(WineFontFallback.tahomaReplacementNeeded(replacementsKey + "\"Tahoma\"=\"Arial\"\n"))
        // The user's own choice (any data, any case, CRLF) is kept.
        assertFalse(WineFontFallback.tahomaReplacementNeeded(replacementsKey.replace("\n", "\r\n") + "\"tahoma\"=\"DejaVu Sans\"\r\n"))
        assertFalse(WineFontFallback.tahomaReplacementNeeded(
            "[SOFTWARE\\\\Wine\\\\Fonts\\\\Replacements] 1\n\"Tahoma\"=hex(7):41,00,00,00,00,00\n"))
    }

    @Test
    fun tahomaValueInAnotherKeyDoesNotCount() {
        val text = "[Software\\\\Wine\\\\Fonts] 1\n\"Tahoma\"=\"x\"\n\n" + replacementsKey + "\"Segoe UI\"=\"Arial\"\n\n" +
            "[Software\\\\Wine\\\\Fonts\\\\Replacements\\\\Sub] 1\n\"Tahoma\"=\"x\"\n"
        assertTrue(WineFontFallback.tahomaReplacementNeeded(text))
    }

    @Test
    fun theReplacementsKeyInTheMiddleOfAFileIsFound() {
        val text = "WINE REGISTRY Version 2\n\n[Control Panel\\\\Desktop] 1\n\"A\"=\"b\"\n\n" +
            replacementsKey + "\"Tahoma\"=\"Arial\"\n\n[Software\\\\Wine\\\\X11 Driver] 1\n\"C\"=\"d\"\n"
        assertFalse(WineFontFallback.tahomaReplacementNeeded(text))
    }
}
