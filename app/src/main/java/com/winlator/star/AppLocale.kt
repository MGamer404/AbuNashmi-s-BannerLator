package com.winlator.star

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * The app ignores the phone's language: every screen is English, left-to-right, with Latin digits and the
 * English date/time formats, whatever the system is set to (Arabic, Hebrew, Persian...). The layouts, the XMB
 * row positions (Modifier.offset mirrors in a right-to-left layout) and the shortcut cards were built for
 * English only, and mirrored they broke.
 *
 * Layers: `android:supportsRtl="false"` in the manifest (classic Views), [wrap] in every Application and
 * Activity `attachBaseContext` (the Configuration Compose and DateFormat read), [ForceLtr] at the Compose
 * theme roots (so nothing mirrors even if a Configuration slips through), and [Locale.setDefault] here (what
 * String.format / SimpleDateFormat use).
 *
 * Not touched: the Wine/guest locale (the container's LC_ALL is its own setting). Values that identify the
 * device to a store or seed a container (region for the store catalogs, the new-container LC_ALL, the Epic
 * install language) read [deviceLocale], the locale the phone really has.
 */
object AppLocale {
    /** The locale the whole app presents itself in. */
    @JvmField
    val UI: Locale = Locale.US

    @Volatile
    private var device: Locale? = null

    /**
     * The phone's own locale, captured before [UI] replaced the process default. For backend-facing values
     * (store region, guest LC_ALL seed), never for anything the user reads.
     */
    @JvmStatic
    fun deviceLocale(): Locale = device ?: Locale.getDefault()

    /** Makes [UI] the process default locale. Idempotent; the first call remembers the phone's locale. */
    @JvmStatic
    fun applyDefault() {
        if (device == null) device = Locale.getDefault()
        Locale.setDefault(UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) LocaleList.setDefault(LocaleList(UI))
    }

    /**
     * [base] with the English locale and a left-to-right layout direction, for `attachBaseContext`. Falls back
     * to [base] untouched if the system refuses the configuration, so start-up can never fail here.
     *
     * The override is a fresh [Configuration] that sets only the locale and the layout direction (what AppCompat does too):
     * a copy of the whole base configuration would pin orientation, screen size and UI mode of this moment as overrides, and
     * the activities that handle those changes themselves (the manifest's configChanges) would then keep reading the old
     * values from their resources. A new Configuration has every field "undefined" (unset), and Android merges only the fields an
     * override sets, so everything else keeps following the system; `fontScale = 0` says so explicitly for the font scale.
     */
    @JvmStatic
    fun wrap(base: Context): Context {
        applyDefault()
        return try {
            val config = Configuration()
            config.fontScale = 0f
            config.setLocale(UI)
            config.setLayoutDirection(UI)
            base.createConfigurationContext(config)
        } catch (_: Throwable) {
            base
        }
    }
}

/** Runs [content] in a left-to-right layout direction (see [AppLocale]); dialogs and popups opened inside inherit it. */
@Composable
fun ForceLtr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
