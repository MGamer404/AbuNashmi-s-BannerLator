package com.winlator.star.core

import com.winlator.star.core.GameSaveBackup.BackupLayout
import com.winlator.star.xenvironment.ImageFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * What [GameSaveBackup] puts in a zip and where a restore puts it back, on fake prefixes in a temp
 * dir. Covers the emulator save folders outside the profile ([EmuSaveRoots]) — stored and restored
 * under their own drive_c path — and that archives made before them restore exactly as they did.
 */
class GameSaveBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val user = ImageFs.USER

    /** A container root with an empty Wine profile; its drive_c is `<root>/.wine/drive_c`. */
    private fun container(name: String): File =
        tmp.newFolder(name).also { File(it, ".wine/drive_c/users/$user").mkdirs() }

    private fun driveC(root: File) = File(root, ".wine/drive_c")

    private fun file(base: File, rel: String, text: String = rel): File =
        File(base, rel).apply { parentFile?.mkdirs(); writeText(text) }

    private fun entries(zip: File): Set<String> = ZipFile(zip).use { z -> z.entries().toList().map { it.name }.toSet() }

    private fun zipOf(vararg items: Pair<String, String>): File {
        val out = File(tmp.newFolder(), "backup.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            for ((name, text) in items) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return out
    }

    private fun restore(zip: File, root: File): GameSaveBackup.RestoreResult =
        GameSaveBackup.restoreInto(FileInputStream(zip), root, File(tmp.root, "staging"))

    /** DiRT Showdown's prefix as found on device, plus another game's and a little noise. */
    private fun dirtShowdownPrefix(name: String = "src"): File {
        val root = container(name)
        val c = driveC(root)
        file(c, "users/$user/Documents/My Games/DiRT Showdown/hardwaresettings/hardware_settings_config.xml")
        file(c, "users/$user/AppData/Local/Temp/crash.tmp")
        file(c, "users/$user/AppData/Roaming/FLT/steam_id.txt", "76561198000000001")
        file(c, "users/$user/AppData/Roaming/GSE Saves/201700/remote/profile.sav")
        file(c, "users/$user/AppData/Roaming/GSE Saves/999/remote/other.sav")
        file(c, "ProgramData/Steam/elamigos/201700/storage/career.bin")
        file(c, "ProgramData/Steam/elamigos/201700/stats/stats.bin")
        file(c, "ProgramData/Steam/elamigos/201700/last.txt")
        file(c, "ProgramData/Steam/elamigos/12345/storage/other.bin")
        file(c, "ProgramData/Package Cache/setup.log")
        file(c, "users/Public/Documents/Steam/CODEX/480/remote/save.dat")
        return root
    }

    // ---------------------------------------------------------------- backup

    @Test
    fun wholeContainer_takesEveryEmulatorFolder_underItsOwnDriveCPath() {
        val c = driveC(dirtShowdownPrefix())
        val zip = File(tmp.root, "whole.zip")
        val r = GameSaveBackup.backupDriveC(c, null, BackupLayout.GAMEHUB, zip)

        assertTrue(r.ok)
        assertEquals(
            setOf(
                "/drive_c/users/steamuser/Documents/My Games/DiRT Showdown/hardwaresettings/hardware_settings_config.xml",
                "/drive_c/users/steamuser/AppData/Roaming/FLT/steam_id.txt",
                "/drive_c/users/steamuser/AppData/Roaming/GSE Saves/201700/remote/profile.sav",
                "/drive_c/users/steamuser/AppData/Roaming/GSE Saves/999/remote/other.sav",
                "/drive_c/ProgramData/Steam/elamigos/201700/storage/career.bin",
                "/drive_c/ProgramData/Steam/elamigos/201700/stats/stats.bin",
                "/drive_c/ProgramData/Steam/elamigos/201700/last.txt",
                "/drive_c/ProgramData/Steam/elamigos/12345/storage/other.bin",
                "/drive_c/users/Public/Documents/Steam/CODEX/480/remote/save.dat",
            ),
            entries(zip),
        )
        assertEquals(9, r.fileCount)
    }

    @Test
    fun winlatorLayout_rootsEmulatorFoldersAtDriveC() {
        val c = driveC(dirtShowdownPrefix())
        val zip = File(tmp.root, "whole.zip")
        GameSaveBackup.backupDriveC(c, null, BackupLayout.WINLATOR, zip)

        val names = entries(zip)
        assertTrue("drive_c/ProgramData/Steam/elamigos/201700/storage/career.bin" in names)
        assertTrue("drive_c/users/Public/Documents/Steam/CODEX/480/remote/save.dat" in names)
        assertTrue("drive_c/users/$user/Documents/My Games/DiRT Showdown/hardwaresettings/hardware_settings_config.xml" in names)
    }

    @Test
    fun perGame_withAppId_addsOnlyThatGamesEmulatorFolders() {
        val c = driveC(dirtShowdownPrefix())
        val zip = File(tmp.root, "game.zip")
        val r = GameSaveBackup.backupDriveC(c, listOf("Documents/My Games/DiRT Showdown"), BackupLayout.WINLATOR, zip, 201700)

        assertTrue(r.ok)
        assertEquals(
            setOf(
                "drive_c/users/$user/Documents/My Games/DiRT Showdown/hardwaresettings/hardware_settings_config.xml",
                "drive_c/users/$user/AppData/Roaming/FLT/steam_id.txt",
                "drive_c/users/$user/AppData/Roaming/GSE Saves/201700/remote/profile.sav",
                "drive_c/ProgramData/Steam/elamigos/201700/storage/career.bin",
                "drive_c/ProgramData/Steam/elamigos/201700/stats/stats.bin",
                "drive_c/ProgramData/Steam/elamigos/201700/last.txt",
            ),
            entries(zip),
        )
    }

    @Test
    fun perGame_withoutAppId_isWhatItWasBefore() {
        val c = driveC(dirtShowdownPrefix())
        val zip = File(tmp.root, "game.zip")
        GameSaveBackup.backupDriveC(c, listOf("Documents/My Games/DiRT Showdown"), BackupLayout.WINLATOR, zip)

        assertEquals(
            setOf(
                "drive_c/users/$user/Documents/My Games/DiRT Showdown/hardwaresettings/hardware_settings_config.xml",
                "drive_c/users/$user/AppData/Roaming/FLT/steam_id.txt",
            ),
            entries(zip),
        )
    }

    @Test
    fun perGame_emulatorFoldersAlone_areABackup() {
        val root = container("src")
        file(driveC(root), "ProgramData/Steam/elamigos/0000201700/storage/career.bin")
        val zip = File(tmp.root, "game.zip")
        val r = GameSaveBackup.backupDriveC(driveC(root), emptyList(), BackupLayout.GAMEHUB, zip, 201700)

        assertTrue(r.ok)
        assertEquals(setOf("/drive_c/ProgramData/Steam/elamigos/0000201700/storage/career.bin"), entries(zip))
    }

    @Test
    fun perGame_accountIdAlone_isStillNothingToBackUp() {
        val root = container("src")
        file(driveC(root), "users/$user/AppData/Roaming/FLT/steam_id.txt", "76561198000000001")
        file(driveC(root), "ProgramData/Steam/elamigos/12345/storage/other.bin")
        val zip = File(tmp.root, "game.zip")
        val r = GameSaveBackup.backupDriveC(driveC(root), emptyList(), BackupLayout.GAMEHUB, zip, 201700)

        assertFalse(r.ok)
        assertFalse(zip.exists())
    }

    @Test
    fun perGame_pickedRootOverlappingAnAppIdFolder_isZippedOnce() {
        val c = driveC(dirtShowdownPrefix())
        val zip = File(tmp.root, "game.zip")
        val r = GameSaveBackup.backupDriveC(c, listOf("AppData/Roaming/GSE Saves"), BackupLayout.WINLATOR, zip, 201700)

        assertTrue(r.ok)
        val names = entries(zip)
        assertTrue("drive_c/users/$user/AppData/Roaming/GSE Saves/201700/remote/profile.sav" in names)
        assertTrue("drive_c/users/$user/AppData/Roaming/GSE Saves/999/remote/other.sav" in names)
    }

    // ---------------------------------------------------------------- restore

    @Test
    fun remap_emulatorFoldersStayVerbatim_profileUserBecomesOurs() {
        assertEquals(
            "ProgramData/Steam/RLD!/201700/stats/achievements.ini",
            GameSaveBackup.remapForRestore("/drive_c/ProgramData/Steam/RLD!/201700/stats/achievements.ini"),
        )
        assertEquals(
            "users/Public/Documents/OnlineFix/480/Saves/a.sav",
            GameSaveBackup.remapForRestore("drive_c\\users\\Public\\Documents\\OnlineFix\\480\\Saves\\a.sav"),
        )
        assertEquals(
            "users/$user/AppData/Roaming/GSE Saves/201700/remote/profile.sav",
            GameSaveBackup.remapForRestore("/drive_c/users/steamuser/AppData/Roaming/GSE Saves/201700/remote/profile.sav"),
        )
        assertNull(GameSaveBackup.remapForRestore("/drive_c/users/Public/Desktop/DiRT Showdown.lnk"))
        assertNull(GameSaveBackup.remapForRestore("ProgramData/Steam/elamigos/201700/last.txt"))
    }

    @Test
    fun restore_putsEmulatorFoldersBackAtTheirDriveCPath() {
        val zip = zipOf(
            "/drive_c/ProgramData/Steam/elamigos/201700/storage/career.bin" to "career",
            "/drive_c/users/Public/Documents/Steam/CODEX/480/remote/save.dat" to "codex",
            "/drive_c/users/steamuser/Documents/My Games/DiRT Showdown/profile.xml" to "profile",
        )
        val root = container("dst")
        val r = restore(zip, root)
        val c = driveC(root)

        assertTrue(r.ok)
        assertEquals(3, r.filesWritten)
        assertEquals("career", File(c, "ProgramData/Steam/elamigos/201700/storage/career.bin").readText())
        assertEquals("codex", File(c, "users/Public/Documents/Steam/CODEX/480/remote/save.dat").readText())
        assertEquals("profile", File(c, "users/$user/Documents/My Games/DiRT Showdown/profile.xml").readText())
        assertFalse(File(c, "users/$user/Documents/Steam").exists())
    }

    @Test
    fun restore_overwritesWhatTheTargetHasAtTheSamePath() {
        val zip = zipOf("drive_c/ProgramData/Steam/elamigos/201700/last.txt" to "from backup")
        val root = container("dst")
        file(driveC(root), "ProgramData/Steam/elamigos/201700/last.txt", "stale")
        restore(zip, root)
        assertEquals("from backup", File(driveC(root), "ProgramData/Steam/elamigos/201700/last.txt").readText())
    }

    @Test
    fun backupThenRestore_roundTripsEverySaveIntoAFreshContainer() {
        for (layout in BackupLayout.values()) {
            val src = driveC(dirtShowdownPrefix("src-$layout"))
            val zip = File(tmp.newFolder(), "game.zip")
            GameSaveBackup.backupDriveC(src, listOf("Documents/My Games/DiRT Showdown"), layout, zip, 201700)
            val dst = container("dst-$layout")
            restore(zip, dst)

            for (rel in listOf(
                "users/$user/Documents/My Games/DiRT Showdown/hardwaresettings/hardware_settings_config.xml",
                "users/$user/AppData/Roaming/FLT/steam_id.txt",
                "users/$user/AppData/Roaming/GSE Saves/201700/remote/profile.sav",
                "ProgramData/Steam/elamigos/201700/storage/career.bin",
                "ProgramData/Steam/elamigos/201700/stats/stats.bin",
                "ProgramData/Steam/elamigos/201700/last.txt",
            )) {
                assertEquals("$layout $rel", File(src, rel).readText(), File(driveC(dst), rel).readText())
            }
            assertFalse(File(driveC(dst), "ProgramData/Steam/elamigos/12345").exists())
        }
    }

    @Test
    fun oldZip_withoutEmulatorFolders_restoresAsBefore() {
        // A GameHub archive and one of ours from before this change: profile entries only, a
        // launcher shortcut GameHub bundles, and an emulator account id for a fresh prefix.
        val zip = zipOf(
            "/drive_c/users/steamuser/Saved Games/God of War/123/game.sav" to "gow",
            "/drive_c/users/steamuser/Desktop/God of War.lnk" to "lnk",
            "/drive_c/users/steamuser/AppData/Roaming/FLT/steam_id.txt" to "76561198000000123",
            "drive_c/users/$user/Documents/My Games/Elden Ring/ER0000.sl2" to "er",
            "drive_c/users/Public/Documents/shared.dat" to "public",
        )
        val root = container("dst")
        val r = restore(zip, root)
        val c = driveC(root)

        assertTrue(r.ok)
        assertTrue(r.emuConflicts.isEmpty())
        assertEquals(4, r.filesWritten)
        assertEquals("gow", File(c, "users/$user/Saved Games/God of War/123/game.sav").readText())
        assertEquals("er", File(c, "users/$user/Documents/My Games/Elden Ring/ER0000.sl2").readText())
        assertEquals("public", File(c, "users/Public/Documents/shared.dat").readText())
        assertEquals("76561198000000123", File(c, "users/$user/AppData/Roaming/FLT/steam_id.txt").readText())
        assertFalse(File(c, "users/$user/Desktop/God of War.lnk").exists())
        assertFalse(File(c, "ProgramData").exists())
    }
}
