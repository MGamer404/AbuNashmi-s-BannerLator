package com.winlator.star.store

import com.winlator.star.store.steamscript.InstallScriptExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Ubisoft support: the launcher's `settings.yaml` overlay edit ([UbisoftSupport.overlayOff]), Steam's
 * `<appId>_install.vdf` script name, and finding the installer a depot bundles.
 */
class UbisoftSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun overlayOffCreatesTheSectionInAnEmptyFile() {
        assertEquals("overlay:\n  enabled: false\n  warning_enabled: false\n", UbisoftSupport.overlayOff(""))
    }

    @Test
    fun overlayOffAppendsTheSectionAndKeepsTheRest() {
        val out = UbisoftSupport.overlayOff("misc:\n  language: en\n")
        assertEquals("misc:\n  language: en\noverlay:\n  enabled: false\n  warning_enabled: false\n", out)
    }

    @Test
    fun overlayOffFlipsExistingKeysAndKeepsNeighbours() {
        val before = "overlay:\n  enabled: true\n  forceunhookgame: false\n  warning_enabled: true\nmisc:\n  x: 1\n"
        val after = "overlay:\n  enabled: false\n  forceunhookgame: false\n  warning_enabled: false\nmisc:\n  x: 1\n"
        assertEquals(after, UbisoftSupport.overlayOff(before))
    }

    @Test
    fun overlayOffAddsOnlyTheMissingKey() {
        val out = UbisoftSupport.overlayOff("overlay:\n  enabled: false\n\nmisc:\n  x: 1\n")
        assertEquals("overlay:\n  enabled: false\n  warning_enabled: false\n\nmisc:\n  x: 1\n", out)
    }

    @Test
    fun overlayOffIsIdempotentAndKeepsCrLf() {
        val once = UbisoftSupport.overlayOff("overlay:\r\n  enabled: true\r\n")
        assertEquals("overlay:\r\n  enabled: false\r\n  warning_enabled: false\r\n", once)
        assertEquals(once, UbisoftSupport.overlayOff(once))
    }

    @Test
    fun findsStevesAppNamedInstallScript() {
        val dir = tmp.newFolder("game")
        assertNull(InstallScriptExecutor.locateScript(dir))
        val script = File(dir, "242050_install.vdf").apply { writeText("\"InstallScript\" { }") }
        assertEquals(script, InstallScriptExecutor.locateScript(dir))
        // The classic name still wins when both are there.
        val classic = File(dir, "installScript.vdf").apply { writeText("\"InstallScript\" { }") }
        assertEquals(classic, InstallScriptExecutor.locateScript(dir))
    }

    @Test
    fun findsTheBundledInstallerInTheRootOrBelowIt() {
        val dir = tmp.newFolder("game2")
        assertNull(UbisoftSupport.bundledInstaller(dir))
        val nested = File(dir, "Support/Uplay").apply { mkdirs() }
        val old = File(nested, "UplayInstaller.exe").apply { writeText("x") }
        assertEquals(old, UbisoftSupport.bundledInstaller(dir))
        val root = File(dir, "UbisoftConnectInstaller.exe").apply { writeText("x") }
        assertEquals(root, UbisoftSupport.bundledInstaller(dir))
    }

    @Test
    fun detectsUbisoftTitlesFromTheirFiles() {
        val plain = tmp.newFolder("plain")
        File(plain, "game.exe").writeText("x")
        assertFalse(UbisoftSupport.detect(plain))

        val loader = tmp.newFolder("loader")
        File(loader, "uplay_r1_loader.dll").writeText("x")
        assertTrue(UbisoftSupport.detect(loader))

        val bundled = tmp.newFolder("bundled")
        File(bundled, "UbisoftConnectInstaller.exe").writeText("x")
        assertTrue(UbisoftSupport.detect(bundled))
    }

    @Test
    fun fallbackRunProcessUsesTheBundledInstallerSilently() {
        val dir = tmp.newFolder("game3")
        assertTrue(UbisoftSupport.fallbackRunProcesses(dir).isEmpty())
        File(dir, "Support/Uplay").mkdirs()
        File(dir, "Support/Uplay/UplayInstaller.exe").writeText("x")
        val rp = UbisoftSupport.fallbackRunProcesses(dir).single()
        assertEquals("%INSTALLDIR%\\Support\\Uplay\\UplayInstaller.exe", rp.process)
        assertEquals("/S", rp.command)
        assertNotNull(rp.name)
        assertTrue(UbisoftSupport.isInstaller(rp.process))
        assertFalse(UbisoftSupport.isInstaller("%INSTALLDIR%\\__Installer\\Origin\\redist\\internal\\EAappInstaller.exe"))
    }
}
