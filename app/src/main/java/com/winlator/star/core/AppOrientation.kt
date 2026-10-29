package com.winlator.star.core

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.preference.PreferenceManager
import com.winlator.star.ui.theme.AppThemeState

/**
 * The user's "App orientation" preference — applied to the app's OWN UI activity only. The game's
 * [com.winlator.star.XServerDisplayActivity] sets its own orientation and is never touched here, so
 * launching a game always releases/ignores this app-side lock.
 *
 * Modes: "auto" (follow device rotation — the default / historical behavior), "portrait", "landscape".
 *
 * The Deck interface has its own lock instead ([deckMode]): landscape by default, portrait after its bars'
 * rotate button, never the sensor. The Classic preference above is left as it is for when Classic is back.
 */
object AppOrientation {
    const val PREF_KEY = "app_orientation"
    const val AUTO = "auto"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"

    /** The Deck interface's lock, flipped by the rotate button in its bars. Landscape until it is pressed. */
    const val DECK_PREF_KEY = "deck_orientation"

    fun mode(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, AUTO) ?: AUTO

    fun setMode(context: Context, mode: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_KEY, mode).apply()
    }

    fun deckMode(context: Context): String =
        if (PreferenceManager.getDefaultSharedPreferences(context).getString(DECK_PREF_KEY, LANDSCAPE) == PORTRAIT) PORTRAIT
        else LANDSCAPE

    /** Flip the Deck lock between landscape and portrait and apply it to [activity] at once. */
    fun toggleDeck(activity: Activity) {
        val next = if (deckMode(activity) == PORTRAIT) LANDSCAPE else PORTRAIT
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(DECK_PREF_KEY, next).apply()
        apply(activity)
    }

    // Read from the theme prefs directly: the store activities apply this without AppThemeState being set up.
    private fun isDeck(context: Context): Boolean =
        context.getSharedPreferences("winlator_theme", Context.MODE_PRIVATE)
            .getString("ui_style", AppThemeState.UI_STYLE_CLASSIC) == AppThemeState.UI_STYLE_DECK

    private fun requested(mode: String): Int = when (mode) {
        PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR   // "auto" — matches the manifest default
    }

    /** Apply the stored preference (the Deck lock while the Deck style is on) to [activity]'s requestedOrientation (call on start + on change). */
    fun apply(activity: Activity) {
        activity.requestedOrientation = requested(if (isDeck(activity)) deckMode(activity) else mode(activity))
    }
}
