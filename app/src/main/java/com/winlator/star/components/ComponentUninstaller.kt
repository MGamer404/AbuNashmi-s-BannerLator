package com.winlator.star.components

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import java.io.File

/**
 * Takes an installed component back out of a container's prefix: what the Remove action in the
 * classic components sheet, the recommendation chips and the Deck components page runs, only ever
 * after the user confirms it.
 *
 *  - A component installed since its changes are journaled ([ComponentUndo]) is reverted exactly:
 *    the files it added are deleted, the ones it replaced put back, its registry values undone.
 *  - One installed before that, with no journal, is removed by its own Windows Installer uninstall
 *    when [PrefixInstalledDetector] finds the product (a container session, as installers run).
 *  - Anything else can't be removed: nothing is deleted on a guess. Reinstalling it records a journal.
 *
 * Removal acts on the container's prefix, so it goes for every game in that container. The
 * "component_installs" record (key "c<id>") loses the component once it is out.
 */
object ComponentUninstaller {
    private const val TAG = "ComponentUninstaller"
    private const val INSTALLS_PREFS = "component_installs"

    const val NO_RECORD = "It was installed before removal was supported, so there is no record of what it changed " +
        "and nothing is deleted on a guess. Reinstalling it records what to undo; then it can be removed."

    /** How [name] would come out of a container; see [plan]. */
    data class Plan(
        /** The name the lists show (a file-drop `_dll` variant goes by its base). */
        val name: String,
        /** The journals to revert, newest first; empty when there are none. */
        val journals: List<String>,
        /** With no journal: its Windows Installer products (display name to product code), whose uninstall runs in a session. */
        val products: List<Pair<String, String>>,
        /** Why it can't be removed, or null. */
        val blocked: String?,
        /** Installed components that need this one. */
        val dependents: List<String>,
    ) {
        val viaUninstaller: Boolean get() = blocked == null && journals.isEmpty() && products.isNotEmpty()
    }

    sealed class Outcome {
        /** Reverted; [notes] say what was left as it was. */
        data class Removed(val notes: List<String>) : Outcome()
        /** Its own uninstaller is running in a container session; the app restarts when it ends. */
        object Launched : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /**
     * Works out how [name] would come out of [container]. Reads the prefix and the journals: call it
     * off the main thread. [installed] is what the caller shows as installed, for the dependents.
     */
    fun plan(
        context: Context, container: Container, name: String, installed: Set<String>,
        catalog: Map<String, Component> = ComponentCatalog.byName,
    ): Plan {
        val base = name.removeSuffix("_dll")
        val dependents = dependents(base, installed, catalog)
        fun blocked(reason: String) = Plan(base, emptyList(), emptyList(), reason, dependents)
        if (ComponentExecInstaller.pendingComponentName(context)?.removeSuffix("_dll") == base)
            return blocked("Its install is still waiting to be finished. Finish or discard it first.")
        if (!File(container.rootDir, ".wine").isDirectory) return blocked("This container has no Wine prefix.")
        val journals = ComponentUndo.journalsFor(container.rootDir, base)
        if (journals.isNotEmpty()) return Plan(base, journals, emptyList(), null, dependents)
        val products = PrefixInstalledDetector.msiProducts(container, base)
        if (products.isNotEmpty()) return Plan(base, emptyList(), products, null, dependents)
        return blocked(NO_RECORD)
    }

    /** The installed components whose catalog entry needs [name], directly or through another. */
    fun dependents(name: String, installed: Set<String>, catalog: Map<String, Component>): List<String> {
        val base = name.removeSuffix("_dll")
        val ids = setOf(base, "${base}_dll")
        return installed.filter { it.removeSuffix("_dll") != base }.filter { other ->
            val seen = HashSet<String>()
            fun needs(c: Component?, depth: Int): Boolean {
                if (c == null || depth > 8 || !seen.add(c.name)) return false
                return c.dependencies.any { it in ids || needs(catalog[it], depth + 1) }
            }
            needs(catalog[other], 0) || needs(catalog["${other.removeSuffix("_dll")}_dll"], 0)
        }.map { it.removeSuffix("_dll") }.distinct().sorted()
    }

    /**
     * Takes [plan]'s component out of [container]. [onProgress] gets 0..1, [onStage] a line for what
     * is happening. Runs on the caller's thread: call it off the main thread.
     */
    fun uninstall(
        context: Context, container: Container, plan: Plan,
        onProgress: (Float) -> Unit = {}, onStage: (String) -> Unit = {},
    ): Outcome {
        plan.blocked?.let { return Outcome.Failed(it) }
        return try {
            if (plan.journals.isNotEmpty()) {
                val notes = ArrayList<String>()
                plan.journals.forEachIndexed { i, j ->
                    onStage(if (plan.journals.size > 1) "Removing $j" else "Putting the prefix back")
                    notes += ComponentUndo.revert(container.rootDir, j) { f -> onProgress((i + f) / plan.journals.size) }
                }
                forget(context, container, plan.name)
                onProgress(1f)
                Outcome.Removed(notes)
            } else if (plan.products.isNotEmpty()) {
                onStage("Opening the container")
                // Like an installer's marker, the record goes before the session (which restarts the
                // app); if the uninstall did not finish, the prefix check finds the component again.
                forget(context, container, plan.name)
                ComponentExecInstaller.launchUninstaller(context, container, plan.name, plan.products.map { it.second })
                Outcome.Launched
            } else Outcome.Failed(NO_RECORD)
        } catch (e: Exception) {
            Log.e(TAG, "uninstall ${plan.name}", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun forget(context: Context, container: Container, name: String) {
        val prefs = context.getSharedPreferences(INSTALLS_PREFS, Context.MODE_PRIVATE)
        val key = "c${container.id}"
        val now = prefs.getStringSet(key, emptySet()).orEmpty()
        prefs.edit().putStringSet(key, now - setOf(name, "${name}_dll")).apply()
    }
}
