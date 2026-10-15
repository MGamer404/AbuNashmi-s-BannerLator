package com.winlator.star.store

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Picks the Steam Input layout ("controller_mappings" VDF text) the SteamLite agent loads for a game
 * when the per-game **Steam Input** toggle is on (`WN_STEAM_INPUT_VDF`, see [RealSteamLauncher]).
 *
 * Resolution order (first hit wins):
 *  1. **The game's own layout** — a `controller_<type>.vdf` beside the action manifest Valve's app-info
 *     names in `config.steaminputmanifestpath` (games that ship Steam Input bindings in their depot).
 *  2. **A Valve template** from the SteamLite package (`controller_base/templates/`, Valve's own files
 *     as shipped in the Steam client), chosen from `config.steamcontrollertemplateindex` the way the
 *     client itself seeds a first layout: gamepad with FPS camera / WASD / twin-stick / gamepad + mouse.
 *  3. The `gamepad+mouse` template when the index is unknown or absent.
 *
 * App-info comes from one bounded PICS fetch ([SteamRepository.fetchAppKeyValues]); without it (offline,
 * timeout) step 2 falls through to the default template so a controller still shows up. Returns null
 * only when the package has no templates and the game ships none — the agent then activates whatever
 * the client already has for the app (usually nothing) and logs it.
 *
 * Runs on the launch worker thread (file + network I/O); never throws.
 */
object SteamInputLayout {
    private const val TAG = "BH_REALSTEAM"
    private const val PICS_TIMEOUT_MS = 8_000L
    private const val MAX_VDF_BYTES = 4L * 1024 * 1024

    /** Controller types to look for beside a game-shipped action manifest, most useful first. */
    private val SHIPPED_TYPES = listOf("controller_xboxone", "controller_xbox360", "controller_generic")

    /** What the resolver found, for the launch log. */
    data class Pick(val vdf: String, val source: String)

    fun resolve(ctx: Context, steamLiteInstallDir: File, appId: Int, hostInstallDir: String?): Pick? {
        if (appId <= 0) return null
        var manifestPath = ""
        var templateIndex = -1
        try {
            val kv = SteamRepository.getInstance().fetchAppKeyValues(appId, PICS_TIMEOUT_MS)
            val config = kv?.get("config")
            manifestPath = config?.get("steaminputmanifestpath")?.value?.trim().orEmpty()
            templateIndex = config?.get("steamcontrollertemplateindex")?.value?.trim()?.toIntOrNull() ?: -1
        } catch (t: Throwable) {
            Log.w(TAG, "steam input: app-info unavailable for $appId (${t.message}) — using the default template")
        }
        Log.i(TAG, "steam input: appId=$appId templateIndex=$templateIndex manifest='${manifestPath}'")

        // 1. game-shipped layout next to its action manifest
        if (manifestPath.isNotEmpty() && !hostInstallDir.isNullOrEmpty()) {
            try {
                val manifest = findCaseInsensitive(File(hostInstallDir), manifestPath)
                val dir = manifest?.parentFile
                if (dir != null) {
                    for (type in SHIPPED_TYPES) {
                        val f = findCaseInsensitive(dir, "$type.vdf") ?: continue
                        if (f.length() in 1..MAX_VDF_BYTES) {
                            return Pick(f.readText(Charsets.UTF_8), "game-shipped ${f.name} (beside $manifestPath)")
                        }
                    }
                    Log.i(TAG, "steam input: manifest found but no controller_*.vdf beside it ($dir)")
                } else {
                    Log.i(TAG, "steam input: manifest '$manifestPath' not found under $hostInstallDir")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "steam input: game-shipped layout lookup failed: ${t.message}")
            }
        }

        // 2./3. Valve template from the SteamLite package
        val templates = File(steamLiteInstallDir, "controller_base/templates")
        val name = templateFor(templateIndex)
        val f = File(templates, "$name.vdf")
        if (f.isFile && f.length() in 1..MAX_VDF_BYTES) {
            return try { Pick(f.readText(Charsets.UTF_8), "template $name (index $templateIndex)") }
            catch (t: Throwable) { Log.w(TAG, "steam input: template unreadable: ${t.message}"); null }
        }
        Log.w(TAG, "steam input: no template '$name' in $templates — the SteamLite package predates Steam Input support")
        return null
    }

    /**
     * Template per `steamcontrollertemplateindex`. Bias towards the plain **gamepad** template: with the
     * layout active, Steam injects whatever the template maps — and a mouse/keyboard template turns the
     * right stick into a mouse and buttons into keys, which a game that also reads the pad directly then
     * receives twice (on Windows the overlay hides the raw pad; we run without it). The mouse template is
     * used only for indexes that mean a keyboard-and-mouse game. Device note: L4D2 (index 1) froze mid-match
     * under the gamepad+mouse template while buttons were mashed; a pad-only layout avoids the double input.
     */
    private fun templateFor(index: Int): String = when (index) {
        6 -> "controller_xboxone_wasd"               // keyboard (WASD) + mouse game
        3 -> "gamepad+mouse"                         // mouse-driven game
        4, 5 -> "controller_xboxone_gamepad_joystick" // twin-stick
        else -> "controller_xboxone_gamepad_fps"      // 1/2/12/unknown: the pad stays a pad
    }

    /** Walks [rel] (either slash style) under [root] matching each component case-insensitively. */
    private fun findCaseInsensitive(root: File, rel: String): File? {
        var cur = root
        for (part in rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }) {
            val exact = File(cur, part)
            cur = if (exact.exists()) exact
            else cur.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return if (cur.exists()) cur else null
    }
}
