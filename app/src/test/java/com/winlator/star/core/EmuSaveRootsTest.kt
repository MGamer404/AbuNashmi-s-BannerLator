package com.winlator.star.core

import com.winlator.star.xenvironment.ImageFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Root selection and app-id lookup for [EmuSaveRoots]. Pure JVM: fake drive_c layouts in a temp
 * dir, laid out the way the emulators leave them on device (DiRT Showdown's RLD-style
 * ProgramData/Steam/elamigos/201700 is the case that lost a career).
 */
class EmuSaveRootsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(base: File, rel: String): File = File(base, rel).apply { mkdirs() }

    private fun file(base: File, rel: String, text: String = "x"): File =
        File(base, rel).apply { parentFile?.mkdirs(); writeText(text) }

    private fun driveC(): File = tmp.newFolder("drive_c").also { dir(it, "users/${ImageFs.USER}") }

    // ---------------------------------------------------------------- outside the profile

    @Test
    fun allDriveCRoots_listsEveryEmulatorAppIdFolder() {
        val c = driveC()
        file(c, "ProgramData/Steam/elamigos/201700/storage/career.bin")
        file(c, "ProgramData/Steam/RLD!/12345/stats.bin")
        file(c, "users/Public/Documents/Steam/CODEX/480/remote/save.dat")
        file(c, "users/Public/Documents/Steam/RUNE/570/achievements.ini")
        file(c, "users/Public/Documents/OnlineFix/999/Saves/a.sav")
        file(c, "users/Public/Documents/EMPRESS/1196590/remote/win64_save/data.bin")

        assertEquals(
            listOf(
                "ProgramData/Steam/RLD!/12345",
                "ProgramData/Steam/elamigos/201700",
                "users/Public/Documents/Steam/CODEX/480",
                "users/Public/Documents/Steam/RUNE/570",
                "users/Public/Documents/OnlineFix/999",
                "users/Public/Documents/EMPRESS/1196590",
            ),
            EmuSaveRoots.allDriveCRoots(c),
        )
    }

    @Test
    fun allDriveCRoots_skipsFoldersNotNamedByAnAppId() {
        val c = driveC()
        file(c, "ProgramData/Steam/elamigos/settings/user.ini")
        file(c, "ProgramData/Steam/elamigos/stray.txt")
        file(c, "ProgramData/Package Cache/123/setup.exe")
        file(c, "users/Public/Documents/Steam/CODEX/0/x")
        file(c, "users/Public/Documents/My Games/123/x")

        assertEquals(emptyList<String>(), EmuSaveRoots.allDriveCRoots(c))
    }

    @Test
    fun allDriveCRoots_emptyPrefix_isEmpty() {
        assertEquals(emptyList<String>(), EmuSaveRoots.allDriveCRoots(driveC()))
    }

    @Test
    fun driveCRootsFor_takesOnlyThisGamesFolders() {
        val c = driveC()
        dir(c, "ProgramData/Steam/elamigos/201700/storage")
        dir(c, "ProgramData/Steam/elamigos/12345")
        dir(c, "ProgramData/Steam/RLD!/201700")
        dir(c, "users/Public/Documents/Steam/CODEX/201700")
        dir(c, "users/Public/Documents/OnlineFix/480")

        assertEquals(
            listOf(
                "ProgramData/Steam/RLD!/201700",
                "ProgramData/Steam/elamigos/201700",
                "users/Public/Documents/Steam/CODEX/201700",
            ),
            EmuSaveRoots.driveCRootsFor(c, 201700),
        )
    }

    @Test
    fun driveCRootsFor_matchesTheAppIdByNumber_leadingZerosAllowed() {
        val c = driveC()
        dir(c, "ProgramData/Steam/elamigos/0000201700")
        assertEquals(listOf("ProgramData/Steam/elamigos/0000201700"), EmuSaveRoots.driveCRootsFor(c, 201700))
    }

    @Test
    fun driveCRoots_lookUpParentsIgnoringCase_andKeepTheOnDiskSpelling() {
        val c = driveC()
        dir(c, "programdata/STEAM/RLD!/201700")
        dir(c, "users/public/documents/onlinefix/201700")
        assertEquals(
            listOf("programdata/STEAM/RLD!/201700", "users/public/documents/onlinefix/201700"),
            EmuSaveRoots.driveCRootsFor(c, 201700),
        )
    }

    // ---------------------------------------------------------------- under the profile

    @Test
    fun profileRootsFor_takesTheGamesAppIdFolders_notIdentityOrOtherGames() {
        val c = driveC()
        val profile = File(c, "users/${ImageFs.USER}")
        dir(profile, "AppData/Roaming/GSE Saves/201700/remote")
        dir(profile, "AppData/Roaming/GSE Saves/999")
        dir(profile, "AppData/Roaming/GSE Saves/settings")
        dir(profile, "AppData/Roaming/Goldberg SteamEmu Saves/201700")
        dir(profile, "AppData/Roaming/EMPRESS/201700")

        assertEquals(
            listOf(
                "AppData/Roaming/GSE Saves/201700",
                "AppData/Roaming/Goldberg SteamEmu Saves/201700",
                "AppData/Roaming/EMPRESS/201700",
            ),
            EmuSaveRoots.profileRootsFor(profile, 201700),
        )
    }

    @Test
    fun profileRootsFor_findsOlderCodexUnderRoamingSteam() {
        // DiRT 3 Complete Edition's codex.ini: %APPDATA%\Steam\CODEX\321040\remote
        val c = driveC()
        val profile = File(c, "users/${ImageFs.USER}")
        file(profile, "AppData/Roaming/Steam/CODEX/321040/remote/savegame@profile0#ngsqmyy")
        file(profile, "AppData/Roaming/Steam/CODEX/321040/achievements.ini")
        dir(profile, "AppData/Roaming/Steam/CODEX/480")
        file(profile, "AppData/Roaming/Steam/settings/321040.ini")
        dir(profile, "AppData/Roaming/GSE Saves/321040")

        assertEquals(
            listOf(
                "AppData/Roaming/GSE Saves/321040",
                "AppData/Roaming/Steam/CODEX/321040",
            ),
            EmuSaveRoots.profileRootsFor(profile, 321040),
        )
    }

    @Test
    fun includedFor_givesBothKinds_drivecRelative() {
        val c = driveC()
        dir(c, "users/${ImageFs.USER}/AppData/Roaming/GSE Saves/201700")
        dir(c, "ProgramData/Steam/elamigos/201700")
        assertEquals(
            listOf(
                "users/${ImageFs.USER}/AppData/Roaming/GSE Saves/201700",
                "ProgramData/Steam/elamigos/201700",
            ),
            EmuSaveRoots.includedFor(c, 201700),
        )
    }

    // ---------------------------------------------------------------- the game's app id

    @Test
    fun appId_fromTheShortcutExtra_winsOverTheFiles() {
        val exeDir = tmp.newFolder("DiRT Showdown")
        file(exeDir, "steam_api.ini", "[Steam]\nAppId=0000201700\n")
        assertEquals(1234, EmuSaveRoots.appIdFor(" 1234 ", exeDir))
    }

    @Test
    fun appId_blankOrZeroExtra_fallsBackToTheFiles() {
        val exeDir = tmp.newFolder("Game")
        file(exeDir, "steam_appid.txt", "480\n")
        assertEquals(480, EmuSaveRoots.appIdFor("", exeDir))
        assertEquals(480, EmuSaveRoots.appIdFor("0", exeDir))
        assertEquals(480, EmuSaveRoots.appIdFor(null, exeDir))
    }

    @Test
    fun appId_fromRldStyleSteamApiIni_leadingZerosAllowed() {
        val exeDir = tmp.newFolder("DiRT Showdown")
        file(
            exeDir, "steam_api.ini",
            "###\n### Stats and achievements: C:\\ProgramData\\Steam\\%UserName%\\%Appid%\\\n###\n" +
                "[Steam]\nAppId=0000201700\n\n[Settings]\nUserName=elamigos\n",
        )
        assertEquals(201700, EmuSaveRoots.appIdFor("", exeDir))
    }

    @Test
    fun appId_fromSteamEmuIni() {
        val exeDir = tmp.newFolder("Codex")
        file(exeDir, "steam_emu.ini", "[Settings]\n; AppId=1\nAppId=570\n")
        assertEquals(570, EmuSaveRoots.appIdFor("", exeDir))
    }

    @Test
    fun appId_fromSteamAppIdTxt() {
        val exeDir = tmp.newFolder("Goldberg")
        file(exeDir, "steam_appid.txt", "\uFEFF1623730")
        assertEquals(1623730, EmuSaveRoots.appIdFor("", exeDir))
    }

    @Test
    fun appId_iniBeatsSteamAppIdTxt() {
        val exeDir = tmp.newFolder("Both")
        file(exeDir, "steam_appid.txt", "480")
        file(exeDir, "steam_api.ini", "[Steam]\nAppId=201700\n")
        assertEquals(201700, EmuSaveRoots.appIdFor("", exeDir))
    }

    @Test
    fun appId_onlyReadsTheExeFolder_notItsParents() {
        val parent = tmp.newFolder("Games")
        file(parent, "steam_appid.txt", "480")
        val exeDir = dir(parent, "Some Game")
        assertNull(EmuSaveRoots.appIdFor("", exeDir))
    }

    @Test
    fun appId_nothingToGoOn_isNull() {
        assertNull(EmuSaveRoots.appIdFor("", tmp.newFolder("Empty")))
        assertNull(EmuSaveRoots.appIdFor("", null))
        assertNull(EmuSaveRoots.appIdFor("not a number", File(tmp.root, "missing")))
    }
}
