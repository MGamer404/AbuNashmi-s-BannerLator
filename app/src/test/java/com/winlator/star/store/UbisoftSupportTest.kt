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
 * `<appId>_install.vdf` script name, finding the installer a depot bundles.
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
    fun installerNamesAreRecognised() {
        for (name in listOf(
            "UbisoftConnectInstaller.exe", "UplayInstaller.exe", "Ubisoft Connect Installer.exe",
            "UbisoftGameLauncherInstaller.exe", "Uplay_Installer.exe", "UplayInstaller_x64.exe",
            "ubisoft-connect-installer.EXE", "UPCInstaller.exe",
        )) assertTrue(name, UbisoftSupport.isInstallerName(name))
        for (name in listOf(
            "UplayLaunch.exe", "UplayService.exe", "upc.exe", "UbisoftGameLauncher.exe", "uplay_r1_loader64.dll",
            "DXSETUP.exe", "vcredist_x86.exe", "UplayInstaller.dll", "UplayInstaller", "UplayInstaller.exe.bak",
            "UplayUninstaller.exe", "UbisoftConnectUninstall.exe", "UpcomingInstaller.exe", "EAappInstaller.exe",
            "UplaySetup.exe", "Setup.exe", "",
        )) assertFalse(name, UbisoftSupport.isInstallerName(name))
    }

    @Test
    fun installerNamesRankConnectFirstThenUbisoftThenUplay() {
        assertEquals(0, UbisoftSupport.installerRank("UbisoftConnectInstaller.exe"))
        assertEquals(1, UbisoftSupport.installerRank("UbisoftGameLauncherInstaller.exe"))
        assertEquals(2, UbisoftSupport.installerRank("UplayInstaller.exe"))
        assertEquals(2, UbisoftSupport.installerRank("UPCInstaller.exe"))
        assertNull(UbisoftSupport.installerRank("upc.exe"))
    }

    @Test
    fun scriptStepsAndDepotSearchShareOneMatcher() {
        for (name in listOf("Ubisoft Connect Installer.exe", "Uplay_Installer.exe", "UplayInstaller_x64.exe", "UplayLaunch.exe", "DXSETUP.exe")) {
            assertEquals(name, UbisoftSupport.isInstallerName(name), UbisoftSupport.isInstaller("%INSTALLDIR%\\Support\\$name"))
            assertEquals(name, UbisoftSupport.isInstallerName(name), UbisoftSupport.isInstaller("\"%INSTALLDIR%/$name\""))
        }
    }

    @Test
    fun theLauncherInstallerIsRunFirst() {
        val redist = InstallScriptModel.RunProcess("Redist", "%INSTALLDIR%\\redist\\vcredist_x86.exe", "/q", null, null, null)
        val other = InstallScriptModel.RunProcess("Other", "%INSTALLDIR%\\other.exe", "", null, null, null)
        val uplay = InstallScriptModel.RunProcess("Uplay", "%INSTALLDIR%\\UplayInstaller.exe", "/S", null, null, null)
        assertEquals(listOf(uplay, redist, other), UbisoftSupport.installerFirst(listOf(redist, uplay, other)))
        assertEquals(listOf(redist, other), UbisoftSupport.installerFirst(listOf(redist, other)))
        // Two installers: Ubisoft Connect's own comes before an older Uplay one.
        val connect = InstallScriptModel.RunProcess("Connect", "%INSTALLDIR%\\UbisoftConnectInstaller.exe", "/S", null, null, null)
        assertEquals(listOf(connect, uplay, redist), UbisoftSupport.installerFirst(listOf(redist, uplay, connect)))
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
        UbisoftSupport.forgetScans()
        assertEquals(old, UbisoftSupport.bundledInstaller(dir))
        val root = File(dir, "UbisoftConnectInstaller.exe").apply { writeText("x") }
        UbisoftSupport.forgetScans()
        assertEquals(root, UbisoftSupport.bundledInstaller(dir))
    }

    @Test
    fun theShallowestInstallerWinsOverADeeperOne() {
        val dir = tmp.newFolder("depth")
        File(dir, "A/B/C").mkdirs()
        File(dir, "Support").mkdirs()
        File(dir, "A/B/C/UbisoftConnectInstaller.exe").writeText("x")
        val shallow = File(dir, "Support/UplayInstaller.exe").apply { writeText("x") }
        UbisoftSupport.forgetScans()
        assertEquals(shallow, UbisoftSupport.bundledInstaller(dir))
    }

    @Test
    fun inOneFolderConnectBeatsOtherUbisoftBeatsUplayThenNameOrder() {
        val dir = tmp.newFolder("rank")
        val uplay = File(dir, "UplayInstaller.exe").apply { writeText("x") }
        val uplayUnderscore = File(dir, "Uplay_Installer.exe").apply { writeText("x") }
        val launcher = File(dir, "UbisoftGameLauncherInstaller.exe").apply { writeText("x") }
        val connect = File(dir, "UbisoftConnectInstaller.exe").apply { writeText("x") }
        File(dir, "Redist").mkdirs()
        File(dir, "UplayService.exe").writeText("x")
        File(dir, "Folder Installer Ubisoft.exe").mkdirs()
        UbisoftSupport.forgetScans()
        assertEquals(connect, UbisoftSupport.bundledInstaller(dir))
        connect.delete(); UbisoftSupport.forgetScans()
        assertEquals(launcher, UbisoftSupport.bundledInstaller(dir))
        launcher.delete(); UbisoftSupport.forgetScans()
        // Same rank: by name, "uplay_installer" sorts before "uplayinstaller".
        assertEquals(uplayUnderscore, UbisoftSupport.bundledInstaller(dir))
        uplayUnderscore.delete(); UbisoftSupport.forgetScans()
        assertEquals(uplay, UbisoftSupport.bundledInstaller(dir))
    }

    @Test
    fun theInstallerSearchGoesThreeFoldersDeepAndNoFurther() {
        val dir = tmp.newFolder("limit")
        File(dir, "a/b/c/d").mkdirs()
        File(dir, "a/b/c/d/UplayInstaller.exe").writeText("x")
        UbisoftSupport.forgetScans()
        assertNull(UbisoftSupport.bundledInstaller(dir))
        val ok = File(dir, "a/b/c/Uplay_Installer.exe").apply { writeText("x") }
        UbisoftSupport.forgetScans()
        assertEquals(ok, UbisoftSupport.bundledInstaller(dir))
    }

    @Test
    fun aDepotScanIsReusedUntilItIsForgotten() {
        val dir = tmp.newFolder("scanned")
        assertFalse(UbisoftSupport.hasLauncherFiles(dir))
        File(dir, "uplay_r1_loader.dll").writeText("x")
        // Still the answer from a moment ago: the folders are not walked again for every question.
        assertFalse(UbisoftSupport.hasLauncherFiles(dir))
        UbisoftSupport.forgetScans()
        assertTrue(UbisoftSupport.hasLauncherFiles(dir))
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
        UbisoftSupport.forgetScans()
        val rp = UbisoftSupport.fallbackRunProcesses(dir).single()
        assertEquals("%INSTALLDIR%\\Support\\Uplay\\UplayInstaller.exe", rp.process)
        assertEquals("/S", rp.command)
        assertEquals("Ubisoft Connect PC Client", rp.name)
        assertTrue(UbisoftSupport.isInstaller(rp.process))
        assertFalse(UbisoftSupport.isInstaller("%INSTALLDIR%\\__Installer\\Origin\\redist\\internal\\EAappInstaller.exe"))
    }

    @Test
    fun epicLoaderAndInstallRecordNamesCountAsUbisoft() {
        for (name in listOf("uplay_r1_loader64.dll", "uplay_r2_loader.dll", "upc_r2_loader64.dll", "UPLAY_INSTALL.manifest")) {
            val dir = tmp.newFolder("epic_" + name)
            UbisoftSupport.forgetScans()
            assertFalse(name, UbisoftSupport.hasLauncherFiles(dir))
            File(dir, name).writeText("x")
            UbisoftSupport.forgetScans()
            assertTrue(name, UbisoftSupport.hasLauncherFiles(dir))
        }
        val plain = tmp.newFolder("epic_plain")
        File(plain, "Game.exe").writeText("x")
        File(plain, "Engine/Binaries").mkdirs()
        UbisoftSupport.forgetScans()
        assertFalse(UbisoftSupport.hasLauncherFiles(plain))
    }

    @Test
    fun findsTheInstallerAnEpicBuildKeepsInItsUplayFolder() {
        val dir = tmp.newFolder("acs")
        File(dir, "Uplay").mkdirs()
        File(dir, "Uplay/UplayInstaller.exe").writeText("x")
        UbisoftSupport.forgetScans()
        assertEquals(File(dir, "Uplay/UplayInstaller.exe"), UbisoftSupport.bundledInstaller(dir))
        val rp = UbisoftSupport.fallbackRunProcesses(dir).single()
        assertEquals("%INSTALLDIR%\\Uplay\\UplayInstaller.exe", rp.process)
        assertEquals("/S", rp.command)
    }

    @Test
    fun anEpicBuildWithAnOddlyNamedInstallerIsStillFound() {
        val dir = tmp.newFolder("odd")
        File(dir, "Uplay").mkdirs()
        val exe = File(dir, "Uplay/Ubisoft Connect Installer.exe").apply { writeText("x") }
        UbisoftSupport.forgetScans()
        assertEquals(exe, UbisoftSupport.bundledInstaller(dir))
        assertEquals("%INSTALLDIR%\\Uplay\\Ubisoft Connect Installer.exe", UbisoftSupport.fallbackRunProcesses(dir).single().process)
    }

    @Test
    fun stampValueReadsTheShortcutTag() {
        assertEquals(true, UbisoftSupport.stampValue("1"))
        assertEquals(true, UbisoftSupport.stampValue(" 1 "))
        assertEquals(false, UbisoftSupport.stampValue("0"))
        assertNull(UbisoftSupport.stampValue(""))
        assertNull(UbisoftSupport.stampValue(null))
        assertNull(UbisoftSupport.stampValue("yes"))
    }

    @Test
    fun gameFolderUnderTakesTheFolderBelowTheRoot() {
        fun folder(path: String, root: String = "epic_games") = UbisoftSupport.gameFolderUnder(path, root)?.path?.replace('\\', '/')
        assertEquals(
            "/data/user/0/app/files/imagefs/epic_games/Assassins Creed Syndicate",
            folder("/data/user/0/app/files/imagefs/epic_games/Assassins Creed Syndicate/ACS.exe"),
        )
        assertEquals("/x/Epic_Games/G", folder("/x/Epic_Games/G/Binaries/Win64/g.exe"))
        assertEquals("/x/epic_games/G", folder("\\x\\epic_games\\G\\g.exe"))
        assertNull(folder("/x/steam_games/G/g.exe"))
        assertNull(folder("/x/epic_games"))
        assertNull(folder("/x/epic_games/"))
        assertNull(folder("/x/epic_games_old/G/g.exe"))
    }
}
