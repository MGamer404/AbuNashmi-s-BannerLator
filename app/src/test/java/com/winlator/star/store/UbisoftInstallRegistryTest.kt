package com.winlator.star.store

import com.winlator.star.store.UbisoftInstallRegistry.RegWrite
import com.winlator.star.store.UbisoftInstallRegistry.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [UbisoftInstallRegistry]: the writes built from the real Syndicate state file (compared with what Locate wrote,
 * captured from a device's `system.reg`), the `Z:` path, and the one-pass check of a hive's text.
 */
class UbisoftInstallRegistryTest {

    private val game = "Z:/epic_games/Assassins Creed Syndicate/"

    private fun state(): UbisoftInstallState =
        UbisoftInstallState.parse(javaClass.getResourceAsStream("/ubisoft/uplay_install.state")!!.use { it.readBytes() })!!

    private fun writes(): List<RegWrite> = UbisoftInstallRegistry.installWrites(state(), game)

    /** `system.reg` text as Wine writes it after Locate (the device capture), plus the native view of the game's key. */
    private fun locatedHive(): String = """
[Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Uplay Install 1875] 1793632643
#time=1dd6add431606e2
"DisplayIcon"="C:/Program Files (x86)/Ubisoft/Ubisoft Game Launcher/data/2cc31c01f09cd8f98ce44c69f60a575b.ico"
"DisplayName"="Assassin's Creed Syndicate"
"DisplayVersion"=""
"HelpLink"="https://support.ubi.com"
"InstallLocation"="Z:/epic_games/Assassins Creed Syndicate/"
"NoModify"=dword:00000001
"NoRepair"=dword:00000001
"Publisher"="Ubisoft"
"UninstallString"="\"C:\\Program Files (x86)\\Ubisoft\\Ubisoft Game Launcher\\upc.exe\" uplay://uninstall/1875"
"URLInfoAbout"="http://www.ubisoft.com/"

[Software\\Ubisoft\\Assassin's Creed Syndicate] 1793633389
#time=1dd6adeff9f6ac8
"Language"="English(US)"

[Software\\Wow6432Node\\Ubisoft\\Assassin's Creed Syndicate] 1793633389
#time=1dd6adeff9f6ac8
"Language"="English(US)"

[Software\\Wow6432Node\\Ubisoft\\Launcher] 1793630773
#time=1dd6ad8e82cadc0
"InstallDir"="C:\\Program Files (x86)\\Ubisoft\\Ubisoft Game Launcher\\"
"Installer Language"="1033"
"Version"="13393"

[Software\\Wow6432Node\\Ubisoft\\Launcher\\Installs\\1875] 1793632641
#time=1dd6add41b1b6d4
"InstallDir"="Z:/epic_games/Assassins Creed Syndicate/"
"Language"="en-US"

[Software\\Wow6432Node\\Valve\\Steam] 1793628533
#time=1dd6ad3b139d090
"InstallPath"="C:\\Program Files (x86)\\Steam"
""".trimIndent()

    private fun valueOf(key: String, name: String): RegWrite? = writes().firstOrNull { it.key == key && it.name == name }

    // ---- The writes -----------------------------------------------------------------------------

    @Test
    fun theLauncherInstallRecordIsWritten() {
        val key = "Software\\Wow6432Node\\Ubisoft\\Launcher\\Installs\\1875"
        assertEquals(RegWrite(key, "InstallDir", Type.PATH, game), valueOf(key, "InstallDir"))
        assertEquals(RegWrite(key, "Language", Type.STRING, "en-US"), valueOf(key, "Language"))
        assertEquals(2, writes().count { it.key == key })
    }

    @Test
    fun theUninstallEntryHasTheValuesLocateWroteWithoutTheIcon() {
        val key = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Uplay Install 1875"
        val mine = writes().filter { it.key == key }.associate { it.name to it }
        assertEquals(
            setOf(
                "DisplayName", "DisplayVersion", "HelpLink", "InstallLocation", "NoModify", "NoRepair", "Publisher",
                "UninstallString", "URLInfoAbout",
            ),
            mine.keys,
        )
        assertEquals("Assassin's Creed Syndicate", mine.getValue("DisplayName").data)
        assertEquals("", mine.getValue("DisplayVersion").data)
        assertEquals("https://support.ubi.com", mine.getValue("HelpLink").data)
        assertEquals(RegWrite(key, "InstallLocation", Type.PATH, game), mine.getValue("InstallLocation"))
        assertEquals(RegWrite(key, "NoModify", Type.DWORD, "1"), mine.getValue("NoModify"))
        assertEquals(RegWrite(key, "NoRepair", Type.DWORD, "1"), mine.getValue("NoRepair"))
        assertEquals("Ubisoft", mine.getValue("Publisher").data)
        assertEquals(
            "\"C:\\Program Files (x86)\\Ubisoft\\Ubisoft Game Launcher\\upc.exe\" uplay://uninstall/1875",
            mine.getValue("UninstallString").data,
        )
        assertEquals("http://www.ubisoft.com/", mine.getValue("URLInfoAbout").data)
    }

    @Test
    fun theGamesLanguageValueGoesToBothViews() {
        assertEquals(
            RegWrite("Software\\Wow6432Node\\Ubisoft\\Assassin's Creed Syndicate", "Language", Type.STRING, "English(US)"),
            valueOf("Software\\Wow6432Node\\Ubisoft\\Assassin's Creed Syndicate", "Language"),
        )
        assertEquals(
            RegWrite("Software\\Ubisoft\\Assassin's Creed Syndicate", "Language", Type.STRING, "English(US)"),
            valueOf("Software\\Ubisoft\\Assassin's Creed Syndicate", "Language"),
        )
        assertEquals(2 + 9 + 2, writes().size)
    }

    @Test
    fun theGamesLanguageFollowsTheInstallLanguage() {
        val fr = state().copy(language = "fr-FR")
        val out = UbisoftInstallRegistry.installWrites(fr, game)
        assertEquals("fr-FR", out.first { it.name == "Language" && it.key.endsWith("\\1875") }.data)
        assertEquals("French(France)", out.first { it.key == "Software\\Ubisoft\\Assassin's Creed Syndicate" }.data)
    }

    @Test
    fun noGameValueWhenTheStateHasNoEntryForTheLanguageOrAnOddPath() {
        val noEntry = state().copy(language = "xx-XX")
        assertEquals(2 + 9, UbisoftInstallRegistry.installWrites(noEntry, game).size)
        val odd = state().copy(
            registry = listOf(
                UbisoftInstallState.RegistryEntry("HKEY_CURRENT_USER\\Software\\Ubisoft\\G\\Language", "en-US", "English(US)"),
            ),
        )
        assertEquals(2 + 9, UbisoftInstallRegistry.installWrites(odd, game).size)
        val shallow = state().copy(
            registry = listOf(UbisoftInstallState.RegistryEntry("HKEY_LOCAL_MACHINE\\SOFTWARE\\Language", "en-US", "x")),
        )
        assertEquals(2 + 9, UbisoftInstallRegistry.installWrites(shallow, game).size)
    }

    @Test
    fun theGamePathIsMappedFromTheStatesRegistryPathWhateverItsCase() {
        val s = state().copy(
            registry = listOf(
                UbisoftInstallState.RegistryEntry("hkey_local_machine\\software\\Ubisoft\\Game X\\Language", "en-US", "English(US)"),
            ),
        )
        val out = UbisoftInstallRegistry.installWrites(s, game)
        assertEquals("English(US)", out.first { it.key == "Software\\Wow6432Node\\Ubisoft\\Game X" }.data)
        assertEquals("English(US)", out.first { it.key == "Software\\Ubisoft\\Game X" }.data)
    }

    @Test
    fun aValueWithALineBreakIsLeftOut() {
        val s = state().copy(name = "Bad\nName", displayName = "Bad\nName")
        val out = UbisoftInstallRegistry.installWrites(s, game)
        assertTrue(out.none { it.name == "DisplayName" })
        assertEquals(2 + 8 + 2, out.size)
    }

    // ---- The Z: path ----------------------------------------------------------------------------

    @Test
    fun theZPathUsesForwardSlashesAndEndsWithOne() {
        val root = File("/data/user/0/app/files/imagefs")
        assertEquals(game, UbisoftInstallRegistry.zPathOf(File(root, "epic_games/Assassins Creed Syndicate"), root))
        assertEquals(game, UbisoftInstallRegistry.zPathOf(File(root, "epic_games/Assassins Creed Syndicate/"), root))
    }

    @Test
    fun aFolderOutsideTheImageFsRootHasNoZPath() {
        val root = File("/data/user/0/app/files/imagefs")
        assertNull(UbisoftInstallRegistry.zPathOf(File("/storage/emulated/0/Games/Syndicate"), root))
        assertNull(UbisoftInstallRegistry.zPathOf(File("/data/user/0/app/files/imagefs2/epic_games/x"), root))
        assertNull(UbisoftInstallRegistry.zPathOf(root, root))
    }

    // ---- Checking the hive ----------------------------------------------------------------------

    @Test
    fun aHiveThatHasEverythingNeedsNoWrite() {
        assertEquals(emptyList<RegWrite>(), UbisoftInstallRegistry.pendingWrites(locatedHive(), writes()))
    }

    @Test
    fun anEmptyHiveNeedsEveryWrite() {
        assertEquals(writes(), UbisoftInstallRegistry.pendingWrites("", writes()))
        assertEquals(writes(), UbisoftInstallRegistry.pendingWrites(
            "[Software\\\\Wow6432Node\\\\Valve\\\\Steam] 1\n#time=1\n\"InstallPath\"=\"C:\\\\Steam\"\n", writes()))
    }

    @Test
    fun aMissingNativeViewIsTheOnlyPendingWrite() {
        val hive = locatedHive().replace(
            "[Software\\\\Ubisoft\\\\Assassin's Creed Syndicate] 1793633389\n#time=1dd6adeff9f6ac8\n\"Language\"=\"English(US)\"\n\n", "")
        assertEquals(
            listOf(RegWrite("Software\\Ubisoft\\Assassin's Creed Syndicate", "Language", Type.STRING, "English(US)")),
            UbisoftInstallRegistry.pendingWrites(hive, writes()),
        )
    }

    @Test
    fun anotherInstallDirIsPendingForBothPathValues() {
        val hive = locatedHive().replace("Z:/epic_games/Assassins Creed Syndicate/", "Z:/epic_games/Moved/")
        assertEquals(
            listOf("InstallDir", "InstallLocation"),
            UbisoftInstallRegistry.pendingWrites(hive, writes()).map { it.name },
        )
    }

    @Test
    fun aPathWrittenInAnotherSpellingCountsAsTheSame() {
        val hive = locatedHive().replace(
            "\"Z:/epic_games/Assassins Creed Syndicate/\"", "\"Z:\\\\epic_games\\\\assassins creed syndicate\"")
        assertEquals(emptyList<RegWrite>(), UbisoftInstallRegistry.pendingWrites(hive, writes()))
    }

    @Test
    fun anotherValueIsPending() {
        val hive = locatedHive().replace("\"Language\"=\"en-US\"", "\"Language\"=\"fr-FR\"")
        assertEquals(listOf("Language"), UbisoftInstallRegistry.pendingWrites(hive, writes()).map { it.name })
        val dword = locatedHive().replace("\"NoModify\"=dword:00000001", "\"NoModify\"=dword:00000000")
        assertEquals(listOf("NoModify"), UbisoftInstallRegistry.pendingWrites(dword, writes()).map { it.name })
    }

    @Test
    fun aValueInAnotherKeyDoesNotCount() {
        // "Language" lives under Launcher\Installs\1875 and the game's key; the Launcher key's own values are not it.
        val hive = locatedHive().replace(
            "[Software\\\\Wow6432Node\\\\Ubisoft\\\\Launcher\\\\Installs\\\\1875] 1793632641\n#time=1dd6add41b1b6d4\n" +
                "\"InstallDir\"=\"Z:/epic_games/Assassins Creed Syndicate/\"\n\"Language\"=\"en-US\"\n", "")
        val pending = UbisoftInstallRegistry.pendingWrites(hive, writes())
        assertEquals(listOf("InstallDir", "Language"), pending.map { it.name })
        assertTrue(pending.all { it.key.endsWith("\\Installs\\1875") })
    }

    @Test
    fun keyAndValueNamesCompareIgnoringCase() {
        val hive = locatedHive().replace("Wow6432Node", "WOW6432NODE").replace("\"InstallDir\"", "\"installdir\"")
        assertFalse(UbisoftInstallRegistry.pendingWrites(hive, writes()).any { it.name == "InstallDir" && it.key.endsWith("1875") })
    }

    @Test
    fun theCheckStopsReadingOnceEverythingIsFound() {
        // Nothing after the last needed value is read: a reader that fails past it would throw if it were.
        val full = locatedHive()
        val cut = full.indexOf("[Software\\\\Wow6432Node\\\\Valve")
        val reader = object : java.io.BufferedReader(java.io.StringReader(full)) {
            override fun readLine(): String? {
                val line = super.readLine()
                if (line != null && line.startsWith("[Software\\\\Wow6432Node\\\\Valve")) throw AssertionError("read past the last needed key at $cut")
                return line
            }
        }
        assertEquals(emptyList<RegWrite>(), UbisoftInstallRegistry.pendingWrites(reader, writes()))
    }
}
