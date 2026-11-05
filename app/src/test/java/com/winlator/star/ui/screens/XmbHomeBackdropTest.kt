package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home backdrop's numbers: the one flat veil and the size the art is shrunk to where Modifier.blur does nothing (below API 31). */
class XmbHomeBackdropTest {

    @Test fun veil_isALightFlatDim() {
        assertTrue(XMB_BACKDROP_VEIL in 0.25f..0.35f)
    }

    @Test fun softSize_isASixteenthOfThePicture() {
        assertEquals(32 to 48, xmbBackdropSoftSize(512, 768))
    }

    @Test fun softSize_neverCollapsesBelowOnePixel() {
        assertEquals(1 to 1, xmbBackdropSoftSize(8, 3))
        assertEquals(1 to 1, xmbBackdropSoftSize(0, 0))
    }
}
