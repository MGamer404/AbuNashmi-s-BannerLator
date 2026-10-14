package com.winlator.star.linux

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Android's phantom-process monitor, as it applies to a Linux session.
 *
 * A session is one proot holding gamescope, the Steam client, its web helper and a game, which
 * passes the thirty-two child processes Android 12 and later allow an app. Over that the system
 * kills them, and killing proot ends the session — the "it just died for no reason" report, with
 * nothing in any log because the kill is the system's, not ours.
 *
 * The monitor is off while `settings_enable_monitor_phantom_procs` is false. Developer options
 * carries the switch for it ("Disable child process restrictions") from Android 14; before that
 * only adb can set it. The app can do neither itself — writing that setting needs a permission
 * only the system grants — so all it can do is detect the state and say so.
 *
 * (The same gap, found and closed independently in WinNative, maxjivi05, e86cc2fc.)
 */
object ChildProcessRestrictions {
    private const val SETTING = "settings_enable_monitor_phantom_procs"

    /** Whether this device will kill a session's processes, so there is still something to do. */
    @JvmStatic
    fun active(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val value = try {
            Settings.Global.getString(context.contentResolver, SETTING)
        } catch (e: SecurityException) {
            // Unreadable means unknown, and unknown on S+ is the restricted default.
            return true
        }
        return !"false".equals(value, ignoreCase = true) && value != "0"
    }

    /** Whether Developer options carries the switch, or the user needs adb. */
    @JvmStatic
    fun hasSwitch(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The adb line for a device whose Developer options has no switch. */
    @JvmStatic
    fun adbCommand(): String =
        "adb shell settings put global settings_enable_monitor_phantom_procs false"

    /** Opens Developer options, and reports whether the device let it. */
    @JvmStatic
    fun openDeveloperOptions(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
