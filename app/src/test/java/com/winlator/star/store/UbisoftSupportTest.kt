package com.winlator.star.store

import com.winlator.star.store.steamscript.InstallScriptExecutor
import com.winlator.star.store.steamscript.InstallScriptModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun findsAnInstallScriptNamedAfterTheApp() {
        val dir = tmp.newFolder("game")
        File(dir, "uplay_r1_loader.dll").writeText("x")
        assertNull(InstallScriptExecutor.locateScript(dir))
        val script = File(dir, "242050_install.vdf").apply { writeText("\"InstallScript\" { }") }
        assertEquals(script, InstallScriptExecutor.locateScript(dir))
        // The classic name still wins when both are there.
        val classic = File(dir, "installScript.vdf").apply { writeText("\"InstallScript\" { }") }
        assertEquals(classic, InstallScriptExecutor.locateScript(dir))
    }

    @Test
    fun appNamedInstallScriptIsOnlyUsedForUbisoftDepots() {
        val plain = tmp.newFolder("plainScript")
        File(plain, "game.exe").writeText("x")
        File(plain, "242050_install.vdf").writeText("\"InstallScript\" { }")
        assertNull(InstallScriptExecutor.locateScript(plain))

        val loader = tmp.newFolder("loaderScript")
        File(loader, "upc_r1_loader.dll").writeText("x")
        val loaderScript = File(loader, "242050_install.vdf").apply { writeText("\"InstallScript\" { }") }
        assertEquals(loaderScript, InstallScriptExecutor.locateScript(loader))

        val bundled = tmp.newFolder("bundledScript")
        File(bundled, "UbisoftConnectInstaller.exe").writeText("x")
        val bundledScript = File(bundled, "242050_install.vdf").apply { writeText("\"InstallScript\" { }") }
        assertEquals(bundledScript, InstallScriptExecutor.locateScript(bundled))

        // Other names are unaffected by the Ubisoft check.
        val classic = File(plain, "installScript.vdf").apply { writeText("\"InstallScript\" { }") }
        assertEquals(classic, InstallScriptExecutor.locateScript(plain))
    }

    @Test
    fun isInstallerLooksAtTheFileNameOnly() {
        assertTrue(UbisoftSupport.isInstaller("%INSTALLDIR%\\UbisoftConnectInstaller.exe"))
        assertTrue(UbisoftSupport.isInstaller("%INSTALLDIR%/Support/uplayinstaller.exe"))
        assertTrue(UbisoftSupport.isInstaller("UplayInstaller.exe"))
        assertFalse(UbisoftSupport.isInstaller("%INSTALLDIR%\\UplayInstaller\\setup.exe"))
        assertFalse(UbisoftSupport.isInstaller("%INSTALLDIR%/UbisoftConnectInstaller/setup.exe"))
    }

    @Test
    fun theLauncherInstallerIsRunFirst() {
        val redist = InstallScriptModel.RunProcess("Redist", "%INSTALLDIR%\\redist\\vcredist_x86.exe", "/q", null, null, null)
        val other = InstallScriptModel.RunProcess("Other", "%INSTALLDIR%\\other.exe", "", null, null, null)
        val uplay = InstallScriptModel.RunProcess("Uplay", "%INSTALLDIR%\\UplayInstaller.exe", "/S", null, null, null)
        assertEquals(listOf(uplay, redist, other), UbisoftSupport.installerFirst(listOf(redist, uplay, other)))
        assertEquals(listOf(redist, other), UbisoftSupport.installerFirst(listOf(redist, other)))
    }

    @Test
    fun needsSomethingInstallableToSetUp() {
        val loaderOnly = tmp.newFolder("loaderOnly")
        File(loaderOnly, "uplay_r1_loader.dll").writeText("x")
        assertTrue(UbisoftSupport.detect(loaderOnly))
        assertFalse(UbisoftSupport.hasInstallableLauncher(loaderOnly))

        val bundled = tmp.newFolder("bundledInstallable")
        File(bundled, "UbisoftConnectInstaller.exe").writeText("x")
        assertTrue(UbisoftSupport.hasInstallableLauncher(bundled))

        val scripted = tmp.newFolder("scriptedInstallable")
        File(scripted, "uplay_r1_loader.dll").writeText("x")
        File(scripted, "installScript.vdf").writeText(
            "\"InstallScript\" { \"Run Process\" { \"Ubisoft Connect PC Client\" " +
                "{ \"process 1\" \"%INSTALLDIR%/UbisoftConnectInstaller.exe\" \"command 1\" \"/S\" } } }"
        )
        assertTrue(UbisoftSupport.hasInstallableLauncher(scripted))
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
        assertEquals("Ubisoft Connect PC Client", rp.name)
        assertTrue(UbisoftSupport.isInstaller(rp.process))
        assertFalse(UbisoftSupport.isInstaller("%INSTALLDIR%\\__Installer\\Origin\\redist\\internal\\EAappInstaller.exe"))
    }

    @Test
    fun dllOverrideIsAppendedOnce() {
        assertEquals("windows.ui=d", UbisoftSupport.mergeDllOverrides(""))
        assertEquals("windows.ui=d", UbisoftSupport.mergeDllOverrides("  ;"))
        assertEquals("a=n;b=b;windows.ui=d", UbisoftSupport.mergeDllOverrides("a=n;b=b"))
        assertEquals("a=n;windows.ui=d", UbisoftSupport.mergeDllOverrides("a=n;"))
        val merged = UbisoftSupport.mergeDllOverrides("a=n")
        assertEquals(merged, UbisoftSupport.mergeDllOverrides(merged))
    }

    @Test
    fun dllOverrideKeepsTheUsersOwnWindowsUiEntry() {
        assertEquals("windows.ui=n", UbisoftSupport.mergeDllOverrides("windows.ui=n"))
        assertEquals("a=n;Windows.UI=b,n", UbisoftSupport.mergeDllOverrides("a=n;Windows.UI=b,n"))
        assertEquals("x,windows.ui=n;a=b", UbisoftSupport.mergeDllOverrides("x,windows.ui=n;a=b"))
        assertEquals("windows.ui.dll=n", UbisoftSupport.mergeDllOverrides("windows.ui.dll=n"))
        assertEquals("windows.uix=n;windows.ui=d", UbisoftSupport.mergeDllOverrides("windows.uix=n"))
    }
}
