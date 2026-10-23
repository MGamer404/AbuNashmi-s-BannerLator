package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastOpenGLTest {

    @Test fun containerDefaultsOn() {
        assertTrue(FastOpenGL.containerOn(null))
        assertTrue(FastOpenGL.containerOn(""))
        assertTrue(FastOpenGL.containerOn("1"))
        assertFalse(FastOpenGL.containerOn("0"))
        assertTrue(FastOpenGL.containerOn("junk"))
    }

    @Test fun shortcutOverridesContainer() {
        assertTrue(FastOpenGL.requested(null, null))
        assertFalse(FastOpenGL.requested("0", ""))
        assertTrue(FastOpenGL.requested("0", "1"))
        assertFalse(FastOpenGL.requested("1", "0"))
        assertFalse(FastOpenGL.requested("0", "junk"))
    }

    @Test fun firstReasonWins() {
        assertNull(FastOpenGL.unavailableReason(driverUsable = true, bundled = true))
        assertEquals(FastOpenGL.NEEDS_TURNIP, FastOpenGL.unavailableReason(driverUsable = false, bundled = false))
        assertEquals(FastOpenGL.NOT_BUNDLED, FastOpenGL.unavailableReason(driverUsable = true, bundled = false))
    }

    private fun decide(
        requested: Boolean = true, egl: Boolean? = null, glx: Boolean = false,
        unavailable: String? = null, layerHasEgl: Boolean = false,
    ) = FastOpenGL.decideX11(requested, "container", egl, glx, unavailable, layerHasEgl)

    @Test fun layerPicksTheRoute() {
        assertEquals(FastOpenGL.Route.EGL, decide(layerHasEgl = true).route)
        assertEquals(FastOpenGL.Route.GLX, decide(layerHasEgl = false).route)
        assertEquals("fast opengl: on (container, glx route)", decide().toString())
        assertEquals("fast opengl: on (game, egl route)",
            FastOpenGL.decideX11(true, "game", null, false, null, true).toString())
    }

    @Test fun settingAndSupportGateIt() {
        assertFalse(decide(requested = false, layerHasEgl = true).on)
        assertEquals("fast opengl: off (container)", decide(requested = false).toString())
        val d = decide(unavailable = FastOpenGL.NEEDS_TURNIP, layerHasEgl = true)
        assertNull(d.route)
        assertEquals("fast opengl: off (unsupported: ${FastOpenGL.NEEDS_TURNIP})", d.toString())
    }

    @Test fun envOverridesWinInOrder() {
        // BANNER_X11_EGL=0 beats everything, including BANNER_X11_GLX=1.
        assertFalse(decide(egl = false, glx = true, layerHasEgl = true).on)
        // BANNER_X11_GLX=1 beats BANNER_X11_EGL=1 and an EGL-capable layer, and skips the setting.
        assertEquals(FastOpenGL.Route.GLX, decide(requested = false, egl = true, glx = true, layerHasEgl = true).route)
        // BANNER_X11_EGL=1 forces EGL on a layer without it, past the support check.
        val d = decide(requested = false, egl = true, unavailable = FastOpenGL.NEEDS_TURNIP)
        assertEquals(FastOpenGL.Route.EGL, d.route)
        assertEquals("fast opengl: on (env override, egl route)", d.toString())
    }
}

class GlLaunchSwitchTest {
    @Test fun defaultsOffAndOverride() {
        val sw = FastOpenGL.FPS_HUD
        assertFalse(sw.containerOn(null))
        assertTrue(sw.containerOn("1"))
        assertTrue(sw.requested("0", "1"))
        assertFalse(sw.requested("1", "0"))
        assertTrue(sw.requested("1", ""))
        assertFalse(FastOpenGL.VSYNC_OFF.requested(null, null))
    }
}
