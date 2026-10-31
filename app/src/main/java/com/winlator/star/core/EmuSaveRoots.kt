package com.winlator.star.core

import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.xenvironment.ImageFs
import java.io.File

/**
 * The save folders Steam emulators name after a game's Steam app id — the part of a cracked game's
 * progress a backup of the Wine user profile alone never sees.
 *
 * Many emulators shipped beside a game keep saves, stats and achievements OUTSIDE the profile. An
 * RLD-style steam_api.ini ("[Steam] AppId=", "[Settings] UserName=") keeps the whole career in
 * C:\ProgramData\Steam\<UserName>\<AppId>\; CODEX and RUNE use C:\Users\Public\Documents\Steam\<group>\<AppId>\,
 * OnlineFix and EMPRESS their own folder under Public\Documents. DiRT Showdown lost its career this
 * way: every backup on the device held the profile and not one ProgramData file. Older CODEX releases
 * write under the profile instead, %APPDATA%\Steam\CODEX\<AppId>\ (DiRT 3 Complete Edition's codex.ini).
 *
 * Two kinds of root, both '/'-separated like [EmuAccountIdentity]:
 *  - drive_c-relative ones outside the profile ([allDriveCRoots], [driveCRootsFor]), which a backup
 *    stores and a restore puts back VERBATIM — ProgramData and Public belong to no Wine user, so
 *    there is no user segment to translate;
 *  - profile-relative ones ([profileRootsFor]) for the emulators that do write under the profile but
 *    name the folder by app id, which a scoped backup's name-matched roots never pick up.
 *
 * An app-id folder matches by number, so "0000201700" (as the ini spells it) and "201700" (as the
 * emulator names the folder) are the same game. Folder names are looked up ignoring case, the way
 * Wine resolves them, and returned with their on-disk spelling.
 */
object EmuSaveRoots {

    /** drive_c-relative parents laid out <parent>/<emulator user name or group>/<appid>. */
    private val GROUPED_DRIVE_C = listOf(
        "ProgramData/Steam",            // RLD!, REVOLT, elamigos: <UserName or tag>/<appid>
        "users/Public/Documents/Steam", // CODEX, RUNE, …: <group>/<appid>
    )

    /** drive_c-relative parents laid out <parent>/<appid>. */
    private val FLAT_DRIVE_C = listOf(
        "users/Public/Documents/OnlineFix",
        "users/Public/Documents/EMPRESS",
    )

    /**
     * Profile-relative parents laid out <parent>/<appid>. Only the app-id children are this game's:
     * "GSE Saves" also holds the emulator account (swept by [EmuAccountIdentity]) and every other
     * game's saves.
     */
    private val FLAT_PROFILE = listOf(
        "AppData/Roaming/GSE Saves",
        "AppData/Roaming/Goldberg SteamEmu Saves",
        "AppData/Roaming/EMPRESS",
    )

    /** Profile-relative parents laid out <parent>/<group>/<appid>. */
    private val GROUPED_PROFILE = listOf(
        "AppData/Roaming/Steam",        // older CODEX: CODEX/<appid>
    )

    /** Every emulator save folder outside the profile under [driveC], any app id (whole-container backup). */
    fun allDriveCRoots(driveC: File): List<String> = driveCRoots(driveC) { true }

    /** [appId]'s emulator save folders outside the profile under [driveC] (per-game backup). */
    fun driveCRootsFor(driveC: File, appId: Int): List<String> = driveCRoots(driveC) { it == appId }

    /** [appId]'s emulator save folders under the Wine user [profile], profile-relative. */
    fun profileRootsFor(profile: File, appId: Int): List<String> =
        FLAT_PROFILE.flatMap { parent -> appIdDirs(profile, parent) { it == appId } } +
            groupedAppIdDirs(profile, GROUPED_PROFILE) { it == appId }

    /**
     * Every folder a per-game backup adds for [appId] on its own, drive_c-relative (the profile's as
     * "users/xuser/…"), so the UI can show what goes in beside the folders the user ticked.
     */
    fun includedFor(driveC: File, appId: Int): List<String> {
        val profileRel = "users/${ImageFs.USER}"
        return profileRootsFor(File(driveC, profileRel), appId).map { "$profileRel/$it" } +
            driveCRootsFor(driveC, appId)
    }

    // ---------------------------------------------------------------- the game's app id

    /**
     * The Steam app id [shortcut]'s emulator saves are filed under. Reads files; call off the main
     * thread.
     */
    fun appIdFor(container: Container, shortcut: Shortcut): Int? =
        appIdFor(container, shortcut.getExtra("steamAppId"), shortcut.path)

    /**
     * The shortcut's `steamAppId` extra when it carries one, else what the emulator config beside the
     * game's exe names ([GameIdentifier.appIdInFolder]: an `AppId=` ini such as steam_api.ini or
     * steam_emu.ini, else steam_appid.txt). Null when neither does — these folders are named by number
     * only, so the game's name is no way to find them. [exePath] is the shortcut's Wine path.
     */
    fun appIdFor(container: Container, appIdExtra: String?, exePath: String?): Int? {
        val exeDir = exePath?.takeIf { it.isNotBlank() }
            ?.let { runCatching { WinePath.resolveAndroidPath(container, it) }.getOrNull() }
            ?.parentFile
        return appIdFor(appIdExtra, exeDir)
    }

    internal fun appIdFor(appIdExtra: String?, exeDir: File?): Int? =
        appIdExtra?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: exeDir?.takeIf { it.isDirectory }?.let { GameIdentifier.appIdInFolder(it) }

    // ---------------------------------------------------------------- helpers

    private fun driveCRoots(driveC: File, match: (Int) -> Boolean): List<String> =
        groupedAppIdDirs(driveC, GROUPED_DRIVE_C, match) + FLAT_DRIVE_C.flatMap { appIdDirs(driveC, it, match) }

    /** "<parent>/<group>/<child>" for each of [parents] (under [base]) whose child's app id [match] accepts. */
    private fun groupedAppIdDirs(base: File, parents: List<String>, match: (Int) -> Boolean): List<String> =
        parents.mapNotNull { resolve(base, it) }.flatMap { parentRel ->
            File(base, parentRel).listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.flatMap { group -> appIdDirs(base, "$parentRel/${group.name}", match) }
                .orEmpty()
        }

    /** "<parent>/<child>" for each child folder of [parent] (under [base]) whose app id [match] accepts. */
    private fun appIdDirs(base: File, parent: String, match: (Int) -> Boolean): List<String> {
        val parentRel = resolve(base, parent) ?: return emptyList()
        return File(base, parentRel).listFiles()
            ?.filter { it.isDirectory && appIdOf(it.name)?.let(match) == true }
            ?.map { "$parentRel/${it.name}" }
            ?.sorted()
            .orEmpty()
    }

    /** A folder name read as a Steam app id: digits only, leading zeros allowed. */
    private fun appIdOf(name: String): Int? =
        name.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 }

    /** [rel] under [base] resolved a segment at a time ignoring case, in its on-disk spelling, or null. */
    private fun resolve(base: File, rel: String): String? {
        var dir = base
        val out = ArrayList<String>()
        for (seg in rel.split('/')) {
            val next = File(dir, seg).takeIf { it.isDirectory }
                ?: dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(seg, ignoreCase = true) }
                ?: return null
            out += next.name
            dir = next
        }
        return out.joinToString("/")
    }
}
