package com.winlator.star.components

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container

/**
 * The per-container "installed" record the Win Components screens, recommendation chips and the
 * Xbox/GOG redist installs share (SharedPreferences "component_installs", key "c<id>").
 *
 * Container ids are reused (a new container gets the highest id + 1), so the record goes with its
 * container: cleared when one is created or removed, copied when one is duplicated. A Visual C++
 * entry is only kept while its files are in the prefix ([PrefixInstalledDetector.vcRuntimeMissing]).
 */
object ComponentInstallRecords {
    private const val TAG = "ComponentInstallRecords"
    const val PREFS = "component_installs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(id: Int) = "c$id"

    @JvmStatic fun reset(context: Context, containerId: Int) {
        prefs(context).edit().remove(key(containerId)).apply()
    }

    @JvmStatic fun add(context: Context, containerId: Int, name: String) {
        val p = prefs(context)
        val names = p.getStringSet(key(containerId), emptySet()).orEmpty()
        if (name !in names) p.edit().putStringSet(key(containerId), names + name).apply()
    }

    @JvmStatic fun copy(context: Context, fromId: Int, toId: Int) {
        val p = prefs(context)
        val names = p.getStringSet(key(fromId), emptySet()).orEmpty()
        p.edit().apply { if (names.isEmpty()) remove(key(toId)) else putStringSet(key(toId), HashSet(names)) }.apply()
    }

    /**
     * The record for [container] without the Visual C++ entries whose files are not in the prefix;
     * those are dropped from the record too. Reads the prefix, so call it off the main thread.
     */
    @JvmStatic fun verified(context: Context, container: Container): Set<String> {
        val p = prefs(context)
        val recorded = p.getStringSet(key(container.id), emptySet()).orEmpty().toSet()
        val stale = recorded.filter { PrefixInstalledDetector.vcRuntimeMissing(container, it) != null }.toSet()
        if (stale.isEmpty()) return recorded
        Log.i(TAG, "container ${container.id}: $stale recorded but their files are not in the prefix, dropped")
        val kept = recorded - stale
        p.edit().putStringSet(key(container.id), kept).apply()
        return kept
    }
}
