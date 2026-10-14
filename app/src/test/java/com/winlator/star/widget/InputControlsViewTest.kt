package com.winlator.star.widget

import com.winlator.star.inputcontrols.ControlsProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControlsViewTest {
    @Test
    fun mouseMoveTimerRunsOnlyWhileMovementIsActive() {
        assertFalse(InputControlsView.shouldRunMouseMoveTimer(0f, 0f, false))
        assertTrue(InputControlsView.shouldRunMouseMoveTimer(1f, 0f, false))
        assertTrue(InputControlsView.shouldRunMouseMoveTimer(0f, -1f, false))
        assertTrue(InputControlsView.shouldRunMouseMoveTimer(0f, 0f, true))
    }

    @Test
    fun mouseMoveUsesThePhysicalLaneWhenTheOnScreenControlsAreOff() {
        val osc = ControlsProfile(null, 1)
        val physical = ControlsProfile(null, 2)
        assertSame(osc, InputControlsView.mouseMoveProfile(osc, physical))
        assertSame(physical, InputControlsView.mouseMoveProfile(null, physical))
        assertSame(osc, InputControlsView.mouseMoveProfile(osc, null))
        assertNull(InputControlsView.mouseMoveProfile(null, null))
    }
}
