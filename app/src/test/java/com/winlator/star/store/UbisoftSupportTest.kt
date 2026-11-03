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

    @Test
    fun onlyAnUncheckedZeroStampIsLookedAtAgain() {
        assertTrue(UbisoftSupport.stampNeedsRecheck(false, alreadyChecked = false))
        assertFalse(UbisoftSupport.stampNeedsRecheck(false, alreadyChecked = true))
        assertFalse(UbisoftSupport.stampNeedsRecheck(true, alreadyChecked = false))
        assertFalse(UbisoftSupport.stampNeedsRecheck(true, alreadyChecked = true))
        assertFalse(UbisoftSupport.stampNeedsRecheck(null, alreadyChecked = false))
        assertFalse(UbisoftSupport.stampNeedsRecheck(null, alreadyChecked = true))
    }

    @Test
    fun stampForStampsZeroOnlyWhenTheFolderHoldsFiles() {
        assertEquals("1", UbisoftSupport.stampFor(folderHasFiles = true, ubisoft = true))
        assertEquals("1", UbisoftSupport.stampFor(folderHasFiles = false, ubisoft = true))
        assertEquals("0", UbisoftSupport.stampFor(folderHasFiles = true, ubisoft = false))
        assertNull(UbisoftSupport.stampFor(folderHasFiles = false, ubisoft = false))
    }

    @Test
    fun stampForFolderLeavesAnEmptyOrMissingFolderUnset() {
        UbisoftSupport.forgetScans()
        val empty = tmp.newFolder("empty")
        val onlyDirs = tmp.newFolder("onlyDirs")
        File(onlyDirs, "Bin").mkdirs()
        val plain = tmp.newFolder("plain")
        File(plain, "game.exe").writeText("x")
        val ubisoft = tmp.newFolder("ubisoft")
        File(ubisoft, "uplay_r1_loader.dll").writeText("x")
        assertNull(UbisoftSupport.stampForFolder(null))
        assertNull(UbisoftSupport.stampForFolder(File(tmp.root, "missing")))
        assertNull(UbisoftSupport.stampForFolder(empty))
        assertNull(UbisoftSupport.stampForFolder(onlyDirs))
        assertEquals("0", UbisoftSupport.stampForFolder(plain))
        assertEquals("1", UbisoftSupport.stampForFolder(ubisoft))
    }

    @Test
    fun dropStaleStampRemovesOnlyTheZeroLine() {
        val text = "[Desktop Entry]\nName=G\n\n[Extra Data]\nstoreSource=epic\nubisoft=0\nepicEos=1\n"
        assertEquals("[Desktop Entry]\nName=G\n\n[Extra Data]\nstoreSource=epic\nepicEos=1\n", UbisoftSupport.dropStaleStamp(text))
    }

    @Test
    fun dropStaleStampKeepsAOneAndAnythingElse() {
        val one = "[Extra Data]\nstoreSource=epic\nubisoft=1\n"
        assertEquals(one, UbisoftSupport.dropStaleStamp(one))
        val none = "[Extra Data]\nstoreSource=epic\n"
        assertEquals(none, UbisoftSupport.dropStaleStamp(none))
        val similar = "[Extra Data]\nxubisoft=0\nubisoft_note=0\nubisoft=yes\n"
        assertEquals(similar, UbisoftSupport.dropStaleStamp(similar))
        assertEquals("", UbisoftSupport.dropStaleStamp(""))
    }

    @Test
    fun dropStaleStampHandlesCrLfAndAMissingFinalNewline() {
        assertEquals("a=1\r\nb=2", UbisoftSupport.dropStaleStamp("a=1\r\nubisoft=0\r\nb=2"))
        assertEquals("a=1", UbisoftSupport.dropStaleStamp("a=1\nubisoft=0"))
        assertEquals("a=1\n", UbisoftSupport.dropStaleStamp("a=1\nubisoft=0\n"))
    }

    // ---- Epic-like detection ----------------------------------------------------------------------

    private val epicExe = "Z:\\epic_games\\Assassins Creed Syndicate\\ACS.exe"

    @Test
    fun anEpicStoreTagIsEpicWhateverThePath() {
        assertTrue(UbisoftSupport.isEpicLike("epic", epicExe))
        assertTrue(UbisoftSupport.isEpicLike("epic", "C:\\Games\\X\\x.exe"))
        assertTrue(UbisoftSupport.isEpicLike("epic", ""))
    }

    @Test
    fun aCustomOrUntaggedShortcutUnderEpicGamesIsEpicLike() {
        assertTrue(UbisoftSupport.isEpicLike("custom", epicExe))
        assertTrue(UbisoftSupport.isEpicLike("", epicExe))
        assertTrue(UbisoftSupport.isEpicLike("custom", "Z:/epic_games/Assassins Creed Syndicate/ACS.exe"))
        assertTrue(UbisoftSupport.isEpicLike("", "/data/user/0/app/files/imagefs/epic_games/G/Bin/g.exe"))
    }

    @Test
    fun epicLikePathMatchingIgnoresCaseAndNeedsAGameFolder() {
        assertTrue(UbisoftSupport.isEpicLike("custom", "Z:\\EPIC_GAMES\\Assassins Creed Syndicate\\ACS.EXE"))
        assertTrue(UbisoftSupport.isEpicLike("CUSTOM", epicExe))
        assertFalse(UbisoftSupport.isEpicLike("custom", "Z:\\epic_games"))
        assertFalse(UbisoftSupport.isEpicLike("custom", "Z:\\epic_games\\"))
        assertFalse(UbisoftSupport.isEpicLike("custom", "Z:\\epic_games_old\\G\\g.exe"))
    }

    @Test
    fun aCustomOrUntaggedShortcutElsewhereIsNotEpicLike() {
        assertFalse(UbisoftSupport.isEpicLike("custom", "C:\\Games\\Assassins Creed Syndicate\\ACS.exe"))
        assertFalse(UbisoftSupport.isEpicLike("", "C:\\Games\\Assassins Creed Syndicate\\ACS.exe"))
        assertFalse(UbisoftSupport.isEpicLike("", ""))
    }

    @Test
    fun anotherStoresTagIsNeverEpicLikeEvenUnderEpicGames() {
        for (tag in listOf("steam", "gog", "amazon", "xbox", "xbox:abc", "android", "Steam", "ea", "origin")) {
            assertFalse("storeSource=$tag", UbisoftSupport.isEpicLike(tag, epicExe))
        }
    }

    @Test
    fun aSteamInstallFolderIsNotEpicLikeEvenWithEpicGamesInItsPath() {
        assertFalse(UbisoftSupport.isEpicLike("steam", "Z:\\steam_games\\epic_games\\G\\g.exe"))
        assertFalse(UbisoftSupport.isEpicLike("", "Z:\\steam_games\\epic_games\\G\\g.exe"))
        assertFalse(UbisoftSupport.isEpicLike("custom", "Z:\\steam_games\\epic_games\\G\\g.exe"))
        assertFalse(UbisoftSupport.isEpicLike("", "G:\\steamapps\\common\\epic_games\\G\\g.exe"))
        assertFalse(UbisoftSupport.isEpicLike("steam", "Z:\\games\\epic_games\\steamapps\\common\\G\\g.exe"))
    }

    @Test
    fun theEpicLikeReasonNamesTheTagAndThePath() {
        val under = UbisoftSupport.epicLikeReason("custom", epicExe)
        assertTrue(under.first)
        assertTrue(under.second, under.second.contains("custom") && under.second.contains("epic_games") && under.second.contains("Assassins Creed Syndicate"))
        assertEquals("storeSource=epic", UbisoftSupport.epicLikeReason("epic", "").second)
        val elsewhere = UbisoftSupport.epicLikeReason("custom", "C:\\Games\\x.exe")
        assertFalse(elsewhere.first)
        assertEquals("storeSource is custom and the exe path is not under epic_games", elsewhere.second)
        assertEquals("storeSource is empty and the exe path is not under epic_games", UbisoftSupport.epicLikeReason("", "C:\\x.exe").second)
        assertEquals("storeSource='steam' is another store's tag", UbisoftSupport.epicLikeReason("steam", epicExe).second)
    }

    // ---- Launch report route -----------------------------------------------------------------------

    @Test
    fun theReportRouteNeedsAnEpicLikeGameAndEitherTheStampOrARecordedInstall() {
        val skipped = UbisoftInstallRegistry.Result.SKIPPED_NOT_UBISOFT
        assertTrue(UbisoftSupport.epicRouteDecision(true, true, null))
        assertTrue(UbisoftSupport.epicRouteDecision(true, true, skipped))
        assertFalse(UbisoftSupport.epicRouteDecision(true, false, skipped))
        assertFalse(UbisoftSupport.epicRouteDecision(true, null, skipped))
        assertFalse(UbisoftSupport.epicRouteDecision(true, null, null))
        // A wrong 0 stamp cannot hide the report when the install record found a state file or folder.
        assertTrue(UbisoftSupport.epicRouteDecision(true, false, UbisoftInstallRegistry.Result.REGISTERED))
        assertTrue(UbisoftSupport.epicRouteDecision(true, false, UbisoftInstallRegistry.Result.ALREADY_PRESENT))
        assertTrue(UbisoftSupport.epicRouteDecision(true, null, UbisoftInstallRegistry.Result.NO_STATE_FILE))
        // Not an Epic-like game: never.
        assertFalse(UbisoftSupport.epicRouteDecision(false, true, UbisoftInstallRegistry.Result.REGISTERED))
        assertFalse(UbisoftSupport.epicRouteDecision(false, true, null))
    }

    // ---- Launch decisions note ---------------------------------------------------------------------

    @Test
    fun aUbisoftMarkerInThePathIsFoundWithEitherSeparatorAndAnyCase() {
        assertTrue(UbisoftSupport.pathHasUbisoftMarker("Z:\\Games\\X\\uplay_r1_loader.dll"))
        assertTrue(UbisoftSupport.pathHasUbisoftMarker("C:\\Program Files (x86)\\Ubisoft\\Ubisoft Game Launcher\\UPC.exe"))
        assertTrue(UbisoftSupport.pathHasUbisoftMarker("/x/Uplay/UplayInstaller.exe"))
        assertFalse(UbisoftSupport.pathHasUbisoftMarker("Z:\\steam_games\\Portal 2\\portal2.exe"))
        assertFalse(UbisoftSupport.pathHasUbisoftMarker("Z:\\games\\upcoming\\upcoming.exe"))
        assertFalse(UbisoftSupport.pathHasUbisoftMarker(""))
    }

    @Test
    fun aLaunchGetsANoteOnlyWhenSomethingUbisoftOrEpicLikeApplies() {
        assertNull(UbisoftSupport.decisionsTrigger(false, false, false, false, null))
        assertEquals("Epic-like shortcut", UbisoftSupport.decisionsTrigger(true, false, false, false, null))
        assertEquals("storeSource=epic", UbisoftSupport.decisionsTrigger(false, true, false, false, null))
        assertEquals("Ubisoft launcher chain armed", UbisoftSupport.decisionsTrigger(false, false, true, false, null))
        assertEquals("Ubisoft file name in the exe path", UbisoftSupport.decisionsTrigger(false, false, false, true, null))
        assertEquals("Ubisoft launch report route 'steam'", UbisoftSupport.decisionsTrigger(false, false, false, false, "steam"))
        assertEquals(
            "Epic-like shortcut, storeSource=epic, Ubisoft launch report route 'epic'",
            UbisoftSupport.decisionsTrigger(true, true, false, false, "epic"),
        )
    }

    @Test
    fun theNoReportReasonSaysWhy() {
        assertEquals(
            "storeSource is custom and the exe path is not under epic_games, and no Ubisoft launcher chain in a Steam launch plan",
            UbisoftSupport.noRouteReason("custom", "C:\\Games\\x.exe", "", false, false),
        )
        assertEquals(
            "storeSource is custom and the exe path is under epic_games (game folder 'G'); ubisoft stamp=0 after recheck and the install record was SKIPPED_NOT_UBISOFT",
            UbisoftSupport.noRouteReason("custom", "Z:\\epic_games\\G\\g.exe", "0", false, true),
        )
        assertEquals(
            "storeSource=epic; ubisoft stamp=unset",
            UbisoftSupport.noRouteReason("epic", "Z:\\epic_games\\G\\g.exe", "", false, false),
        )
        assertEquals("the Ubisoft launcher chain is armed but the report was not started", UbisoftSupport.noRouteReason("steam", "", "", true, false))
    }

    private fun facts(route: String?) = UbisoftSupport.LaunchFacts(
        timestamp = "2026-11-03 14:02:11", trigger = "Epic-like shortcut",
        app = "3.1.8 (versionCode 318)", flavor = "standard", debug = false, commit = "none",
        shortcutFile = "/c/desktop/Assassins Creed Syndicate.desktop", name = "Assassins Creed Syndicate", exe = epicExe,
        storeSource = "custom", epicAppName = "", epicSandboxId = "", epicEos = "", ubisoft = "", launchMode = "", steamAppId = "",
        strictEpic = false, epicLike = true, epicLikeWhy = "storeSource is custom and the exe path is under epic_games (game folder 'Assassins Creed Syndicate')",
        installDir = "/imagefs/epic_games/Assassins Creed Syndicate", installRoute = "the game folder below epic_games on the exe path",
        hasLauncherFiles = "true", stateFile = "present, product id 1875 (Assassin's Creed Syndicate)",
        launcherInstalled = "launcherInstalled=false (upc.exe=missing)", registration = "REGISTERED: 11 of 11 value(s) written",
        route = route, routeWhy = "not needed",
    )

    @Test
    fun theDecisionsNoteListsEveryFact() {
        val text = UbisoftSupport.launchDecisionsText(facts("epic"))
        for (part in listOf(
            "timestamp: 2026-11-03 14:02:11", "written because: Epic-like shortcut", "app: 3.1.8 (versionCode 318) flavor=standard debug=false",
            "commit: none", "shortcut file: /c/desktop/Assassins Creed Syndicate.desktop",
            "storeSource='custom'  epicAppName=''  epicSandboxId=''  epicEos=''  ubisoft=''  launchMode=''  steamAppId=''",
            "(storeSource=epic, the old isEpicShortcut): false", "): true - storeSource is custom and the exe path is under epic_games",
            "install folder: /imagefs/epic_games/Assassins Creed Syndicate  (found by: the game folder below epic_games on the exe path)",
            "hasLauncherFiles(install folder): true", "uplay_install.state: present, product id 1875",
            "Ubisoft Connect in the prefix: launcherInstalled=false", "install record (UbisoftInstallRegistry.last): REGISTERED: 11 of 11",
            "Ubisoft launch report route: epic\n",
        )) assertTrue("missing '$part' in:\n$text", text.contains(part))
        assertFalse(text.contains("not needed"))
    }

    @Test
    fun theDecisionsNoteExplainsAMissingReportRoute() {
        val text = UbisoftSupport.launchDecisionsText(facts(null))
        assertTrue(text, text.contains("Ubisoft launch report route: none - not needed\n"))
    }
}
