package com.winlator.star.core

import android.content.Context
import org.json.JSONObject

/**
 * User-configurable "New Container Defaults" — the seed a brand-new container is built from.
 *
 * Stored as TWO INDEPENDENT profiles, one per architecture (x86-64 and arm64ec), because the
 * emulator/box64/wowbox64/FEXCore fields are arch-coupled (refreshWineDependent swaps the
 * box64↔wowbox64 lists on isArm64EC) — a single arch-agnostic profile can't serve both. Each is a
 * JSON blob holding the same container-config `data` shape the create flow builds (see
 * ContainerDetailViewModel.doConfirm), MINUS "name", "drives" and "wineVersion" (per-container /
 * never templated). When an arch's profile is set, ContainerDetailViewModel seeds a matching new
 * container from it; when unset the built-in `Container.DEFAULT_*` constants are used, so the
 * no-profile create path is unchanged.
 *
 * Kept in a DEDICATED SharedPreferences file (not the default prefs, not Room) so it survives an
 * in-place app update and stays completely separate from config export/import and community configs.
 */
object NewContainerDefaults {

    const val ARCH_X86_64 = "x86_64"
    const val ARCH_ARM64EC = "arm64ec"

    private const val PREFS = "new_container_defaults"
    // Per-arch prefs keys: "profile_x86_64" / "profile_arm64ec".
    private fun keyFor(arch: String) = "profile_$arch"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persist the [arch] profile JSON (the stripped container-config `data` object). */
    fun save(context: Context, arch: String, json: String) {
        prefs(context).edit().putString(keyFor(arch), json).apply()
    }

    /** The saved profile JSON for [arch], or null when the user has never set one for it. */
    fun load(context: Context, arch: String): String? = prefs(context).getString(keyFor(arch), null)

    /** Forget the [arch] defaults so new containers of that arch fall back to `Container.DEFAULT_*`. */
    fun clear(context: Context, arch: String) {
        prefs(context).edit().remove(keyFor(arch)).apply()
    }

    /** Whether a profile is set for [arch] (i.e. new containers of that arch should seed from it). */
    fun exists(context: Context, arch: String): Boolean = load(context, arch) != null

    // ── "Set as default…" (Contents › Installed) ────────────────────────────────────────────────
    // One setting at a time, per arch. When [arch] already has a profile the value is patched
    // straight into it, so the New Container Defaults screen shows it. Without a profile, writing a
    // whole one would freeze every OTHER field at Container's raw field values (renderer, screen size,
    // an empty dxwrapperConfig…), which is not what the no-profile create path builds — so the value
    // goes into a sparse per-arch "picks" object instead, laid over the built-in defaults by
    // ContainerDetailViewModel (create form and the defaults form alike). Saving or resetting that
    // arch on the New Container Defaults screen folds the picks in / drops them ([clearPicks]).
    //
    // The layer is separate: profiles never template wineVersion, so each arch's default layer has
    // its own key, and the arch whose layer was set last decides which layer the create form opens on.

    private fun picksKey(arch: String) = "picks_$arch"
    private fun layerKey(arch: String) = "layer_$arch"
    private const val KEY_LAYER_ARCH = "layer_arch"

    /** The sparse picks for [arch] ({ "<kind>": "<value>" }, see contents.SetAsDefault.Kind), or null. */
    fun loadPicks(context: Context, arch: String): JSONObject? =
        prefs(context).getString(picksKey(arch), null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun savePicks(context: Context, arch: String, picks: JSONObject) {
        prefs(context).edit().putString(picksKey(arch), picks.toString()).apply()
    }

    fun clearPicks(context: Context, arch: String) {
        prefs(context).edit().remove(picksKey(arch)).apply()
    }

    /** Default layer entry (`Proton-11.0-2-arm64ec-9`) for new [arch] containers, or null. */
    fun layer(context: Context, arch: String): String? = prefs(context).getString(layerKey(arch), null)

    fun setLayer(context: Context, arch: String, entry: String) {
        prefs(context).edit().putString(layerKey(arch), entry).putString(KEY_LAYER_ARCH, arch).apply()
    }

    fun clearLayer(context: Context, arch: String) {
        val e = prefs(context).edit().remove(layerKey(arch))
        if (prefs(context).getString(KEY_LAYER_ARCH, null) == arch) e.remove(KEY_LAYER_ARCH)
        e.apply()
    }

    /**
     * The layer a new container's form opens on: the default layer of the arch set last, else the
     * other arch's, as long as it is in [installed] (the form's Wine version list); null = the
     * list's first entry, as before this existed.
     */
    fun preferredLayer(context: Context, installed: List<String>): String? {
        val last = prefs(context).getString(KEY_LAYER_ARCH, null)
        val order = listOfNotNull(last) + listOf(ARCH_ARM64EC, ARCH_X86_64).filter { it != last }
        return order.firstNotNullOfOrNull { a -> layer(context, a)?.takeIf { it in installed } }
    }

    /** Raw prefs state of one arch's defaults, for an exact in-session undo ([restore]). */
    data class Snapshot(val arch: String, val profile: String?, val picks: String?, val layer: String?, val layerArch: String?)

    fun capture(context: Context, arch: String): Snapshot {
        val p = prefs(context)
        return Snapshot(arch, p.getString(keyFor(arch), null), p.getString(picksKey(arch), null),
            p.getString(layerKey(arch), null), p.getString(KEY_LAYER_ARCH, null))
    }

    fun restore(context: Context, s: Snapshot) {
        fun android.content.SharedPreferences.Editor.putOrRemove(k: String, v: String?) =
            if (v == null) remove(k) else putString(k, v)
        prefs(context).edit()
            .putOrRemove(keyFor(s.arch), s.profile)
            .putOrRemove(picksKey(s.arch), s.picks)
            .putOrRemove(layerKey(s.arch), s.layer)
            .putOrRemove(KEY_LAYER_ARCH, s.layerArch)
            .apply()
    }
}
